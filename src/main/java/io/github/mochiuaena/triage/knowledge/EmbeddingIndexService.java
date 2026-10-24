package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;
import static io.github.mochiuaena.triage.knowledge.EmbeddingModels.*;
import static org.springframework.http.HttpStatus.*;

import io.github.mochiuaena.triage.execution.RunFailure;
import io.github.mochiuaena.triage.knowledge.KnowledgeRepository.SearchChunk;
import io.github.mochiuaena.triage.model.EmbeddingClient;
import io.github.mochiuaena.triage.settings.EmbeddingModels.View;
import io.github.mochiuaena.triage.settings.SearchModelSettingsService;

import jakarta.annotation.PreDestroy;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/** Builds the optional vector index by sending current chunk texts to the configured embedding service. */
@Service
public class EmbeddingIndexService {
    private static final String EMBEDDING = io.github.mochiuaena.triage.settings.EmbeddingModels.EMBEDDING;
    private final KnowledgeService knowledge;
    private final KnowledgeRepository store;
    private final EmbeddingRepository vectors;
    private final SearchModelSettingsService settings;
    private final EmbeddingClient client;
    private final Clock clock;
    private final Map<UUID, IndexPreview> previews = new LinkedHashMap<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(2), r -> {
            var t = new Thread(r, "knowledge-embedding");
            t.setDaemon(true);
            return t;
        }, new ThreadPoolExecutor.AbortPolicy());

    @org.springframework.beans.factory.annotation.Autowired
    public EmbeddingIndexService(KnowledgeService knowledge, KnowledgeRepository store, EmbeddingRepository vectors,
                                 SearchModelSettingsService settings, EmbeddingClient client) {
        this(knowledge, store, vectors, settings, client, Clock.systemUTC());
    }

    EmbeddingIndexService(KnowledgeService knowledge, KnowledgeRepository store, EmbeddingRepository vectors,
                          SearchModelSettingsService settings, EmbeddingClient client, Clock clock) {
        this.knowledge = knowledge;
        this.store = store;
        this.vectors = vectors;
        this.settings = settings;
        this.client = client;
        this.clock = clock;
    }

    /** Shows exactly which chunks go to which service before any text leaves the machine. */
    public IndexPreview preview(Scope scope) {
        knowledge.validateSearchScope(scope, null);
        View configured = settings.state().embedding();
        if (configured == null) throw bad("尚未配置 Embedding 服务；请先在模型设置中添加，再建立向量索引。");
        long chunks = store.searchCount(scope, "ALL");
        long sources = store.sources(scope).stream().filter(s -> "ACTIVE".equals(s.state())).count();
        boolean truncated = chunks > MAX_INDEX_CHUNKS;
        String coverage = truncated
            ? "；当前范围有效分段 " + chunks + " 个，超过单次上限 " + MAX_INDEX_CHUNKS + "，本次仅索引排序在前的 "
                + MAX_INDEX_CHUNKS + " 段，其余分段不参与向量检索"
            : "";
        String note = "建立向量索引会把 " + Math.min(chunks, MAX_INDEX_CHUNKS) + " 个当前有效知识分段的正文发送至 "
            + configured.baseUrl() + "（模型 " + configured.model() + "）" + coverage
            + "；更新或删除知识后需要重新建立索引。";
        var preview = new IndexPreview(UUID.randomUUID(), scope, chunks, sources, configured.model(),
            configured.baseUrl(), fingerprint(configured.baseUrl(), configured.model()), truncated, note,
            clock.instant(), clock.instant().plus(Duration.ofMinutes(10)));
        synchronized (previews) {
            previews.values().removeIf(p -> !clock.instant().isBefore(p.expiresAt()));
            if (previews.size() >= 10) throw capacity();
            previews.put(preview.id(), preview);
        }
        return preview;
    }

    public IndexJob submit(Scope scope, UUID previewId, boolean documentsConfirmed) {
        knowledge.validateSearchScope(scope, null);
        if (!documentsConfirmed) throw bad("建立向量索引会把知识分段正文发送至 Embedding 服务；请先核对预览并确认 documentsConfirmed。");
        IndexPreview preview;
        synchronized (previews) {
            previews.values().removeIf(p -> !clock.instant().isBefore(p.expiresAt()));
            preview = previews.get(previewId);
        }
        if (preview == null) throw new ResponseStatusException(GONE, "索引预览已过期或不存在，请重新预览。");
        if (!preview.scope().equals(scope)) throw bad("索引预览属于其他范围，请重新预览。");
        UUID jid = UUID.randomUUID();
        IndexJob job = vectors.createJob(jid, scope, preview.fingerprint(), preview.model(), Math.min(preview.chunks(), MAX_INDEX_CHUNKS));
        try {
            worker.execute(() -> run(jid, preview));
        } catch (RejectedExecutionException e) {
            vectors.finish(jid, "FAILED", "向量索引队列已满，请稍后重试。", 0, 0);
            throw capacity();
        }
        synchronized (previews) { previews.remove(previewId); }
        return job;
    }

    private void run(UUID jid, IndexPreview preview) {
        if (!vectors.start(jid)) return;
        int calls = 0;
        long tokens = 0;
        try {
            knowledge.validateSearchScope(preview.scope(), null);
            var credentials = settings.credentials(EMBEDDING);
            String fingerprint = fingerprint(credentials.baseUrl(), credentials.model());
            if (!fingerprint.equals(preview.fingerprint()))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Embedding 配置在预览后已变化，请重新预览。");
            List<SearchChunk> chunks = store.searchChunks(preview.scope(), "ALL", MAX_INDEX_CHUNKS);
            if (chunks.isEmpty()) {
                vectors.finish(jid, "SUCCEEDED", "当前范围没有需要索引的有效知识分段。", 0, 0);
                return;
            }
            var session = client.session(credentials);
            int dimension = -1, progress = 0;
            Instant now = Instant.now();
            for (int from = 0; from < chunks.size(); from += BATCH_SIZE) {
                if (vectors.cancelled(jid)) throw new CancellationException();
                var batch = chunks.subList(from, Math.min(chunks.size(), from + BATCH_SIZE));
                List<String> texts = batch.stream().map(c -> c.chunk().text()).toList();
                var result = session.embed(texts);
                if (dimension == -1) dimension = result.dimensions();
                else if (result.dimensions() != dimension)
                    throw new RunFailure("MODEL_HTTP_ERROR", "Embedding 服务返回的向量维度不一致，任务终止。");
                for (int i = 0; i < batch.size(); i++) {
                    var chunk = batch.get(i).chunk();
                    vectors.replace(preview.scope(), new VectorRow(chunk.id(), chunk.versionId(),
                        batch.get(i).version().sourceId(), fingerprint, credentials.model(), dimension,
                        result.vectors().get(i), now));
                }
                calls++;
                tokens += nonNegative(result.totalTokens() == null ? result.promptTokens() : result.totalTokens());
                progress += batch.size();
                vectors.progress(jid, progress, calls, tokens);
            }
            vectors.deleteOtherFingerprints(preview.scope(), fingerprint);
            String message = "向量索引完成：" + progress + " 段 · " + dimension + " 维 · Embedding 调用 " + calls
                + " 次 · 已知 tokens " + (tokens > 0 ? tokens : "未知") + "。正文已发送至服务；知识更新后需重新建立索引。";
            vectors.finish(jid, "SUCCEEDED", message, calls, tokens);
        } catch (CancellationException e) {
            vectors.finish(jid, Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "CANCELLED",
                "向量索引已取消或中断；已嵌入的分段保留，可重新建立索引补齐。", calls, tokens);
        } catch (Exception e) {
            String reason = e instanceof RunFailure f ? f.getMessage()
                : e instanceof ResponseStatusException r && r.getStatusCode().value() == 409
                    ? "Embedding 配置在预览后已变化，请重新预览。" : null;
            vectors.finish(jid, Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "FAILED",
                "向量索引失败：" + (reason == null ? "服务连接失败或返回异常，已嵌入的分段保留，请核对配置后重试。" : reason), calls, tokens);
        }
    }

    private static long nonNegative(Integer value) { return value == null || value < 0 ? 0 : value; }

    public List<IndexJob> jobs(Scope scope) {
        knowledge.validateSearchScope(scope, null);
        return vectors.jobs(scope);
    }

    public IndexJob job(UUID jid) {
        IndexJob job = vectors.job(jid);
        knowledge.validateSearchScope(job.scope(), null);
        return job;
    }

    public IndexJob cancel(UUID jid) {
        IndexJob job = vectors.job(jid);
        knowledge.validateSearchScope(job.scope(), null);
        return vectors.cancel(jid);
    }

    private static ResponseStatusException bad(String text) { return error(BAD_REQUEST, text); }

    private static ResponseStatusException error(HttpStatus status, String text) {
        return new ResponseStatusException(status, text);
    }

    private static ResponseStatusException capacity() {
        return error(TOO_MANY_REQUESTS, "向量索引预览或任务已达容量上限，请稍后重试（最多 10 个预览、1 个任务及 2 个排队）。");
    }

    @PreDestroy
    public void close() { worker.shutdownNow(); }
}
