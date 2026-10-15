package io.github.mochiuaena.triage.sdk;

import feign.Response;
import java.io.FilterInputStream;
import java.io.FilterReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;

final class TriageFeignBody implements Response.Body {
    private final Response.Body delegate;
    private final TriageRequestFilter.Context context;
    private final TriageObservationProperties properties;
    private final String downstreamId;

    TriageFeignBody(Response.Body delegate, TriageRequestFilter.Context context, TriageObservationProperties properties, String downstreamId) {
        this.delegate = delegate; this.context = context; this.properties = properties; this.downstreamId = downstreamId;
    }

    @Override public Integer length() { return delegate.length(); }
    @Override public boolean isRepeatable() { return delegate.isRepeatable(); }

    @Override public InputStream asInputStream() throws IOException {
        long started = System.nanoTime();
        try {
            InputStream input = delegate.asInputStream();
            if (input == null) return null;
            return new FilterInputStream(input) {
                @Override public int read() throws IOException {
                    long readStarted = System.nanoTime();
                    try { return in.read(); }
                    catch (IOException | RuntimeException error) { markFailure(error); throw error; }
                    finally { recordDuration(readStarted); }
                }
                @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                    long readStarted = System.nanoTime();
                    try { return in.read(bytes, offset, length); }
                    catch (IOException | RuntimeException error) { markFailure(error); throw error; }
                    finally { recordDuration(readStarted); }
                }
            };
        } catch (IOException | RuntimeException error) { markFailure(error); throw error; }
        finally { recordDuration(started); }
    }

    @Override public Reader asReader(Charset charset) throws IOException {
        long started = System.nanoTime();
        try {
            Reader reader = delegate.asReader(charset);
            if (reader == null) return null;
            return new FilterReader(reader) {
                @Override public int read() throws IOException {
                    long readStarted = System.nanoTime();
                    try { return in.read(); }
                    catch (IOException | RuntimeException error) { markFailure(error); throw error; }
                    finally { recordDuration(readStarted); }
                }
                @Override public int read(char[] chars, int offset, int length) throws IOException {
                    long readStarted = System.nanoTime();
                    try { return in.read(chars, offset, length); }
                    catch (IOException | RuntimeException error) { markFailure(error); throw error; }
                    finally { recordDuration(readStarted); }
                }
            };
        } catch (IOException | RuntimeException error) { markFailure(error); throw error; }
        finally { recordDuration(started); }
    }

    @Override public void close() throws IOException { delegate.close(); }

    private void recordDuration(long started) { context.addDownstreamMillis(downstreamId, ObservationRecorder.elapsed(started)); }
    private void markFailure(Throwable error) {
        context.recordFailure(downstreamId, TriageHttpTimeouts.isTimeout(error),
            () -> FailureLocations.capture(error, properties, "HTTP_CLIENT_FAILURE"));
    }
}
