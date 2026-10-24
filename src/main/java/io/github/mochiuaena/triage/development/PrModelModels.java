package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.PrModels.CiSnapshot;
import io.github.mochiuaena.triage.development.PrModels.Finding;
import io.github.mochiuaena.triage.development.PrModels.IssueReference;
import io.github.mochiuaena.triage.domain.TriageModel.TokenUsage;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Records for the optional user-enabled model review on top of a saved PR rule report. */
public final class PrModelModels {
    private PrModelModels() {}

    public record ModelOption(boolean available, UUID providerId, long version, String displayName, String modelName,
                              String baseUrl, String message) {}

    /** A diff file whose patch (redacted, bounded) will be sent, with the exact line ranges it covers. */
    public record SendFile(String file, String status, int additions, int deletions, String patch, boolean clipped,
                           List<LineRange> newLines, List<LineRange> oldLines) {}

    public record LineRange(int start, int count) {}

    /** Exactly what one model call may receive; patches are re-read at the report's frozen SHAs. */
    public record Preview(UUID id, UUID reportId, UUID repositoryId, String repository, long number, String title,
                          String baseSha, String headSha, String mergeBaseSha, int changedFiles, List<SendFile> files,
                          List<Finding> findings, CiSnapshot ci, List<IssueReference> issues, List<String> omissions,
                          Instant createdAt, Instant expiresAt, ModelOption model) {}

    public record FileLine(String file, int line, String side) {}

    public record Change(String text, List<FileLine> references) {}

    public record Suggestion(String summary, String intent, String confidence, String reviewedSha,
                             List<Change> risks, List<Change> testGaps, List<FileLine> references) {}

    public record ModelResult(String state, String modelName, UUID providerId, long providerVersion,
                              String responseModel, TokenUsage usage, long elapsedMs, String message) {}

    /** Saved separately from the rule report; a failed attempt records the state without a suggestion. */
    public record Advice(UUID id, UUID reportId, UUID repositoryId, String repository, long number, String baseSha,
                         String headSha, String mergeBaseSha, Instant createdAt, Suggestion suggestion, ModelResult model) {}

    record ModelAnalysis(Suggestion suggestion, ModelResult model) {}
}
