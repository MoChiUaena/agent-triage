package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.filter.OncePerRequestFilter;

final class TriageRequestFilter extends OncePerRequestFilter {
    static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    static final String CONTEXT_ATTRIBUTE = TriageRequestFilter.class.getName() + ".context";
    static final class Context {
        final String trace = UUID.randomUUID().toString();
        private double downstreamMs;
        private boolean timeout;
        volatile MvcEndpoint endpoint;
        volatile Class<?> handlerClass;
        private FailureLocations.Location failureLocation;
        private boolean failureRecorded;
        private boolean active = true;
        record Completed(double downstreamMs, boolean timeout, MvcEndpoint endpoint, FailureLocations.Location failureLocation) {}
        synchronized boolean isActive() { return active; }
        synchronized void addDownstreamMillis(double elapsed) { if (active) downstreamMs += elapsed; }
        synchronized void recordFailure(boolean timedOut, java.util.function.Supplier<FailureLocations.Location> capture) {
            if (!active) return;
            timeout |= timedOut;
            if (!failureRecorded) {
                failureRecorded = true;
                try { failureLocation = capture.get(); }
                catch (RuntimeException | LinkageError ignored) { /* Observation cannot change the business failure. */ }
            }
        }
        synchronized void recordIfActive(Runnable observation) { if (active) observation.run(); }
        synchronized Completed finish() {
            if (!active) return null;
            active = false;
            return new Completed(downstreamMs, timeout, endpoint, failureLocation);
        }
    }
    private final ObservationRecorder recorder;
    TriageRequestFilter(ObservationRecorder recorder) { this.recorder = recorder; }
    private final class AsyncCompletion implements AsyncListener {
        private final Context context;
        private final HttpServletResponse response;
        private final long start;
        private final AtomicBoolean recorded = new AtomicBoolean();
        private volatile boolean failed;

        AsyncCompletion(Context context, HttpServletResponse response, long start, boolean failed) {
            this.context = context; this.response = response; this.start = start; this.failed = failed;
        }
        void record() {
            if (recorded.compareAndSet(false, true)) complete(context, response, start, failed);
        }
        @Override public void onComplete(AsyncEvent event) { record(); }
        @Override public void onTimeout(AsyncEvent event) { failed = true; }
        @Override public void onError(AsyncEvent event) {
            failed = true;
            if (event.getThrowable() != null) context.recordFailure(false, () -> recorder.requestFailure(event.getThrowable(), context.handlerClass));
        }
        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Context context = new Context();
        Context previous = CURRENT.get();
        CURRENT.set(context);
        request.setAttribute(CONTEXT_ATTRIBUTE, context);
        response.setHeader("X-Triage-Trace-Id", context.trace);
        long start = System.nanoTime();
        boolean failed = false;
        try { chain.doFilter(request, response); }
        catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            context.recordFailure(false, () -> recorder.requestFailure(e, context.handlerClass));
            throw e;
        }
        finally {
            if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
            if (request.isAsyncStarted()) {
                var completion = new AsyncCompletion(context, response, start, failed);
                try { request.getAsyncContext().addListener(completion); }
                catch (IllegalStateException completed) { completion.record(); }
            } else complete(context, response, start, failed);
        }
    }
    private void complete(Context context, HttpServletResponse response, long start, boolean failed) {
        var completed = context.finish();
        if (completed == null) return;
        recorder.recordHttp(ObservationRecorder.elapsed(start), completed.downstreamMs(), completed.timeout(),
            failed || response.getStatus() >= 500, context.trace, completed.endpoint(), completed.failureLocation(),
            failed && response.getStatus() < 400 ? 0 : response.getStatus());
    }
}
