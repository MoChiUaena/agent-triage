package io.github.mochiuaena.triage.development;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Local, editable GitHub operation drafts. kind: COMMENT / LABEL; state: DRAFT / DISCARDED. Drafts are
 * derived from locally saved analysis reports and are never published — no GitHub write call exists until
 * W2 delivers confirmed writes with an audit trail.
 */
public final class OperationDraftModels {
    private OperationDraftModels() {}

    public record Draft(UUID id, UUID repositoryId, String repository, String kind, Target target, Basis basis,
                        String body, List<String> addLabels, List<String> removeLabels,
                        String state, int revision, Instant createdAt, Instant updatedAt) {}

    /** objectType: ISSUE / PULL_REQUEST. headSha/updatedAt freeze the target version for W2 re-checks. */
    public record Target(String objectType, long number, String title, String url, String headSha, Instant updatedAt) {}

    /** type: ISSUE_REPORT / PR_REPORT. Immutable after creation so edits keep the source relationship. */
    public record Basis(String type, UUID reportId, String title, Instant createdAt) {}
}
