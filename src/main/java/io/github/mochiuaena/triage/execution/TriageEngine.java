package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.Diagnosis;
import io.github.mochiuaena.triage.domain.TriageModel.Status;
import io.github.mochiuaena.triage.domain.TriageModel.ModelSource;

public interface TriageEngine {
    String mode();
    default String modelName() { return null; }
    default ModelSource source() { return null; }
    default String selectionToken() {
        ModelSource config = source();
        return mode() + ":" + (config == null ? "ENV:" + modelName() : config.providerId() + ":" + config.version());
    }
    default TriageEngine snapshot() { return this; }
    Decision investigate(ExecutionSession session);

    record Decision(Status status, Diagnosis diagnosis) {}
}
