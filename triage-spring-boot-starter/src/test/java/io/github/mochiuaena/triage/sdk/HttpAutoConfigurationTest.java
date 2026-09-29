package io.github.mochiuaena.triage.sdk;

import java.net.SocketTimeoutException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;

class HttpAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(RestClientAutoConfiguration.class, TriageObservationAutoConfiguration.class));
    private WebApplicationContextRunner enabled() { return runner.withPropertyValues("triage.sdk.enabled=true",
        "triage.sdk.service-id=catalog-service", "triage.sdk.downstream-id=inventory-service",
        "triage.sdk.downstream-base-url=http://127.0.0.1:18084"); }

    @Test void dependencyAloneDoesNotExposeAnEndpointOrFilter() {
        runner.run(context -> assertThat(context).doesNotHaveBean(ObservationRecorder.class).doesNotHaveBean(TriageObservationsEndpoint.class));
    }
    @Test void missingIdentitiesFailAtStartup() {
        runner.withPropertyValues("triage.sdk.enabled=true").run(context -> assertThat(context).hasFailed());
    }
    @Test void handlesTimeoutsEvenWhenApplicationCatchesTheExceptionAndKeepsInputsPrivate() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            var mock = MockRestServiceServer.bindTo(builder).build();
            mock.expect(requestTo("http://127.0.0.1:18084/api/inventory/private-item?token=private-query"))
                .andExpect(method(HttpMethod.GET)).andRespond(request -> { throw new SocketTimeoutException("private-error"); });
            var client = builder.build();
            var request = new MockHttpServletRequest("GET", "/api/products/private-item");
            request.addHeader("Authorization", "private-header"); request.setContent("private-body".getBytes());
            var response = new MockHttpServletResponse();
            new TriageRequestFilter(recorder).doFilter(request, response, (req, res) -> {
                try { client.get().uri("http://127.0.0.1:18084/api/inventory/private-item?token=private-query").retrieve().body(String.class); }
                catch (ResourceAccessException ignored) { response.setStatus(504); }
            });
            var value = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(value.requestCount()).isEqualTo(1);
            assertThat(value.timeoutCount()).isEqualTo(1);
            assertThat(value.errors().getFirst().message()).isEqualTo("inventory-service request timeout");
            assertThat(value.toString()).doesNotContain("private-item", "private-query", "private-error", "private-body", "private-header");
            assertThat(TriageRequestFilter.CURRENT.get()).isNull();
            assertThat(response.getHeader("X-Triage-Trace-Id")).isEqualTo(value.errors().getFirst().traceId());
            mock.verify();
        });
    }
    @Test void endpointRefusesRemoteClientsAndUnsupportedWindows() {
        enabled().run(context -> {
            var endpoint = context.getBean(TriageObservationsEndpoint.class);
            var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.10");
            assertThatThrownBy(() -> endpoint.observations(5, Instant.now(), request)).hasMessageContaining("403");
            request.setRemoteAddr("127.0.0.1");
            assertThatThrownBy(() -> endpoint.observations(60, Instant.now(), request)).hasMessageContaining("400");
            assertThat(endpoint.observations(5, Instant.now(), request)).isInstanceOf(ObservationRecorder.HttpWindow.class);
        });
    }
    @Test void timeoutWhileReadingTheResponseBodyIsAlsoCounted() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            RestClient.Builder builder = context.getBean(RestClient.Builder.class);
            var mock = MockRestServiceServer.bindTo(builder).build();
            mock.expect(requestTo("http://127.0.0.1:18084/body")).andRespond(request -> {
                return new MockClientHttpResponse(new byte[0], org.springframework.http.HttpStatus.OK) {
                    @Override public java.io.InputStream getBody() {
                        return new java.io.InputStream() {
                            @Override public int read() throws java.io.IOException { throw new SocketTimeoutException("private-body-error"); }
                        };
                    }
                };
            });
            var client = builder.build();
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (req, res) -> {
                    try { client.get().uri("http://127.0.0.1:18084/body").retrieve().body(String.class); }
                    catch (RestClientException ignored) { }
                });
            var value = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(value.timeoutCount()).isEqualTo(1);
            assertThat(value.toString()).doesNotContain("private-body-error");
            mock.verify();
        });
    }
}
