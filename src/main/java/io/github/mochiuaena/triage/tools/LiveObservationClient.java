package io.github.mochiuaena.triage.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.core.JsonParser;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
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
    public record ErrorEntry(Instant timestamp, String traceId, String level, String message) {}
    public record Snapshot(String service, Scenario scenario, Instant windowStart, Instant windowEnd,
                           int requestCount, int normalCount, int timeoutCount, long recordedRequestCount, double orderP95Ms,
                           double downstreamP95Ms, double downstreamTimeoutRate, Double baselineOrderP95Ms,
                           List<ErrorEntry> errors, boolean synthetic) {}
    public record ObservationsV1(Integer schemaVersion, String service, String downstreamService, Instant windowStart, Instant windowEnd,
                                 Integer requestCount, Integer timeoutCount, Long recordedRequestCount, Double requestP95Ms,
                                 Double downstreamP95Ms, Double downstreamTimeoutRate, Double baselineRequestP95Ms,
                                 List<ErrorEntry> errors, Boolean synthetic) {}
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
            if (scenario == Scenario.OBSERVED) throw new IllegalStateException("Unexpected lab scenario");
            return scenario;
        } catch (IOException e) { throw new IllegalStateException("Invalid observation response", e); }
    }

    public Snapshot snapshot(ToolContext context) {
        context = registry.freeze(context);
        ServiceRegistry.Target target = context.target();
        String end = URLEncoder.encode(context.endTime().toString(), StandardCharsets.UTF_8);
        try {
            String path = target.protocol() == ServiceRegistry.Protocol.LAB ? "/lab/observations" : "/triage/observations";
            byte[] response = get(target, path + "?windowMinutes=" + context.windowMinutes() + "&endTime=" + end);
            Snapshot snapshot;
            if (target.protocol() == ServiceRegistry.Protocol.OBSERVATIONS_V1) {
                ObservationsV1 value = json.readValue(response, ObservationsV1.class);
                if (!Integer.valueOf(1).equals(value.schemaVersion()) || !target.info().downstreamId().equals(value.downstreamService())
                    || !Boolean.FALSE.equals(value.synthetic()) || value.requestCount() == null || value.timeoutCount() == null
                    || value.recordedRequestCount() == null || value.requestP95Ms() == null || value.downstreamP95Ms() == null
                    || value.downstreamTimeoutRate() == null) throw unexpected();
                snapshot = new Snapshot(value.service(), Scenario.OBSERVED, value.windowStart(), value.windowEnd(),
                    value.requestCount(), value.requestCount() - value.timeoutCount(), value.timeoutCount(), value.recordedRequestCount(),
                    value.requestP95Ms(), value.downstreamP95Ms(), value.downstreamTimeoutRate(), value.baselineRequestP95Ms(), value.errors(), false);
            } else snapshot = json.readValue(response, Snapshot.class);
            validate(snapshot, context);
            return snapshot;
        } catch (IOException e) { throw new IllegalStateException("Invalid observation response", e); }
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
        }
    }
    private boolean metric(double value) { return Double.isFinite(value) && value >= 0; }
    private IllegalStateException unexpected() { return new IllegalStateException("Observation identity, window or values do not match the contract"); }

    private byte[] get(ServiceRegistry.Target target, String path) {
        URI uri = URI.create(target.baseUrl().toString().replaceAll("/$", "") + path);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(1200)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, info -> new BoundedBody());
            if (response.statusCode() != 200) throw new IllegalStateException("Observation service is unavailable");
            return response.body();
        } catch (IOException e) { throw new IllegalStateException("Observation service is unavailable", e); }
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
                    subscription.cancel(); result.completeExceptionally(new IOException("Observation body exceeds 32000 bytes")); return;
                }
                byte[] chunk = new byte[buffer.remaining()]; buffer.get(chunk); bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
