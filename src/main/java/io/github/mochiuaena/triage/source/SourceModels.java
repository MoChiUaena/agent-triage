package io.github.mochiuaena.triage.source;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.Map;

public final class SourceModels {
    private SourceModels() {}
    public record Symbol(String id, String path, String fileHash, String className, String method, String signature,
                         String route, List<String> httpMethods, List<String> calls, int startLine, int endLine, MethodInfo details) {
        public Symbol(String id, String path, String fileHash, String className, String method, String signature,
                      String route, List<String> httpMethods, List<String> calls, int startLine, int endLine) {
            this(id, path, fileHash, className, method, signature, route, httpMethods, calls, startLine, endLine, null);
        }
    }
    public record Parameter(String name, String type) {}
    public record Invocation(String expression, String receiverType, boolean staticReceiver, String method,
                             List<String> argumentTypes, List<String> chain, int line, boolean deferred) {}
    public record MethodInfo(List<Parameter> parameters, String returnType, boolean isStatic, boolean isAbstract,
                             boolean varargs, List<Invocation> invocations, boolean callsTruncated) {}
    public record TypeInfo(String name, String packageName, String kind, boolean abstractType, List<String> parents, Map<String,String> fields,
                           List<String> imports, List<String> staticImports) {}
    public record FileEntry(String path, String hash, List<Symbol> symbols, List<TypeInfo> types) {
        public FileEntry(String path, String hash, List<Symbol> symbols) { this(path, hash, symbols, List.of()); }
    }
    public record Index(String hash, Instant indexedAt, int visitedFiles, int skippedFiles, int parseFailures, List<FileEntry> files, int formatVersion) {
        public Index(String hash, Instant indexedAt, int visitedFiles, int skippedFiles, int parseFailures, List<FileEntry> files) {
            this(hash, indexedAt, visitedFiles, skippedFiles, parseFailures, files, 0);
        }
    }
    public record Excerpt(String id, String path, String fileHash, String className, String method, String route,
                          List<String> httpMethods, List<String> calls, int startLine, int endLine, String content) {}
    public record Stored(UUID id, String name, String service, String root, long revision, Index index, Instant updatedAt,
                         UUID providerId, Long providerVersion, String model, String selection) {}
    public record View(UUID id, String name, String service, String root, long revision, String indexHash, Instant indexedAt,
                       int files, int symbols, int skippedFiles, int parseFailures, boolean modelSharing,
                       UUID providerId, String model, boolean sharingActive) {}
    public record Analysis(UUID projectId, String projectName, long revision, String indexHash, String state,
                           boolean modelUsed, String message, List<Excerpt> excerpts, CallGraph graph) {
        public Analysis(UUID projectId, String projectName, long revision, String indexHash, String state,
                        boolean modelUsed, String message, List<Excerpt> excerpts) {
            this(projectId, projectName, revision, indexHash, state, modelUsed, message, excerpts, null);
        }
    }
    public record CallNode(Excerpt excerpt, int depth, String signature) {}
    public record CallEdge(String id, String fromId, List<String> targetIds, String call, int line, String kind,
                           String resolution, String message, Excerpt callSite) {}
    public record EvidenceLink(List<String> edgeIds, List<String> evidenceIds, String kind, String message) {}
    public record EndpointMatch(io.github.mochiuaena.triage.domain.TriageModel.RequestEndpoint endpoint, int requestCount, int timeoutCount,
                                String state, String message, List<String> sourceIds) {}
    public record FrameMatch(io.github.mochiuaena.triage.domain.TriageModel.FailureFrame frame, String state, String message, List<Excerpt> excerpts) {}
    public record FailureMatch(String evidenceId, Instant timestamp, String traceId, String kind, List<String> exceptionTypes,
                               boolean truncated, List<FrameMatch> frames) {}
    public record CallGraph(String state, String message, boolean truncated, List<String> rootIds, List<CallNode> nodes,
                            List<CallEdge> edges, List<EvidenceLink> evidenceLinks, List<EndpointMatch> endpointMatches, List<FailureMatch> failureMatches) {
        public CallGraph(String state, String message, boolean truncated, List<String> rootIds, List<CallNode> nodes,
                         List<CallEdge> edges, List<EvidenceLink> evidenceLinks, List<EndpointMatch> endpointMatches) {
            this(state, message, truncated, rootIds, nodes, edges, evidenceLinks, endpointMatches, List.of());
        }
        public CallGraph(String state, String message, boolean truncated, List<String> rootIds, List<CallNode> nodes,
                         List<CallEdge> edges, List<EvidenceLink> evidenceLinks) {
            this(state, message, truncated, rootIds, nodes, edges, evidenceLinks, List.of());
        }
    }
}
