package io.github.mochiuaena.triage.sdk;

import java.io.IOException;
import java.io.FilterInputStream;
import java.io.InputStream;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;

final class TriageHttpClientInterceptor implements ClientHttpRequestInterceptor {
    private final TriageObservationProperties properties;
    TriageHttpClientInterceptor(TriageObservationProperties properties) { this.properties = properties; }
    @Override public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        var context = TriageRequestFilter.CURRENT.get();
        if (context == null || !context.isActive() || !properties.matches(request.getURI())) return execution.execute(request, body);
        long start = System.nanoTime();
        try {
            ClientHttpResponse response = execution.execute(request, body);
            return new ClientHttpResponse() {
                @Override public HttpStatusCode getStatusCode() throws IOException {
                    long statusStart = System.nanoTime();
                    try { return response.getStatusCode(); }
                    catch (IOException | RuntimeException e) { markTimeout(context, e); throw e; }
                    finally { context.addDownstreamMillis(ObservationRecorder.elapsed(statusStart)); }
                }
                @Override public String getStatusText() throws IOException {
                    long textStart = System.nanoTime();
                    try { return response.getStatusText(); }
                    catch (IOException | RuntimeException e) { markTimeout(context, e); throw e; }
                    finally { context.addDownstreamMillis(ObservationRecorder.elapsed(textStart)); }
                }
                @Override public HttpHeaders getHeaders() {
                    long headersStart = System.nanoTime();
                    try { return response.getHeaders(); }
                    catch (RuntimeException e) { markTimeout(context, e); throw e; }
                    finally { context.addDownstreamMillis(ObservationRecorder.elapsed(headersStart)); }
                }
                @Override public void close() { response.close(); }
                @Override public InputStream getBody() throws IOException {
                    InputStream input;
                    long bodyStart = System.nanoTime();
                    try { input = response.getBody(); }
                    catch (IOException | RuntimeException e) { markTimeout(context, e); throw e; }
                    finally { context.addDownstreamMillis(ObservationRecorder.elapsed(bodyStart)); }
                    return new FilterInputStream(input) {
                        @Override public int read() throws IOException {
                            long readStart = System.nanoTime();
                            try { return in.read(); }
                            catch (IOException | RuntimeException e) { markTimeout(context, e); throw e; }
                            finally { context.addDownstreamMillis(ObservationRecorder.elapsed(readStart)); }
                        }
                        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                            long readStart = System.nanoTime();
                            try { return in.read(bytes, offset, length); }
                            catch (IOException | RuntimeException e) { markTimeout(context, e); throw e; }
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
    }
    private void markTimeout(TriageRequestFilter.Context context, Throwable error) {
        context.recordFailure(TriageHttpTimeouts.isTimeout(error),
            () -> FailureLocations.capture(error, properties, "HTTP_CLIENT_FAILURE"));
    }
}
