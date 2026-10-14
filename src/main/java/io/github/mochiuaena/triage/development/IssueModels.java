package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.domain.TriageModel.TokenUsage;
import java.time.Instant;
import java.util.*;

public final class IssueModels {
    private IssueModels() {}
    public record Issue(long number, String title, String url, String state, Instant updatedAt, List<String> labels,
                        List<String> assignees, int comments, boolean bodyTruncated) {}
    record ReadIssue(Issue issue, String body, String fingerprint) {}
    public record Evidence(String id, String kind, String title, String url, String file, int startLine, int endLine, String text) {}
    public record Owner(String login, String url, String evidenceId) {}
    public record ModelOption(boolean available, UUID providerId, long version, String displayName, String modelName, String baseUrl, String message) {}
    public record Preview(UUID id, UUID repositoryId, String repository, Issue issue, String branch, String headSha,
                          Instant readAt, Instant expiresAt, List<Evidence> evidence, List<Owner> owners, List<String> gaps, ModelOption model) {}
    record Prepared(Preview preview, String fingerprint) {}
    public record Action(String text, List<String> evidenceIds) {}
    public record OwnerCandidate(String login, String reason, List<String> evidenceIds) {}
    public record Proposal(String summary, String category, String priority, String reason, List<String> evidenceIds,
                           List<String> questions, List<Action> actions, List<OwnerCandidate> ownerCandidates) {}
    public record ModelResult(String state, String modelName, UUID providerId, long providerVersion, String responseModel,
                              TokenUsage usage, long elapsedMs, String message) {}
    record ModelAnalysis(Proposal proposal, ModelResult model) {}
    public record Report(UUID id, UUID repositoryId, String repository, Issue issue, String branch, String headSha,
                         Instant contextReadAt, Instant createdAt, String source, Proposal proposal, ModelResult model,
                         List<Evidence> evidence, List<Owner> owners, List<String> gaps) {}
}
