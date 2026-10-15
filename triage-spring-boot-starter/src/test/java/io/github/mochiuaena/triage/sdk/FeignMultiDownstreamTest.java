package io.github.mochiuaena.triage.sdk;

import feign.Client;
import feign.Request;
import feign.Response;
import java.io.InputStream;
import java.io.Reader;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import static org.assertj.core.api.Assertions.*;

class FeignMultiDownstreamTest {
    private static TriageObservationProperties properties() {
        var properties = new TriageObservationProperties();
        properties.setServiceId("checkout-service");
        properties.setEndpointObservations(true);
        properties.setDownstreams(List.of(
            new TriageObservationProperties.Downstream("inventory-service", URI.create("http://127.0.0.1:18084")),
            new TriageObservationProperties.Downstream("payment-service", URI.create("http://127.0.0.1:18085"))));
        properties.validate();
        return properties;
    }
    private static Request request(int port) {
        return Request.create(Request.HttpMethod.GET, "http://127.0.0.1:" + port + "/private?token=private-query",
            Map.of(), new byte[0], StandardCharsets.UTF_8);
    }
    private static ObservationRecorder.EndpointWindow window(ObservationRecorder recorder) {
        return (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
    }

    @Test void executeTimeoutStaysWithInventoryWhenPaymentHasNoBody() throws Exception {
        var properties = properties();
        var recorder = new ObservationRecorder(properties);
        var timeout = new SocketTimeoutException("private-execute-timeout");
        Client delegate = (request, options) -> {
            if (URI.create(request.url()).getPort() == 18084) throw timeout;
            return Response.builder().status(204).request(request).build();
        };
        var client = new TriageFeignCapability(properties).enrich(delegate);
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/checkout"),
            new MockHttpServletResponse(), (inbound, outbound) -> {
                assertThatThrownBy(() -> client.execute(request(18084), new Request.Options())).isSameAs(timeout);
                try (var response = client.execute(request(18085), new Request.Options())) {
                    assertThat(response.status()).isEqualTo(204);
                    assertThat(response.body()).isNull();
                }
            });
        var result = window(recorder);
        assertThat(result.schemaVersion()).isEqualTo(5);
        assertThat(result.timeoutCount()).isEqualTo(1);
        assertThat(result.downstreams().get(0).requestCount()).isEqualTo(1);
        assertThat(result.downstreams().get(0).timeoutCount()).isEqualTo(1);
        assertThat(result.downstreams().get(0).responseStatuses().unknown()).isEqualTo(1);
        assertThat(result.downstreams().get(1).requestCount()).isEqualTo(1);
        assertThat(result.downstreams().get(1).timeoutCount()).isZero();
        assertThat(result.downstreams().get(1).responseStatuses().successful()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().getFirst().message()).contains("inventory-service");
        assertThat(result.toString()).doesNotContain("null request timeout", "private-execute-timeout", "private-query");
    }

    @Test void bodyTimeoutKeepsOriginAndReceivedStatusForStreamsAndReaders() throws Exception {
        for (boolean reader : new boolean[]{false, true}) {
            var properties = properties();
            var recorder = new ObservationRecorder(properties);
            var body = new FailingBody();
            Client delegate = (request, options) -> Response.builder().status(200).request(request).body(body).build();
            var client = new TriageFeignCapability(properties).enrich(delegate);
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/checkout"),
                new MockHttpServletResponse(), (inbound, outbound) -> {
                    try (var response = client.execute(request(18084), new Request.Options())) {
                        if (reader) assertThatThrownBy(() -> response.body().asReader(StandardCharsets.UTF_8).read())
                            .isSameAs(body.timeout);
                        else assertThatThrownBy(() -> response.body().asInputStream().read()).isSameAs(body.timeout);
                    }
                });
            var result = window(recorder);
            assertThat(result.timeoutCount()).isEqualTo(1);
            assertThat(result.downstreams().get(0).timeoutCount()).isEqualTo(1);
            assertThat(result.downstreams().get(0).responseStatuses().successful()).isEqualTo(1);
            assertThat(result.downstreams().get(1).requestCount()).isZero();
            assertThat(body.closes.get()).isEqualTo(1);
            assertThat(result.toString()).doesNotContain("private-body-timeout", "private-query");
        }
    }

    @Test void responseClassesStaySeparateAndUnregisteredOriginsRemainUnobserved() throws Exception {
        var properties = properties();
        var recorder = new ObservationRecorder(properties);
        Client delegate = (request, options) -> Response.builder()
            .status(URI.create(request.url()).getPort() == 18084 ? 200 : 503)
            .request(request).body("private-response", StandardCharsets.UTF_8).build();
        var client = new TriageFeignCapability(properties).enrich(delegate);
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/checkout"),
            new MockHttpServletResponse(), (inbound, outbound) -> {
                for (int port : new int[]{18084, 18085, 18086}) {
                    try (var response = client.execute(request(port), new Request.Options())) {
                        assertThat(new String(response.body().asInputStream().readAllBytes(), StandardCharsets.UTF_8))
                            .isEqualTo("private-response");
                    }
                }
            });
        var result = window(recorder);
        assertThat(result.timeoutCount()).isZero();
        assertThat(result.downstreams()).hasSize(2);
        assertThat(result.downstreams().get(0).requestCount()).isEqualTo(1);
        assertThat(result.downstreams().get(0).responseStatuses().successful()).isEqualTo(1);
        assertThat(result.downstreams().get(1).requestCount()).isEqualTo(1);
        assertThat(result.downstreams().get(1).responseStatuses().serverError()).isEqualTo(1);
        assertThat(result.toString()).doesNotContain("private-response", "private-query", "http://");
    }

    private static final class FailingBody implements Response.Body {
        final SocketTimeoutException timeout = new SocketTimeoutException("private-body-timeout");
        final AtomicInteger closes = new AtomicInteger();
        @Override public Integer length() { return 1; }
        @Override public boolean isRepeatable() { return false; }
        @Override public InputStream asInputStream() {
            return new InputStream() { @Override public int read() throws java.io.IOException { throw timeout; } };
        }
        @Override public Reader asReader(Charset charset) {
            return new Reader() {
                @Override public int read(char[] chars, int offset, int length) throws java.io.IOException { throw timeout; }
                @Override public void close() { }
            };
        }
        @Override public void close() { closes.incrementAndGet(); }
    }
}
