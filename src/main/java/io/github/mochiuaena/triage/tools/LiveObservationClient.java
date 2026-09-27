package io.github.mochiuaena.triage.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import org.springframework.stereotype.Component;
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

/** Reads only the local sample service's observations; it cannot change the lab scenario. */
@Component
public class LiveObservationClient {
    public record ErrorEntry(Instant timestamp, String traceId, String level, String message) {}
    public record Snapshot(String service, Scenario scenario, Instant windowStart, Instant windowEnd,
                           int requestCount, int normalCount, int timeoutCount, double orderP95Ms,
                           double downstreamP95Ms, double downstreamTimeoutRate, Double baselineOrderP95Ms,
                           List<ErrorEntry> errors, boolean synthetic) {}
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).build();
    private final ObservationSource source;
    private final ObjectMapper json;

    public LiveObservationClient(ObservationSource source, ObjectMapper json) {
        this.source = source;
        this.json = json;
    }

    public Scenario scenario() {
        try { return Scenario.valueOf(json.readTree(get("/lab/scenario")).get("scenario").asText()); }
        catch (IOException e) { throw new IllegalStateException("Invalid sample service response", e); }
    }

    public Snapshot snapshot(ToolContext context) {
        String end = URLEncoder.encode(context.endTime().toString(), StandardCharsets.UTF_8);
        try {
            Snapshot snapshot = json.readValue(get("/lab/observations?windowMinutes=" + context.windowMinutes() + "&endTime=" + end), Snapshot.class);
            if (!"order-service".equals(snapshot.service()) || snapshot.synthetic()
                || snapshot.scenario() != context.scenario()
                || !context.endTime().equals(snapshot.windowEnd())) throw new IllegalStateException("Unexpected sample service observations");
            return snapshot;
        } catch (IOException e) { throw new IllegalStateException("Invalid sample service response", e); }
    }

    private byte[] get(String path) {
        URI uri = URI.create(source.baseUrl().toString().replaceAll("/$", "") + path);
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(1200)).GET().build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || response.body().length > 32_000)
                throw new IllegalStateException("Sample service is unavailable or returned too much data");
            return response.body();
        } catch (IOException e) { throw new IllegalStateException("Sample service is unavailable", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Observation request interrupted", e); }
    }
}
