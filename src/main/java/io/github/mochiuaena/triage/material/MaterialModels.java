package io.github.mochiuaena.triage.material;

import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;

/** Snapshot-only contracts. Text is untrusted evidence, never model instructions. */
public final class MaterialModels {
    private MaterialModels() {}
    public enum Type { SERVICE, PR, ISSUE, CI, WORKFLOW }
    public record Selection(@NotNull Type type, @NotNull UUID reportId, UUID projectId, UUID repositoryId, String service) {}
    public record Scope(UUID projectId, UUID repositoryId, String service) {}
    public record Reference(Type type, UUID reportId, Scope scope, String version, String contentSha256,
                            Instant readAt, String confirmation, String originalLink) {}
    public record Material(Reference reference, String text, List<String> fields, List<String> omissions) {
        public Material { fields = List.copyOf(fields); omissions = List.copyOf(omissions); }
    }
    public record ScopeOption(String kind, String id, String name, String service) {}
    public record Recipient(boolean available, UUID providerId, long version, String selection,
                            String displayName, String model, String baseUrl, String message) {}
    public record Preview(UUID id, Instant createdAt, Instant expiresAt, Recipient recipient,
                          List<Material> materials, String text, String contentSha256, List<String> omissions) {
        public Preview { materials = List.copyOf(materials); omissions = List.copyOf(omissions); }
    }
    public record Consumed(UUID previewId, Recipient recipient, List<Reference> references, String text, String contentSha256) {
        public Consumed { references = List.copyOf(references); }
    }
}
