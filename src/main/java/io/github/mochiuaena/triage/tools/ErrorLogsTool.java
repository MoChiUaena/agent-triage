package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

@Component
public class ErrorLogsTool implements ReadOnlyTool {
    @Override public String name() { return "query_error_logs"; }

    @Override public List<Evidence> execute(ToolContext context, String query) {
        boolean timeout = context.scenario() == Scenario.DOWNSTREAM_TIMEOUT;
        List<Map<String, Object>> entries = timeout ? IntStream.range(1, 4).mapToObj(i -> Map.<String, Object>of(
            "timestamp", context.endTime().minusSeconds(i * 10L).toString(),
            "traceId", "synthetic-trace-" + i, "level", "ERROR",
            "message", "GET /inventory/availability: java.net.SocketTimeoutException: Read timed out after 2000ms"
        )).toList() : List.of();
        return List.of(new Evidence("LOGS-ORDER-" + context.scenario(), name(), "近期错误日志（合成，最多 3 条）",
            timeout ? "发现 3 条库存下游读取超时样例，配置的读取超时为 2000ms。" : "查询窗口内没有错误日志。日志为空不能单独证明服务无故障。",
            Map.of("service", context.service(), "windowStart", context.startTime().toString(),
                "windowEnd", context.endTime().toString(), "entries", entries,
                "returnedCount", entries.size(), "sampleLimit", 3, "synthetic", true)));
    }
}
