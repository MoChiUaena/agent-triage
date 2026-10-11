package io.github.mochiuaena.triage.sdk;

import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import reactor.core.publisher.Mono;

final class TriageWebClientFilter implements ExchangeFilterFunction {
    private final TriageObservationProperties properties;

    TriageWebClientFilter(TriageObservationProperties properties) { this.properties = properties; }

    @Override public Mono<ClientResponse> filter(ClientRequest request, ExchangeFunction next) {
        return Mono.defer(() -> {
            var context = TriageRequestFilter.CURRENT.get();
            if (context == null || !context.isActive() || !properties.matches(request.url())) return next.exchange(request);
            long started = System.nanoTime();
            return next.exchange(request)
                .doFinally(signal -> context.addDownstreamMillis(ObservationRecorder.elapsed(started)));
        });
    }
}
