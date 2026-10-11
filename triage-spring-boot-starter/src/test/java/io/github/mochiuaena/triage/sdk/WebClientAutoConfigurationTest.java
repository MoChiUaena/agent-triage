package io.github.mochiuaena.triage.sdk;

import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.reactive.function.client.WebClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import static org.assertj.core.api.Assertions.*;

class WebClientAutoConfigurationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(WebClientAutoConfiguration.class, TriageObservationAutoConfiguration.class,
            TriageWebClientAutoConfiguration.class));

    @Test void bootBuilderRetainsRequestContextAcrossUnwrappedSubscriptionThread() {
        runner.withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
            "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=http://127.0.0.1:18084")
            .run(context -> {
                var recorder = context.getBean(ObservationRecorder.class);
                var client = context.getBean(WebClient.Builder.class)
                    .exchangeFunction(request -> Mono.just(ClientResponse.create(HttpStatus.OK).body("ok").build())).build();
                var request = new MockHttpServletRequest("GET", "/api/products/item");
                request.setAsyncSupported(true);
                var response = new MockHttpServletResponse();
                var pending = new AtomicReference<Mono<String>>();
                new TriageRequestFilter(recorder).doFilter(request, response, (req, res) -> {
                    req.startAsync(req, res);
                    pending.set(client.get().uri("http://127.0.0.1:18084/private?token=private-query")
                        .retrieve().bodyToMono(String.class));
                });
                try (var workers = Executors.newSingleThreadExecutor()) {
                    assertThat(workers.submit(() -> TriageRequestFilter.CURRENT.get()).get(5, TimeUnit.SECONDS)).isNull();
                    assertThat(workers.submit(() -> pending.get().block()).get(5, TimeUnit.SECONDS)).isEqualTo("ok");
                }
                request.getAsyncContext().complete();
                var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
                assertThat(window.requestCount()).isEqualTo(1);
                assertThat(window.downstreamP95Ms()).isGreaterThan(0);
                assertThat(window.toString()).doesNotContain("private-query", "http://");
            });
    }

    @Test void subscriptionAfterServletCompletionCannotChangeTheFrozenWindow() {
        runner.withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
            "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=http://127.0.0.1:18084")
            .run(context -> {
                var recorder = context.getBean(ObservationRecorder.class);
                var client = context.getBean(WebClient.Builder.class)
                    .exchangeFunction(request -> Mono.error(new java.net.SocketTimeoutException("private-late-error"))).build();
                var pending = new AtomicReference<Mono<String>>();
                new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
                    new MockHttpServletResponse(), (request, response) -> pending.set(client.get()
                        .uri("http://127.0.0.1:18084/private").retrieve().bodyToMono(String.class)));
                var completed = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
                assertThatThrownBy(() -> pending.get().block()).isNotNull();
                var after = (ObservationRecorder.HttpWindow) recorder.snapshot(5, completed.windowEnd());
                assertThat(after).isEqualTo(completed);
                assertThat(after.timeoutCount()).isZero();
            });
    }

    @Test void optionalWebClientDependencyCanBeAbsentAndSdkStillStarts() {
        runner.withClassLoader(new FilteredClassLoader(WebClient.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=http://127.0.0.1:18084")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TriageWebClientCustomizer.class));
    }

    @Test void disabledSdkAndInboundOnlyModeDoNotCustomizeWebClient() {
        runner.run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TriageWebClientCustomizer.class));
        runner.withPropertyValues("triage.sdk.enabled=true", "triage.sdk.kind=HTTP_REQUESTS",
            "triage.sdk.service-id=catalog-service")
            .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(TriageWebClientCustomizer.class));
    }
}
