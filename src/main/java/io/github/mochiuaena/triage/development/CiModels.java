package io.github.mochiuaena.triage.development;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CiModels {
    private CiModels() {}
    public record Step(int number, String name, String conclusion, Instant startedAt, Instant completedAt) {}
    public record Job(long id, long runId, int attempt, String headSha, String name, String status,
                      String conclusion, List<Step> steps, String url) {}
    public record Context(long runId, int attempt, String headSha, String name, String branch,
                          String status, String conclusion, String url, List<Job> jobs, boolean truncated) {}
    public record Evidence(String id, int startLine, int endLine, String text) {}
    public record Location(String file, int line) {}
    public record Analysis(String category, String status, String title, String summary, List<String> checks,
                           List<Evidence> evidence, List<Location> locations, String scope, boolean truncated) {}
    public record Report(UUID id, UUID repositoryId, String repository, long runId, int attempt, String headSha,
                         String workflowName, Job job, String source, Instant createdAt, String notice, Analysis analysis) {}
}
