package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.OperationDraftModels.Basis;
import io.github.mochiuaena.triage.development.OperationDraftModels.Target;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Confirmed execution of local operation drafts against GitHub, with a local audit trail. state:
 * SUCCESS / FAILED / UNCERTAIN. Audit payloads never contain credentials; uncertain outcomes are
 * verified against the remote state instead of being retried automatically.
 */
public final class DraftExecutionModels {
    private DraftExecutionModels() {}

    public record WriteOption(boolean enabled, String message) {}

    /** Frozen view of what a confirmed execution will do; valid for ten minutes and usable once. */
    public record ExecutionPreview(UUID id, UUID draftId, int draftRevision, String kind, Target target,
                                   String operation, String body, List<String> currentLabels, List<String> resultingLabels,
                                   boolean permitted, String permissionMessage, Instant readAt, Instant expiresAt,
                                   List<String> notes) {}

    public record ExecutionRecord(UUID id, UUID draftId, UUID repositoryId, String repository, String kind,
                                  Target target, Basis basis, String body, List<String> addLabels, List<String> removeLabels,
                                  List<String> resultingLabels, String state, Instant confirmedAt, Instant executedAt,
                                  String resultUrl, String resultSummary, String message, String confirmedVia) {}
}
