package io.github.mochiuaena.triage.sdk;

import java.util.concurrent.Callable;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;

/** One interceptor per MVC request; worker state is restored by Spring's postProcess callback. */
final class TriageCallableContext implements CallableProcessingInterceptor {
    private final TriageObservationContext.Snapshot snapshot;
    private final TriageObservationProperties properties;
    private final ThreadLocal<TriageObservationContext.Scope> worker = new ThreadLocal<>();

    TriageCallableContext(TriageObservationContext.Snapshot snapshot, TriageObservationProperties properties) {
        this.snapshot = snapshot; this.properties = properties;
    }
    @Override public <T> void preProcess(NativeWebRequest request, Callable<T> task) { worker.set(snapshot.open()); }
    @Override public <T> void postProcess(NativeWebRequest request, Callable<T> task, Object result) {
        try {
            var context = TriageRequestFilter.CURRENT.get();
            if (context != null && result instanceof Throwable error)
                context.recordFailure(false, () -> FailureLocations.capture(error, properties, "REQUEST_EXCEPTION", context.handlerClass));
        } finally {
            var scope = worker.get(); worker.remove();
            if (scope != null) scope.close();
        }
    }
}
