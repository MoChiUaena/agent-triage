package io.github.mochiuaena.triage.execution;

import java.util.List;
import java.util.Locale;

/** Narrow product scope check; it only rejects clearly unrelated questions. */
public final class QuestionScope {
    private static final List<String> TERMS = List.of("订单", "order", "库存", "inventory", "超时", "timeout",
        "延迟", "latency", "慢", "slow", "健康", "healthy", "正常", "baseline", "连接池", "数据库", "database", "hikari", "sql");
    private QuestionScope() {}

    public static boolean supports(String question) {
        String normalized = question.toLowerCase(Locale.ROOT);
        return TERMS.stream().anyMatch(normalized::contains);
    }
    public static boolean supports(String question, io.github.mochiuaena.triage.tools.ToolContext context) {
        String normalized = question.toLowerCase(Locale.ROOT);
        var info = context.serviceInfo();
        return supports(question) || List.of(info.id(), info.name(), info.downstreamId(), info.downstreamName())
            .stream().map(value -> value.toLowerCase(Locale.ROOT)).anyMatch(normalized::contains);
    }
}
