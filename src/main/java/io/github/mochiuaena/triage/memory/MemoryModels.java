package io.github.mochiuaena.triage.memory;

import java.time.Instant;
import java.util.*;

/** Local user-reviewed historical experience, never a retrieval or sharing grant. */
public final class MemoryModels {
    private MemoryModels() {}

    public record Scope(String kind, UUID id, String service) {}

    public record Reference(String type, UUID reportId, UUID versionId, UUID chunkId) {}

    public record Snapshot(Reference reference, Object saved) {}

    public record Basis(
            String note,
            List<Snapshot> references,
            UUID conversationId,
            List<UUID> messageIds,
            List<Object> messages,
            String provenance) {}

    public record Body(String topic, String content, Basis basis) {}

    public record Version(
            UUID id,
            Scope scope,
            long revision,
            String status,
            Body body,
            String bodySha256,
            Instant createdAt,
            Instant updatedAt,
            Instant confirmedAt,
            String confirmationProvenance) {}

    public record Summary(
            UUID id,
            Scope scope,
            long revision,
            String status,
            String topic,
            String bodySha256,
            Instant updatedAt) {}
}
