package io.github.mochiuaena.triage.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.core.JsonParser;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import io.github.mochiuaena.triage.domain.TriageModel.RequestEndpoint;
import io.github.mochiuaena.triage.domain.TriageModel.RequestDetails;
import io.github.mochiuaena.triage.domain.TriageModel.EndpointSummary;
import io.github.mochiuaena.triage.domain.TriageModel.FailureLocation;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.nio.ByteBuffer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;

/** Reads only startup-registered loopback origins. Redirects and oversized bodies are rejected. */
@Component
public class LiveObservationClient {
    public record ErrorEntry(Instant timestamp, String traceId, String level, String message, String code, FailureLocation failureLocation) {
        public ErrorEntry(Instant timestamp, String traceId, String level, String message, String code) { this(timestamp, traceId, level, message, code, null); }
        public ErrorEntry(Instant timestamp, String traceId, String level, String message) { this(timestamp, traceId, level, message, null); }
    }
    public record Snapshot(String service, Scenario scenario, Instant windowStart, Instant windowEnd,
                           int requestCount, int normalCount, int timeoutCount, long recordedRequestCount, double orderP95Ms,
                           double downstreamP95Ms, double downstreamTimeoutRate, Double baselineOrderP95Ms,
                           List<ErrorEntry> errors, boolean synthetic, DatabasePool databasePool, RequestDetails requestDetails) {
        public Snapshot(String service, Scenario scenario, Instant windowStart, Instant windowEnd,
                        int requestCount, int normalCount, int timeoutCount, long recordedRequestCount, double orderP95Ms,
                        double downstreamP95Ms, double downstreamTimeoutRate, Double baselineOrderP95Ms, List<ErrorEntry> errors,
                        boolean synthetic, DatabasePool databasePool) {
            this(service, scenario, windowStart, windowEnd, requestCount, normalCount, timeoutCount, recordedRequestCount, orderP95Ms,
                downstreamP95Ms, downstreamTimeoutRate, baselineOrderP95Ms, errors, synthetic, databasePool, null);
        }
        public Snapshot(String service, Scenario scenario, Instant windowStart, Instant windowEnd,
                        int requestCount, int normalCount, int timeoutCount, long recordedRequestCount, double orderP95Ms,
                        double downstreamP95Ms, double downstreamTimeoutRate, Double baselineOrderP95Ms, List<ErrorEntry> errors, boolean synthetic) {
            this(service, scenario, windowStart, windowEnd, requestCount, normalCount, timeoutCount, recordedRequestCount, orderP95Ms,
                downstreamP95Ms, downstreamTimeoutRate, baselineOrderP95Ms, errors, synthetic, null);
        }
    }
    public record DatabasePool(Integer maximumConnections, Integer peakActiveConnections, Integer peakPendingThreads,
                               Integer poolSamples, Integer exhaustedSamples, Integer acquisitionTimeoutCount, Integer acquisitionErrorCount,
                               Integer queryCount, Integer queryErrorCount, Double acquisitionP95Ms, Double queryP95Ms) {}
    public record DatabaseObservations(Integer schemaVersion, String kind, String service, String database, Instant windowStart, Instant windowEnd,
                                       Integer requestCount, Long recordedRequestCount, Double requestP95Ms, Double baselineRequestP95Ms,
                                       DatabasePool databasePool, List<ErrorEntry> errors, Boolean synthetic) {}
    public record ObservationsV1(Integer schemaVersion, String service, String downstreamService, Instant windowStart, Instant windowEnd,
                                 Integer requestCount, Integer timeoutCount, Long recordedRequestCount, Double requestP95Ms,
                                 Double downstreamP95Ms, Double downstreamTimeoutRate, Double baselineRequestP95Ms,
                                 List<ErrorEntry> errors, Boolean synthetic) {}
    public record EndpointObservations(Integer schemaVersion, String kind, String service, String downstreamService, Instant windowStart, Instant windowEnd,
                                       Integer requestCount, Integer timeoutCount, Long recordedRequestCount, Double requestP95Ms,
                                       Double downstreamP95Ms, Double downstreamTimeoutRate, Double baselineRequestP95Ms, List<ErrorEntry> errors, Boolean synthetic,
                                       RequestEndpoint endpoint, List<EndpointInput> endpoints, Integer unattributedRequestCount, Integer otherEndpointRequestCount) {}
    public record EndpointInput(RequestEndpoint endpoint, Integer requestCount, Integer timeoutCount, Double requestP95Ms, Double downstreamP95Ms) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600))
        .followRedirects(HttpClient.Redirect.NEVER).build();
    private final ServiceRegistry registry;
    private final ObjectMapper json;

    public LiveObservationClient(ObservationSource source, ObjectMapper json) {
        this(new ServiceRegistry(source, List.of()), json);
    }

    @Autowired
    public LiveObservationClient(ServiceRegistry registry, ObjectMapper json) {
        this.registry = registry;
        this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES)
            .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT).disable(MapperFeature.ALLOW_COERCION_OF_SCALARS)
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    public Scenario scenario() {
        return scenario(registry.defaultTarget());
    }

    public Scenario scenario(ServiceRegistry.Target target) {
        if (!registry.require(target.info().id()).equals(target)) throw new IllegalArgumentException("Unregistered observation target");
        if (target.protocol() != ServiceRegistry.Protocol.LAB) return Scenario.OBSERVED;
        try {
            Scenario scenario = Scenario.valueOf(json.readTree(get(target, "/lab/scenario")).path("scenario").asText());
            if (scenario != Scenario.NORMAL && scenario != Scenario.DOWNSTREAM_TIMEOUT) throw new IllegalStateException("Unexpected lab scenario");
            return scenario;
        } catch (IOException | IllegalArgumentException e) { throw unexpected(); }
    }

    public Snapshot snapshot(ToolContext context) {
        context = registry.freeze(context);
        ServiceRegistry.Target target = context.target();
        String end = URLEncoder.encode(context.endTime().toString(), StandardCharsets.UTF_8);
        try {
            String path = target.protocol() == ServiceRegistry.Protocol.LAB ? "/lab/observations" : target.protocol() == ServiceRegistry.Protocol.OBSERVATIONS_V3 ? "/triage/endpoint-observations"
                : target.databaseAlias() ? "/triage/database-observations" : "/triage/observations";
            byte[] response = get(target, path + "?windowMinutes=" + context.windowMinutes() + "&endTime=" + end
                + (context.endpoint() == null ? "" : "&endpointId=" + context.endpoint().id()));
            Snapshot snapshot;
            if (target.protocol() == ServiceRegistry.Protocol.OBSERVATIONS_V1) {
                ObservationsV1 value = json.readValue(response, ObservationsV1.class);
                if (value == null) throw unexpected();
                if (!Integer.valueOf(1).equals(value.schemaVersion())) throw versionMismatch();
                if (!target.info().downstreamId().equals(value.downstreamService())
                    || !Boolean.FALSE.equals(value.synthetic()) || value.requestCount() == null || value.timeoutCount() == null
                    || value.recordedRequestCount() == null || value.requestP95Ms() == null || value.downstreamP95Ms() == null
                    || value.downstreamTimeoutRate() == null) throw unexpected();
                snapshot = new Snapshot(value.service(), Scenario.OBSERVED, value.windowStart(), value.windowEnd(),
                    value.requestCount(), value.requestCount() - value.timeoutCount(), value.timeoutCount(), value.recordedRequestCount(),
                    value.requestP95Ms(), value.downstreamP95Ms(), value.downstreamTimeoutRate(), value.baselineRequestP95Ms(), value.errors(), false);
            } else if (target.protocol() == ServiceRegistry.Protocol.OBSERVATIONS_V3) {
                EndpointObservations value = json.readValue(response, EndpointObservations.class);
                if (value == null) throw unexpected();
                if (!Integer.valueOf(3).equals(value.schemaVersion())) throw versionMismatch();
                if (!"HTTP_ENDPOINTS".equals(value.kind()) || !target.info().downstreamId().equals(value.downstreamService())
                        || !Boolean.FALSE.equals(value.synthetic()) || value.requestCount() == null || value.timeoutCount() == null || value.recordedRequestCount() == null
                        || value.requestP95Ms() == null || value.downstreamP95Ms() == null || value.downstreamTimeoutRate() == null) throw unexpected();
                RequestDetails details = validateEndpoints(value, context);
                snapshot = new Snapshot(value.service(), Scenario.OBSERVED, value.windowStart(), value.windowEnd(), value.requestCount(),
                    value.requestCount() - value.timeoutCount(), value.timeoutCount(), value.recordedRequestCount(), value.requestP95Ms(), value.downstreamP95Ms(),
                    value.downstreamTimeoutRate(), value.baselineRequestP95Ms(), value.errors(), false, null, details);
            } else if (target.protocol() == ServiceRegistry.Protocol.DATABASE_V2) {
                DatabaseObservations value = json.readValue(response, DatabaseObservations.class);
                if (value == null) throw unexpected();
                if (!Integer.valueOf(2).equals(value.schemaVersion())) throw versionMismatch();
                if (!"DATABASE_POOL".equals(value.kind())
                    || !target.info().downstreamId().equals(value.database()) || !Boolean.FALSE.equals(value.synthetic())
                    || value.requestCount() == null || value.recordedRequestCount() == null || value.requestP95Ms() == null) throw unexpected();
                validateDatabase(value.databasePool(), value.requestCount(), value.errors());
                var pool = value.databasePool();
                snapshot = new Snapshot(value.service(), Scenario.OBSERVED, value.windowStart(), value.windowEnd(), value.requestCount(),
                    value.requestCount() - pool.acquisitionTimeoutCount() - pool.acquisitionErrorCount() - pool.queryErrorCount(), 0,
                    value.recordedRequestCount(), value.requestP95Ms(), 0, 0, value.baselineRequestP95Ms(), value.errors(), false, pool);
            } else {
                snapshot = json.readValue(response, Snapshot.class);
                if (snapshot.databasePool() != null) throw unexpected();
            }
            if (snapshot == null) throw unexpected();
            validate(snapshot, context);
            return snapshot;
        } catch (IOException e) { throw unexpected(); }
    }
    public Snapshot endpointSnapshot(ServiceRegistry.Target target, int minutes, Instant end, String id) {
        if (target.protocol() != ServiceRegistry.Protocol.OBSERVATIONS_V3) throw new IllegalArgumentException("Endpoint observations require V3");
        var context = new ToolContext(target.info().id(), minutes, Scenario.OBSERVED, end, target);
        if (id == null) return snapshot(context);
        Snapshot catalogue = snapshot(context);
        RequestEndpoint endpoint = catalogue.requestDetails().endpoints().stream().map(EndpointSummary::endpoint).filter(value -> value.id().equals(id)).findFirst()
            .orElseThrow(() -> new ObservationFailure("OBSERVATION_ENDPOINT_CHANGED", "所选接口不在当前窗口的可选列表中，请刷新接口列表。"));
        return snapshot(new ToolContext(target.info().id(), minutes, Scenario.OBSERVED, end, target, endpoint));
    }
    private RequestDetails validateEndpoints(EndpointObservations value, ToolContext context) {
        if (value.endpoints() == null || value.endpoints().size() > 8 || value.unattributedRequestCount() == null || value.otherEndpointRequestCount() == null
                || value.unattributedRequestCount() < 0 || value.otherEndpointRequestCount() < 0 || !java.util.Objects.equals(value.endpoint(), context.endpoint())) throw unexpected();
        if (value.endpoint() != null) validateEndpoint(value.endpoint());
        long requests = value.unattributedRequestCount().longValue() + value.otherEndpointRequestCount(); long timeouts = 0;
        var summaries = new java.util.ArrayList<EndpointSummary>();
        var seen = new java.util.HashSet<String>();
        for (var summary : value.endpoints()) {
            if (summary == null || summary.requestCount() == null || summary.timeoutCount() == null || summary.requestP95Ms() == null || summary.downstreamP95Ms() == null) throw unexpected(); validateEndpoint(summary.endpoint());
            if (!seen.add(summary.endpoint().id()) || summary.requestCount() <= 0 || summary.timeoutCount() < 0 || summary.timeoutCount() > summary.requestCount()
                    || !metric(summary.requestP95Ms()) || !metric(summary.downstreamP95Ms())) throw unexpected();
            requests += summary.requestCount(); timeouts += summary.timeoutCount();
            if (value.endpoint() != null && !summary.endpoint().equals(value.endpoint())) throw unexpected();
            summaries.add(new EndpointSummary(summary.endpoint(), summary.requestCount(), summary.timeoutCount(), summary.requestP95Ms(), summary.downstreamP95Ms()));
        }
        if (requests != value.requestCount() || timeouts > value.timeoutCount() || value.unattributedRequestCount() == 0 && value.otherEndpointRequestCount() == 0 && timeouts != value.timeoutCount()
            || value.endpoint() != null && (value.unattributedRequestCount() != 0 || value.otherEndpointRequestCount() != 0)) throw unexpected();
        return new RequestDetails(value.endpoint(), List.copyOf(summaries), value.unattributedRequestCount(), value.otherEndpointRequestCount());
    }
    private void validateEndpoint(RequestEndpoint value) {
        if (value == null || value.id() == null || !value.id().matches("EP-[a-f0-9]{32}") || !"MVC_SELECTED".equals(value.stage())
            || value.httpMethod() == null || !List.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE", "OPTIONS", "TRACE").contains(value.httpMethod())
            || value.routeTemplate() == null || !value.routeTemplate().startsWith("/") || value.routeTemplate().length() > 160 || value.routeTemplate().codePoints().anyMatch(Character::isISOControl)
            || !javaName(value.handlerClass(), 240, true) || !javaName(value.handlerMethod(), 80, false)
            || !sourceHash(value.sourceHash())
            || value.parameterTypes() == null || value.parameterTypes().size() > 8 || value.parameterTypes().stream().anyMatch(type -> type == null || type.length() > 128 || !javaName(type.replace("[]", ""), 128, true))) throw unexpected();
        try {
            String identity = String.join("\0", value.httpMethod(), value.routeTemplate(), value.handlerClass(), value.handlerMethod(), String.join(",", value.parameterTypes()));
            String id = "EP-" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
            if (!id.equals(value.id())) throw unexpected();
        } catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException("Cannot identify an MVC handler"); }
    }
    private boolean javaName(String text, int length, boolean qualified) {
        if (text == null || text.isBlank() || text.length() > length || text.codePoints().anyMatch(Character::isISOControl)) return false;
        String[] parts = qualified ? text.split("\\.", -1) : new String[]{text};
        for (String part : parts) if (part.isEmpty() || !Character.isJavaIdentifierStart(part.codePointAt(0)) || !part.codePoints().allMatch(Character::isJavaIdentifierPart)) return false;
        return true;
    }
    private boolean sourceHash(String value) { return value == null || value.matches("[a-f0-9]{64}"); }
    private void validateFailure(FailureLocation value) {
        if (value.kind() == null || !List.of("HTTP_CLIENT_FAILURE", "REQUEST_EXCEPTION").contains(value.kind())
            || value.exceptionTypes() == null || value.exceptionTypes().isEmpty() || value.exceptionTypes().size() > 4
            || value.exceptionTypes().stream().anyMatch(type -> !javaName(type, 240, true))
            || value.frames() == null || value.frames().size() > 8 || value.truncated() == null) throw unexpected();
        for (var frame : value.frames()) {
            if (frame == null || !javaName(frame.className(), 240, true)
                || !("<init>".equals(frame.methodName()) || "<clinit>".equals(frame.methodName()) || javaName(frame.methodName(), 80, false))
                || frame.fileName() != null && !frame.fileName().matches("[\\p{L}\\p{N}_$-]{1,150}\\.java")
                || frame.lineNumber() != null && (frame.lineNumber() < 1 || frame.lineNumber() > 1_000_000) || !sourceHash(frame.sourceHash())) throw unexpected();
        }
    }

    private void validateDatabase(DatabasePool pool, int requests, List<ErrorEntry> errors) {
        if (pool == null || java.util.Arrays.stream(new Object[]{pool.maximumConnections(), pool.peakActiveConnections(), pool.peakPendingThreads(),
            pool.poolSamples(), pool.exhaustedSamples(), pool.acquisitionTimeoutCount(), pool.acquisitionErrorCount(), pool.queryCount(), pool.queryErrorCount(),
            pool.acquisitionP95Ms(), pool.queryP95Ms()}).anyMatch(java.util.Objects::isNull)) throw unexpected();
        if (pool.maximumConnections() < 1 || pool.maximumConnections() > 64 || pool.peakActiveConnections() < 0
            || pool.peakActiveConnections() > pool.maximumConnections() || pool.peakPendingThreads() < 0
            || pool.poolSamples() < 0 || pool.exhaustedSamples() < 0 || pool.exhaustedSamples() > pool.poolSamples()
            || pool.acquisitionTimeoutCount() < 0 || pool.acquisitionErrorCount() < 0 || pool.queryErrorCount() < 0
            || (long) pool.acquisitionTimeoutCount() + pool.acquisitionErrorCount() + pool.queryErrorCount() > requests
            || pool.queryCount() != (long) requests - pool.acquisitionTimeoutCount() - pool.acquisitionErrorCount()
            || pool.queryCount() == 0 && pool.queryP95Ms() != 0
            || !metric(pool.acquisitionP95Ms()) || !metric(pool.queryP95Ms())
            || requests == 0 && (pool.acquisitionP95Ms() != 0 || pool.queryP95Ms() != 0)
            || pool.poolSamples() == 0 && (pool.peakActiveConnections() != 0 || pool.peakPendingThreads() != 0)
            || pool.exhaustedSamples() > 0 && (pool.peakActiveConnections() != pool.maximumConnections().intValue() || pool.peakPendingThreads() == 0)
            || errors == null) throw unexpected();
        for (ErrorEntry error : errors) {
            if (error == null || error.code() == null || !List.of("DB_CONNECTION_ACQUIRE_TIMEOUT", "DB_CONNECTION_ACQUIRE_FAILED", "SQL_QUERY_FAILED").contains(error.code())) throw unexpected();
        }
        if (errors.stream().filter(e -> "DB_CONNECTION_ACQUIRE_TIMEOUT".equals(e.code())).count() > pool.acquisitionTimeoutCount()
            || errors.stream().filter(e -> "DB_CONNECTION_ACQUIRE_FAILED".equals(e.code())).count() > pool.acquisitionErrorCount()
            || errors.stream().filter(e -> "SQL_QUERY_FAILED".equals(e.code())).count() > pool.queryErrorCount()) throw unexpected();
    }

    private void validate(Snapshot value, ToolContext context) {
        if (!context.service().equals(value.service()) || value.synthetic() || value.scenario() != context.scenario()
            || !context.startTime().equals(value.windowStart()) || !context.endTime().equals(value.windowEnd())
            || value.requestCount() < 0 || value.timeoutCount() < 0 || value.timeoutCount() > value.requestCount()
            || value.normalCount() < 0 || value.normalCount() > value.requestCount()
            || value.recordedRequestCount() < value.requestCount() || !metric(value.orderP95Ms()) || !metric(value.downstreamP95Ms())
            || value.baselineOrderP95Ms() != null && !metric(value.baselineOrderP95Ms())
            || !metric(value.downstreamTimeoutRate()) || value.downstreamTimeoutRate() > 1
            || Math.abs(value.downstreamTimeoutRate() - (value.requestCount() == 0 ? 0 : (double) value.timeoutCount() / value.requestCount())) > 0.000001
            || value.requestCount() == 0 && (value.orderP95Ms() != 0 || value.downstreamP95Ms() != 0)
            || value.errors() == null || value.errors().size() > 3) throw unexpected();
        for (ErrorEntry error : value.errors()) {
            if (error == null || error.timestamp() == null || error.timestamp().isBefore(context.startTime()) || error.timestamp().isAfter(context.endTime())
                || error.traceId() == null || error.traceId().length() > 128 || error.level() == null || !List.of("ERROR", "WARN").contains(error.level())
                || error.message() == null || error.message().isBlank() || error.message().length() > 2000) throw unexpected();
            if (error.failureLocation() != null) {
                if (context.target().protocol() != ServiceRegistry.Protocol.OBSERVATIONS_V3) throw unexpected();
                validateFailure(error.failureLocation());
            }
        }
        if (value.requestDetails() != null && value.errors().size() > value.requestCount()) throw unexpected();
    }
    private boolean metric(double value) { return Double.isFinite(value) && value >= 0; }
    private ObservationFailure unexpected() {
        return new ObservationFailure("OBSERVATION_CONTRACT", "观测数据不符合接入契约，请核对服务身份、时间窗口与字段格式。");
    }
    private ObservationFailure versionMismatch() {
        return new ObservationFailure("OBSERVATION_VERSION", "观测接口版本与服务配置不一致，请核对 OBSERVATIONS_V1、DATABASE_V2 或 OBSERVATIONS_V3 配置。");
    }

    private byte[] get(ServiceRegistry.Target target, String path) {
        URI uri = URI.create(target.baseUrl().toString().replaceAll("/$", "") + path);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(1200)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, info -> new BoundedBody());
            if (response.statusCode() != 200) throw switch (response.statusCode()) {
                case 404 -> new ObservationFailure("OBSERVATION_ENDPOINT_MISSING", "所选服务没有提供观测接口，请启用 Starter 或实现接入接口。");
                case 401, 403 -> new ObservationFailure("OBSERVATION_ACCESS_DENIED", "观测接口拒绝访问，请确认本机直连和服务端访问限制。");
                case 422 -> new ObservationFailure("OBSERVATION_WINDOW_LOST", "观测窗口已超过保留范围或容量，请缩小窗口后重新连接。");
                case 409 -> new ObservationFailure("OBSERVATION_ENDPOINT_CHANGED", "所选接口已不在服务保留的观测中，请刷新接口列表。");
                default -> new ObservationFailure("OBSERVATION_HTTP_ERROR", "观测接口返回错误状态，请检查服务日志和查询窗口配置。");
            };
            return response.body();
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ObservationFailure("OBSERVATION_TIMEOUT", "等待观测接口超时，请检查所选服务负载与接口响应时间。");
        } catch (IOException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause())
                if (cause instanceof ObservationFailure failure) throw failure;
            throw new ObservationFailure("OBSERVATION_UNAVAILABLE", "无法连接所选服务，请确认服务已启动且登记端口正确。");
        }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Observation request interrupted", e); }
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (ByteBuffer buffer : buffers) {
                if (bytes.size() + buffer.remaining() > 32_000) {
                    subscription.cancel(); result.completeExceptionally(new ObservationFailure("OBSERVATION_CONTRACT", "观测响应超过允许大小，请按接入契约限制返回内容。")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
