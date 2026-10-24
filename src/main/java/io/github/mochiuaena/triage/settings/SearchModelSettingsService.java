package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.execution.RunFailure;
import io.github.mochiuaena.triage.model.EmbeddingClient;
import io.github.mochiuaena.triage.model.RerankClient;
import io.github.mochiuaena.triage.settings.EmbeddingModels.*;
import io.github.mochiuaena.triage.settings.SearchModelRepository.Stored;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/** Single optional embedding and rerank configuration; credentials stay local, views never carry secrets. */
@Service
public class SearchModelSettingsService {
    private final SearchModelRepository repository;
    private final CredentialCipher cipher;
    private final EmbeddingClient embeddings;
    private final RerankClient reranks;

    public SearchModelSettingsService(SearchModelRepository repository, CredentialCipher cipher,
                                      EmbeddingClient embeddings, RerankClient reranks) {
        this.repository = repository;
        this.cipher = cipher;
        this.embeddings = embeddings;
        this.reranks = reranks;
    }

    public ConfigState state() {
        return new ConfigState(repository.find(EmbeddingModels.EMBEDDING).map(Stored::view).orElse(null),
            repository.find(EmbeddingModels.RERANK).map(Stored::view).orElse(null));
    }

    public Optional<Stored> stored(String kind) {
        return repository.find(canonical(kind));
    }

    /** Decrypted credentials for one external call; callers must not log or persist the key. */
    public record Credentials(String kind, UUID id, String baseUrl, String model, String apiKey, Duration timeout) {
        @Override public String toString() { return "SearchModelCredentials[kind=" + kind + ", credentials=redacted]"; }
    }

    public Credentials credentials(String kind) {
        Stored stored = stored(kind).orElseThrow(() -> error(HttpStatus.BAD_REQUEST,
            "尚未配置" + label(kind) + "服务；请先在模型设置中添加，或改用不需要该服务的方式。"));
        return new Credentials(stored.kind(), stored.id(), stored.baseUrl(), stored.model(),
            cipher.decrypt(stored.id(), stored.encryptedKey()), Duration.ofSeconds(stored.timeoutSeconds()));
    }

    public View save(String kind, Input input) {
        kind = canonical(kind);
        if (input == null) throw error(HttpStatus.BAD_REQUEST, "请填写服务配置。");
        String url = normalize(input.baseUrl());
        Stored existing = repository.find(kind).orElse(null);
        if (existing == null) {
            if (input.apiKey() == null || input.apiKey().isBlank())
                throw error(HttpStatus.BAD_REQUEST, "新增" + label(kind) + "服务需要填写 API Key。");
            UUID id = UUID.randomUUID();
            String key = input.apiKey().strip();
            validate(kind, input, url, key);
            Stored stored = new Stored(kind, id, input.displayName().strip(), url, input.model().strip(),
                cipher.encrypt(id, key, repository.count() == 0), input.timeoutSeconds(), 1, Instant.now());
            repository.insert(stored);
            return stored.view();
        }
        if (existing.version() != input.version())
            throw error(HttpStatus.CONFLICT, "配置已被修改，请刷新后重新编辑。");
        boolean replace = input.apiKey() != null && !input.apiKey().isBlank();
        if (!url.equals(existing.baseUrl()) && !replace)
            throw error(HttpStatus.BAD_REQUEST, "修改服务地址时请重新填写 API Key。");
        String key = replace ? input.apiKey().strip() : cipher.decrypt(existing.id(), existing.encryptedKey());
        validate(kind, input, url, key);
        Stored updated = new Stored(kind, existing.id(), input.displayName().strip(), url, input.model().strip(),
            replace ? cipher.encrypt(existing.id(), key, false) : existing.encryptedKey(),
            input.timeoutSeconds(), existing.version() + 1, existing.createdAt());
        if (!repository.update(updated, existing.version()))
            throw error(HttpStatus.CONFLICT, "配置已被修改，请刷新后重新编辑。");
        return updated.view();
    }

    public void delete(String kind, long version) {
        String checked = canonical(kind);
        Stored stored = stored(checked).orElseThrow(() -> error(HttpStatus.NOT_FOUND, "尚未配置" + label(checked) + "服务。"));
        if (stored.version() != version) throw error(HttpStatus.CONFLICT, "配置已被修改，请刷新后再删除。");
        repository.delete(stored.id());
    }

    /** One short paid call against the saved configuration; failures never expose remote response bodies. */
    public TestResult test(String kind) {
        String checked = canonical(kind);
        Credentials credentials = credentials(checked);
        long start = System.nanoTime();
        try {
            if (EmbeddingModels.RERANK.equals(checked)) {
                reranks.rerank(credentials, "连接池诊断测试", List.of("文档一：数据库连接池配置。", "文档二：前端发布流程。"));
                return new TestResult(true, "Rerank 服务可用。", null, null, elapsed(start));
            }
            var batch = embeddings.session(credentials).embed(List.of("连接池诊断测试"));
            Integer reported = batch.totalTokens() == null ? batch.promptTokens() : batch.totalTokens();
            Long tokens = reported == null ? null : reported.longValue();
            return new TestResult(true, "Embedding 服务可用，向量维度 " + batch.dimensions() + "。", batch.dimensions(),
                tokens, elapsed(start));
        } catch (RunFailure e) {
            return new TestResult(false, message(e), null, null, elapsed(start));
        } catch (Exception e) {
            return new TestResult(false, "调用失败，请检查地址、模型名与 Key。", null, null, elapsed(start));
        }
    }

    private static String message(Exception e) {
        String text = e.getMessage();
        return text == null || text.isBlank() ? "调用失败，请检查地址、模型名与 Key。" : text;
    }

    private static long elapsed(long start) { return Duration.ofNanos(System.nanoTime() - start).toMillis(); }

    private static String canonical(String kind) {
        String value = kind == null ? "" : kind.trim().toUpperCase(Locale.ROOT);
        if (!EmbeddingModels.EMBEDDING.equals(value) && !EmbeddingModels.RERANK.equals(value))
            throw error(HttpStatus.BAD_REQUEST, "服务类型必须为 embedding 或 rerank。");
        return value;
    }

    public static String label(String kind) {
        return EmbeddingModels.RERANK.equals(kind) ? "Rerank 重排" : "Embedding";
    }

    private static String normalize(String url) {
        String normalized = url == null ? "" : url.strip().replaceAll("/+$", "");
        if (normalized.endsWith("/chat/completions") || normalized.endsWith("/rerank") || normalized.endsWith("/embeddings"))
            throw error(HttpStatus.BAD_REQUEST, "请填写 Base URL，不要包含接口路径。");
        return normalized;
    }

    private void validate(String kind, Input input, String url, String key) {
        if (input.displayName() == null || input.displayName().strip().isEmpty() || input.displayName().strip().length() > 80
            || key.length() > 4096 || key.chars().anyMatch(c -> c < 33 || c > 126)
            || input.model() == null || !input.model().strip().matches("[A-Za-z0-9._:/-]{1,120}")
            || input.timeoutSeconds() < 1 || input.timeoutSeconds() > 60)
            throw error(HttpStatus.BAD_REQUEST, "配置无效。请检查名称、模型名、Key 和超时时间。");
        URI uri;
        try { uri = URI.create(url); }
        catch (RuntimeException e) { throw error(HttpStatus.BAD_REQUEST, "服务地址无效。"); }
        boolean localHttp = "http".equals(uri.getScheme())
            && Set.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost() == null ? "" : uri.getHost());
        if (uri.getHost() == null || (!"https".equals(uri.getScheme()) && !localHttp)
            || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
            throw error(HttpStatus.BAD_REQUEST, "服务地址须为 HTTPS 或本机 HTTP，不能包含凭据、查询参数或片段。");
    }

    static ResponseStatusException error(HttpStatus status, String text) {
        return new ResponseStatusException(status, text);
    }
}
