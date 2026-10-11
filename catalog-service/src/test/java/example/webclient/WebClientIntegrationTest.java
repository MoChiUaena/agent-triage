package example.webclient;

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
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(classes = WebClientIntegrationTest.Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {"spring.datasource.url=jdbc:h2:mem:webclient-integration;DB_CLOSE_DELAY=-1", "spring.sql.init.mode=never"})
class WebClientIntegrationTest {
    private static final ExecutorService workers = Executors.newCachedThreadPool();
    private static final HttpServer downstream = startDownstream();
    @Autowired TestRestTemplate http;

    @SpringBootConfiguration @EnableAutoConfiguration @Import(Controller.class)
    static class Application { }

    @RestController
    static class Controller {
        private final WebClient normal, timed;
        Controller(WebClient.Builder builder, @Value("${triage.sdk.downstream-base-url}") String origin) {
            normal = builder.clone().baseUrl(origin).clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofSeconds(2)))).build();
            timed = builder.clone().baseUrl(origin).clientConnector(new ReactorClientHttpConnector(
                HttpClient.create().responseTimeout(Duration.ofMillis(250)))).build();
        }
        @GetMapping({"/api/webclient/{mode}", "/outside/{mode}"})
        ResponseEntity<String> lookup(@PathVariable String mode) {
            try {
                String body = (mode.equals("normal") ? normal : timed).get()
                    .uri("/" + mode + "?token=private-query").retrieve().bodyToMono(String.class).block(Duration.ofSeconds(3));
                return ResponseEntity.ok(body);
            } catch (RuntimeException failure) {
                return ResponseEntity.status(504).body("Downstream did not complete");
            }
        }
    }

    @DynamicPropertySource static void configure(DynamicPropertyRegistry properties) {
        properties.add("triage.sdk.enabled", () -> "true");
        properties.add("triage.sdk.service-id", () -> "webclient-service");
        properties.add("triage.sdk.downstream-id", () -> "inventory-service");
        properties.add("triage.sdk.downstream-base-url", () -> "http://127.0.0.1:" + downstream.getAddress().getPort());
        properties.add("triage.sdk.endpoint-observations", () -> "true");
        properties.add("triage.sdk.exception-locations", () -> "true");
        properties.add("triage.sdk.application-packages", () -> "example.webclient");
        properties.add("triage.sdk.response-status-counts", () -> "true");
    }

    @Test void independentServletApplicationReportsWebClientOutcomes() {
        var excluded = http.getForEntity("/outside/normal", String.class);
        assertThat(excluded.getStatusCode().value()).isEqualTo(200);
        assertThat(excluded.getHeaders().getFirst("X-Triage-Trace-Id")).isNull();
        var normal = http.getForEntity("/api/webclient/normal", String.class);
        assertThat(normal.getStatusCode().value()).isEqualTo(200);
        assertThat(normal.getBody()).isEqualTo("ok");
        var headers = http.getForEntity("/api/webclient/header-timeout", String.class);
        var body = http.getForEntity("/api/webclient/body-timeout", String.class);
        assertThat(headers.getStatusCode().value()).isEqualTo(504);
        assertThat(body.getStatusCode().value()).isEqualTo(504);
        await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(20)).untilAsserted(() -> {
            var observed = http.getForEntity("/triage/observations?windowMinutes=5&endTime={end}", Map.class, Instant.now().toString());
            assertThat(observed.getStatusCode().value()).isEqualTo(200);
            assertThat(observed.getBody()).containsEntry("service", "webclient-service")
                .containsEntry("requestCount", 3).containsEntry("timeoutCount", 2).containsEntry("synthetic", false);
        });
        var endpoints = http.getForEntity("/triage/endpoint-observations?windowMinutes=5&endTime={end}",
            String.class, Instant.now().toString());
        assertThat(endpoints.getStatusCode().value()).isEqualTo(200);
        assertThat(endpoints.getBody()).contains("HTTP_CLIENT_FAILURE", "example.webclient", "/api/webclient/{mode}",
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
                } catch (IOException ignored) { /* A timed-out client may close before the reply. */ }
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
