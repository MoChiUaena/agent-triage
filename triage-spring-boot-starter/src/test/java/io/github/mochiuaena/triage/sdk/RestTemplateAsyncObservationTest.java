package io.github.mochiuaena.triage.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;

class RestTemplateAsyncObservationTest {
    private final WebApplicationContextRunner runner = new WebApplicationContextRunner().withConfiguration(
        AutoConfigurations.of(RestTemplateAutoConfiguration.class, TriageObservationAutoConfiguration.class))
        .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service", "triage.sdk.downstream-id=inventory-service",
            "triage.sdk.downstream-base-url=http://127.0.0.1:18084");

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void workerRequiresWrappingAndRestoresItsContextAfterException(boolean wrapped) {
        runner.run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            String url = "http://127.0.0.1:18084/private-item?token=private-query";
            transport.expect(requestTo(url)).andRespond(call -> { throw new SocketTimeoutException("private-worker-error"); });
            var request = new MockHttpServletRequest("GET", "/api/async"); request.setAsyncSupported(true);
            var response = new MockHttpServletResponse();
            var workerContext = new TriageRequestFilter.Context();
            try (var workers = Executors.newSingleThreadExecutor()) {
                workers.submit(() -> TriageRequestFilter.CURRENT.set(workerContext)).get(5, TimeUnit.SECONDS);
                var task = new AtomicReference<Future<?>>();
                new TriageRequestFilter(recorder).doFilter(request, response, (req, res) -> {
                    req.startAsync(req, res);
                    Runnable work = () -> client.getForObject(url, String.class);
                    task.set(workers.submit(wrapped ? TriageObservationContext.capture().wrap(work) : work));
                });
                assertThatThrownBy(() -> task.get().get(5, TimeUnit.SECONDS)).isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ResourceAccessException.class);
                response.setStatus(504); request.getAsyncContext().complete(); request.getAsyncContext().complete();
                var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
                assertThat(window.requestCount()).isEqualTo(1);
                assertThat(window.timeoutCount()).isEqualTo(wrapped ? 1 : 0);
                assertThat(window.toString()).doesNotContain("private-item", "private-query", "private-worker-error");
                assertThat(workers.submit(() -> TriageRequestFilter.CURRENT.get()).get(5, TimeUnit.SECONDS)).isSameAs(workerContext);
                assertThat(TriageRequestFilter.CURRENT.get()).isNull();
            }
            transport.verify();
        });
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void lateExecutionAndBodyTimeoutsCannotChangeCompletedWindows(boolean body) {
        runner.run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            var client = context.getBean(RestTemplateBuilder.class).build();
            var transport = MockRestServiceServer.bindTo(client).build();
            var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
            String url = "http://127.0.0.1:18084/private-late";
            transport.expect(requestTo(url)).andRespond(call -> {
                if (!body) failAfterRelease(entered, release);
                return new MockClientHttpResponse(new byte[0], HttpStatus.OK) {
                    @Override public InputStream getBody() {
                        return new InputStream() {
                            @Override public int read() throws IOException { failAfterRelease(entered, release); return -1; }
                        };
                    }
                };
            });
            var request = new MockHttpServletRequest("GET", "/api/late"); request.setAsyncSupported(true);
            var response = new MockHttpServletResponse();
            try (var workers = Executors.newSingleThreadExecutor()) {
                var task = new AtomicReference<Future<?>>();
                new TriageRequestFilter(recorder).doFilter(request, response, (req, res) -> {
                    req.startAsync(req, res);
                    task.set(workers.submit(TriageObservationContext.capture().wrap((Runnable) () -> client.getForObject(url, String.class))));
                });
                assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
                request.getAsyncContext().complete();
                var completed = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
                release.countDown();
                assertThatThrownBy(() -> task.get().get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(ResourceAccessException.class);
                var after = (ObservationRecorder.HttpWindow) recorder.snapshot(5, completed.windowEnd());
                assertThat(after).isEqualTo(completed);
                assertThat(after.timeoutCount()).isZero(); assertThat(after.errors()).isEmpty();
                assertThat(workers.submit(() -> TriageRequestFilter.CURRENT.get()).get(5, TimeUnit.SECONDS)).isNull();
            } finally { release.countDown(); }
            transport.verify();
        });
    }
    private static void failAfterRelease(CountDownLatch entered, CountDownLatch release) throws IOException {
        entered.countDown();
        try { if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("Test release timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new IOException(failure); }
        throw new SocketTimeoutException("private-late-error");
    }
}
