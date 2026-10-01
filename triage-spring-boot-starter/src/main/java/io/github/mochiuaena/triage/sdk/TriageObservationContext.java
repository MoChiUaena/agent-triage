package io.github.mochiuaena.triage.sdk;

import java.util.Objects;
import java.util.concurrent.Callable;

/** Explicit propagation of an active request's observation context; no request inputs are copied. */
public final class TriageObservationContext {
    private TriageObservationContext() {}

    public static Snapshot capture() {
        var context = TriageRequestFilter.CURRENT.get();
        return new Snapshot(context != null && context.isActive() ? context : null);
    }

    public static final class Snapshot {
        private final TriageRequestFilter.Context context;
        private Snapshot(TriageRequestFilter.Context context) { this.context = context; }

        public Runnable wrap(Runnable task) {
            Objects.requireNonNull(task, "task");
            return () -> { try (var scope = open()) { task.run(); } };
        }
        public <T> Callable<T> wrap(Callable<T> task) {
            Objects.requireNonNull(task, "task");
            return () -> { try (var scope = open()) { return task.call(); } };
        }
        Scope open() { return new Scope(context); }
    }

    static final class Scope implements AutoCloseable {
        private final TriageRequestFilter.Context previous;
        private boolean closed;
        Scope(TriageRequestFilter.Context context) {
            previous = TriageRequestFilter.CURRENT.get();
            if (context == null || !context.isActive()) TriageRequestFilter.CURRENT.remove();
            else TriageRequestFilter.CURRENT.set(context);
        }
        @Override public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) TriageRequestFilter.CURRENT.remove(); else TriageRequestFilter.CURRENT.set(previous);
        }
    }
}
