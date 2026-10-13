package io.github.mochiuaena.triage.development;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class GithubModels {
    private GithubModels() {}
    public record Key(String owner, String name) {
        public String fullName() { return owner + "/" + name; }
        public String url() { return "https://github.com/" + fullName(); }
        public static Key parse(String input) {
            if (input == null) throw new IllegalArgumentException("请输入 GitHub 仓库。");
            String value = input.strip();
            if (value.startsWith("https://")) {
                URI url = URI.create(value);
                if (!"github.com".equalsIgnoreCase(url.getHost()) || url.getPort() != -1
                    || url.getUserInfo() != null || url.getQuery() != null || url.getFragment() != null)
                    throw new IllegalArgumentException("请使用标准 GitHub 仓库链接。");
                value = url.getRawPath().replaceFirst("^/", "");
            }
            value = value.replaceFirst("/$", "").replaceFirst("\\.git$", "");
            String[] parts = value.split("/", -1);
            if (parts.length != 2 || !parts[0].matches("[A-Za-z0-9](?:[A-Za-z0-9-]{0,37}[A-Za-z0-9])?")
                || !parts[1].matches("[A-Za-z0-9_.-]{1,100}") || parts[1].equals(".") || parts[1].equals(".."))
                throw new IllegalArgumentException("仓库格式应为 owner/repository 或 https://github.com/owner/repository。");
            return new Key(parts[0].toLowerCase(Locale.ROOT), parts[1].toLowerCase(Locale.ROOT));
        }
    }
    public record Registration(UUID id, String owner, String name, String url, Instant registeredAt,
                               Instant checkedAt, String lastError) {}
    public record RepositoryInfo(String fullName, String description, String defaultBranch,
                                 boolean privateRepository, boolean archived, long openItems) {}
    public record Issue(long number, String title, String url, Instant updatedAt, List<String> labels) {}
    public record PullRequest(long number, String title, String url, Instant updatedAt, boolean draft, String headSha) {}
    public record Workflow(long id, String name, String url, String status, String conclusion,
                           String branch, String headSha, Instant updatedAt) {}
    public record Section<T>(boolean available, List<T> items, boolean truncated, String message) {}
    public record Health(RepositoryInfo repository, Section<Issue> issues, Section<PullRequest> pullRequests,
                         Section<Workflow> workflows, Instant checkedAt) {}
    public record Snapshot(Registration registration, Health health, boolean stale, String error) {}
}
