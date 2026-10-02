package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(2)
@ConditionalOnProperty(name = "triage.observation.source", havingValue = "LIVE")
public class LiveMetricsTool implements ReadOnlyTool {
    private final LiveObservationClient client;
    public LiveMetricsTool(LiveObservationClient client) { this.client = client; }
    @Override public String name() { return "read_service_metrics"; }

    @Override public List<Evidence> execute(ToolContext context, String query) {
        LiveObservationClient.Snapshot observation = client.snapshot(context);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", observation.service());
        data.put("windowStart", observation.windowStart().toString());
        data.put("windowEnd", observation.windowEnd().toString());
        data.put("requestCount", observation.requestCount());
        if (observation.databasePool() != null) return database(context, observation, data);
        if (context.target() != null && context.target().protocol() == ServiceRegistry.Protocol.HTTP_REQUESTS_V4) return inbound(context, observation, data);
        data.put("micrometerRecordedRequestCount", observation.recordedRequestCount());
        data.put("orderP95Ms", observation.orderP95Ms());
        data.put("requestP95Ms", observation.orderP95Ms());
        data.put("downstreamP95Ms", observation.downstreamP95Ms());
        data.put("downstreamTimeoutRate", observation.downstreamTimeoutRate());
        data.put("baselineOrderP95Ms", observation.baselineOrderP95Ms());
        data.put("baselineRequestP95Ms", observation.baselineOrderP95Ms());
        data.put("timeoutCount", observation.timeoutCount());
        data.put("observedScenario", observation.scenario().name());
        data.put("synthetic", false);
        boolean scoped = context.endpoint() != null;
        if (observation.requestDetails() != null) {
            data.put("requestDetails", observation.requestDetails()); data.put("endpointScoped", scoped);
            var counts = observation.requestDetails().responseStatuses();
            if (counts != null) data.put("responseStatuses", Map.of("informational", counts.informational(), "successful", counts.successful(),
                "redirection", counts.redirection(), "clientError", counts.clientError(), "serverError", counts.serverError(), "unknown", counts.unknown()));
        }
        String summary = observation.requestCount() == 0
            ? "该时间窗口尚无" + context.serviceInfo().name() + "请求，不能判断延迟或超时。"
            : (scoped ? "所选接口窗口处理 " : "本窗口处理 ") + observation.requestCount() + " 个" + context.serviceInfo().name() + "请求，其中" + context.serviceInfo().downstreamName() + "调用超时 "
                + observation.timeoutCount() + " 次；请求 p95 为 " + observation.orderP95Ms()
                + "ms，下游调用 p95 为 " + observation.downstreamP95Ms() + "ms，本窗口超时率为 "
                + Math.round(observation.downstreamTimeoutRate() * 1000) / 10.0 + "%。";
        if (observation.requestDetails() != null && observation.requestDetails().responseStatuses() != null) {
            var counts = observation.requestDetails().responseStatuses();
            summary += " 响应分类：2xx " + counts.successful() + " 次、4xx " + counts.clientError() + " 次、5xx " + counts.serverError()
                + " 次；1xx/3xx/未知为 " + counts.informational() + "/" + counts.redirection() + "/" + counts.unknown() + " 次。";
        }
        return List.of(new Evidence("METRICS-LIVE-" + context.endTime().toEpochMilli(), name(),
            (scoped ? "所选接口" : context.serviceInfo().name()) + "窗口指标（实际请求）", summary, data));
    }

    private List<Evidence> inbound(ToolContext context, LiveObservationClient.Snapshot value, Map<String, Object> data) {
        data.put("observationType", "HTTP_REQUESTS"); data.put("requestP95Ms", value.orderP95Ms());
        data.put("baselineRequestP95Ms", value.baselineOrderP95Ms()); data.put("recordedRequestCount", value.recordedRequestCount());
        data.put("requestDetails", value.requestDetails()); data.put("endpointScoped", context.endpoint() != null); data.put("synthetic", false);
        var counts = value.requestDetails().responseStatuses();
        var failures = value.requestDetails().requestFailures();
        if (failures != null) data.put("requestFailures", failureData(failures));
        if (counts != null) data.put("responseStatuses", Map.of("informational", counts.informational(), "successful", counts.successful(),
            "redirection", counts.redirection(), "clientError", counts.clientError(), "serverError", counts.serverError(), "unknown", counts.unknown()));
        String summary = "本窗口记录 " + value.requestCount() + " 个" + context.serviceInfo().name() + "入站请求，请求 p95 为 "
            + value.orderP95Ms() + "ms；未采集下游调用，不能将缺少下游指标解释为零超时。";
        if (counts != null) summary += " 响应分类：2xx " + counts.successful() + "、4xx " + counts.clientError() + "、5xx " + counts.serverError()
            + " 次；未知 " + counts.unknown() + " 次。";
        if (failures != null) summary += " 请求执行异常 " + failures.executionFailures() + " 次，仅返回 5xx " + failures.serverErrorResponses()
            + " 次，异步超时/错误 " + failures.asyncTimeouts() + "/" + failures.asyncErrors() + " 次，已处理异常 " + failures.handledExceptions() + " 次。";
        return List.of(new Evidence("METRICS-REQUESTS-" + context.endTime().toEpochMilli(), name(),
            (context.endpoint() == null ? context.serviceInfo().name() : "所选接口") + "入站请求窗口", summary, data));
    }
    static Map<String, Integer> failureData(io.github.mochiuaena.triage.domain.TriageModel.RequestFailureCounts value) {
        return Map.of("executionFailures", value.executionFailures(), "serverErrorResponses", value.serverErrorResponses(), "asyncTimeouts", value.asyncTimeouts(),
            "asyncErrors", value.asyncErrors(), "handledExceptions", value.handledExceptions());
    }

    private List<Evidence> database(ToolContext context, LiveObservationClient.Snapshot value, Map<String, Object> data) {
        var pool = value.databasePool();
        data.put("observationType", "DATABASE_POOL");
        data.put("requestP95Ms", value.orderP95Ms()); data.put("baselineRequestP95Ms", value.baselineOrderP95Ms());
        data.put("recordedRequestCount", value.recordedRequestCount()); data.put("synthetic", false);
        Map<String, Object> poolData = new LinkedHashMap<>(Map.of("maximumConnections", pool.maximumConnections(), "peakActiveConnections", pool.peakActiveConnections(),
            "peakPendingThreads", pool.peakPendingThreads(), "poolSamples", pool.poolSamples(), "exhaustedSamples", pool.exhaustedSamples(),
            "acquisitionTimeoutCount", pool.acquisitionTimeoutCount(), "acquisitionErrorCount", pool.acquisitionErrorCount(), "queryErrorCount", pool.queryErrorCount(),
            "acquisitionP95Ms", pool.acquisitionP95Ms(), "queryP95Ms", pool.queryP95Ms()));
        poolData.put("queryCount", pool.queryCount()); data.put("databasePool", poolData);
        String summary = value.requestCount() == 0 ? "该时间窗口没有数据库操作，不能判断连接等待情况。"
            : "本窗口记录 " + value.requestCount() + " 次数据库操作，获取连接超时 " + pool.acquisitionTimeoutCount() + " 次，获取连接失败 "
                + pool.acquisitionErrorCount() + " 次，SQL 查询失败 " + pool.queryErrorCount() + " 次；获取连接 p95 为 " + pool.acquisitionP95Ms()
                + "ms，" + (pool.queryCount() == 0 ? "本窗口未执行 SQL 查询" : "SQL 查询 p95 为 " + pool.queryP95Ms() + "ms") + "。连接使用峰值 " + pool.peakActiveConnections() + "/" + pool.maximumConnections()
                + "，等待线程峰值 " + pool.peakPendingThreads() + "。";
        return List.of(new Evidence("METRICS-DB-" + context.endTime().toEpochMilli(), name(), context.serviceInfo().name() + "数据库窗口指标", summary, data));
    }
}
