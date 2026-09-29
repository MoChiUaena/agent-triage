package io.github.mochiuaena.triage.sdk;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.UUID;
import org.springframework.web.filter.OncePerRequestFilter;

final class TriageRequestFilter extends OncePerRequestFilter {
    static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();
    static final class Context {
        final String trace = UUID.randomUUID().toString();
        double downstreamMs;
        boolean timeout;
        MvcEndpoint endpoint;
        FailureLocations.Location failureLocation;
    }
    private final ObservationRecorder recorder;
    TriageRequestFilter(ObservationRecorder recorder) { this.recorder = recorder; }
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
            if (context.failureLocation == null) context.failureLocation = recorder.requestFailure(e);
            throw e;
        }
        finally {
            CURRENT.remove();
            if (!request.isAsyncStarted()) recorder.recordHttp(ObservationRecorder.elapsed(start), context.downstreamMs,
                context.timeout, failed || response.getStatus() >= 500, context.trace, context.endpoint, context.failureLocation);
        }
    }
}
