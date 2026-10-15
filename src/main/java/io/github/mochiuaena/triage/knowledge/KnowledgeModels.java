package io.github.mochiuaena.triage.knowledge;

import java.time.Instant;
import java.util.*;

public final class KnowledgeModels {
    private KnowledgeModels() {}

    public static final int MAX_FILES = 100,
            MAX_FILE_BYTES = 262144,
            MAX_BYTES = 2097152,
            MAX_CHUNKS = 1000,
            CHUNK_CHARS = 2000,
            MAX_ENTRIES = 5000,
            MAX_DEPTH = 20;

    public record Scope(String kind, UUID id) {}

    public record Input(
            String scopeKind,
            UUID scopeId,
            String directory,
            String commitSha,
            UUID sourceId,
            Long revision) {}

    public record Limits(
            int files,
            int fileBytes,
            int totalBytes,
            int chunks,
            int chunkChars,
            int visitedEntries,
            int depth) {}

    public static Limits limits() {
        return new Limits(
                MAX_FILES,
                MAX_FILE_BYTES,
                MAX_BYTES,
                MAX_CHUNKS,
                CHUNK_CHARS,
                MAX_ENTRIES,
                MAX_DEPTH);
    }

    public record FileManifest(
            String path, String sha256, int bytes, String title, String excerpt) {}

    public record Exclusion(String path, String reason) {}

    public record Document(String path, String sha256, int bytes, String title, String text) {}

    public record Read(
            String root,
            String commitSha,
            List<Document> documents,
            List<Exclusion> exclusions,
            int visited,
            boolean complete) {}

    public record Preview(
            UUID id,
            Scope scope,
            String origin,
            String root,
            String commitSha,
            String scopeStamp,
            UUID sourceId,
            long revision,
            List<FileManifest> files,
            List<Exclusion> exclusions,
            int visited,
            int bytes,
            int chunks,
            boolean complete,
            String manifestHash,
            Instant readAt,
            Instant expiresAt,
            Limits limits) {}

    public record Source(
            UUID id,
            Scope scope,
            String origin,
            String root,
            String commitSha,
            long revision,
            String state,
            String manifestHash,
            Instant importedAt,
            int documents,
            Preview coverage) {}

    public record Version(
            UUID id,
            UUID sourceId,
            Scope scope,
            String origin,
            String root,
            String commitSha,
            String path,
            String sha256,
            String title,
            String text,
            Instant importedAt) {}

    public record VersionInfo(
            UUID id,
            UUID sourceId,
            String path,
            String sha256,
            String title,
            String commitSha,
            Instant importedAt,
            boolean active) {}

    public record Chunk(
            UUID id,
            UUID versionId,
            int ordinal,
            int startLine,
            int endLine,
            int startOffset,
            int endOffset,
            String sha256,
            String text) {}

    public record Job(
            UUID id,
            UUID sourceId,
            Scope scope,
            String state,
            int progress,
            int total,
            boolean cancelRequested,
            String message,
            Instant createdAt,
            Instant updatedAt) {}

    public record Action(
            String scopeKind, UUID scopeId, long revision, String action, boolean confirmed) {}
}
