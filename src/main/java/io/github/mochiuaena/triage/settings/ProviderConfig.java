package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.model.ModelSettings;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.UUID;

public final class ProviderConfig {
    private ProviderConfig() {}
    public enum Protocol { DEEPSEEK, DASHSCOPE, GLM, KIMI, LM_STUDIO, OPENAI_COMPATIBLE }

    public record Input(@NotBlank @Size(max = 80) String displayName,
                        @NotNull Protocol protocol,
                        @NotBlank @Size(max = 512) String baseUrl,
                        @NotBlank @Size(max = 120) String model,
                        @Size(max = 4096) String apiKey,
                        @DecimalMin("0") @DecimalMax("2") double temperature,
                        @Min(1) @Max(60) int timeoutSeconds,
                        @Min(1) @Max(8) int maxRounds,
                        @Min(256) @Max(4096) int maxTokens,
                        @Min(0) long version) {
        @Override public String toString() { return "ProviderInput[apiKey=redacted]"; }
    }

    record Stored(UUID id, String displayName, Protocol protocol, String baseUrl, String model, String encryptedKey,
                  double temperature, int timeoutSeconds, int maxRounds, int maxTokens, long version, Instant createdAt) {
        View view(boolean active) {
            return new View(id, displayName, protocol, baseUrl, model, true, temperature,
                timeoutSeconds, maxRounds, maxTokens, version, active);
        }
        ModelSettings settings(String key) {
            return new ModelSettings(baseUrl, key, model, Duration.ofSeconds(timeoutSeconds), maxRounds, maxTokens);
        }
        @Override public String toString() { return "StoredProvider[id=" + id + ", credentials=redacted]"; }
    }

    public record View(UUID id, String displayName, Protocol protocol, String baseUrl, String model, boolean keyConfigured,
                       double temperature, int timeoutSeconds, int maxRounds, int maxTokens, long version, boolean active) {}
    public record Selection(String mode, UUID providerId, String source) {}
    public record State(java.util.List<View> providers, Selection selection) {}
    public record TestResult(boolean success, String message, long elapsedMs) {}
}
