package io.github.mochiuaena.triage.source;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import java.util.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;

/** Matches observation categories, not a trace, endpoint or method execution. */
final class SourceEvidenceLinks {
    private SourceEvidenceLinks() {}
    static CallGraph attach(CallGraph graph, List<Evidence> evidence, boolean synthetic) {
        Evidence metrics = evidence.stream().filter(value -> value.source().equals("read_service_metrics")).findFirst().orElse(null);
        if (metrics == null) return graph;
        var links = new ArrayList<EvidenceLink>();
        List<Evidence> logs = evidence.stream().filter(value -> value.source().equals("query_error_logs")).toList();
        List<String> ids = new ArrayList<>(List.of(metrics.id())); logs.forEach(value -> ids.add(value.id()));
        String prefix = synthetic ? "合成观测：" : "运行观测：";
        if (metrics.data().get("requestCount") instanceof Number count && count.longValue() == 0) {
            links.add(new EvidenceLink(List.of(), List.of(metrics.id()), "NO_REQUESTS", prefix + "窗口内没有请求，不能证明任何候选方法被执行。"));
        } else if ("DATABASE_POOL".equals(metrics.data().get("observationType")) && metrics.data().get("databasePool") instanceof Map<?,?> pool) {
            if (number(pool.get("acquisitionTimeoutCount")) > 0) add(links, graph, ids, "DATABASE_ACQUIRE", "DB_ACQUIRE_TIMEOUT", prefix + "窗口记录到获取连接超时，可优先核查这些获取连接位置；尚未关联到具体方法执行。");
            if (number(pool.get("queryErrorCount")) > 0) add(links, graph, ids, "DATABASE_QUERY", "DB_QUERY_ERROR", prefix + "窗口记录到 SQL 查询失败，可核查这些数据库操作位置；这不等于连接池耗尽。");
        } else if (number(metrics.data().get("timeoutCount")) > 0) {
            add(links, graph, ids, "HTTP", "HTTP_TIMEOUT", prefix + "窗口记录到下游 HTTP 超时，可优先核查这些 HTTP 调用位置；没有调用级轨迹证明该方法在本次请求中执行。");
        }
        if (links.isEmpty()) links.add(new EvidenceLink(List.of(), List.of(metrics.id()), "WINDOW_ONLY", prefix + "指标描述" + (Boolean.TRUE.equals(metrics.data().get("endpointScoped")) ? "所选接口" : "服务") + "窗口，尚未提供方法级执行轨迹；以下调用关系来自静态源码。"));
        return new CallGraph(graph.state(), graph.message(), graph.truncated(), graph.rootIds(), graph.nodes(), graph.edges(), List.copyOf(links), graph.endpointMatches());
    }
    private static void add(List<EvidenceLink> links, CallGraph graph, List<String> ids, String boundary, String kind, String message) {
        List<String> edges = graph.edges().stream().filter(value -> value.kind().equals(boundary)).map(CallEdge::id).toList();
        links.add(new EvidenceLink(edges, List.copyOf(ids), kind, edges.isEmpty() ? message + "当前展开范围未找到对应调用类型。" : message));
    }
    private static long number(Object value) { return value instanceof Number number ? number.longValue() : 0; }
}
