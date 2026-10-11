package io.github.mochiuaena.triage.sdk;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import reactor.netty.http.client.HttpClient;
import static org.assertj.core.api.Assertions.*;

class WebClientSocketObservationTest {
    @ParameterizedTest @ValueSource(strings = {"normal", "header-timeout", "body-timeout"})
    void realSocketRecordsNormalAndTypedTimeouts(String scenario) throws Exception {
        try (var downstream = new Downstream()) {
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class,
                TriageObservationAutoConfiguration.class, TriageWebClientAutoConfiguration.class))
                .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                    "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=" + downstream.origin())
                .run(context -> {
                    var recorder = context.getBean(ObservationRecorder.class);
                    var timeout = scenario.equals("normal") ? Duration.ofSeconds(2) : Duration.ofMillis(250);
                    var client = context.getBean(org.springframework.web.reactive.function.client.WebClient.Builder.class)
                        .clientConnector(new ReactorClientHttpConnector(HttpClient.create().responseTimeout(timeout))).build();
                    var response = new MockHttpServletResponse();
                    new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/private-item"),
                        response, (request, servletResponse) -> {
                            java.util.function.Supplier<String> call = () -> client.get().uri(downstream.origin() + "/" + scenario + "?token=private-query")
                                .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(3));
                            if (scenario.equals("normal")) assertThat(call.get()).isEqualTo("ok");
                            else {
                                assertThatThrownBy(call::get).isNotNull();
                                response.setStatus(504);
                            }
                        });
                    var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
                    assertThat(window.requestCount()).isEqualTo(1);
                    assertThat(window.timeoutCount()).isEqualTo(scenario.equals("normal") ? 0 : 1);
                    assertThat(window.downstreamP95Ms()).isGreaterThan(scenario.equals("normal") ? 0 : 150);
                    assertThat(window.toString()).doesNotContain("private-item", "private-query", downstream.origin());
                });
        }
    }

    @Test void realConnectionRefusalIsNotTypedAsTimeout() throws Exception {
        int unavailablePort;
        try (var reserved = new ServerSocket(0)) { unavailablePort = reserved.getLocalPort(); }
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(WebClientAutoConfiguration.class,
            TriageObservationAutoConfiguration.class, TriageWebClientAutoConfiguration.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=http://127.0.0.1:" + unavailablePort)
            .run(context -> {
                var recorder = context.getBean(ObservationRecorder.class);
                var client = context.getBean(org.springframework.web.reactive.function.client.WebClient.Builder.class).build();
                var response = new MockHttpServletResponse();
                new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
                    response, (request, servletResponse) -> {
                        assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:" + unavailablePort + "/private")
                            .retrieve().bodyToMono(String.class).block(Duration.ofSeconds(3))).isNotNull();
                        response.setStatus(504);
                    });
                var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
                assertThat(window.requestCount()).isEqualTo(1);
                assertThat(window.timeoutCount()).isZero();
                assertThat(window.downstreamP95Ms()).isGreaterThan(0);
            });
    }

    private static final class Downstream implements AutoCloseable {
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final HttpServer server;
        Downstream() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes();
                    String path = exchange.getRequestURI().getPath();
                    if (path.contains("header-timeout")) pause();
                    exchange.sendResponseHeaders(200, 2);
                    if (path.contains("body-timeout")) {
                        exchange.getResponseBody().write('o');
                        exchange.getResponseBody().flush();
                        pause();
                        exchange.getResponseBody().write('k');
                    } else exchange.getResponseBody().write(new byte[]{'o', 'k'});
                } catch (IOException ignored) { /* A timed-out client may close first. */ }
                finally { exchange.close(); }
            });
            server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private static void pause() throws IOException {
            try { Thread.sleep(1000); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
        }
        @Override public void close() { server.stop(0); workers.shutdownNow(); workers.close(); }
    }
}
