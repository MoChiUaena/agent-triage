package io.github.mochiuaena.triage.domain;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Public execution contract shared by storage, tools and the UI. */
public final class TriageModel {
    private TriageModel() {}

    public enum Scenario { NORMAL, DOWNSTREAM_TIMEOUT, OBSERVED, DB_POOL_EXHAUSTED, DB_POOL_RECOVERY, DB_QUERY_LOCK_WAIT }

    public record ServiceInfo(String id, String name, String downstreamId, String downstreamName) {
        public static ServiceInfo order() { return new ServiceInfo("order-service", "订单服务", "inventory-service", "库存服务"); }
    }
    public record RequestEndpoint(String id, String httpMethod, String routeTemplate, String handlerClass, String handlerMethod,
                                  List<String> parameterTypes, String stage, String sourceHash) {
        public RequestEndpoint(String id, String httpMethod, String routeTemplate, String handlerClass, String handlerMethod, List<String> parameterTypes, String stage) {
            this(id, httpMethod, routeTemplate, handlerClass, handlerMethod, parameterTypes, stage, null);
        }
    }
    public record EndpointSummary(RequestEndpoint endpoint, int requestCount, int timeoutCount, double requestP95Ms, double downstreamP95Ms) {}
    public record RequestDetails(RequestEndpoint endpoint, List<EndpointSummary> endpoints, int unattributedRequestCount, int otherEndpointRequestCount) {}
    public record FailureFrame(String className, String methodName, String fileName, Integer lineNumber, String sourceHash) {
        public FailureFrame(String className, String methodName, String fileName, Integer lineNumber) { this(className, methodName, fileName, lineNumber, null); }
    }
    public record FailureLocation(String kind, List<String> exceptionTypes, List<FailureFrame> frames, Boolean truncated) {}
    public record RequestFailure(Instant timestamp, String traceId, FailureLocation location) {}

    public enum Status {
        QUEUED, RUNNING, SUCCEEDED, INSUFFICIENT_EVIDENCE, FAILED, CANCELLED;
        public boolean terminal() { return this != QUEUED && this != RUNNING; }
    }

    public record Evidence(String id, String source, String title, String summary,
                           Map<String, Object> data) {}

    public record Finding(String text, List<String> evidenceIds) {}

    public record Diagnosis(List<Finding> observations, List<Finding> possibleCauses,
                            List<String> nextSteps, String uncertainty) {}

    public record Event(int sequence, Instant timestamp, String type, String tool,
                        String message, List<String> evidenceIds) {}

    public record Failure(String code, String message) {}

    public record TokenUsage(long inputTokens, long outputTokens, long totalTokens) {}

    public record ModelSource(UUID providerId, String displayName, long version) {}

    public record ModelExecution(String configuredModel, String responseModel, int calls, TokenUsage usage, ModelSource source,
                                 String assessment, List<String> nextChecks, List<String> requestedNextChecks,
                                 TokenUsage knownUsage, Integer completedCalls, Integer usageReportedCalls) {
        public ModelExecution(String configuredModel, String responseModel, int calls, TokenUsage usage, ModelSource source,
                              String assessment, List<String> nextChecks, List<String> requestedNextChecks) {
            this(configuredModel, responseModel, calls, usage, source, assessment, nextChecks, requestedNextChecks, null, null, null);
        }
        public ModelExecution(String configuredModel, String responseModel, int calls, TokenUsage usage, ModelSource source,
                              String assessment, List<String> nextChecks) {
            this(configuredModel, responseModel, calls, usage, source, assessment, nextChecks, null);
        }
        public ModelExecution(String configuredModel, String responseModel, int calls, TokenUsage usage, ModelSource source, String assessment) {
            this(configuredModel, responseModel, calls, usage, source, assessment, null);
        }
        public ModelExecution(String configuredModel, String responseModel, int calls, TokenUsage usage, ModelSource source) {
            this(configuredModel, responseModel, calls, usage, source, null);
        }
        public ModelExecution(String configuredModel, String responseModel, int calls, TokenUsage usage) {
            this(configuredModel, responseModel, calls, usage, null, null);
        }
    }

    public record Run(UUID id, String question, String service, int windowMinutes, Scenario scenario,
                      String mode, boolean synthetic, Status status, Instant createdAt, Instant finishedAt,
                      int toolCalls, List<Event> events, List<Evidence> evidence,
                      Diagnosis diagnosis, Failure failure, ModelExecution modelExecution, ServiceInfo serviceInfo,
                      io.github.mochiuaena.triage.source.SourceModels.Analysis sourceAnalysis, RequestEndpoint endpoint) {
        public Run(UUID id, String question, String service, int windowMinutes, Scenario scenario,
                   String mode, boolean synthetic, Status status, Instant createdAt, Instant finishedAt,
                   int toolCalls, List<Event> events, List<Evidence> evidence, Diagnosis diagnosis, Failure failure,
                   ModelExecution modelExecution, ServiceInfo serviceInfo, io.github.mochiuaena.triage.source.SourceModels.Analysis sourceAnalysis) {
            this(id, question, service, windowMinutes, scenario, mode, synthetic, status, createdAt, finishedAt,
                toolCalls, events, evidence, diagnosis, failure, modelExecution, serviceInfo, sourceAnalysis, null);
        }
        public Run(UUID id, String question, String service, int windowMinutes, Scenario scenario,
                   String mode, boolean synthetic, Status status, Instant createdAt, Instant finishedAt,
                   int toolCalls, List<Event> events, List<Evidence> evidence, Diagnosis diagnosis, Failure failure,
                   ModelExecution modelExecution, ServiceInfo serviceInfo) {
            this(id, question, service, windowMinutes, scenario, mode, synthetic, status, createdAt, finishedAt,
                toolCalls, events, evidence, diagnosis, failure, modelExecution, serviceInfo, null);
        }
        public Run(UUID id, String question, String service, int windowMinutes, Scenario scenario,
                   String mode, boolean synthetic, Status status, Instant createdAt, Instant finishedAt,
                   int toolCalls, List<Event> events, List<Evidence> evidence, Diagnosis diagnosis, Failure failure, ModelExecution modelExecution) {
            this(id, question, service, windowMinutes, scenario, mode, synthetic, status, createdAt, finishedAt,
                toolCalls, events, evidence, diagnosis, failure, modelExecution, null);
        }
        public Run(UUID id, String question, String service, int windowMinutes, Scenario scenario,
                   String mode, boolean synthetic, Status status, Instant createdAt, Instant finishedAt,
                   int toolCalls, List<Event> events, List<Evidence> evidence, Diagnosis diagnosis, Failure failure) {
            this(id, question, service, windowMinutes, scenario, mode, synthetic, status, createdAt, finishedAt,
                toolCalls, events, evidence, diagnosis, failure, null);
        }
    }

    public record RunSummary(UUID id, String question, Scenario scenario, Status status,
                             Instant createdAt, int toolCalls, String mode, String service, ServiceInfo serviceInfo, RequestEndpoint endpoint) {
        public RunSummary(UUID id, String question, Scenario scenario, Status status, Instant createdAt, int toolCalls, String mode, String service, ServiceInfo serviceInfo) {
            this(id, question, scenario, status, createdAt, toolCalls, mode, service, serviceInfo, null);
        }
        public RunSummary(UUID id, String question, Scenario scenario, Status status, Instant createdAt, int toolCalls, String mode) {
            this(id, question, scenario, status, createdAt, toolCalls, mode, null, null);
        }
    }
}
