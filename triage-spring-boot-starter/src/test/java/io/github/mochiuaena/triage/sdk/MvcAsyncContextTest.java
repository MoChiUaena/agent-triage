package io.github.mochiuaena.triage.sdk;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.*;
import org.springframework.http.*;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.scheduling.concurrent.ConcurrentTaskExecutor;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.context.request.async.WebAsyncTask;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MvcAsyncContextTest {
    @RestController static final class Controller {
        final RestClient client; final Executor executor;
        Controller(RestClient client, Executor executor) { this.client = client; this.executor = executor; }
        ResponseEntity<Void> lookup() {
            try { client.get().uri("http://127.0.0.1:18084/timeout").retrieve().toBodilessEntity(); return ResponseEntity.noContent().build(); }
            catch (ResourceAccessException expected) { return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build(); }
        }
        @GetMapping("/api/callable") Callable<ResponseEntity<Void>> callable() { return this::lookup; }
        @GetMapping("/api/web-task") WebAsyncTask<ResponseEntity<Void>> webTask() {
            return new WebAsyncTask<>(3000L, new ConcurrentTaskExecutor(executor), this::lookup);
        }
        @GetMapping("/api/deferred") DeferredResult<ResponseEntity<Void>> deferred() {
            var result = new DeferredResult<ResponseEntity<Void>>();
            executor.execute(TriageObservationContext.capture().wrap(() -> { result.setResult(lookup()); }));
            return result;
        }
        @GetMapping("/api/callable-error") Callable<ResponseEntity<Void>> failure() {
            return () -> { throw new IllegalStateException("private-worker-error"); };
        }
    }
    @RestControllerAdvice static final class Advice {
        @ExceptionHandler(IllegalStateException.class) ResponseEntity<Void> failed() { return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build(); }
    }
    static final class Fixture implements AutoCloseable {
        final ExecutorService workers = Executors.newSingleThreadExecutor();
        final ObservationRecorder recorder;
        final MockRestServiceServer transport;
        final MockMvc mvc;
        Fixture(boolean automatic) {
            var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true); properties.setResponseStatusCounts(true);
            properties.setExceptionLocations(true); properties.setApplicationPackages(List.of("io.github.mochiuaena.triage.sdk"));
            Binder.get(new MockEnvironment().withProperty("triage.sdk.async-context-propagation", Boolean.toString(automatic)))
                .bind("triage.sdk", Bindable.ofInstance(properties));
            recorder = new ObservationRecorder(properties);
            var builder = RestClient.builder(); new TriageRestClientCustomizer(properties).customize(builder);
            transport = MockRestServiceServer.bindTo(builder).build();
            mvc = MockMvcBuilders.standaloneSetup(new Controller(builder.build(), workers)).setControllerAdvice(new Advice())
                .addInterceptors(new TriageMvcEndpoints(properties))
                .addFilters(new TriageRequestFilter(recorder)).build();
            mvc.getDispatcherServlet().getWebApplicationContext().getBean(org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter.class)
                .setTaskExecutor(new ConcurrentTaskExecutor(workers));
        }
        void expectTimeout() {
            transport.expect(requestTo("http://127.0.0.1:18084/timeout"))
                .andRespond(request -> { throw new java.net.SocketTimeoutException("private-downstream-error"); });
        }
        ObservationRecorder.EndpointWindow window() { return (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null); }
        boolean workerCleared() throws Exception { return workers.submit(() -> TriageRequestFilter.CURRENT.get() == null).get(5, TimeUnit.SECONDS); }
        @Override public void close() { workers.close(); }
    }
    @Test void optedInMvcCallableAttributesTimeoutAndRestoresTheExecutorThread() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.expectTimeout();
            var pending = fixture.mvc.perform(get("/api/callable")).andExpect(request().asyncStarted()).andReturn();
            fixture.mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout());
            var window = fixture.window();
            assertThat(window.requestCount()).isEqualTo(1); assertThat(window.timeoutCount()).isEqualTo(1);
            assertThat(window.responseStatuses().serverError()).isEqualTo(1);
            assertThat(window.errors().getFirst().failureLocation().kind()).isEqualTo("HTTP_CLIENT_FAILURE");
            assertThat(fixture.workerCleared()).isTrue(); fixture.transport.verify();
        }
    }
    @Test void defaultCallableDoesNotPropagateButExplicitDeferredTaskDoes() throws Exception {
        try (var fixture = new Fixture(false)) {
            fixture.expectTimeout();
            var pending = fixture.mvc.perform(get("/api/callable")).andExpect(request().asyncStarted()).andReturn();
            fixture.mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout());
            assertThat(fixture.window().timeoutCount()).isZero(); fixture.transport.verify();
        }
        try (var fixture = new Fixture(false)) {
            fixture.expectTimeout();
            var pending = fixture.mvc.perform(get("/api/deferred")).andExpect(request().asyncStarted()).andReturn();
            fixture.mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout());
            var window = fixture.window();
            assertThat(window.timeoutCount()).isEqualTo(1); assertThat(window.requestCount()).isEqualTo(1);
            assertThat(window.endpoints().getFirst().endpoint().routeTemplate()).isEqualTo("/api/deferred");
            assertThat(fixture.workerCleared()).isTrue(); fixture.transport.verify();
        }
    }
    @Test void optedInWebAsyncTaskAlsoPropagatesWithItsOwnExecutor() throws Exception {
        try (var fixture = new Fixture(true)) {
            fixture.expectTimeout();
            var pending = fixture.mvc.perform(get("/api/web-task")).andExpect(request().asyncStarted()).andReturn();
            fixture.mvc.perform(asyncDispatch(pending)).andExpect(status().isGatewayTimeout());
            assertThat(fixture.window().timeoutCount()).isEqualTo(1);
            assertThat(fixture.window().requestCount()).isEqualTo(1);
            assertThat(fixture.workerCleared()).isTrue(); fixture.transport.verify();
        }
    }
    @Test void callableBusinessExceptionKeepsApplicationHandlingAndClearsTheWorker() throws Exception {
        try (var fixture = new Fixture(true)) {
            var pending = fixture.mvc.perform(get("/api/callable-error")).andExpect(request().asyncStarted()).andReturn();
            fixture.mvc.perform(asyncDispatch(pending)).andExpect(status().isServiceUnavailable());
            var window = fixture.window();
            assertThat(window.requestCount()).isEqualTo(1); assertThat(window.timeoutCount()).isZero();
            assertThat(window.errors().getFirst().failureLocation().kind()).isEqualTo("REQUEST_EXCEPTION");
            assertThat(window.toString()).doesNotContain("private-worker-error");
            assertThat(fixture.workerCleared()).isTrue();
        }
    }
}
