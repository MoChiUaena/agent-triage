package io.github.mochiuaena.triage.settings;

import jakarta.validation.constraints.*;
import java.util.UUID;

/** Optional retrieval helper services: an embedding service and a rerank service, each a single local row. */
public final class EmbeddingModels {
    private EmbeddingModels() {}
    public static final String EMBEDDING = "EMBEDDING", RERANK = "RERANK";

    public record Input(@NotBlank @Size(max = 80) String displayName,
                        @NotBlank @Size(max = 512) String baseUrl,
                        @NotBlank @Size(max = 120) String model,
                        @Size(max = 4096) String apiKey,
                        @Min(1) @Max(60) int timeoutSeconds,
                        @Min(0) long version) {
        @Override public String toString() { return "SearchModelInput[apiKey=redacted]"; }
    }

    public record View(String kind, UUID id, String displayName, String baseUrl, String model,
                       boolean keyConfigured, int timeoutSeconds, long version) {}

    public record ConfigState(View embedding, View rerank) {}

    public record TestResult(boolean success, String message, Integer dimensions, Long tokens, long elapsedMs) {}
}
