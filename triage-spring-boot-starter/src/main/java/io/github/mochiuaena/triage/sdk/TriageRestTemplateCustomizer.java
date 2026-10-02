package io.github.mochiuaena.triage.sdk;

import org.springframework.boot.web.client.RestTemplateCustomizer;
import org.springframework.web.client.RestTemplate;

final class TriageRestTemplateCustomizer implements RestTemplateCustomizer {
    private final TriageHttpClientInterceptor interceptor;
    TriageRestTemplateCustomizer(TriageObservationProperties properties) { interceptor = new TriageHttpClientInterceptor(properties); }
    @Override public void customize(RestTemplate client) {
        if (client.getInterceptors().stream().noneMatch(TriageHttpClientInterceptor.class::isInstance)) {
            client.getInterceptors().add(interceptor);
        }
    }
}
