package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.web.filter.OncePerRequestFilter;

final class TriageRequestFilter extends OncePerRequestFilter {
    static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    static final class Context {
        final String trace = UUID.randomUUID().toString();
        double downstreamMs;
        boolean timeout;
        MvcEndpoint endpoint;
        Class<?> handlerClass;
        FailureLocations.Location failureLocation;
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
            if (recorded.compareAndSet(false, true)) recorder.recordHttp(ObservationRecorder.elapsed(start), context.downstreamMs,
                context.timeout, failed || response.getStatus() >= 500, context.trace, context.endpoint, context.failureLocation);
        }
        @Override public void onComplete(AsyncEvent event) { record(); }
        @Override public void onTimeout(AsyncEvent event) { failed = true; }
        @Override public void onError(AsyncEvent event) {
            failed = true;
            if (context.failureLocation == null && event.getThrowable() != null)
                context.failureLocation = recorder.requestFailure(event.getThrowable(), context.handlerClass);
        }
        @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
    }
    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Context context = new Context();
        CURRENT.set(context);
        response.setHeader("X-Triage-Trace-Id", context.trace);
        long start = System.nanoTime();
        boolean failed = false;
        try { chain.doFilter(request, response); }
        catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            if (context.failureLocation == null) context.failureLocation = recorder.requestFailure(e, context.handlerClass);
            throw e;
        }
        finally {
            CURRENT.remove();
            if (request.isAsyncStarted()) {
                var completion = new AsyncCompletion(context, response, start, failed);
                try { request.getAsyncContext().addListener(completion); }
                catch (IllegalStateException completed) { completion.record(); }
            } else recorder.recordHttp(ObservationRecorder.elapsed(start), context.downstreamMs,
                context.timeout, failed || response.getStatus() >= 500, context.trace, context.endpoint, context.failureLocation);
        }
    }
}
