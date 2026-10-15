package io.github.mochiuaena.triage.sdk;

import feign.Capability;
import feign.Client;
import feign.Response;
import java.io.IOException;
import java.net.URI;

public final class TriageFeignCapability implements Capability {
    private final TriageObservationProperties properties;
    TriageFeignCapability(TriageObservationProperties properties) { this.properties = properties; }

    @Override public Client enrich(Client client) {
        return (request, options) -> {
            var context = TriageRequestFilter.CURRENT.get();
            if (context == null || !context.isActive()) return client.execute(request, options);
            URI uri;
            try { uri = URI.create(request.url()); }
            catch (IllegalArgumentException invalid) { return client.execute(request, options); }
            if (!properties.matches(uri)) return client.execute(request, options);
            long started = System.nanoTime();
            try {
                Response response = client.execute(request, options);
                return response == null || response.body() == null ? response : response.toBuilder()
                    .body(new TriageFeignBody(response.body(), context, properties)).build();
            }
            catch (IOException | RuntimeException error) {
                context.recordFailure(TriageHttpTimeouts.isTimeout(error),
                    () -> FailureLocations.capture(error, properties, "HTTP_CLIENT_FAILURE"));
                throw error;
            }
            finally { context.addDownstreamMillis(ObservationRecorder.elapsed(started)); }
        };
    }
}
