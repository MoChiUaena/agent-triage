package io.github.mochiuaena.triage.sdk;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Keeps an owned HttpExchange open while timer workers write a healthy response. */
final class NormalResponseScheduler implements AutoCloseable {
    @FunctionalInterface interface Action { void run() throws IOException; }

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

    @Override public void close() { timers.shutdown(); timers.close(); }
}
