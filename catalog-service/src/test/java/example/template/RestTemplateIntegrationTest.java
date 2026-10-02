package example.template;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = RestTemplateIntegrationTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.url=jdbc:h2:mem:template-integration;DB_CLOSE_DELAY=-1", "spring.sql.init.mode=never"})
class RestTemplateIntegrationTest {
    private static final ExecutorService workers = Executors.newCachedThreadPool();
    private static final HttpServer downstream = startDownstream();
    @Autowired TestRestTemplate http;

    @SpringBootConfiguration @EnableAutoConfiguration @Import(Controller.class)
    static class Application { }
    @RestController
    static class Controller {
        private final RestTemplate client;
        Controller(RestTemplateBuilder builder, @Value("${triage.sdk.downstream-base-url}") String origin) {
            client = builder.requestFactory(org.springframework.http.client.SimpleClientHttpRequestFactory::new).rootUri(origin).connectTimeout(Duration.ofSeconds(1)).readTimeout(Duration.ofMillis(250)).build();
        }
        @GetMapping({"/api/template/{mode}", "/outside/{mode}"})
        ResponseEntity<String> lookup(@PathVariable String mode) {
            try { return ResponseEntity.ok(client.getForObject("/" + mode + "?token=private-query", String.class)); }
            catch (RestClientException failure) { return ResponseEntity.status(504).body("Downstream did not complete"); }
        }
    }
    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("triage.sdk.enabled", () -> "true");
        properties.add("triage.sdk.service-id", () -> "template-service");
        properties.add("triage.sdk.downstream-id", () -> "inventory-service");
        properties.add("triage.sdk.downstream-base-url", () -> "http://127.0.0.1:" + downstream.getAddress().getPort());
        properties.add("triage.sdk.endpoint-observations", () -> "true");
        properties.add("triage.sdk.exception-locations", () -> "true");
        properties.add("triage.sdk.application-packages", () -> "example.template");
        properties.add("triage.sdk.response-status-counts", () -> "true");
    }
    @Test void independentApplicationReportsRealHeaderAndBodyTimeoutsThroughReadOnlyEndpoints() {
        var excluded = http.getForEntity("/outside/normal", String.class);
        assertThat(excluded.getStatusCode().value()).isEqualTo(200);
        assertThat(excluded.getHeaders().getFirst("X-Triage-Trace-Id")).isNull();
        var normal = http.getForEntity("/api/template/normal", String.class);
        assertThat(normal.getStatusCode().value()).isEqualTo(200);
        assertThat(normal.getBody()).isEqualTo("ok");
        var headers = http.getForEntity("/api/template/header-timeout", String.class);
        var body = http.getForEntity("/api/template/body-timeout", String.class);
        assertThat(headers.getStatusCode().value()).isEqualTo(504);
        assertThat(body.getStatusCode().value()).isEqualTo(504);
        // Receiving the reply does not guarantee that the servlet filter has recorded its completion yet.
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            var observed = http.getForEntity("/triage/observations?windowMinutes=5&endTime={end}", Map.class, Instant.now().toString());
            assertThat(observed.getStatusCode().value()).isEqualTo(200);
            assertThat(observed.getBody()).containsEntry("service", "template-service").containsEntry("requestCount", 3)
                .containsEntry("timeoutCount", 2).containsEntry("synthetic", false);
        });
        String end = Instant.now().toString();
        var endpoints = http.getForEntity("/triage/endpoint-observations?windowMinutes=5&endTime={end}", String.class, end);
        assertThat(endpoints.getStatusCode().value()).isEqualTo(200);
        assertThat(endpoints.getBody()).contains("HTTP_CLIENT_FAILURE", "example.template", "/api/template/{mode}",
            headers.getHeaders().getFirst("X-Triage-Trace-Id"), body.getHeaders().getFirst("X-Triage-Trace-Id"));
        assertThat(endpoints.getBody()).doesNotContain("private-query", "/outside/", "Downstream did not complete");
    }
    private static HttpServer startDownstream() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    if (path.contains("header-timeout")) pause();
                    exchange.sendResponseHeaders(200, 2);
                    exchange.getResponseBody().write('o'); exchange.getResponseBody().flush();
                    if (path.contains("body-timeout")) pause();
                    exchange.getResponseBody().write('k');
                } catch (IOException ignored) { /* The application timeout may close the socket before the reply. */ }
                finally { exchange.close(); }
            });
            server.start(); return server;
        } catch (IOException failure) { workers.shutdownNow(); throw new UncheckedIOException(failure); }
    }
    private static void pause() throws IOException {
        try { Thread.sleep(1000); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
    }
    @AfterAll static void closeDownstream() { downstream.stop(0); workers.shutdownNow(); workers.close(); }
}
