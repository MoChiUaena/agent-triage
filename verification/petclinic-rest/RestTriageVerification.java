package org.springframework.samples.petclinic.triage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PreDestroy;
import io.github.mochiuaena.triage.sdk.TriageObservationContext;
import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.context.request.async.DeferredResult;

/** Used only by the isolated public-project acceptance run. */
@RestController
@ConditionalOnProperty(name = "triage.verification.enabled", havingValue = "true")
public class RestTriageVerification {
    private final RestClient downstream;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8), task -> {
        var thread = new Thread(task, "triage-verification-worker"); thread.setDaemon(true); return thread;
    });
    private final AtomicInteger lateFinished = new AtomicInteger();
    public RestTriageVerification(RestClient.Builder builder,
            @Value("${triage.verification.downstream-base-url:http://127.0.0.1:1}") URI base) {
        if (!"http".equals(base.getScheme()) || !"127.0.0.1".equals(base.getHost()) || base.getPort() < 1
            || base.getPort() > 65535 || base.getUserInfo() != null || base.getQuery() != null || base.getFragment() != null
            || !(base.getPath().isEmpty() || "/".equals(base.getPath()))) throw new IllegalArgumentException("Use the isolated loopback downstream");
        var factory = new SimpleClientHttpRequestFactory(); factory.setConnectTimeout(150); factory.setReadTimeout(150);
        downstream = builder.requestFactory(factory).baseUrl(base.toString()).build();
    }
    @GetMapping("/api/triage-verification/error")
    public void fail(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        ProbeFailure.fail();
    }
    @GetMapping("/api/triage-verification/timeout")
    public ResponseEntity<Void> timeout(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        return lookup();
    }
    @GetMapping("/api/triage-verification/plain-server-error")
    public ResponseEntity<Void> plain(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
    }
    @GetMapping("/api/triage-verification/recovery")
    public ResponseEntity<Void> recovery(@RequestHeader(value = "X-Triage-Lab", required = false) String header,
                                         @RequestHeader(value = "X-Triage-Fail", required = false) String fail, HttpServletRequest request) {
        local(header, request);
        if ("1".equals(fail)) ProbeFailure.fail();
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/api/triage-verification/callable-error")
    public Callable<ResponseEntity<Void>> callableError(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        return () -> { ProbeFailure.fail(); return ResponseEntity.noContent().build(); };
    }
    @GetMapping("/api/triage-verification/servlet-timeout")
    public DeferredResult<ResponseEntity<Void>> servletTimeout(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        return new DeferredResult<>(100L);
    }
    private ResponseEntity<Void> lookup() {
        try { downstream.get().uri("/delay").retrieve().toBodilessEntity(); return ResponseEntity.noContent().build(); }
        catch (ResourceAccessException failure) { return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build(); }
    }
    @GetMapping("/api/triage-verification/callable-timeout")
    public Callable<ResponseEntity<Void>> callable(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        return this::lookup;
    }
    @GetMapping("/api/triage-verification/deferred-timeout")
    public DeferredResult<ResponseEntity<Void>> deferred(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        var result = new DeferredResult<ResponseEntity<Void>>(3000L);
        workers.execute(TriageObservationContext.capture().wrap(() -> { result.setResult(lookup()); }));
        return result;
    }
    @GetMapping("/api/triage-verification/parallel-timeout")
    public DeferredResult<ResponseEntity<Void>> parallel(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        var result = new DeferredResult<ResponseEntity<Void>>(3000L);
        var snapshot = TriageObservationContext.capture(); var remaining = new AtomicInteger(2); var timeout = new AtomicBoolean();
        for (int item = 0; item < 2; item++) workers.execute(snapshot.wrap(() -> {
            if (lookup().getStatusCode().value() == 504) timeout.set(true);
            if (remaining.decrementAndGet() == 0) result.setResult(ResponseEntity.status(timeout.get() ? 504 : 204).build());
        }));
        return result;
    }
    @GetMapping("/api/triage-verification/late-timeout")
    public ResponseEntity<Void> late(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) throws InterruptedException {
        local(header, request);
        var entered = new CountDownLatch(1);
        workers.execute(TriageObservationContext.capture().wrap(() -> {
            entered.countDown(); lookup(); lateFinished.incrementAndGet();
        }));
        if (!entered.await(1, TimeUnit.SECONDS)) throw new IllegalStateException("Verification worker did not start");
        return ResponseEntity.noContent().build();
    }
    @GetMapping("/triage-verification/late-finished")
    public Map<String, Integer> lateFinished(@RequestHeader(value = "X-Triage-Lab", required = false) String header, HttpServletRequest request) {
        local(header, request);
        return Map.of("completed", lateFinished.get());
    }
    @PreDestroy public void stopWorkers() { workers.shutdownNow(); }
    private static void local(String header, HttpServletRequest request) {
        try {
            if ("1".equals(header) && InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) {
                return;
            }
        } catch (Exception ignored) { /* Deny malformed or non-loopback requests. */ }
        throw new ResponseStatusException(HttpStatus.FORBIDDEN);
    }

    static final class ProbeFailure {
        static void fail() { throw new IllegalStateException("verification-only failure"); }
    }
}
