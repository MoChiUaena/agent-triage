package io.github.mochiuaena.triage.development;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Saved rule-based activity report over a repository overview snapshot plus locally stored analysis
 * records, grouped by date. Purely local: generation reads no GitHub data and calls no model; sample
 * lists are never presented as full statistics.
 */
public final class ActivityModels {
    private ActivityModels() {}

    public record ActivityReport(UUID id, UUID repositoryId, String repository, Instant createdAt,
                                 LocalDate fromDate, LocalDate toDate, boolean stale, Instant snapshotReadAt,
                                 List<DayActivity> days, List<ActionItem> actionItems, List<LocalRecord> localRecords,
                                 List<String> gaps, List<String> notes) {}

    public record DayActivity(LocalDate date, List<Item> items) {}

    /**
     * One traceable activity entry. kind: COMMIT / ISSUE / PULL_REQUEST / WORKFLOW / CI_REPORT / PR_REPORT /
     * ISSUE_REPORT. url is the GitHub source; reportType/reportId are set for locally saved reports so the
     * page can reopen them.
     */
    public record Item(String kind, String title, String detail, String url, String reportType, UUID reportId, Instant at) {}

    /** kind: FAILING_WORKFLOW / OPEN_ISSUE / OPEN_PULL_REQUEST. Current unfinished items from the snapshot sample. */
    public record ActionItem(String kind, String title, String detail, String url) {}

    /** kind: CI / PR / ISSUE. inRange counts date-filtered reports; saved is the store's recent window (max 20). */
    public record LocalRecord(String kind, int inRange, int saved, Instant latestInRangeAt) {}
}
