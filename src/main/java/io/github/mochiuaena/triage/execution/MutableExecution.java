package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Owned by exactly one coordinator task. Only immutable snapshots leave this class. */
final class MutableExecution {
    private final Run initial;
    Status status = Status.QUEUED;
    int toolCalls;
    Instant finishedAt;
    Diagnosis diagnosis;
    Failure failure;
    ModelExecution modelExecution;
    final List<Event> events = new ArrayList<>();
    final List<Evidence> evidence = new ArrayList<>();

    MutableExecution(Run initial) { this.initial = initial; this.modelExecution = initial.modelExecution(); }
    void event(String type, String tool, String message, List<String> ids) {
        events.add(new Event(events.size() + 1, Instant.now(), type, tool, message, List.copyOf(ids)));
    }
    Run snapshot() {
        return new Run(initial.id(), initial.question(), initial.service(), initial.windowMinutes(), initial.scenario(),
            initial.mode(), initial.synthetic(), status, initial.createdAt(), finishedAt, toolCalls, List.copyOf(events), List.copyOf(evidence), diagnosis, failure, modelExecution);
    }
}
