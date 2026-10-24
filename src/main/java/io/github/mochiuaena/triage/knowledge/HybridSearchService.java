package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.EmbeddingModels.*;
import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import io.github.mochiuaena.triage.execution.RunFailure;
import io.github.mochiuaena.triage.knowledge.HybridFusion.Fused;
import io.github.mochiuaena.triage.knowledge.KnowledgeRepository.SearchChunk;
import io.github.mochiuaena.triage.knowledge.KnowledgeSearchService.Citation;
import io.github.mochiuaena.triage.knowledge.KnowledgeSearchService.KeywordPool;
import io.github.mochiuaena.triage.model.EmbeddingClient;
import io.github.mochiuaena.triage.model.RerankClient;
import io.github.mochiuaena.triage.settings.EmbeddingModels.View;
import io.github.mochiuaena.triage.settings.SearchModelSettingsService;
import io.github.mochiuaena.triage.settings.SearchModelSettingsService.Credentials;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Optional hybrid retrieval: local keyword candidates fused (RRF) with vector candidates from the
 * embedding index, then an optional rerank pass. Every degraded stage keeps working and says why;
 * citations always point back to the original persisted chunks.
 */
@Service
public class HybridSearchService {
    static final int KEYWORD_POOL = 100, VECTOR_POOL = 100, RERANK_POOL = 20, RRF_K = 60;
    private static final String EMBEDDING = io.github.mochiuaena.triage.settings.EmbeddingModels.EMBEDDING;
    private static final String RERANK = io.github.mochiuaena.triage.settings.EmbeddingModels.RERANK;

    private final KnowledgeService knowledge;
    private final KnowledgeSearchService search;
    private final KnowledgeRepository store;
    private final EmbeddingRepository vectors;
    private final SearchModelSettingsService settings;
    private final EmbeddingClient embeddings;
    private final RerankClient reranks;

    public HybridSearchService(KnowledgeService knowledge, KnowledgeSearchService search, KnowledgeRepository store,
                               EmbeddingRepository vectors, SearchModelSettingsService settings,
                               EmbeddingClient embeddings, RerankClient reranks) {
        this.knowledge = knowledge;
        this.search = search;
        this.store = store;
        this.vectors = vectors;
        this.settings = settings;
        this.embeddings = embeddings;
        this.reranks = reranks;
    }

    public record HybridHit(Citation citation, List<String> matchedBy, double fusedScore, Double rerankScore) {}

    public record VectorStatus(String status, String reason, long indexedChunks, long activeChunks,
                               int scannedEmbeddings, int dimension, Integer queryTokens) {}

    public record RerankStatus(String status, String reason, int candidates) {}

    public record HybridResult(Scope scope, String query, String docType, String service, String recallMethod,
                               List<HybridHit> results, int scannedChunks, long availableChunks, boolean truncated,
                               String coverage, VectorStatus vector, RerankStatus rerank) {}

    public record Input(String scopeKind, UUID scopeId, String query, String docType, String service, Integer limit,
                        boolean confirmed, boolean rerank, boolean documentsConfirmed) {}

    public HybridResult search(Input input) {
        int limit = input.limit() == null ? 10 : input.limit();
        if (limit < 1 || limit > 20) throw bad("结果上限须为 1–20。");
        View embeddingView = settings.state().embedding();
        if (embeddingView == null)
            throw bad("尚未配置 Embedding 服务；关键词检索不需要该服务，或在模型设置中添加后重试。");
        if (!input.confirmed())
            throw bad("混合检索会把检索词发送至 Embedding 服务（" + embeddingView.displayName() + " · "
                + embeddingView.model() + "）；请先核对发送范围并确认 confirmed。");
        View rerankView = settings.state().rerank();
        if (input.rerank() && rerankView != null && !input.documentsConfirmed())
            throw bad("启用重排会把融合候选的摘录发送至 Rerank 服务（" + rerankView.displayName() + " · "
                + rerankView.model() + "）；请单独确认 documentsConfirmed。");

        KeywordPool pool = search.keywordPool(new Scope(input.scopeKind(), input.scopeId()),
            input.query(), input.docType(), input.service(), KEYWORD_POOL);
        Scope scope = pool.scope();

        VectorStage vector = vectorStage(scope, pool);
        List<Fused> fused = HybridFusion.fuse(poolKeywordOrder(pool), vector.order(), RRF_K);
        List<SearchChunk> rows = store.chunksByIds(scope, fused.stream().map(Fused::chunkId).toList());
        Map<UUID, SearchChunk> byChunk = new HashMap<>();
        rows.forEach(row -> byChunk.put(row.chunk().id(), row));

        RerankStage rerankStage = rerankStage(input, rerankView, fused, byChunk, pool);
        List<Fused> ordered = rerankStage.order() == null ? fused : rerankStage.order();

        List<HybridHit> hits = new ArrayList<>();
        for (Fused item : ordered.subList(0, Math.min(limit, ordered.size()))) {
            SearchChunk row = byChunk.get(item.chunkId());
            if (row == null) continue;
            List<String> matchedBy = new ArrayList<>();
            if (item.keywordHit()) matchedBy.add("KEYWORD");
            if (item.vectorHit()) matchedBy.add("VECTOR");
            String method = matchedBy.size() == 2 ? "FUSED" : matchedBy.isEmpty() ? "FUSED" : matchedBy.getFirst();
            Citation citation = KnowledgeSearchService.citation(row.version(), row.chunk(), pool.tokens(), 0, false, method);
            hits.add(new HybridHit(citation, List.copyOf(matchedBy), round(item.score()),
                rerankStage.scores().get(item.chunkId())));
        }

        boolean truncated = pool.truncated() || vector.truncated();
        return new HybridResult(scope, pool.query(), pool.type(), pool.service(), "HYBRID", List.copyOf(hits),
            pool.scannedChunks(), pool.availableChunks(), truncated, coverage(pool, vector, rerankStage),
            vector.status(), rerankStage.status());
    }

    private record VectorStage(VectorStatus status, List<UUID> order, boolean truncated) {}

    private VectorStage vectorStage(Scope scope, KeywordPool pool) {
        Credentials credentials;
        try { credentials = settings.credentials(EMBEDDING); }
        catch (ResponseStatusException e) {
            return new VectorStage(new VectorStatus("UNAVAILABLE", "Embedding 配置不可用；已回退关键词排序。", 0, 0, 0, 0, null),
                List.of(), false);
        }
        String fingerprint = fingerprint(credentials.baseUrl(), credentials.model());
        long indexed = vectors.countVectors(scope, fingerprint, pool.type());
        long active = store.searchCount(scope, pool.type());
        if (indexed == 0) {
            String reason;
            if (vectors.countVectors(scope, fingerprint, "ALL") > 0)
                reason = "当前筛选类型内没有已索引的有效分段；已回退关键词排序。";
            else if (vectors.countScopeVectors(scope) > 0)
                reason = "Embedding 配置已变化，当前范围的向量索引需要重建；已回退关键词排序。";
            else
                reason = "尚未建立向量索引；请先在知识页生成向量索引，或使用关键词检索。已回退关键词排序。";
            return new VectorStage(new VectorStatus("UNAVAILABLE", reason, 0, active, 0, 0, null), List.of(), false);
        }
        try {
            var batch = embeddings.session(credentials).embed(List.of(pool.query()));
            Integer tokens = batch.totalTokens() == null ? batch.promptTokens() : batch.totalTokens();
            List<VectorRow> rows = vectors.loadVectors(scope, fingerprint, pool.type(), MAX_INDEX_CHUNKS);
            List<VectorRow> matching = new ArrayList<>();
            int foreignDimensions = 0;
            for (VectorRow row : rows) {
                if (row.dimensions() != batch.dimensions()) foreignDimensions++;
                else matching.add(row);
            }
            if (matching.isEmpty())
                return new VectorStage(new VectorStatus("UNAVAILABLE", "向量维度与索引不一致（索引中有 " + foreignDimensions
                    + " 段为其他维度，本次查询 " + batch.dimensions() + " 维），请重建向量索引；已回退关键词排序。",
                    indexed, active, rows.size(), batch.dimensions(), tokens), List.of(), false);
            record Scored(UUID id, double similarity) {}
            List<Scored> scored = new ArrayList<>();
            for (VectorRow row : matching)
                scored.add(new Scored(row.chunkId(), HybridFusion.cosine(batch.vectors().getFirst(), row.vector())));
            scored.sort(Comparator.comparingDouble(Scored::similarity).reversed().thenComparing(Scored::id));
            List<UUID> order = scored.stream().limit(VECTOR_POOL).map(Scored::id).toList();
            StringBuilder reason = new StringBuilder("已使用 ").append(matching.size()).append(" 段当前索引（本类型索引 ")
                .append(indexed).append(" 段，范围内当前分段 ").append(active).append(" 段）");
            if (matching.size() < rows.size()) reason.append("；部分索引分段维度不一致，需重建索引");
            if (indexed < active) reason.append("；向量索引不完整（").append(indexed).append("/").append(active).append("），知识更新后请重建索引");
            reason.append("。");
            return new VectorStage(new VectorStatus("USED", reason.toString(), indexed, active, rows.size(),
                batch.dimensions(), tokens), order, indexed > MAX_INDEX_CHUNKS);
        } catch (RunFailure e) {
            return new VectorStage(new VectorStatus("FAILED", "Embedding 服务调用失败：" + e.getMessage() + "已回退关键词排序。",
                indexed, active, 0, 0, null), List.of(), false);
        } catch (Exception e) {
            return new VectorStage(new VectorStatus("FAILED", "Embedding 服务连接失败或超时；已回退关键词排序。",
                indexed, active, 0, 0, null), List.of(), false);
        }
    }

    private static List<UUID> poolKeywordOrder(KeywordPool pool) {
        return pool.hits().stream().map(Citation::chunkId).toList();
    }

    private record RerankStage(RerankStatus status, List<Fused> order, Map<UUID, Double> scores) {
        static RerankStage off(String reason, int candidates) {
            return new RerankStage(new RerankStatus("OFF", reason, candidates), null, Map.of());
        }
    }

    private RerankStage rerankStage(Input input, View rerankView, List<Fused> fused, Map<UUID, SearchChunk> byChunk,
                                    KeywordPool pool) {
        if (!input.rerank()) return RerankStage.off("本次未启用重排。", 0);
        if (rerankView == null) return RerankStage.off("未配置 Rerank 服务，保持融合排序。", 0);
        List<Fused> candidates = fused.subList(0, Math.min(RERANK_POOL, fused.size()));
        if (candidates.isEmpty()) return new RerankStage(new RerankStatus("OFF", "没有可重排的候选。", 0), null, Map.of());
        List<String> documents = new ArrayList<>();
        for (Fused item : candidates) {
            SearchChunk row = byChunk.get(item.chunkId());
            documents.add(row == null ? "" : row.chunk().text().substring(0,
                Math.min(RERANK_DOCUMENT_CHARS, row.chunk().text().length())));
        }
        Credentials credentials;
        try { credentials = settings.credentials(RERANK); }
        catch (ResponseStatusException e) { return RerankStage.off("Rerank 配置不可用，保持融合排序。", candidates.size()); }
        try {
            List<RerankClient.Scored> scored = reranks.rerank(credentials, pool.query(), documents);
            Map<Integer, Double> byDocument = new LinkedHashMap<>();
            scored.forEach(s -> byDocument.putIfAbsent(s.index(), s.score()));
            List<Fused> ordered = HybridFusion.reranked(candidates, new ArrayList<>(byDocument.keySet()),
                new ArrayList<>(byDocument.values()), candidates::indexOf);
            Map<UUID, Double> byChunkScore = new LinkedHashMap<>();
            for (int i = 0; i < ordered.size(); i++) {
                Double value = byDocument.get(candidates.indexOf(ordered.get(i)));
                if (value != null) byChunkScore.put(ordered.get(i).chunkId(), value);
            }
            return new RerankStage(new RerankStatus("USED", "已按 Rerank 分数重排 " + candidates.size() + " 个融合候选"
                + (byDocument.size() < candidates.size() ? "；服务返回 " + byDocument.size() + " 项，其余保持融合顺序。" : "。"),
                candidates.size()), List.copyOf(ordered), Map.copyOf(byChunkScore));
        } catch (RunFailure e) {
            return new RerankStage(new RerankStatus("FAILED", "Rerank 调用失败：" + e.getMessage() + "保持融合排序。",
                candidates.size()), null, Map.of());
        } catch (Exception e) {
            return new RerankStage(new RerankStatus("FAILED", "Rerank 连接失败或超时，保持融合排序。", candidates.size()), null, Map.of());
        }
    }

    private static String coverage(KeywordPool pool, VectorStage vector, RerankStage rerank) {
        StringBuilder text = new StringBuilder("混合检索在本机完成融合（RRF，k=").append(RRF_K).append("），关键词扫描 ")
            .append(pool.scannedChunks()).append("/").append(pool.availableChunks()).append(" 当前分段");
        if (pool.truncated()) text.append("（关键词扫描达上限，可能遗漏后续高分内容）");
        text.append("。向量：").append(vector.status().reason());
        text.append("重排：").append(rerank.status().reason());
        text.append("引用仍指向原始知识分段；文档经验不替代运行观测。");
        return text.toString();
    }

    private static double round(double value) { return Math.round(value * 1_000_000.0) / 1_000_000.0; }

    private static ResponseStatusException bad(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, text);
    }
}
