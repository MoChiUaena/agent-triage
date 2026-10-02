package io.github.mochiuaena.triage.sdk;

import java.net.SocketTimeoutException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class RestTemplateObservationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(RestTemplateAutoConfiguration.class, TriageObservationAutoConfiguration.class));
    private WebApplicationContextRunner enabled() {
        return runner.withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
            "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=http://127.0.0.1:18084");
    }
    private static ObservationRecorder.HttpWindow window(ObservationRecorder recorder) {
        return (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
    }
    @Test void bootBuilderRecordsDownstreamDuration() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            transport.expect(requestTo("http://127.0.0.1:18084/private-item")).andRespond(request -> {
                try { Thread.sleep(80); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new java.io.IOException(error); }
                return new MockClientHttpResponse("ok".getBytes(java.nio.charset.StandardCharsets.UTF_8), HttpStatus.OK);
            });
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (request, response) ->
                    assertThat(client.getForObject("http://127.0.0.1:18084/private-item", String.class)).isEqualTo("ok"));
            assertThat(window(recorder).requestCount()).isEqualTo(1);
            assertThat(window(recorder).downstreamP95Ms()).isGreaterThanOrEqualTo(50);
            assertThat(window(recorder).timeoutCount()).isZero();
            transport.verify();
        });
    }
    @Test void bootBuilderRecordsCaughtTimeoutWithoutExportingInputs() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            String url = "http://127.0.0.1:18084/private-item?token=private-query";
            transport.expect(requestTo(url)).andRespond(request -> { throw new SocketTimeoutException("private-error"); });
            var response = new MockHttpServletResponse();
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/private-input"),
                response, (request, ignored) -> {
                    assertThatThrownBy(() -> client.getForObject(url, String.class)).isInstanceOf(ResourceAccessException.class);
                    response.setStatus(504);
                });
            var observed = window(recorder);
            assertThat(observed.timeoutCount()).isEqualTo(1);
            assertThat(observed.requestCount()).isEqualTo(1);
            assertThat(observed.errors()).singleElement().satisfies(error -> {
                assertThat(error.message()).isEqualTo("inventory-service request timeout");
                assertThat(error.traceId()).isEqualTo(response.getHeader("X-Triage-Trace-Id"));
            });
            assertThat(observed.toString()).doesNotContain("private-item", "private-query", "private-error", "private-input");
            assertThat(TriageRequestFilter.CURRENT.get()).isNull();
            transport.verify();
        });
    }
}
