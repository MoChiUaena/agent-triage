package io.github.mochiuaena.triage.sdk;

import org.springframework.boot.web.reactive.function.client.WebClientCustomizer;
import org.springframework.web.reactive.function.client.WebClient;

final class TriageWebClientCustomizer implements WebClientCustomizer {
    private final TriageWebClientFilter filter;

    TriageWebClientCustomizer(TriageObservationProperties properties) { filter = new TriageWebClientFilter(properties); }

    @Override public void customize(WebClient.Builder builder) {
        builder.defaultRequest(spec -> {
            var context = TriageRequestFilter.CURRENT.get();
            if (context != null && context.isActive()) spec.attribute(TriageWebClientFilter.CONTEXT_ATTRIBUTE, context);
        });
        builder.filters(filters -> {
            if (filters.stream().noneMatch(TriageWebClientFilter.class::isInstance)) filters.add(filter);
        });
    }
}
