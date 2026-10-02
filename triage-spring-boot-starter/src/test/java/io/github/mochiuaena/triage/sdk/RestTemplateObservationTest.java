package io.github.mochiuaena.triage.sdk;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.*;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.*;
import org.springframework.test.web.client.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

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
                catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new IOException(error); }
                return new MockClientHttpResponse("ok".getBytes(UTF_8), HttpStatus.OK);
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
    @Test void dependencyAndInboundModeDoNotInstallDownstreamObservation() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(TriageRestTemplateCustomizer.class);
            assertThat(context.getBean(RestTemplateBuilder.class).build().getInterceptors()).isEmpty();
        });
        runner.withPropertyValues("triage.sdk.enabled=true", "triage.sdk.kind=HTTP_REQUESTS",
            "triage.sdk.service-id=catalog-service").run(context -> {
                assertThat(context).doesNotHaveBean(TriageRestTemplateCustomizer.class).doesNotHaveBean(TriageRestClientCustomizer.class);
                assertThat(context.getBean(RestTemplateBuilder.class).build().getInterceptors()).isEmpty();
            });
    }
    @Test void missingContextForeignOriginAndManuallyCreatedClientsStayUnobserved() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            transport.expect(requestTo("http://127.0.0.1:18084/no-context"))
                .andRespond(request -> { throw new SocketTimeoutException("outside-request"); });
            transport.expect(requestTo("http://localhost:18084/other-host"))
                .andRespond(request -> { throw new SocketTimeoutException("outside-origin"); });
            var manual = new RestTemplate();
            var manualTransport = MockRestServiceServer.bindTo(manual).build();
            manualTransport.expect(requestTo("http://127.0.0.1:18084/manual"))
                .andRespond(request -> { throw new SocketTimeoutException("manual-client"); });
            assertThatThrownBy(() -> client.getForObject("http://127.0.0.1:18084/no-context", String.class))
                .isInstanceOf(ResourceAccessException.class);
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (request, response) -> {
                    assertThatThrownBy(() -> client.getForObject("http://localhost:18084/other-host", String.class))
                        .isInstanceOf(ResourceAccessException.class);
                    assertThatThrownBy(() -> manual.getForObject("http://127.0.0.1:18084/manual", String.class))
                        .isInstanceOf(ResourceAccessException.class);
                });
            assertThat(window(recorder).requestCount()).isEqualTo(1);
            assertThat(window(recorder).downstreamP95Ms()).isZero();
            assertThat(window(recorder).timeoutCount()).isZero();
            transport.verify(); manualTransport.verify();
        });
    }
    @Test void repeatedCustomizationKeepsUserInterceptorAndErrorHandler() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var calls = new AtomicInteger();
            var handler = new ResponseErrorHandler() {
                @Override public boolean hasError(ClientHttpResponse response) { return false; }
                @Override public void handleError(java.net.URI url, HttpMethod method, ClientHttpResponse response) { fail("Unexpected error handling"); }
            };
            var builder = context.getBean(RestTemplateBuilder.class).errorHandler(handler)
                .additionalInterceptors((request, body, execution) -> { calls.incrementAndGet(); return execution.execute(request, body); });
            var client = builder.build(); context.getBean(TriageRestTemplateCustomizer.class).customize(client);
            assertThat(client.getErrorHandler()).isSameAs(handler);
            var transport = MockRestServiceServer.bindTo(client).build();
            transport.expect(requestTo("http://127.0.0.1:18084/server-error"))
                .andRespond(request -> new MockClientHttpResponse("private-server-body".getBytes(UTF_8), HttpStatus.INTERNAL_SERVER_ERROR));
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (request, response) ->
                    assertThat(client.getForObject("http://127.0.0.1:18084/server-error", String.class)).isEqualTo("private-server-body"));
            assertThat(client.getInterceptors()).filteredOn(TriageHttpClientInterceptor.class::isInstance).hasSize(1);
            assertThat(calls.get()).isEqualTo(1);
            assertThat(window(recorder).requestCount()).isEqualTo(1);
            assertThat(window(recorder).timeoutCount()).isZero();
            assertThat(window(recorder).toString()).doesNotContain("private-server-body");
            transport.verify();
        });
    }
    @Test void defaultErrorHandlerStillThrowsForServerErrorsWithoutCountingTimeouts() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            transport.expect(requestTo("http://127.0.0.1:18084/server-error"))
                .andRespond(request -> new MockClientHttpResponse("private-body".getBytes(UTF_8), HttpStatus.SERVICE_UNAVAILABLE));
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (request, response) ->
                    assertThatThrownBy(() -> client.getForObject("http://127.0.0.1:18084/server-error", String.class))
                        .isInstanceOf(HttpServerErrorException.class)
                        .satisfies(error -> assertThat(((HttpServerErrorException) error).getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)));
            assertThat(window(recorder).timeoutCount()).isZero();
            assertThat(window(recorder).requestCount()).isEqualTo(1);
            assertThat(window(recorder).errors()).isEmpty();
            transport.verify();
        });
    }
    @ParameterizedTest
    @MethodSource("transportFailures")
    void transportFailureClassificationPreservesApplicationExceptions(Throwable failure, boolean timeout) {
        enabled().withPropertyValues("triage.sdk.endpoint-observations=true", "triage.sdk.exception-locations=true",
            "triage.sdk.application-packages=io.github.mochiuaena.triage.sdk").run(context -> {
                var recorder = context.getBean(ObservationRecorder.class);
                var client = context.getBean(RestTemplateBuilder.class).build();
                var transport = MockRestServiceServer.bindTo(client).build();
                transport.expect(requestTo("http://127.0.0.1:18084/private-uri")).andRespond(request -> {
                    if (failure instanceof IOException io) throw io;
                    throw (RuntimeException) failure;
                });
                new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                    new MockHttpServletResponse(), (request, response) -> {
                        var caught = catchThrowable(() -> client.getForObject("http://127.0.0.1:18084/private-uri", String.class));
                        if (failure instanceof IOException) assertThat(caught).isInstanceOf(ResourceAccessException.class).hasCause(failure);
                        else assertThat(caught).isSameAs(failure);
                    });
                assertThat(window(recorder).timeoutCount()).isEqualTo(timeout ? 1 : 0);
                var endpointWindow = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
                assertThat(endpointWindow.toString()).doesNotContain("private-failure", "private-uri");
                if (timeout) assertThat(endpointWindow.errors()).singleElement().satisfies(error -> {
                    assertThat(error.failureLocation().kind()).isEqualTo("HTTP_CLIENT_FAILURE");
                    assertThat(error.failureLocation().frames()).isNotEmpty();
                });
                transport.verify();
            });
    }
    static Stream<Arguments> transportFailures() {
        var cyclic = new IOException("private-failure");
        var cause = new IllegalStateException("private-failure"); cyclic.initCause(cause); cause.initCause(cyclic);
        return Stream.of(
            Arguments.of(new SocketTimeoutException("private-failure"), true),
            Arguments.of(new HttpTimeoutException("private-failure"), true),
            Arguments.of(new IOException("private-failure", new SocketTimeoutException("private-failure")), true),
            Arguments.of(new IllegalStateException("private-failure", new HttpTimeoutException("private-failure")), true),
            Arguments.of(cyclic, false));
    }

    @RestController
    static final class RouteController {
        private final RestTemplate client;
        RouteController(RestTemplate client) { this.client = client; }
        @GetMapping({"/outside", "/api/inside"})
        void call() {
            try { client.getForObject("http://127.0.0.1:18084/timeout", String.class); }
            catch (ResourceAccessException ignored) { }
        }
    }
    @Test void registeredFilterOnlyObservesConfiguredRequestPrefix() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            transport.expect(ExpectedCount.twice(), requestTo("http://127.0.0.1:18084/timeout"))
                .andRespond(request -> { throw new SocketTimeoutException("private-error"); });
            FilterRegistrationBean<?> registration = context.getBean(FilterRegistrationBean.class);
            var mvc = standaloneSetup(new RouteController(client))
                .addFilter(registration.getFilter(), registration.getUrlPatterns().toArray(String[]::new)).build();
            var outside = mvc.perform(get("/outside")).andReturn();
            assertThat(outside.getResponse().getHeader("X-Triage-Trace-Id")).isNull();
            assertThat(window(recorder).requestCount()).isZero();
            mvc.perform(get("/api/inside"));
            assertThat(window(recorder).requestCount()).isEqualTo(1);
            assertThat(window(recorder).timeoutCount()).isEqualTo(1);
            transport.verify();
        });
    }

    @Test void multipleTimeoutsStillCountTheInboundRequestOnce() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            String url = "http://127.0.0.1:18084/twice";
            transport.expect(ExpectedCount.twice(), requestTo(url)).andRespond(request -> { throw new SocketTimeoutException("private-error"); });
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/demo"),
                new MockHttpServletResponse(), (request, response) -> {
                    for (int attempt = 0; attempt < 2; attempt++) {
                        assertThatThrownBy(() -> client.getForObject(url, String.class)).isInstanceOf(ResourceAccessException.class);
                    }
                });
            assertThat(window(recorder).requestCount()).isEqualTo(1);
            assertThat(window(recorder).recordedRequestCount()).isEqualTo(1);
            assertThat(window(recorder).timeoutCount()).isEqualTo(1);
            transport.verify();
        });
    }

}
