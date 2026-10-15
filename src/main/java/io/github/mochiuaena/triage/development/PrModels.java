package io.github.mochiuaena.triage.development;

import java.time.Instant;
import java.util.*;

public final class PrModels {
    private PrModels() {}
    public record Context(long number, String title, String url, String state, boolean draft,
                          String baseSha, String headSha, int changedFiles, int additions, int deletions,
                          List<Long> issueNumbers, boolean issueReferencesTruncated) {}
    public record FileChange(String file, String previousFile, String status, int additions, int deletions, String coverage) {}
    // Patches are transient. Reports store metadata and selected, redacted evidence only.
    record PatchFile(String file, String previousFile, String status, int additions, int deletions, String patch) {}
    record Diff(String mergeBaseSha, List<PatchFile> files, List<String> gaps) {}
    public record Finding(String id, String category, String title, String file, int line, String side,
                          String excerpt, String reason, String check, String url) {}
    public record CiCheck(String name, String status, String conclusion, String url) {}
    public record CiSnapshot(String state, boolean checksAvailable, boolean statusesAvailable, boolean truncated,
                             List<CiCheck> checks, List<String> gaps) {}
    public record IssueReference(long number, String title, String state, String url) {}
    record IssueSnapshot(List<IssueReference> issues, List<String> gaps) {}
    record Analysis(List<FileChange> files, List<Finding> findings, List<String> gaps) {}
    public record Report(UUID id, UUID repositoryId, String repository, Context pull, String mergeBaseSha,
                         Instant createdAt, String source, List<FileChange> files, List<Finding> findings,
                         CiSnapshot ci, List<IssueReference> issues, List<String> gaps, String notice) {}
}
