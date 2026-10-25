package io.github.mochiuaena.triage.development;

import java.time.Instant;
import java.util.*;

public final class WorkflowModels {
    private WorkflowModels() {}
    public record Spec(String id, String title, List<String> requires, List<String> after) {}
    /** Saved custom template; tasks are frozen snapshots, so later edits never affect running workflows. */
    public record Template(UUID id, UUID repositoryId, String repository, String name, List<Spec> tasks,
                           int revision, Instant createdAt, Instant updatedAt) {}
    public record Plan(UUID id, UUID repositoryId, String repository, PrModels.Context pull, Long issueNumber,
                       String issueRelation, UUID issueReportId, UUID ciReportId, Instant createdAt, Instant expiresAt,
                       List<Spec> tasks, String notice) {}
    public record Reference(String kind, UUID id, String title, String version) {}
    public record Task(String id, String title, List<String> requires, List<String> after, String state,
                       String message, String errorCode, Instant startedAt, Instant finishedAt, List<Reference> references) {}
    public record RepositoryContext(String repository, String branch, Instant readAt, Integer openIssueSample,
                                    Integer openPrSample, Integer defaultBranchFailureSample, List<String> gaps) {}
    public record CiObservation(String state, CiModels.Report report, String source, int runsRead, boolean truncated, List<String> gaps) {}
    public record Check(String code, String state, String message, List<String> sources) {}
    public record Summary(String verdict, String text, List<String> actions, List<String> gaps, List<Reference> sources, String scope) {}
    public record Data(RepositoryContext repository, PrModels.Report pr, IssueModels.Report issue, CiObservation ci,
                       CiModels.Report priorCi, List<Check> checks, Summary summary) {
        static Data empty() { return new Data(null, null, null, null, null, List.of(), null); }
        Data repository(RepositoryContext value) { return new Data(value, pr, issue, ci, priorCi, checks, summary); }
        Data pr(PrModels.Report value) { return new Data(repository, value, issue, ci, priorCi, checks, summary); }
        Data issue(IssueModels.Report value) { return new Data(repository, pr, value, ci, priorCi, checks, summary); }
        Data ci(CiObservation value) { return new Data(repository, pr, issue, value, priorCi, checks, summary); }
        Data rechecked(PrModels.Report p, CiObservation c, List<Check> values) { return new Data(repository, p, issue, c, ci == null ? priorCi : ci.report(), values, summary); }
        Data checks(List<Check> value) { return new Data(repository, pr, issue, ci, priorCi, value, summary); }
        Data summary(Summary value) { return new Data(repository, pr, issue, ci, priorCi, checks, value); }
    }
    public record Snapshot(UUID id, UUID repositoryId, String repository, Plan plan, String state, String message,
                           Instant createdAt, Instant updatedAt, List<Task> tasks, Data data, int resumes) {}
    record Result(String state, String message, Data data, List<Reference> references) {}
    /** WAITING is not terminal: the workflow persists, frees its worker and can be resumed by the user. */
    static boolean terminal(String state) { return !Set.of("PLANNED", "RUNNING", "WAITING").contains(state); }
}
