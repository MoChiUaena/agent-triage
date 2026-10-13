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
    private record Payload(JsonNode data, boolean truncated) {}
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
        return new Health(repository, issues, pulls, workflows, Instant.now());
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
    private Payload get(String path) {
        var builder = HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(8)).GET()
            .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "agent-triage");
        if (!token.isEmpty()) builder.header("Authorization", "Bearer " + token);
        var body = new BoundedBody();
        var pending = http.sendAsync(builder.build(), info -> body);
        try {
            var response = pending.get(8, TimeUnit.SECONDS);
                int status = response.statusCode();
                if (status == 401) throw new ResponseStatusException(SERVICE_UNAVAILABLE, "GitHub Token 不可用，请检查启动配置。");
                if (status == 404) throw new ResponseStatusException(NOT_FOUND, "GitHub 仓库或数据不可访问，请核对名称与访问权限。");
                if (status == 429 || status == 403 && (response.headers().firstValue("X-RateLimit-Remaining").orElse("").equals("0")
                    || response.headers().firstValue("Retry-After").isPresent()))
                    throw new ResponseStatusException(TOO_MANY_REQUESTS, "GitHub 请求额度已用尽，请稍后更新。");
                if (status == 403) throw new ResponseStatusException(FORBIDDEN, "GitHub 未允许读取这部分数据，请检查访问权限。");
                if (status >= 300 && status < 400) throw new ResponseStatusException(CONFLICT, "GitHub 仓库地址发生变化，请登记新地址。");
                if (status != 200) throw new ResponseStatusException(BAD_GATEWAY, "GitHub 暂时不可用，请稍后更新。");
                byte[] bytes = response.body();
                JsonNode data = json.readTree(bytes);
                if (data == null) throw invalid();
                return new Payload(data, response.headers().allValues("Link").stream().anyMatch(value -> value.contains("rel=\"next\"")));
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
        } catch (IOException | IllegalArgumentException error) {
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
    private static String text(JsonNode value, String field, int max) {
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
    private static ResponseStatusException invalid() { return new ResponseStatusException(BAD_GATEWAY, "GitHub 响应格式不完整，请稍后更新。"); }
}
