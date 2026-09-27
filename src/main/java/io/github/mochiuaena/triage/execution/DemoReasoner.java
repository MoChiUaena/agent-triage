package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;

/** Deterministic rules for the demonstration, deliberately not presented as an LLM. */
@Component
public class DemoReasoner {
    public boolean supports(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        return List.of("订单", "order", "超时", "timeout", "延迟", "latency", "慢", "slow", "健康", "正常")
            .stream().anyMatch(q::contains);
    }

    public Diagnosis diagnose(List<Evidence> evidence) {
        Evidence metrics = evidence.stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
        Evidence logs = evidence.stream().filter(e -> e.source().equals("query_error_logs")).findFirst().orElseThrow();
        boolean timeout = ((Number) metrics.data().get("downstreamTimeoutRate")).doubleValue() > 0;
        var observations = List.of(new Finding(metrics.summary(), List.of(metrics.id())), new Finding(logs.summary(), List.of(logs.id())));
        String requiredDoc = timeout ? "DOC-DOWNSTREAM-TIMEOUT#v1" : "DOC-HEALTHY-BASELINE#v1";
        boolean hasRule = evidence.stream().anyMatch(e -> e.id().equals(requiredDoc));
        var causes = hasRule ? List.of(new Finding(timeout
            ? "订单查询可能受库存服务的读取超时影响，需要进一步检查库存服务和网络。"
            : "当前指标接近基线，未发现下游超时证据。",
            List.of(metrics.id(), logs.id(), requiredDoc))) : List.<Finding>of();
        return new Diagnosis(observations, causes,
            timeout ? List.of("查看同一时间段内 inventory-service 的延迟、错误率和资源使用情况。",
                "用日志中的 traceId 对照订单与库存服务的调用耗时。", "核对客户端的 2000ms 读取超时设置，检查连接池等待和网络延迟。")
                : List.of("找到具体慢请求的时间和 traceId，缩小查询范围。", "补充数据库和其他依赖的调用耗时。"),
            hasRule ? "尚未采集数据库、网络和库存服务的资源指标，无法进一步判断原因。"
                : "已获取观测数据，但没有检索到对应的排障文档，暂时无法判断原因。");
    }
}
