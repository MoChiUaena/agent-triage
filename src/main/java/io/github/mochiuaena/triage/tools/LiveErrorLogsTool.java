package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import io.github.mochiuaena.triage.domain.TriageModel.RequestFailure;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@Order(3)
@ConditionalOnProperty(name = "triage.observation.source", havingValue = "LIVE")
public class LiveErrorLogsTool implements ReadOnlyTool {
    private final LiveObservationClient client;
    public LiveErrorLogsTool(LiveObservationClient client) { this.client = client; }
    @Override public String name() { return "query_error_logs"; }

    @Override public List<Evidence> execute(ToolContext context, String query) {
        LiveObservationClient.Snapshot observation = client.snapshot(context);
        List<Map<String, Object>> entries = observation.errors().stream().map(error -> {
            Map<String, Object> entry = new LinkedHashMap<>(Map.of("timestamp", error.timestamp().toString(), "traceId", error.traceId(),
                "level", error.level(), "message", error.message()));
            if (error.code() != null) entry.put("code", error.code());
            return entry;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", observation.service());
        data.put("windowStart", observation.windowStart().toString());
        data.put("windowEnd", observation.windowEnd().toString());
        data.put("entries", entries);
        data.put("returnedCount", entries.size());
        data.put("timeoutCount", observation.timeoutCount());
        data.put("sampleLimit", 3);
        data.put("synthetic", false);
        var locations = observation.errors().stream().filter(error -> error.failureLocation() != null)
            .map(error -> new RequestFailure(error.timestamp(), error.traceId(), error.failureLocation())).toList();
        if (!locations.isEmpty()) data.put("failureLocations", locations);
        if (observation.requestDetails() != null) data.put("endpointScoped", context.endpoint() != null);
        if (observation.databasePool() != null) {
            data.put("observationType", "DATABASE_POOL");
            data.put("requestCount", observation.requestCount());
            data.put("acquisitionTimeoutCount", observation.databasePool().acquisitionTimeoutCount());
            data.put("acquisitionErrorCount", observation.databasePool().acquisitionErrorCount());
            data.put("queryErrorCount", observation.databasePool().queryErrorCount());
            data.remove("timeoutCount");
            return List.of(new Evidence("LOGS-DB-" + context.endTime().toEpochMilli(), name(), "数据库错误事件（最多 3 条）",
                entries.isEmpty() ? "本窗口没有记录数据库错误事件。" : "展示本窗口最近 " + entries.size() + " 条数据库错误事件；获取连接失败与 SQL 查询失败分别记录。", data));
        }
        String summary = observation.timeoutCount() > 0
            ? "该窗口记录了 " + observation.timeoutCount() + " 次" + context.serviceInfo().downstreamName() + "请求超时，展示最近 " + entries.size() + " 条错误事件。"
            : !entries.isEmpty()
                ? "该窗口记录了 " + entries.size() + " 条请求错误，尚不能确定是否涉及下游或超时。"
            : "该窗口没有下游请求超时错误；日志为空不能单独证明服务无故障。";
        return List.of(new Evidence("LOGS-LIVE-" + context.endTime().toEpochMilli(), name(),
            context.serviceInfo().name() + "错误事件（实际请求，最多 3 条）", summary, data));
    }
}
