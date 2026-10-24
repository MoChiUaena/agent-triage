package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import io.github.mochiuaena.triage.material.MaterialService;

import java.time.Instant;
import java.util.UUID;

/** Records for the optional embedding index over current knowledge chunks. */
public final class EmbeddingModels {
    private EmbeddingModels() {}

    /** Matches the bounded keyword scan: one index build never embeds more chunks than one search may scan. */
    public static final int MAX_INDEX_CHUNKS = 2000, BATCH_SIZE = 16, RERANK_DOCUMENT_CHARS = 1000;

    public static String fingerprint(String baseUrl, String model) {
        return MaterialService.sha((baseUrl == null ? "" : baseUrl.strip()) + "\n" + (model == null ? "" : model.strip()));
    }

    public record IndexPreview(UUID id, Scope scope, long chunks, long sources, String model, String baseUrl,
                               String fingerprint, boolean truncated, String note, Instant createdAt, Instant expiresAt) {}

    public record IndexJob(UUID id, Scope scope, String state, int progress, int total, boolean cancelRequested,
                           String message, String fingerprint, String model, int usageCalls, long usageTokens,
                           Instant createdAt, Instant updatedAt) {}

    public record VectorRow(UUID chunkId, UUID versionId, UUID sourceId, String fingerprint, String model,
                            int dimensions, float[] vector, Instant embeddedAt) {}
}
