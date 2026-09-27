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
        data.put("orderP95Ms", observation.orderP95Ms());
        data.put("downstreamP95Ms", observation.downstreamP95Ms());
        data.put("downstreamTimeoutRate", observation.downstreamTimeoutRate());
        data.put("baselineOrderP95Ms", observation.baselineOrderP95Ms());
        data.put("timeoutCount", observation.timeoutCount());
        data.put("observedScenario", observation.scenario().name());
        data.put("synthetic", false);
        String summary = observation.requestCount() == 0
            ? "该时间窗口尚无订单请求，不能判断延迟或超时。"
            : "实际处理 " + observation.requestCount() + " 个订单请求；订单查询 p95 为 " + observation.orderP95Ms()
                + "ms，库存调用 p95 为 " + observation.downstreamP95Ms() + "ms，超时率为 "
                + Math.round(observation.downstreamTimeoutRate() * 1000) / 10.0 + "%。";
        return List.of(new Evidence("METRICS-LIVE-" + context.endTime().toEpochMilli(), name(),
            "订单服务窗口指标（实际请求）", summary, data));
    }
}
