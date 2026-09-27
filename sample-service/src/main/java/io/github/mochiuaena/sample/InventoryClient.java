package io.github.mochiuaena.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

/** All inventory calls cross the process boundary through this HTTP client. */
@Component
public class InventoryClient {
    private final URI origin;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(300)).build();

    public InventoryClient(@Value("${sample.inventory.base-url:http://127.0.0.1:18084}") String baseUrl, ObjectMapper json) {
        this.origin = URI.create(baseUrl);
        this.json = json;
        String host = origin.getHost();
        if (!"http".equals(origin.getScheme()) || host == null
            || !Set.of("127.0.0.1", "localhost", "[::1]").contains(host)
            || origin.getPort() < 1 || origin.getPort() > 65535
            || origin.getUserInfo() != null || origin.getQuery() != null || origin.getFragment() != null
            || !(origin.getPath().isEmpty() || "/".equals(origin.getPath())))
            throw new IllegalArgumentException("Inventory URL must be a loopback HTTP origin with an explicit port");
    }

    public void availability(String traceId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri("/api/inventory/sku"))
            .timeout(Duration.ofMillis(300)).header("X-Trace-Id", traceId).GET().build();
        HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != 200) throw new IOException("Inventory returned " + response.statusCode());
    }

    public ObservationStore.Scenario scenario() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send("GET", "/lab/scenario", null);
        if (response.statusCode() != 200 || response.body().length > 1024) throw new IOException("Inventory scenario unavailable");
        String value = json.readTree(response.body()).path("scenario").asText();
        try { return ObservationStore.Scenario.valueOf(value); }
        catch (IllegalArgumentException e) { throw new IOException("Inventory returned invalid scenario", e); }
    }

    public void scenario(ObservationStore.Scenario scenario) throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send("POST", "/lab/scenario", "{\"scenario\":\"" + scenario.name() + "\"}");
        if (response.statusCode() != 200) throw new IOException("Inventory scenario change failed");
    }

    public void reset() throws IOException, InterruptedException {
        HttpResponse<byte[]> response = send("POST", "/lab/reset", "{}");
        if (response.statusCode() != 200) throw new IOException("Inventory reset failed");
    }

    private HttpResponse<byte[]> send(String method, String path, String body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(1));
        if ("POST".equals(method)) request.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
        else request.GET();
        return http.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) { return URI.create(origin.toString().replaceAll("/$", "") + path); }
}
