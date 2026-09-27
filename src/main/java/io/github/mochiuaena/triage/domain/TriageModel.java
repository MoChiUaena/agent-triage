package io.github.mochiuaena.triage.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public execution contract shared by storage, tools and the UI. */
public final class TriageModel {
    private TriageModel() {}

    public enum Scenario { NORMAL, DOWNSTREAM_TIMEOUT }

    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, INSUFFICIENT_EVIDENCE, FAILED;
        public boolean terminal() { return this != QUEUED && this != RUNNING; }
    }

    public record Evidence(String id, String source, String title, String summary,
                           Map<String, Object> data) {}

    public record Finding(String text, List<String> evidenceIds) {}

    public record Diagnosis(List<Finding> observations, List<Finding> possibleCauses,
                            List<String> nextSteps, String uncertainty) {}

    public record Event(int sequence, Instant timestamp, String type, String tool,
                        String message, List<String> evidenceIds) {}

    public record Failure(String code, String message) {}

    public record Run(UUID id, String question, String service, int windowMinutes, Scenario scenario,
                      String mode, boolean synthetic, Status status, Instant createdAt, Instant finishedAt,
                      int toolCalls, List<Event> events, List<Evidence> evidence,
                      Diagnosis diagnosis, Failure failure) {}

    public record RunSummary(UUID id, String question, Scenario scenario, Status status,
                             Instant createdAt, int toolCalls) {}
}
