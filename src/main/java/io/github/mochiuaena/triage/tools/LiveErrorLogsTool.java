package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
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
        List<Map<String, Object>> entries = observation.errors().stream().map(error -> Map.<String, Object>of(
            "timestamp", error.timestamp().toString(), "traceId", error.traceId(),
            "level", error.level(), "message", error.message())).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("service", observation.service());
        data.put("windowStart", observation.windowStart().toString());
        data.put("windowEnd", observation.windowEnd().toString());
        data.put("entries", entries);
        data.put("returnedCount", entries.size());
        data.put("sampleLimit", 3);
        data.put("synthetic", false);
        String summary = observation.timeoutCount() > 0
            ? "该窗口记录了 " + observation.timeoutCount() + " 次库存请求超时，展示最近 " + entries.size() + " 条错误事件。"
            : "该窗口没有库存请求超时错误；日志为空不能单独证明服务无故障。";
        return List.of(new Evidence("LOGS-LIVE-" + context.endTime().toEpochMilli(), name(),
            "订单服务错误事件（实际请求，最多 3 条）", summary, data));
    }
}
