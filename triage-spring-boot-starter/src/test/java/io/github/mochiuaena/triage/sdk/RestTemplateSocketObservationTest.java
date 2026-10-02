package io.github.mochiuaena.triage.sdk;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mock.web.*;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;

class RestTemplateSocketObservationTest {
    @ParameterizedTest @CsvSource({
        "default,normal", "simple,normal", "default,slow-headers", "simple,slow-headers",
        "default,slow-body", "simple,slow-body", "default,header-timeout", "simple,header-timeout",
        "default,body-timeout", "simple,body-timeout", "default,server-error", "simple,server-error"})
    void realSocketKeepsBuilderTimeoutsAndMeasuresHeadersAndBody(String factory, String scenario) throws Exception {
        try (var downstream = new Downstream()) {
            new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class,
                TriageObservationAutoConfiguration.class)).withPropertyValues("triage.sdk.enabled=true",
                    "triage.sdk.service-id=catalog-service", "triage.sdk.downstream-id=inventory-service",
                    "triage.sdk.downstream-base-url=" + downstream.origin()).run(context -> {
                        var recorder = context.getBean(ObservationRecorder.class);
                        var builder = context.getBean(RestTemplateBuilder.class).connectTimeout(Duration.ofSeconds(1))
                            .readTimeout(Duration.ofMillis(scenario.contains("timeout") ? 250 : 2000));
                        if (factory.equals("simple")) builder = builder.requestFactory(SimpleClientHttpRequestFactory::new);
                        var client = builder.build();
                        var response = new MockHttpServletResponse();
                        var businessFailure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
                        long started = System.nanoTime();
                        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/private-id"),
                            response, (request, servletResponse) -> {
                                String url = downstream.origin() + "/" + scenario + "?token=private-query";
                                if (scenario.contains("timeout")) {
                                    var thrown = catchThrowable(() -> client.getForObject(url, String.class));
                                    businessFailure.set(thrown);
                                    if (scenario.equals("body-timeout")) assertThat(thrown).isExactlyInstanceOf(RestClientException.class).hasCauseInstanceOf(IOException.class);
                                    else assertThat(thrown).isInstanceOf(ResourceAccessException.class);
                                    response.setStatus(504);
                                } else if (scenario.equals("server-error")) {
                                    assertThatThrownBy(() -> client.getForObject(url, String.class)).isInstanceOf(HttpServerErrorException.class);
                                } else assertThat(client.getForObject(url, String.class)).isEqualTo("ok");
                            });
                        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
                        assertThat(window.requestCount()).isEqualTo(1);
                        // Spring's JDK factory may close a timed-out body with only an untyped IOException.
                        boolean typedTimeout = scenario.contains("timeout") && !(factory.equals("default") && scenario.equals("body-timeout"));
                        assertThat(window.timeoutCount()).as("%s/%s failure: %s", factory, scenario, causeTypes(businessFailure.get())).isEqualTo(typedTimeout ? 1 : 0);
                        if (scenario.startsWith("slow")) assertThat(window.downstreamP95Ms()).isGreaterThanOrEqualTo(90);
                        if (scenario.contains("timeout")) {
                            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
                            assertThat(window.downstreamP95Ms()).isGreaterThanOrEqualTo(150);
                        }
                        assertThat(window.toString()).doesNotContain("private-id", "private-query", downstream.origin());
                        assertThat(TriageRequestFilter.CURRENT.get()).isNull();
                    });
        }
    }
    private static java.util.List<String> causeTypes(Throwable error) {
        var types = new java.util.ArrayList<String>();
        for (Throwable cause = error; cause != null; cause = cause.getCause()) types.add(cause.getClass().getName());
        return types;
    }
    private static final class Downstream implements AutoCloseable {
        private final ExecutorService workers = Executors.newCachedThreadPool();
        private final HttpServer server;
        Downstream() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    String path = exchange.getRequestURI().getPath();
                    if (path.contains("slow-headers")) pause(120);
                    if (path.contains("header-timeout")) pause(1000);
                    exchange.sendResponseHeaders(path.contains("server-error") ? 503 : 200, 2);
                    if (path.contains("body")) {
                        exchange.getResponseBody().write('o'); exchange.getResponseBody().flush();
                        pause(path.contains("timeout") ? 1000 : 120);
                        exchange.getResponseBody().write('k');
                    } else exchange.getResponseBody().write(new byte[]{'o', 'k'});
                } catch (IOException ignored) { /* The timed-out client may have closed its socket. */ }
                finally { exchange.close(); }
            });
            server.start();
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        private static void pause(long millis) throws IOException {
            try { Thread.sleep(millis); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        }
        @Override public void close() { server.stop(0); workers.shutdownNow(); workers.close(); }
    }
}
