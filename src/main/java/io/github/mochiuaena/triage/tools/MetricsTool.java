package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;

@Component
@Order(2)
@ConditionalOnProperty(name = "triage.observation.source", havingValue = "SYNTHETIC", matchIfMissing = true)
public class MetricsTool implements ReadOnlyTool {
    @Override public String name() { return "read_service_metrics"; }

    @Override public List<Evidence> execute(ToolContext context, String query) {
        boolean timeout = context.scenario() == Scenario.DOWNSTREAM_TIMEOUT;
        return List.of(new Evidence("METRICS-ORDER-" + context.scenario(), name(), "订单服务窗口指标（合成）",
            timeout ? "订单查询 p95 由基线 120ms 升至 2350ms；库存下游 p95 为 2100ms，超时率 18%。"
                    : "订单查询 p95 为 120ms；库存下游 p95 为 45ms，超时率为 0%。",
            Map.of("service", context.service(), "windowStart", context.startTime().toString(),
                "windowEnd", context.endTime().toString(), "requestCount", context.windowMinutes() * 100,
                "orderP95Ms", timeout ? 2350 : 120, "downstreamP95Ms", timeout ? 2100 : 45,
                "downstreamTimeoutRate", timeout ? 0.18 : 0.0, "baselineOrderP95Ms", 120,
                "synthetic", true)));
    }
}
