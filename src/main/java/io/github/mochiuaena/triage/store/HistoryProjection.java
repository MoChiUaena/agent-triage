package io.github.mochiuaena.triage.store;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.time.Duration;

/** Query columns derived from the saved snapshot; never modifies the legacy JSON. */
record HistoryProjection(String service, String serviceName, String mode, String question, Long durationMillis,
                         int toolCalls, int modelCalls, Long inputTokens, Long outputTokens, Long totalTokens, boolean complete) {
    static HistoryProjection of(Run run) {
        String service = run.service() == null ? "order-service" : run.service();
        String name = run.serviceInfo() == null ? service.equals("order-service") ? "订单服务" : service : run.serviceInfo().name();
        var model = run.modelExecution();
        var usage = model == null ? null : model.knownUsage() != null ? model.knownUsage() : model.usage();
        String mode = run.mode() == null ? model == null ? "DEMO" : "MODEL" : run.mode();
        Long duration = run.finishedAt() != null && !run.finishedAt().isBefore(run.createdAt())
            ? Duration.between(run.createdAt(), run.finishedAt()).toMillis() : null;
        return new HistoryProjection(service, name, mode, run.question(), duration, run.toolCalls(), model == null ? 0 : model.calls(),
            usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(), usage == null ? null : usage.totalTokens(),
            model != null && model.calls() > 0 && run.status().terminal() && model.usage() != null);
    }
    Object[] fields() { return new Object[]{service, serviceName, mode, question, durationMillis, toolCalls, modelCalls,
        inputTokens, outputTokens, totalTokens, complete}; }
}
