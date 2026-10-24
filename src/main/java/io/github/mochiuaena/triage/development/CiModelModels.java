package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.CiModels.*;
import io.github.mochiuaena.triage.domain.TriageModel.TokenUsage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Records for the optional user-enabled model suggestions on top of a saved CI rule report. */
public final class CiModelModels {
    private CiModelModels() {}

    public record ModelOption(boolean available, UUID providerId, long version, String displayName, String modelName,
                              String baseUrl, String message) {}

    /** Exactly what one model call may receive; evidence is the report's already-redacted blocks. */
    public record Preview(UUID id, UUID reportId, UUID repositoryId, String repository, String workflowName,
                          long runId, int attempt, String headSha, Job job, String ruleCategory, String ruleStatus,
                          String ruleTitle, String ruleSummary, String ruleScope, List<Evidence> evidence,
                          List<Location> locations, List<String> omissions, Instant createdAt, Instant expiresAt,
                          ModelOption model) {}

    public record Hypothesis(String text, List<String> evidenceIds) {}

    public record Verification(String text, List<String> evidenceIds) {}

    public record Suggestion(String summary, String knownStage, String stageRelation, List<Hypothesis> hypotheses,
                             List<Verification> verifications, List<String> evidenceIds) {}

    public record ModelResult(String state, String modelName, UUID providerId, long providerVersion,
                              String responseModel, TokenUsage usage, long elapsedMs, String message) {}

    /** Saved separately from the rule report; a failed attempt records the state without a suggestion. */
    public record Advice(UUID id, UUID reportId, UUID repositoryId, String repository, long runId, int attempt,
                         String headSha, Instant createdAt, Suggestion suggestion, ModelResult model) {}

    record ModelAnalysis(Suggestion suggestion, ModelResult model) {}
}
