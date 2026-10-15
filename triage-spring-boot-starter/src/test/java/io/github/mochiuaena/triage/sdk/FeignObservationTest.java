package io.github.mochiuaena.triage.sdk;

import feign.Client;
import feign.Request;
import feign.Response;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class FeignObservationTest {
    private static Request request(String url) {
        return Request.create(Request.HttpMethod.GET, url, Map.of(), new byte[0], StandardCharsets.UTF_8);
    }

    private static final class FailingBody implements Response.Body {
        final SocketTimeoutException failure = new SocketTimeoutException("private-body-timeout");
        final AtomicInteger closes = new AtomicInteger();
        @Override public Integer length() { return 2; }
        @Override public boolean isRepeatable() { return false; }
        @Override public InputStream asInputStream() {
            return new InputStream() { @Override public int read() throws java.io.IOException { throw failure; } };
        }
        @Override public Reader asReader(Charset charset) {
            return new Reader() {
                @Override public int read(char[] buffer, int offset, int length) throws java.io.IOException { throw failure; }
                @Override public void close() { }
            };
        }
        @Override public void close() { closes.incrementAndGet(); }
    }

    @Test void matchingFeignCallContributesDurationWithoutSavingTheUrl() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        Client delegate = (request, options) -> {
            try { Thread.sleep(15); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            return Response.builder().status(200).request(request).body("ok", StandardCharsets.UTF_8).build();
        };
        var client = new TriageFeignCapability(properties).enrich(delegate);

        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            new MockHttpServletResponse(), (inbound, outbound) -> {
                try (var result = client.execute(request("http://127.0.0.1:18084/private?token=private-query"), new Request.Options())) {
                    assertThat(result.status()).isEqualTo(200);
                    assertThat(new String(result.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("ok");
                }
            });

        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.requestCount()).isEqualTo(1);
        assertThat(window.downstreamP95Ms()).isGreaterThan(0);
        assertThat(window.toString()).doesNotContain("private-query", "http://");
    }

    @Test void unrelatedFeignOriginDoesNotContributeDownstreamTime() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        Client delegate = (request, options) -> Response.builder().status(200).request(request).body("ok", StandardCharsets.UTF_8).build();
        var client = new TriageFeignCapability(properties).enrich(delegate);

        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            new MockHttpServletResponse(), (inbound, outbound) -> {
                try (var result = client.execute(request("http://127.0.0.1:18085/other"), new Request.Options())) {
                    assertThat(result.status()).isEqualTo(200);
                }
            });

        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.downstreamP95Ms()).isZero();
    }

    @Test void typedFeignTimeoutIsRecordedButConnectionRefusalIsNot() throws Exception {
        for (var failure : new java.io.IOException[]{new SocketTimeoutException("private-timeout"),
            new ConnectException("private-connection")}) {
            var properties = ObservationRecorderTest.properties();
            properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
            var recorder = new ObservationRecorder(properties);
            Client delegate = (request, options) -> { throw failure; };
            var client = new TriageFeignCapability(properties).enrich(delegate);
            var response = new MockHttpServletResponse();

            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
                response, (inbound, outbound) -> {
                    assertThatThrownBy(() -> client.execute(request("http://127.0.0.1:18084/private?token=private-query"),
                        new Request.Options())).isSameAs(failure);
                    response.setStatus(504);
                });

            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.timeoutCount()).isEqualTo(failure instanceof SocketTimeoutException ? 1 : 0);
            assertThat(window.toString()).doesNotContain("private-timeout", "private-connection", "private-query");
        }
    }

    @Test void feignServerErrorDoesNotBecomeDownstreamTimeout() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        Client delegate = (request, options) -> {
            // Keep the simulated wait above the window's displayed duration precision.
            try { Thread.sleep(15); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            return Response.builder().status(503).request(request)
                .body("private-server-body", StandardCharsets.UTF_8).build();
        };
        var client = new TriageFeignCapability(properties).enrich(delegate);

        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            new MockHttpServletResponse(), (inbound, outbound) -> {
                try (var result = client.execute(request("http://127.0.0.1:18084/private"), new Request.Options())) {
                    assertThat(result.status()).isEqualTo(503);
                }
            });

        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.timeoutCount()).isZero();
        assertThat(window.downstreamP95Ms()).isGreaterThan(0);
        assertThat(window.toString()).doesNotContain("private-server-body", "http://");
    }

    @Test void bodyTimeoutAfterHeadersIsObservedForStreamsAndReadersWithoutChangingClose() throws Exception {
        for (boolean reader : new boolean[]{false, true}) {
            var properties = ObservationRecorderTest.properties();
            properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
            var recorder = new ObservationRecorder(properties);
            var body = new FailingBody();
            Client delegate = (request, options) -> Response.builder().status(200).request(request).body(body).build();
            var client = new TriageFeignCapability(properties).enrich(delegate);
            var inboundResponse = new MockHttpServletResponse();

            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
                inboundResponse, (inbound, outbound) -> {
                    try (var result = client.execute(request("http://127.0.0.1:18084/private?token=private-query"),
                        new Request.Options())) {
                        if (reader) assertThatThrownBy(() -> result.body().asReader(StandardCharsets.UTF_8).read())
                            .isSameAs(body.failure);
                        else assertThatThrownBy(() -> result.body().asInputStream().read()).isSameAs(body.failure);
                    }
                    inboundResponse.setStatus(504);
                });

            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.timeoutCount()).isEqualTo(1);
            assertThat(body.closes.get()).isEqualTo(1);
            assertThat(window.toString()).doesNotContain("private-body-timeout", "private-query", "http://");
        }
    }

    @Test void bodyReadAfterRequestCompletionCannotChangeTheFrozenWindow() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        var body = new FailingBody();
        Client delegate = (request, options) -> Response.builder().status(200).request(request).body(body).build();
        var client = new TriageFeignCapability(properties).enrich(delegate);
        var pending = new AtomicReference<Response>();

        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            new MockHttpServletResponse(), (inbound, outbound) -> pending.set(client.execute(
                request("http://127.0.0.1:18084/private"), new Request.Options())));
        var completed = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        try (var result = pending.get()) {
            assertThatThrownBy(() -> result.body().asInputStream().read()).isSameAs(body.failure);
        }
        assertThat((ObservationRecorder.HttpWindow) recorder.snapshot(5, completed.windowEnd())).isEqualTo(completed);
        assertThat(body.closes.get()).isEqualTo(1);
    }
}
