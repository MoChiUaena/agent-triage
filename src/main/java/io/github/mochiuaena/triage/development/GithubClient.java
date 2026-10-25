package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.GithubModels.*;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

/** Only reads fixed GitHub REST routes; no user-supplied host or redirect is followed. */
@Component
public class GithubClient {
    record Payload(JsonNode data, boolean truncated) {}
    private final String base;
    private final String token;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
        .followRedirects(HttpClient.Redirect.NEVER).build();

    public GithubClient(ObjectMapper json, @Value("${triage.github.api-base-url:https://api.github.com}") String api,
                        @Value("${triage.github.token:}") String token) {
        URI uri = URI.create(api);
        boolean official = "https://api.github.com".equals(api) || "https://api.github.com/".equals(api);
        boolean local = "http".equals(uri.getScheme()) && Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost())
            && uri.getPort() > 0 && uri.getPort() <= 65535 && (uri.getPath().isEmpty() || uri.getPath().equals("/"))
            && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        if (!official && !local) throw new IllegalArgumentException("GitHub API must be the official origin or an explicit local test server");
        this.base = api.replaceFirst("/$", ""); this.token = token.strip(); this.json = json;
        if (!this.token.matches("[A-Za-z0-9_.-]{0,512}")) throw new IllegalArgumentException("Invalid GitHub token configuration");
    }
    public boolean authenticated() { return !token.isEmpty(); }

    public CiModels.Context ciContext(Key key, long runId, Integer attempt, boolean includeJobs) {
        if (runId < 1 || attempt != null && attempt < 1) throw new IllegalArgumentException("Invalid CI run");
        String path = "/repos/" + key.fullName() + "/actions/runs/" + runId;
        JsonNode run = get(path + (attempt == null ? "" : "/attempts/" + attempt)).data();
        int selected = (int) positive(run, "run_attempt");
        String sha = text(run, "head_sha", 64);
        if (positive(run, "id") != runId || selected < 1 || attempt != null && selected != attempt
            || !sha.matches("[a-f0-9]{40}") || !key.fullName().equalsIgnoreCase(run.path("repository").path("full_name").asText())) throw invalid();
        var jobs = new ArrayList<CiModels.Job>(); boolean truncated = false;
        if (includeJobs) {
            Payload page = get(path + "/attempts/" + selected + "/jobs?per_page=50");
            if (!page.data().path("jobs").isArray()) throw invalid();
            for (var node : page.data().path("jobs")) {
                if (jobs.size() == 50) { truncated = true; break; }
                var job = ciJob(key, node);
                if (job.runId() != runId || job.attempt() != selected || !job.headSha().equals(sha)) throw invalid();
                jobs.add(job);
            }
            truncated |= page.truncated();
        }
        return new CiModels.Context(runId, selected, sha, text(run, "name", 160), text(run, "head_branch", 160),
            text(run, "status", 40), text(run, "conclusion", 40), key.url() + "/actions/runs/" + runId + "/attempts/" + selected,
            List.copyOf(jobs), truncated);
    }
    public CiModels.Job ciJob(Key key, long id) { return ciJob(key, get("/repos/" + key.fullName() + "/actions/jobs/" + id).data()); }
    private CiModels.Job ciJob(Key key, JsonNode node) {
        long id = positive(node, "id"), run = positive(node, "run_id");
        int attempt = (int) positive(node, "run_attempt");
        String sha = text(node, "head_sha", 64);
        if (attempt < 1 || !sha.matches("[a-f0-9]{40}") || !node.path("steps").isArray() || node.path("steps").size() > 100) throw invalid();
        var steps = new ArrayList<CiModels.Step>();
        for (var step : node.path("steps")) steps.add(new CiModels.Step((int) positive(step, "number"), text(step, "name", 200),
            text(step, "conclusion", 40), optionalTime(step, "started_at"), optionalTime(step, "completed_at")));
        return new CiModels.Job(id, run, attempt, sha, text(node, "name", 200), text(node, "status", 40),
            text(node, "conclusion", 40), List.copyOf(steps), key.url() + "/actions/runs/" + run + "/job/" + id);
    }
    public String ciLog(Key key, long jobId) {
        HttpResponse<byte[]> response = send(URI.create(base + "/repos/" + key.fullName() + "/actions/jobs/" + jobId + "/logs"), true);
        if (response.statusCode() == 302) {
            URI target;
            try { target = URI.create(response.headers().firstValue("Location").orElseThrow()); }
            catch (RuntimeException error) { throw invalid(); }
            String host = target.getHost();
            boolean officialDownload = "https".equals(target.getScheme()) && host != null
                && (host.endsWith(".blob.core.windows.net") || host.endsWith(".actions.githubusercontent.com"))
                && (target.getPort() == -1 || target.getPort() == 443);
            URI origin = URI.create(base);
            boolean localFixture = "http".equals(origin.getScheme()) && Objects.equals(origin.getHost(), host)
                && target.getScheme().equals(origin.getScheme()) && target.getPort() == origin.getPort();
            if ((!officialDownload && !localFixture) || target.getUserInfo() != null || target.getFragment() != null)
                throw new ResponseStatusException(BAD_GATEWAY, "GitHub 日志下载地址不符合受信来源。");
            response = send(target, false); // Signed download URLs never receive the API credential.
        }
        checkStatus(response);
        return new String(response.body(), StandardCharsets.UTF_8);
    }
    private static Instant optionalTime(JsonNode node, String key) {
        if (node.path(key).isNull() || node.path(key).isMissingNode()) return null;
        try { return Instant.parse(node.path(key).asText()); } catch (RuntimeException error) { throw invalid(); }
    }

    public Health read(Key key) {
        String path = "/repos/" + key.fullName();
        JsonNode info = get(path).data();
        if (!info.isObject() || !key.fullName().equalsIgnoreCase(info.path("full_name").asText())
            || !info.path("private").isBoolean() || !info.path("archived").isBoolean()
            || !info.path("open_issues_count").canConvertToLong()) throw invalid();
        String branch = text(info, "default_branch", 160);
        if (branch.isEmpty()) throw invalid();
        var repository = new RepositoryInfo(text(info, "full_name", 140), text(info, "description", 500), branch,
            info.path("private").asBoolean(), info.path("archived").asBoolean(), info.path("open_issues_count").asLong());
        var issues = section(path + "/issues?state=open&sort=updated&per_page=50", "", node -> {
            if (node.has("pull_request")) return null;
            long number = positive(node, "number");
            var labels = new ArrayList<String>();
            if (node.path("labels").isArray()) for (var label : node.path("labels")) {
                if (labels.size() == 10) break;
                labels.add(text(label, "name", 64));
            }
            return new Issue(number, text(node, "title", 300), key.url() + "/issues/" + number,
                timestamp(node), List.copyOf(labels));
        });
        var pulls = section(path + "/pulls?state=open&sort=updated&per_page=20", "", node -> {
            long number = positive(node, "number");
            return new PullRequest(number, text(node, "title", 300), key.url() + "/pull/" + number,
                timestamp(node), node.path("draft").asBoolean(), text(node.path("head"), "sha", 64));
        });
        var workflows = section(path + "/actions/runs?per_page=20&branch=" + URLEncoder.encode(branch, StandardCharsets.UTF_8),
            "workflow_runs", node -> {
                long id = positive(node, "id");
                return new Workflow(id, text(node, "name", 160), key.url() + "/actions/runs/" + id,
                    text(node, "status", 40), text(node, "conclusion", 40), text(node, "head_branch", 160),
                    text(node, "head_sha", 64), timestamp(node));
            });
        var commits = section(path + "/commits?per_page=20&sha=" + URLEncoder.encode(branch, StandardCharsets.UTF_8),
            "", node -> {
                String sha = text(node, "sha", 64);
                if (!sha.matches("[a-f0-9]{40}")) throw invalid();
                JsonNode meta = node.path("commit");
                Instant committedAt = optionalTime(meta.path("author"), "date");
                if (committedAt == null) committedAt = optionalTime(meta.path("committer"), "date");
                if (committedAt == null) throw invalid();
                return new Commit(sha, text(meta, "message", 400), text(meta.path("author"), "name", 100),
                    key.url() + "/commit/" + sha, committedAt);
            });
        return new Health(repository, issues, pulls, workflows, commits, Instant.now());
    }

    private <T> Section<T> section(String path, String field, Function<JsonNode,T> convert) {
        try {
            Payload payload = get(path);
            JsonNode values = field.isEmpty() ? payload.data() : payload.data().path(field);
            if (!values.isArray()) throw invalid();
            var items = new ArrayList<T>();
            boolean truncated = payload.truncated();
            for (var node : values) {
                T item = convert.apply(node);
                if (item != null) { if (items.size() < 20) items.add(item); else truncated = true; }
            }
            return new Section<>(true, List.copyOf(items), truncated, "");
        } catch (ResponseStatusException error) {
            return new Section<>(false, List.of(), false, error.getReason());
        }
    }
    Payload get(String path) {
        var response = send(URI.create(base + path), true);
        checkStatus(response);
        try {
            JsonNode data = json.readTree(response.body());
            if (data == null) throw invalid();
            return new Payload(data, response.headers().allValues("Link").stream().anyMatch(value -> value.contains("rel=\"next\"")));
        } catch (IOException error) { throw invalid(); }
    }
    private void checkStatus(HttpResponse<?> response) {
        int status = response.statusCode();
        if (status == 200) return;
        if (status == 401) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitHub Token 不可用，请检查启动配置。");
        if (status == 404) throw new ResponseStatusException(NOT_FOUND, "GitHub 仓库或数据不可访问，请核对名称与访问权限。");
        if (status == 429 || status == 403 && (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")
            || response.headers().firstValue("Retry-After").isPresent()))
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "GitHub 请求额度已用尽，请稍后更新。");
        if (status == 403) throw new ResponseStatusException(FORBIDDEN, "GitHub 未允许读取这部分数据，请检查访问权限。");
        if (status >= 300 && status < 400) throw new ResponseStatusException(CONFLICT, "GitHub 地址发生变化，请重新读取对应信息。");
        throw new ResponseStatusException(BAD_GATEWAY, "GitHub 暂时不可用，请稍后更新。");
    }
    private HttpResponse<byte[]> send(URI uri, boolean credential) {
        var builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(8)).GET()
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "agent-triage");
        if (credential && !token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        var body = new BoundedBody();
        var pending = http.sendAsync(builder.build(), info -> body);
        try {
            return pending.get(8, TimeUnit.SECONDS);
        } catch (TimeoutException error) {
            body.cancel(); pending.cancel(true);
            throw new ResponseStatusException(BAD_GATEWAY, "GitHub 读取超时，请稍后更新。");
        } catch (InterruptedException error) {
            body.cancel(); pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(BAD_GATEWAY, "GitHub 读取已中断，请重试。");
        } catch (ExecutionException error) {
            for (Throwable cause = error; cause != null; cause = cause.getCause())
                if (cause instanceof ResponseStatusException problem) throw problem;
            throw new ResponseStatusException(BAD_GATEWAY, "GitHub 连接或响应不可用，请稍后更新。");
        } catch (IllegalArgumentException error) {
            throw new ResponseStatusException(BAD_GATEWAY, "GitHub 连接或响应不可用，请稍后更新。");
        }
    }
    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private volatile Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) {
            subscription = value;
            if (result.isDone()) value.cancel(); else value.request(1);
        }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 1_048_576) {
                    subscription.cancel();
                    result.completeExceptionally(new ResponseStatusException(BAD_GATEWAY, "GitHub 返回的数据超过读取上限。"));
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
        void cancel() {
            result.completeExceptionally(new IOException("Cancelled"));
            Flow.Subscription value = subscription; if (value != null) value.cancel();
        }
    }
    static String text(JsonNode value, String field, int max) {
        String text = value.path(field).isTextual() ? value.path(field).asText() : "";
        return text.length() <= max ? text : text.substring(0, max);
    }
    private static long positive(JsonNode value, String field) {
        if (!value.path(field).canConvertToLong() || value.path(field).asLong() < 1) throw invalid();
        return value.path(field).asLong();
    }
    private static Instant timestamp(JsonNode value) {
        try { return Instant.parse(value.path("updated_at").asText()); }
        catch (RuntimeException error) { throw invalid(); }
    }
    static ResponseStatusException invalid() { return new ResponseStatusException(BAD_GATEWAY, "GitHub 响应格式不完整，请稍后更新。"); }
}
