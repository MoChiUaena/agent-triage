package io.github.mochiuaena.triage.sdk;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

final class TriageWebClientFilter implements ExchangeFilterFunction {
    static final String CONTEXT_ATTRIBUTE = TriageWebClientFilter.class.getName() + ".context";
    private final TriageObservationProperties properties;

    TriageWebClientFilter(TriageObservationProperties properties) { this.properties = properties; }

    @Override public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> {
            var attached = request.attribute(CONTEXT_ATTRIBUTE).orElse(null);
            var context = attached instanceof TriageRequestFilter.Context captured ? captured : TriageRequestFilter.CURRENT.get();
            if (context == null || !context.isActive() || !properties.matches(request.url())) return next.exchange(request);
            long started = System.nanoTime();
            var recorded = new AtomicBoolean();
            return next.exchange(request)
                .map(response -> response.mutate().body(body -> Flux.defer(() -> {
                    long bodyStarted = System.nanoTime();
                    var bodyRecorded = new AtomicBoolean();
                    return body.doOnComplete(() -> recordDuration(context, bodyStarted, bodyRecorded))
                        .doOnError(error -> {
                            markFailure(context, error);
                            recordDuration(context, bodyStarted, bodyRecorded);
                        })
                        .doOnCancel(() -> recordDuration(context, bodyStarted, bodyRecorded));
                })).build())
                .doOnSuccess(response -> recordDuration(context, started, recorded))
                .doOnError(error -> {
                    markFailure(context, error);
                    recordDuration(context, started, recorded);
                })
                .doOnCancel(() -> recordDuration(context, started, recorded));
        });
    }

    private static void recordDuration(TriageRequestFilter.Context context, long started, AtomicBoolean recorded) {
        if (recorded.compareAndSet(false, true)) context.addDownstreamMillis(ObservationRecorder.elapsed(started));
    }

    private void markFailure(TriageRequestFilter.Context context, Throwable error) {
        context.recordFailure(TriageHttpTimeouts.isTimeout(error),
            () -> FailureLocations.capture(error, properties, "HTTP_CLIENT_FAILURE"));
    }
}
