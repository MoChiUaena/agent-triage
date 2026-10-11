package io.github.mochiuaena.triage.sdk;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps an owned HttpExchange open while timer workers write a healthy response. */
final class NormalResponseScheduler implements AutoCloseable {
    @FunctionalInterface interface Action { void run() throws IOException; }
    record State(int poolSize, int activeCount, int queuedTaskCount, long nextDelayMillis, long taskCount,
                 long completedTaskCount, boolean shutdown) {
        String line() {
            return String.format(Locale.ROOT,
                "RESOURCE_NORMAL_TIMER enabled=true poolSize=%d active=%d queued=%d nextDelayMs=%d tasks=%d completed=%d shutdown=%b",
                poolSize, activeCount, queuedTaskCount, nextDelayMillis, taskCount, completedTaskCount, shutdown);
        }
    }

    private final ScheduledThreadPoolExecutor timers = new ScheduledThreadPoolExecutor(2,
        Thread.ofPlatform().name("resource-normal-timer-", 0).factory());

    NormalResponseScheduler() { timers.setRemoveOnCancelPolicy(true); }

    void afterDelay(int millis, Action response) throws IOException {
        var completed = new CompletableFuture<Void>();
        timers.schedule(() -> {
            try { response.run(); completed.complete(null); }
            catch (Throwable failure) { completed.completeExceptionally(failure); }
        }, millis, TimeUnit.MILLISECONDS);
        try { completed.get(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IOException(failure);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException checked) throw checked;
            if (cause instanceof RuntimeException unchecked) throw unchecked;
            if (cause instanceof Error error) throw error;
            throw new IOException(cause);
        }
    }

    boolean stopped() { return timers.isTerminated() && timers.getQueue().isEmpty(); }
    State state() {
        var next = timers.getQueue().peek();
        long nextDelayMillis = next instanceof Delayed delayed ? delayed.getDelay(TimeUnit.MILLISECONDS) : 0;
        return new State(timers.getPoolSize(), timers.getActiveCount(), timers.getQueue().size(), nextDelayMillis,
            timers.getTaskCount(), timers.getCompletedTaskCount(), timers.isShutdown());
    }

    @Override public void close() { timers.shutdown(); timers.close(); }
}
