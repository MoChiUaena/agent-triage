package io.github.mochiuaena.triage.sdk;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class BoundedResourceAdmissionTest {
    @Test void waitsForCapacityWithoutAddingWorkersOrDroppingTheRequest() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var executor = executor(Duration.ofSeconds(2));
        var caller = Executors.newSingleThreadExecutor();
        try {
            executor.execute(() -> { entered.countDown(); await(release); completed.incrementAndGet(); });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(completed::incrementAndGet);
            var submitting = caller.submit(() -> executor.execute(completed::incrementAndGet));
            assertThatThrownBy(() -> submitting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            assertThat(executor.getPoolSize()).isEqualTo(1);
            assertThat(executor.getQueue()).hasSize(1);
            release.countDown();
            submitting.get(2, TimeUnit.SECONDS);
            executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(completed).hasValue(3);
            assertThat(executor.getQueue()).isEmpty();
        } finally { release.countDown(); caller.shutdownNow(); caller.close(); executor.shutdownNow(); executor.close(); }
    }

    @Test void fullQueueStillRejectsWhenTheAdmissionDeadlineExpires() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var completed = new AtomicInteger();
        var executor = executor(Duration.ofMillis(30));
        try {
            executor.execute(() -> { entered.countDown(); await(release); completed.incrementAndGet(); });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(completed::incrementAndGet);
            assertTimeoutPreemptively(Duration.ofSeconds(1), () ->
                assertThatThrownBy(() -> executor.execute(completed::incrementAndGet)).isInstanceOf(RejectedExecutionException.class));
            release.countDown(); executor.shutdown();
            assertThat(executor.awaitTermination(2, TimeUnit.SECONDS)).isTrue();
            assertThat(completed).hasValue(2);
        } finally { release.countDown(); executor.shutdownNow(); executor.close(); }
    }

    @Test void shutdownDuringAdmissionDoesNotStrandTheRequest() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = executor(Duration.ofSeconds(2));
        var caller = Executors.newSingleThreadExecutor();
        try {
            executor.execute(() -> { entered.countDown(); await(release); });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });
            var submitting = caller.submit(() -> executor.execute(() -> { }));
            assertThatThrownBy(() -> submitting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            executor.shutdownNow();
            assertThatThrownBy(() -> submitting.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
            assertThat(executor.getQueue()).isEmpty();
        } finally { release.countDown(); caller.shutdownNow(); caller.close(); executor.shutdownNow(); executor.close(); }
    }

    @Test void interruptedAdmissionRestoresTheCallerInterrupt() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var executor = executor(Duration.ofSeconds(2));
        var caller = Executors.newSingleThreadExecutor();
        try {
            executor.execute(() -> { entered.countDown(); await(release); });
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { });
            var interrupted = caller.submit(() -> {
                Thread.currentThread().interrupt();
                assertThatThrownBy(() -> executor.execute(() -> { })).isInstanceOf(RejectedExecutionException.class);
                return Thread.currentThread().isInterrupted();
            });
            assertThat(interrupted.get(2, TimeUnit.SECONDS)).isTrue();
        } finally { release.countDown(); caller.shutdownNow(); caller.close(); executor.shutdownNow(); executor.close(); }
    }

    @Test void shutdownDrainingAnAlreadyOfferedRequestStillRejectsAdmission() throws Exception {
        var firstEntered = new CountDownLatch(1);
        var releaseFirst = new CountDownLatch(1);
        var secondEntered = new CountDownLatch(1);
        var releaseSecond = new CountDownLatch(1);
        var offered = new CountDownLatch(1);
        var returnFromOffer = new CountDownLatch(1);
        var ran = new AtomicInteger();
        var queue = new ArrayBlockingQueue<Runnable>(1) {
            @Override public boolean offer(Runnable task, long timeout, TimeUnit unit) throws InterruptedException {
                boolean accepted = super.offer(task, timeout, unit);
                if (accepted) { offered.countDown(); returnFromOffer.await(); }
                return accepted;
            }
        };
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue,
            new BoundedResourceAdmission(Duration.ofSeconds(2)));
        var caller = Executors.newSingleThreadExecutor();
        try {
            executor.execute(() -> { firstEntered.countDown(); await(releaseFirst); });
            assertThat(firstEntered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.execute(() -> { secondEntered.countDown(); await(releaseSecond); });
            var submitting = caller.submit(() -> executor.execute(ran::incrementAndGet));
            assertThatThrownBy(() -> submitting.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            releaseFirst.countDown();
            assertThat(secondEntered.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(offered.await(1, TimeUnit.SECONDS)).isTrue();
            executor.shutdownNow();
            returnFromOffer.countDown();
            assertThatThrownBy(() -> submitting.get(2, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(RejectedExecutionException.class);
            assertThat(ran).hasValue(0);
            assertThat(executor.getQueue()).isEmpty();
        } finally {
            releaseFirst.countDown(); releaseSecond.countDown(); returnFromOffer.countDown();
            caller.shutdownNow(); caller.close(); executor.shutdownNow(); executor.close();
        }
    }

    static ThreadPoolExecutor executor(Duration limit) {
        return new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(1),
            new BoundedResourceAdmission(limit));
    }
    static void await(CountDownLatch latch) {
        try { latch.await(); } catch (InterruptedException interruption) { Thread.currentThread().interrupt(); }
    }
}
