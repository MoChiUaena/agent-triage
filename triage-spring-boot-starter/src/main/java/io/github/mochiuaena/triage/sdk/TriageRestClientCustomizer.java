package io.github.mochiuaena.triage.sdk;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import java.io.FilterInputStream;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.web.client.RestClient;

final class TriageRestClientCustomizer implements RestClientCustomizer {
    private final TriageObservationProperties properties;
    TriageRestClientCustomizer(TriageObservationProperties properties) { this.properties = properties; }
    @Override public void customize(RestClient.Builder builder) {
        builder.requestInterceptor((request, body, execution) -> {
            var context = TriageRequestFilter.CURRENT.get();
            if (context == null || !context.isActive() || !properties.matches(request.getURI())) return execution.execute(request, body);
            long start = System.nanoTime();
            try {
                ClientHttpResponse response = execution.execute(request, body);
                return new ClientHttpResponse() {
                    @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                    @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                    @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
                    @Override public void close() { response.close(); }
                    @Override public InputStream getBody() throws IOException {
                        return new FilterInputStream(response.getBody()) {
                            @Override public int read() throws IOException {
                                long readStart = System.nanoTime();
                                try { return in.read(); }
                                catch (IOException e) { markTimeout(context, e); throw e; }
                                finally { context.addDownstreamMillis(ObservationRecorder.elapsed(readStart)); }
                            }
                            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                                long readStart = System.nanoTime();
                                try { return in.read(bytes, offset, length); }
                                catch (IOException e) { markTimeout(context, e); throw e; }
                                finally { context.addDownstreamMillis(ObservationRecorder.elapsed(readStart)); }
                            }
                        };
                    }
                };
            }
            catch (IOException | RuntimeException e) {
                markTimeout(context, e);
                throw e;
            } finally { context.addDownstreamMillis(ObservationRecorder.elapsed(start)); }
        });
    }
    private void markTimeout(TriageRequestFilter.Context context, Throwable error) {
        boolean timedOut = false;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) { timedOut = true; break; }
        }
        context.recordFailure(timedOut, () -> FailureLocations.capture(error, properties, "HTTP_CLIENT_FAILURE"));
    }
}
