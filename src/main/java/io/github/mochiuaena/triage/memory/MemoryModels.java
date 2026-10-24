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
            String provenance,
            Object conversationOriginal) {
        public Basis(
                String note,
                List<Snapshot> references,
                UUID conversationId,
                List<UUID> messageIds,
                List<Object> messages,
                String provenance) {
            this(note, references, conversationId, messageIds, messages, provenance, null);
        }
    }

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

    public record ConflictPeer(UUID id, long revision, String topic, Instant confirmedAt) {}

    public record RecallItem(
            UUID id,
            long revision,
            String topic,
            String excerpt,
            int score,
            Instant confirmedAt,
            String scopeService,
            List<String> applicability,
            List<ConflictPeer> conflicts) {}

    public record RecallResult(
            Scope scope,
            String query,
            String recallMethod,
            List<RecallItem> results,
            int scannedMemories,
            long availableMemories,
            boolean truncated,
            String coverage) {}
}
