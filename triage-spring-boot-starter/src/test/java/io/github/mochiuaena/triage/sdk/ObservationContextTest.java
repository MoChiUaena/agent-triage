package io.github.mochiuaena.triage.sdk;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.assertj.core.api.Assertions.*;

class ObservationContextTest {
    @AfterEach void clearThread() { TriageRequestFilter.CURRENT.remove(); }

    @Test void callableUsesCapturedContextAndRestoresWorkerContextAfterFailure() throws Exception {
        var captured = new TriageRequestFilter.Context();
        TriageRequestFilter.CURRENT.set(captured);
        var snapshot = TriageObservationContext.capture();
        TriageRequestFilter.CURRENT.remove();
        try (var workers = Executors.newSingleThreadExecutor()) {
            workers.submit(() -> {
                var previous = new TriageRequestFilter.Context(); TriageRequestFilter.CURRENT.set(previous);
                var failure = new IllegalStateException("private-task-error");
                Callable<String> task = snapshot.wrap(() -> {
                    assertThat(TriageRequestFilter.CURRENT.get()).isSameAs(captured);
                    throw failure;
                });
                try {
                    assertThatThrownBy(task::call).isSameAs(failure);
                    assertThat(TriageRequestFilter.CURRENT.get()).isSameAs(previous);
                } finally { TriageRequestFilter.CURRENT.remove(); }
            }).get(5, TimeUnit.SECONDS);
        }
    }

    @Test void emptySnapshotDoesNotBorrowAnUnrelatedWorkerRequest() throws Exception {
        var snapshot = TriageObservationContext.capture();
        var previous = new TriageRequestFilter.Context(); TriageRequestFilter.CURRENT.set(previous);
        Callable<Boolean> task = snapshot.wrap(() -> TriageRequestFilter.CURRENT.get() == null);
        assertThat(task.call()).isTrue();
        assertThat(TriageRequestFilter.CURRENT.get()).isSameAs(previous);
    }

    @Test void taskStillExecutesAfterRequestCompletionWithoutRequestAttribution() throws Exception {
        var recorder = new ObservationRecorder(ObservationRecorderTest.properties());
        var snapshot = new java.util.concurrent.atomic.AtomicReference<TriageObservationContext.Snapshot>();
        new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/context"), new MockHttpServletResponse(),
            (request, response) -> snapshot.set(TriageObservationContext.capture()));
        var executed = new AtomicBoolean();
        snapshot.get().wrap((Runnable) () -> {
            executed.set(true); assertThat(TriageRequestFilter.CURRENT.get()).isNull();
        }).run();
        assertThat(executed).isTrue();
        assertThat(((ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now())).requestCount()).isEqualTo(1);
    }

    @Test void concurrentSignalsFreezeOnceAndKeepTheFirstFailure() throws Exception {
        var context = new TriageRequestFilter.Context();
        var first = new FailureLocations.Location("HTTP_CLIENT_FAILURE", List.of("java.net.SocketTimeoutException"), List.of(), false);
        var later = new FailureLocations.Location("REQUEST_EXCEPTION", List.of("java.lang.IllegalStateException"), List.of(), false);
        context.recordFailure(true, () -> first);
        try (var workers = Executors.newFixedThreadPool(8)) {
            var ready = new CountDownLatch(8); var start = new CountDownLatch(1);
            var tasks = new java.util.ArrayList<Future<?>>();
            for (int thread = 0; thread < 8; thread++) tasks.add(workers.submit(() -> {
                ready.countDown(); start.await();
                for (int item = 0; item < 2000; item++) context.addDownstreamMillis(1);
                context.recordFailure(false, () -> later);
                return null;
            }));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); start.countDown();
            for (var task : tasks) task.get(5, TimeUnit.SECONDS);
        }
        var completed = context.finish();
        assertThat(completed.downstreamMs()).isEqualTo(16000);
        assertThat(completed.timeout()).isTrue();
        assertThat(completed.failureLocation()).isSameAs(first);
        context.addDownstreamMillis(500);
        context.recordFailure(false, () -> later);
        assertThat(context.finish()).isNull();
        assertThat(completed.downstreamMs()).isEqualTo(16000);
    }
    @Test void explicitlyWrappedWorkerTimeoutBelongsToTheAsyncRequest() throws Exception {
        var properties = ObservationRecorderTest.properties(); var recorder = new ObservationRecorder(properties);
        var builder = RestClient.builder(); new TriageRestClientCustomizer(properties).customize(builder);
        var transport = MockRestServiceServer.bindTo(builder).build();
        String url = properties.getDownstreamBaseUrl() + "/private-item?token=private-query";
        transport.expect(requestTo(url)).andRespond(request -> { throw new java.net.SocketTimeoutException("private-timeout"); });
        var client = builder.build();
        var request = new MockHttpServletRequest("GET", "/api/async"); request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var task = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
            new TriageRequestFilter(recorder).doFilter(request, response, (req, res) -> {
                req.startAsync(req, res);
                task.set(workers.submit(TriageObservationContext.capture().wrap((Runnable) () -> {
                    try { client.get().uri(url).retrieve().body(String.class); }
                    catch (ResourceAccessException expected) { response.setStatus(504); }
                })));
            });
            task.get().get(5, TimeUnit.SECONDS);
            request.getAsyncContext().complete();
            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.requestCount()).isEqualTo(1); assertThat(window.timeoutCount()).isEqualTo(1);
            assertThat(window.errors().getFirst().traceId()).isEqualTo(response.getHeader("X-Triage-Trace-Id"));
            assertThat(window.toString()).doesNotContain("private-item", "private-query", "private-timeout");
            assertThat(workers.submit(() -> TriageRequestFilter.CURRENT.get() == null).get(5, TimeUnit.SECONDS)).isTrue();
        }
        transport.verify();
    }
    @Test void alreadyRunningWorkerCannotChangeACompletedRequestWhenItsCallReturnsLate() throws Exception {
        var properties = ObservationRecorderTest.properties(); var recorder = new ObservationRecorder(properties);
        var builder = RestClient.builder(); new TriageRestClientCustomizer(properties).customize(builder);
        var transport = MockRestServiceServer.bindTo(builder).build();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        String url = properties.getDownstreamBaseUrl() + "/late";
        transport.expect(requestTo(url)).andRespond(call -> {
            entered.countDown();
            try { if (!release.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("Test release timed out"); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
            throw new java.net.SocketTimeoutException("private-late-error");
        });
        var client = builder.build();
        var request = new MockHttpServletRequest("GET", "/api/late"); request.setAsyncSupported(true);
        var response = new MockHttpServletResponse();
        try (var workers = Executors.newSingleThreadExecutor()) {
            var task = new java.util.concurrent.atomic.AtomicReference<Future<?>>();
            new TriageRequestFilter(recorder).doFilter(request, response, (req, res) -> {
                req.startAsync(req, res);
                task.set(workers.submit(TriageObservationContext.capture().wrap((Runnable) () -> {
                    try { client.get().uri(url).retrieve().body(String.class); }
                    catch (ResourceAccessException expected) { }
                })));
            });
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            request.getAsyncContext().complete();
            var completed = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            release.countDown(); task.get().get(5, TimeUnit.SECONDS);
            var after = (ObservationRecorder.HttpWindow) recorder.snapshot(5, completed.windowEnd());
            assertThat(after).isEqualTo(completed);
            assertThat(after.timeoutCount()).isZero(); assertThat(after.downstreamP95Ms()).isZero(); assertThat(after.errors()).isEmpty();
        } finally { release.countDown(); }
        transport.verify();
    }
}
