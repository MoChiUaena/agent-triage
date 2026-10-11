package io.github.mochiuaena.triage.sdk;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class WebClientObservationTest {
    @Test void matchingBootStyleClientContributesToOwnedRequestWithoutSavingUrl() throws Exception {
        var properties = ObservationRecorderTest.properties();
        properties.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        var recorder = new ObservationRecorder(properties);
        var client = WebClient.builder().filter(new TriageWebClientFilter(properties))
            .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK).body("ok").build())).build();
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
}
