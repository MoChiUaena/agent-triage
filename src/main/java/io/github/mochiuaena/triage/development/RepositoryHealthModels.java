package io.github.mochiuaena.triage.development;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Saved rule-based health summary over a repository overview snapshot plus locally stored analysis
 * records. Purely local; no model call and no GitHub request at generation time.
 */
public final class RepositoryHealthModels {
    private RepositoryHealthModels() {}

    public record HealthReport(UUID id, UUID repositoryId, String repository, Instant createdAt, boolean stale,
                               List<SectionSummary> sections, List<LocalRecord> localRecords, List<String> notes) {}

    /** kind: ISSUES / PULL_REQUESTS / WORKFLOWS. */
    public record SectionSummary(String kind, String name, boolean available, int listed, boolean truncated,
                                 int failing, List<FailingRun> failingRuns, String message, Instant readAt) {}

    public record FailingRun(String name, String conclusion, String url) {}

    /** kind: CI / PR / ISSUE. Counts cover the stores' recent-window listing, not the whole repository. */
    public record LocalRecord(String kind, int saved, Instant latestAt, String latestTitle) {}
}
