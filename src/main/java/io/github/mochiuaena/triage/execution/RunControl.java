package io.github.mochiuaena.triage.execution;

import java.util.concurrent.*;

/** All state transitions and step registration share the execution state's monitor. */
final class RunControl {
    final MutableExecution state;
    volatile boolean cancelled;
    Future<?> coordinator;
    Step step;
    record Step(Future<?> future, ExecutorService executor) {}
    RunControl(MutableExecution state) { this.state = state; }
    void checkCancelled() { if (cancelled) throw new RunFailure("RUN_CANCELLED", "本次排查已取消。"); }
    void attach(Future<?> future, ExecutorService executor) {
        checkCancelled(); step = new Step(future, executor);
    }
    void detach(Future<?> future) {
        synchronized (state) { if (step != null && step.future() == future) step = null; }
    }
    static void stop(Future<?> future, ExecutorService executor) {
        if (future == null) return;
        future.cancel(true);
        if (executor instanceof ThreadPoolExecutor pool && future instanceof Runnable task) pool.remove(task);
    }
}
