package io.github.mochiuaena.triage.source;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class SourceModels {
    private SourceModels() {}
    public record Symbol(String id, String path, String fileHash, String className, String method, String signature,
                         String route, List<String> httpMethods, List<String> calls, int startLine, int endLine) {}
    public record FileEntry(String path, String hash, List<Symbol> symbols) {}
    public record Index(String hash, Instant indexedAt, int visitedFiles, int skippedFiles, int parseFailures, List<FileEntry> files) {}
    public record Excerpt(String id, String path, String fileHash, String className, String method, String route,
                          List<String> httpMethods, List<String> calls, int startLine, int endLine, String content) {}
    public record Stored(UUID id, String name, String service, String root, long revision, Index index, Instant updatedAt,
                         UUID providerId, Long providerVersion, String model, String selection) {}
    public record View(UUID id, String name, String service, String root, long revision, String indexHash, Instant indexedAt,
                       int files, int symbols, int skippedFiles, int parseFailures, boolean modelSharing,
                       UUID providerId, String model) {}
    public record Analysis(UUID projectId, String projectName, long revision, String indexHash, String state,
                           boolean modelUsed, String message, List<Excerpt> excerpts) {}
}
