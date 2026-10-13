package io.github.mochiuaena.triage.sdk;

import java.net.URI;
import java.net.SocketTimeoutException;
import java.net.ConnectException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.core.io.buffer.DataBuffer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class WebClientObservationTest {
    @Test void matchingBootStyleClientContributesToOwnedRequestWithoutSavingUrl() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
            .exchangeFunction(request -> Mono.fromCallable(() -> {
                Thread.sleep(2); // Keep the synthetic call above the one-decimal p95 display precision.
                return ClientResponse.create(HttpStatus.OK).body("ok").build();
            })).build();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/private-item"),
            new MockHttpServletResponse(), (request, response) -> {
                assertThat(client.get().uri("http://127.0.0.1:18084/private?token=private-query")
                    .retrieve().bodyToMono(String.class).block()).isEqualTo("ok");
            });
        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.requestCount()).isEqualTo(1);
        assertThat(window.downstreamP95Ms()).isGreaterThan(0);
        assertThat(window.toString()).doesNotContain("private-item", "private-query", "http://");
    }

    @Test void unrelatedOriginDoesNotContributeToDownstreamDuration() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK).body("ok").build())).build();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            new MockHttpServletResponse(), (request, response) -> {
                assertThat(client.get().uri("http://127.0.0.1:18085/other")
                    .retrieve().bodyToMono(String.class).block()).isEqualTo("ok");
            });
        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.downstreamP95Ms()).isZero();
    }

    @Test void responseBodyTimeoutIsRecordedAfterHeadersArrive() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .body(Flux.<DataBuffer>error(new SocketTimeoutException("private-body-error"))).build())).build();
        var response = new MockHttpServletResponse();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            response, (request, servletResponse) -> {
                assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:18084/body?token=private-query")
                    .retrieve().bodyToMono(String.class).block()).isNotNull();
                response.setStatus(504);
            });
        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.timeoutCount()).isEqualTo(1);
        assertThat(window.toString()).doesNotContain("private-body-error", "private-query", "http://");
    }

    @Test void headerTimeoutAndConnectionFailureStayDistinct() throws Exception {
        for (var failure : new Exception[]{new SocketTimeoutException("private-timeout"),
            new ConnectException("private-connection")}) {
            var properties = ObservationRecorderTest.properties();
            properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
            var recorder = new ObservationRecorder(properties);
            var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
                .exchangeFunction(request -> Mono.error(failure)).build();
            var response = new MockHttpServletResponse();
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
                response, (request, servletResponse) -> {
                    assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:18084/private?token=private-query")
                        .retrieve().bodyToMono(String.class).block()).isNotNull();
                    response.setStatus(504);
                });
            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.requestCount()).isEqualTo(1);
            assertThat(window.timeoutCount()).isEqualTo(failure instanceof SocketTimeoutException ? 1 : 0);
            assertThat(window.toString()).doesNotContain("private-timeout", "private-connection", "private-query");
        }
    }

    @Test void serverErrorDoesNotBecomeDownstreamTimeout() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE)
                .body("private-server-body").build())).build();
        var response = new MockHttpServletResponse();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            response, (request, servletResponse) -> {
                assertThatThrownBy(() -> client.get().uri("http://127.0.0.1:18084/private")
                    .retrieve().bodyToMono(String.class).block()).isNotNull();
                response.setStatus(503);
            });
        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.timeoutCount()).isZero();
        assertThat(window.downstreamP95Ms()).isGreaterThan(0);
        assertThat(window.toString()).doesNotContain("private-server-body", "http://");
    }

    @Test void cancelledExchangeDoesNotBecomeATimeoutOrRetainTheRequestThread() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
            .exchangeFunction(request -> Mono.never()).build();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
            new MockHttpServletResponse(), (request, response) -> {
                var subscription = client.get().uri("http://127.0.0.1:18084/private")
                    .retrieve().bodyToMono(String.class).subscribe();
                subscription.dispose();
            });
        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
        assertThat(window.requestCount()).isEqualTo(1);
        assertThat(window.timeoutCount()).isZero();
        assertThat(window.errors()).isEmpty();
        assertThat(TriageRequestFilter.CURRENT.get()).isNull();
    }
}
