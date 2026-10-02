package io.github.mochiuaena.triage.sdk;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.RestClient;

final class TriageRestClientCustomizer implements RestClientCustomizer {
    private final TriageHttpClientInterceptor interceptor;
    TriageRestClientCustomizer(TriageObservationProperties properties) { interceptor = new TriageHttpClientInterceptor(properties); }
    @Override public void customize(RestClient.Builder builder) { builder.requestInterceptor(interceptor); }
}
