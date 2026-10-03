package io.github.mochiuaena.triage.sdk;

import java.time.Duration;
import java.util.concurrent.*;

final class BoundedResourceAdmission implements RejectedExecutionHandler {
    private final long limitNanos;
    BoundedResourceAdmission(Duration limit) { limitNanos = limit.toNanos(); }
    @Override public void rejectedExecution(Runnable task, ThreadPoolExecutor executor) {
        try {
            if (executor.isShutdown() || !executor.getQueue().offer(task, limitNanos, TimeUnit.NANOSECONDS))
                throw new RejectedExecutionException("Resource driver admission deadline exceeded or executor stopped");
            // shutdownNow may have drained this request before the post-offer check.
            if (executor.isShutdown()) {
                executor.remove(task);
                throw new RejectedExecutionException("Resource driver stopped during admission");
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            throw new RejectedExecutionException("Resource driver admission interrupted", interruption);
        }
    }
}
