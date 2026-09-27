package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.Diagnosis;
import io.github.mochiuaena.triage.domain.TriageModel.Status;

public interface TriageEngine {
    String mode();
    Decision investigate(ExecutionSession session);

    record Decision(Status status, Diagnosis diagnosis) {}
}
