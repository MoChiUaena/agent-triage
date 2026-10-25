package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.GithubModels.Key;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Dedicated client for confirmed GitHub write operations, using a credential separate from the read
 * token. Only fixed REST routes are called and no redirect is followed. Timeouts surface as
 * "uncertain" failures so callers verify the remote state instead of retrying blindly.
 */
@Component
public class GithubWriteClient {
    /** Write-path failure; uncertain marks a timeout where the request may or may not have landed. */
    static final class WriteFailure extends RuntimeException {
        final boolean uncertain;
        WriteFailure(String message, boolean uncertain) { super(message); this.uncertain = uncertain; }
    }
    record Access(boolean push) {}
    record IssueState(long number, String state, Instant updatedAt, List<String> labels) {}
    record PullState(long number, String state, String headSha) {}
    record CommentResult(long id, String url, String body) {}

    private static final int MAX_BODY = 1_048_576;
    private final String base;
    private final String token;
    private final ObjectMapper json;
    private final int timeoutSeconds;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public GithubWriteClient(ObjectMapper json,
                             @Value("${triage.github.api-base-url:https://api.github.com}") String api,
                             @Value("${triage.github.write-token:}") String token,
                             @Value("${triage.github.write-timeout-seconds:8}") int timeoutSeconds) {
        URI uri = URI.create(api);
        boolean official = "https://api.github.com".equals(api) || "https://api.github.com/".equals(api);
        boolean local = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
            && uri.getPort() > 0 && uri.getPort() <= 65535 && (uri.getPath().isEmpty() || uri.getPath().equals("/"))
            && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        if (!official && !local) throw new IllegalArgumentException("GitHub API must be the official origin or an explicit local test server");
        if (!token.strip().matches("[A-Za-z0-9_.-]{0,512}")) throw new IllegalArgumentException("Invalid GitHub write token configuration");
        if (timeoutSeconds < 1 || timeoutSeconds > 60) throw new IllegalArgumentException("Invalid GitHub write timeout");
        this.base = api.replaceFirst("/$", "");
        this.token = token.strip();
        this.json = json;
        this.timeoutSeconds = timeoutSeconds;
    }

    public boolean authenticated() { return !token.isEmpty(); }

    /** Permission check with the write credential: the repo object reports push access for that token. */
    Access repositoryAccess(Key key) {
        JsonNode data = send("GET", "/repos/" + key.fullName(), null, 200);
        return new Access(data.path("permissions").path("push").isBoolean() && data.path("permissions").path("push").asBoolean());
    }

    IssueState issue(Key key, long number) {
        JsonNode value = send("GET", "/repos/" + key.fullName() + "/issues/" + number, null, 200);
        if (value.has("pull_request")) throw new WriteFailure("这个编号对应 PR，不能按 Issue 执行。", false);
        if (!value.path("number").canConvertToLong() || value.path("number").asLong() != number) throw malformed();
        String state = value.path("state").asText("");
        if (!Set.of("open", "closed").contains(state)) throw malformed();
        Instant updatedAt = time(value, "updated_at");
        var labels = new ArrayList<String>();
        if (value.path("labels").isArray()) for (JsonNode label : value.path("labels")) {
            if (labels.size() >= 50) break;
            String name = GithubClient.text(label, "name", 64);
            if (!name.isBlank()) labels.add(name);
        }
        return new IssueState(number, state, updatedAt, List.copyOf(labels));
    }

    PullState pull(Key key, long number) {
        JsonNode value = send("GET", "/repos/" + key.fullName() + "/pulls/" + number, null, 200);
        if (!value.path("number").canConvertToLong() || value.path("number").asLong() != number) throw malformed();
        String state = value.path("state").asText("");
        if (!Set.of("open", "closed").contains(state)) throw malformed();
        String head = GithubClient.text(value.path("head"), "sha", 64);
        if (!head.matches("[a-f0-9]{40}")) throw malformed();
        return new PullState(number, state, head);
    }

    List<String> labels(Key key, long number) {
        JsonNode value = send("GET", "/repos/" + key.fullName() + "/issues/" + number + "/labels", null, 200);
        return labelNames(value);
    }

    CommentResult addComment(Key key, long number, String body) {
        JsonNode value = send("POST", "/repos/" + key.fullName() + "/issues/" + number + "/comments", Map.of("body", body), 201);
        long id = value.path("id").canConvertToLong() ? value.path("id").asLong() : -1;
        String url = GithubClient.text(value, "html_url", 300);
        if (id < 1 || !url.startsWith(key.url() + "/issues/" + number)) throw malformed();
        return new CommentResult(id, url, body);
    }

    List<String> setLabels(Key key, long number, List<String> labels) {
        JsonNode value = send("PUT", "/repos/" + key.fullName() + "/issues/" + number + "/labels",
            Map.of("labels", labels), 200);
        return labelNames(value);
    }

    /** Bounded read used to verify whether a timed-out comment actually landed. */
    List<CommentResult> recentComments(Key key, long number) {
        JsonNode value = send("GET", "/repos/" + key.fullName() + "/issues/" + number + "/comments?per_page=5&sort=created&direction=desc",
            null, 200);
        if (!value.isArray() || value.size() > 5) throw malformed();
        var items = new ArrayList<CommentResult>();
        for (JsonNode node : value) {
            long id = node.path("id").canConvertToLong() ? node.path("id").asLong() : -1;
            if (id < 1) throw malformed();
            items.add(new CommentResult(id, GithubClient.text(node, "html_url", 300),
                node.path("body").isNull() ? "" : node.path("body").asText("")));
        }
        return List.copyOf(items);
    }

    private List<String> labelNames(JsonNode value) {
        if (!value.isArray() || value.size() > 100) throw malformed();
        var names = new ArrayList<String>();
        for (JsonNode label : value) {
            String name = GithubClient.text(label, "name", 64);
            if (name.isBlank()) throw malformed();
            names.add(name);
        }
        return List.copyOf(names);
    }

    private JsonNode send(String method, String path, Object payload, int expected) {
        try {
            var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                .header("User-Agent", "agent-triage");
            if (payload != null) {
                builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(payload)));
            } else if (method.equals("GET")) builder.GET();
            else builder.method(method, HttpRequest.BodyPublishers.noBody());
            if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
            HttpResponse<byte[]> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status == expected) {
                if (response.body() == null || response.body().length > MAX_BODY) throw malformed();
                JsonNode data = json.readTree(response.body());
                if (data == null || data.isMissingNode()) throw malformed();
                return data;
            }
            throw statusFailure(status);
        } catch (java.net.http.HttpTimeoutException error) {
            throw new WriteFailure("GitHub 写入或读取超时，结果不确定。", true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new WriteFailure("GitHub 请求被中断。", false);
        } catch (WriteFailure error) {
            throw error;
        } catch (java.io.IOException | IllegalArgumentException error) {
            throw new WriteFailure("GitHub 连接或响应不可用。", false);
        }
    }

    private static WriteFailure statusFailure(int status) {
        if (status == 401) return new WriteFailure("GitHub 写入 Token 不可用，请检查启动配置。", false);
        if (status == 403) return new WriteFailure("GitHub 拒绝了该操作，请检查写入凭据的仓库权限。", false);
        if (status == 404) return new WriteFailure("GitHub 对象不存在或写入凭据无权访问。", false);
        if (status == 422) return new WriteFailure("GitHub 拒绝了请求内容（422）。", false);
        if (status == 429) return new WriteFailure("GitHub 请求额度已用尽，请稍后再试。", false);
        return new WriteFailure("GitHub 暂时不可用（" + status + "）。", false);
    }

    private static Instant time(JsonNode value, String field) {
        try { return Instant.parse(value.path(field).asText()); }
        catch (RuntimeException error) { throw malformed(); }
    }

    private static WriteFailure malformed() { return new WriteFailure("GitHub 响应格式不完整。", false); }
}
