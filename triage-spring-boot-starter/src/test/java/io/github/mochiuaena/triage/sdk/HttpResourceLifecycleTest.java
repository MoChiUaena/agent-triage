package io.github.mochiuaena.triage.sdk;

import com.sun.net.httpserver.HttpServer;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.lang.management.ManagementFactory;
import java.net.*;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.*;
import org.springframework.http.client.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

/** The driver, Servlet and downstream all belong to the sampled JVM. */
class HttpResourceLifecycleTest {
    static final int RPS = 8, CONCURRENCY = 4;
    static final int[] SCENARIOS = {0, 3, 4, 1, 5, 6, 2, 7};
    static final String PAYLOAD = "acceptance-body";
    static final String TYPED_TIMEOUT_ATTRIBUTE = "resource.typedTimeout";
    static final Downstream downstream = new Downstream();
    ConfigurableApplicationContext application;
    Counters counters;
    int port;

    @SpringBootConfiguration @EnableAutoConfiguration
    static class Application {
        @Bean Counters counters() { return new Counters(); }
        @Bean(destroyMethod = "close") java.net.http.HttpClient businessHttpClient() {
            return java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        }
        @Bean Controller controller(RestTemplateBuilder builder, java.net.http.HttpClient businessHttpClient,
                                    Counters counters, @Value("${triage.sdk.downstream-base-url}") String origin) {
            return new Controller(builder, businessHttpClient, counters, origin);
        }
        @Bean FilterRegistrationBean<Filter> cleanupAudit(Counters counters) {
            var registration = new FilterRegistrationBean<Filter>((request, response, chain) -> {
                if (TriageRequestFilter.CURRENT.get() != null) counters.servletContexts.incrementAndGet();
                try { chain.doFilter(request, response); }
                finally {
                    if (TriageRequestFilter.CURRENT.get() != null) counters.servletContexts.incrementAndGet();
                    var context = (TriageRequestFilter.Context) request.getAttribute(TriageRequestFilter.CONTEXT_ATTRIBUTE);
                    if (context != null && (context.isActive() || context.handlerClass != null || context.endpoint != null))
                        counters.servletContexts.incrementAndGet();
                    counters.completed(Boolean.TRUE.equals(request.getAttribute(TYPED_TIMEOUT_ATTRIBUTE)),
                        ((HttpServletResponse) response).getStatus() >= 500);
                }
            });
            registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
            registration.addUrlPatterns("/api/*");
            return registration;
        }
    }
    @RestController
    static class Controller {
        final RestTemplate simple, simpleNormal, jdk;
        final Counters counters;
        Controller(RestTemplateBuilder builder, java.net.http.HttpClient businessHttpClient, Counters counters, String origin) {
            this.counters = counters;
            var configured = builder.rootUri(origin).connectTimeout(Duration.ofSeconds(2)).readTimeout(Duration.ofMillis(250))
                .additionalInterceptors((request, body, execution) -> {
                    var response = execution.execute(request, body);
                    counters.responsesOpened.incrementAndGet();
                    return new ClientHttpResponse() {
                        final AtomicBoolean closed = new AtomicBoolean();
                        @Override public HttpStatusCode getStatusCode() throws IOException { return response.getStatusCode(); }
                        @Override public String getStatusText() throws IOException { return response.getStatusText(); }
                        @Override public HttpHeaders getHeaders() { return response.getHeaders(); }
                        @Override public InputStream getBody() throws IOException { return response.getBody(); }
                        @Override public void close() {
                            try { response.close(); }
                            finally { if (closed.compareAndSet(false, true)) counters.responsesClosed.incrementAndGet(); }
                        }
                    };
                });
            simpleNormal = configured.readTimeout(Duration.ofSeconds(2)).requestFactory(SimpleClientHttpRequestFactory::new).build();
            simple = configured.requestFactory(SimpleClientHttpRequestFactory::new).build();
            // The owned JDK HttpClient already has its connect timeout.
            jdk = configured.connectTimeout(null).requestFactory(() -> new JdkClientHttpRequestFactory(businessHttpClient)).build();
        }
        @RequestMapping("/api/resource/{factory}/{mode}")
        ResponseEntity<String> call(@PathVariable String factory, @PathVariable String mode, HttpServletRequest request,
                                    @RequestBody(required = false) String body) {
            long started = System.nanoTime(), gcBefore = gcMillis();
            try {
                var client = factory.equals("simple") ? mode.equals("normal") ? simpleNormal : simple : jdk;
                var headers = new HttpHeaders();
                if (factory.equals("simple") && mode.contains("timeout")) headers.setConnection("close");
                return ResponseEntity.ok(client.exchange("/" + factory + "-" + mode, HttpMethod.valueOf(request.getMethod()),
                    new HttpEntity<>(body, headers), String.class).getBody());
            } catch (RestClientException failure) {
                boolean typed = typedTimeout(failure);
                boolean unexpected = mode.equals("normal") || (factory.equals("simple") && !typed);
                boolean firstUnexpected = unexpected && counters.dumpedFailure.compareAndSet(false, true);
                var timing = firstUnexpected && downstream.timing != null ? downstream.timing.snapshot() : null;
                if (unexpected)
                    System.out.println(String.format(Locale.ROOT, "RESOURCE_UNEXPECTED_HTTP_FAILURE method=%s factory=%s mode=%s elapsedMs=%.1f typedTimeout=%b gcMillis=%d downstreamActive=%d downstreamQueue=%d causeTypes=%s%s",
                    request.getMethod(), factory, mode, ObservationRecorder.elapsed(started), typed,
                    Math.max(0, gcMillis() - gcBefore), downstream.active.get(), downstream.workers.getQueue().size(), causeTypes(failure),
                    timing == null ? "" : " " + timing.summary() + " jvmUptimeMs=" +
                        ManagementFactory.getRuntimeMXBean().getUptime() + " wallClockMs=" + System.currentTimeMillis()));
                if (firstUnexpected) {
                    dumpResourceTiming(timing);
                    var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
                    for (Throwable cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) {
                        System.out.println("RESOURCE_FAILURE_FRAMES cause=" + cause.getClass().getName());
                        for (var frame : Arrays.stream(cause.getStackTrace()).limit(12).toList()) System.out.println("RESOURCE_FRAME " + frame);
                    }
                    dumpResourceThreads();
                }
                request.setAttribute(TYPED_TIMEOUT_ATTRIBUTE, typed);
                if (factory.equals("jdk") && typed) counters.jdkTypedTimeouts.incrementAndGet();
                if (typed) counters.typedTimeouts.incrementAndGet();
                else counters.untypedFailures.incrementAndGet();
                return ResponseEntity.status(504).body("incomplete");
            }
        }
    }
    record Completion(Instant time, boolean timeout, boolean failed) { }
    static final class Counters {
        final AtomicInteger responsesOpened = new AtomicInteger(), responsesClosed = new AtomicInteger();
        final AtomicInteger typedTimeouts = new AtomicInteger(), untypedFailures = new AtomicInteger(), jdkTypedTimeouts = new AtomicInteger();
        final AtomicInteger servletContexts = new AtomicInteger(), completed = new AtomicInteger();
        final AtomicBoolean dumpedFailure = new AtomicBoolean();
        final Deque<Completion> recent = new ArrayDeque<>();
        synchronized void completed(boolean timeout, boolean failed) {
            recent.addLast(new Completion(Instant.now(), timeout, failed));
            while (recent.size() > 1024) recent.removeFirst();
            completed.incrementAndGet();
        }
        synchronized List<Completion> recent() { return List.copyOf(recent); }
    }
    static java.util.concurrent.ThreadFactory namedThreads(String prefix) {
        var sequence = new AtomicInteger();
        return task -> new Thread(task, prefix + sequence.incrementAndGet());
    }
    static void dumpResourceThreads() {
        var threads = ManagementFactory.getThreadMXBean().dumpAllThreads(true, true);
        var selected = new HashSet<Long>();
        for (var thread : threads) {
            String name = thread.getThreadName();
            if (name.startsWith("resource-") || name.equals("HTTP-Dispatcher") || name.startsWith("HttpClient-") ||
                name.contains("exec-") || name.equals("main") || name.startsWith("Keep-Alive-") || name.endsWith("timeout-task"))
                selected.add(thread.getThreadId());
        }
        boolean changed;
        do {
            changed = false;
            for (var thread : threads) if (selected.contains(thread.getThreadId()) && thread.getLockOwnerId() > 0)
                changed |= selected.add(thread.getLockOwnerId());
        } while (changed);
        var ordered = Arrays.stream(threads).filter(thread -> selected.contains(thread.getThreadId())).sorted(Comparator.comparingInt(thread -> diagnosticPriority(thread.getThreadName()))).toList();
        for (var thread : ordered) {
            System.out.println(String.format(Locale.ROOT, "RESOURCE_THREAD name=%s id=%d state=%s lock=%s owner=%d", thread.getThreadName(), thread.getThreadId(), thread.getThreadState(), thread.getLockName(), thread.getLockOwnerId()));
            for (var frame : Arrays.stream(thread.getStackTrace()).limit(12).toList()) System.out.println("RESOURCE_FRAME " + frame);
        }
    }
    static int diagnosticPriority(String name) {
        if (name.equals("HTTP-Dispatcher") || name.startsWith("Keep-Alive-") || name.endsWith("timeout-task")) return 0;
        if (name.startsWith("resource-downstream-")) return 1;
        if (name.startsWith("resource-driver-") || name.equals("main")) return 3;
        return 2;
    }
    static long gcMillis() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
    }
    static void dumpResourceTiming(HttpResourceTiming.Snapshot timing) {
        if (timing == null) return;
        System.out.println("RESOURCE_TIMING kind=summary " + timing.summary());
        for (var sample : timing.active()) System.out.println(sample.line(true));
        for (var sample : timing.recent()) System.out.println(sample.line(false));
    }
    static String causeTypes(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        var types = new ArrayList<String>();
        for (var cause = failure; cause != null && seen.add(cause); cause = cause.getCause()) types.add(cause.getClass().getName());
        return String.join("/", types);
    }
    static boolean typedTimeout(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable, Boolean>());
        for (var cause = failure; cause != null && seen.add(cause); cause = cause.getCause())
            if (cause instanceof SocketTimeoutException || cause instanceof HttpTimeoutException) return true;
        return false;
    }
    static final class Downstream implements AutoCloseable {
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(8, 8, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(16), namedThreads("resource-downstream-"));
        final AtomicInteger active = new AtomicInteger(), requests = new AtomicInteger(), payloadErrors = new AtomicInteger(), faultConnections = new AtomicInteger();
        final HttpServer server;
        final AtomicBoolean closed = new AtomicBoolean();
        final HealthyConnectionProbe healthyConnections = new HealthyConnectionProbe();
        final HttpResourceTiming timing = Boolean.getBoolean("triage.resource.timing") ? HttpResourceTiming.system() : null;
        Downstream() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(workers);
                server.createContext("/", new ResourceHttpHandler(exchange -> {
                    long timingThread = timing == null ? 0 : Thread.currentThread().threadId();
                    if (timing != null) {
                        String mode = exchange.getRequestURI().getPath();
                        var scenario = mode.contains("normal") ? HttpResourceTiming.Scenario.NORMAL :
                            mode.contains("header-timeout") ? HttpResourceTiming.Scenario.HEADER_TIMEOUT : HttpResourceTiming.Scenario.BODY_TIMEOUT;
                        timing.begin(timingThread, scenario);
                    }
                    try {
                        var body = new String(exchange.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        if (!(exchange.getRequestMethod().equals("GET") ? body.isEmpty() : body.equals(PAYLOAD))) payloadErrors.incrementAndGet();
                        var mode = exchange.getRequestURI().getPath();
                        if (mode.contains("simple") && mode.contains("timeout") && !"close".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Connection")))
                            faultConnections.incrementAndGet();
                        if (mode.contains("timeout")) exchange.getResponseHeaders().set("Connection", "close");
                        if (mode.contains("normal")) healthyConnections.accept(exchange.getRemoteAddress().getPort());
                        if (mode.contains("normal")) {
                            int delay = Integer.getInteger("triage.resource.normal-delay-millis", 350);
                            if (timing != null) timing.phase(timingThread, HttpResourceTiming.Phase.DELAY_HEADERS, delay);
                            Thread.sleep(delay);
                        }
                        if (mode.contains("header-timeout")) {
                            if (timing != null) timing.phase(timingThread, HttpResourceTiming.Phase.DELAY_HEADERS, 1000);
                            Thread.sleep(1000);
                        }
                        if (timing != null) timing.phase(timingThread, HttpResourceTiming.Phase.WRITE_HEADERS, 0);
                        exchange.sendResponseHeaders(200, 2);
                        if (timing != null) timing.phase(timingThread, HttpResourceTiming.Phase.WRITE_FIRST_BYTE, 0);
                        exchange.getResponseBody().write('o'); exchange.getResponseBody().flush();
                        if (mode.contains("body-timeout")) {
                            if (timing != null) timing.phase(timingThread, HttpResourceTiming.Phase.DELAY_BODY, 1000);
                            Thread.sleep(1000);
                        }
                        if (timing != null) timing.phase(timingThread, HttpResourceTiming.Phase.WRITE_LAST_BYTE, 0);
                        exchange.getResponseBody().write('k');
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt(); throw new IOException(failure);
                    } finally { if (timing != null) timing.end(timingThread); }
                }, active, requests));
                server.start();
            } catch (IOException failure) { workers.shutdownNow(); throw new UncheckedIOException(failure); }
        }
        String origin() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        @Override public void close() {
            if (closed.compareAndSet(false, true)) {
                try { server.stop(0); workers.shutdownNow(); workers.close(); }
                finally { if (timing != null) timing.close(); }
            }
        }
    }
    @AfterAll static void closeDownstream() { downstream.close(); }

    @Test void realServletWorkloadMaintainsCountsAndReturnsItsResources() throws Exception {
        int seconds = Integer.getInteger("triage.resource.seconds", 12);
        application = new org.springframework.boot.builder.SpringApplicationBuilder(Application.class).properties(
            "server.port=0", "server.address=127.0.0.1", "triage.sdk.enabled=" + System.getProperty("triage.resource.http.enabled", "true"),
            "triage.sdk.service-id=resource-service", "triage.sdk.downstream-id=loopback-service",
            "triage.sdk.downstream-base-url=" + downstream.origin(), "triage.sdk.endpoint-observations=true",
            "triage.sdk.response-status-counts=true", "triage.sdk.exception-locations=true",
            "triage.sdk.application-packages=io.github.mochiuaena.triage.sdk", "server.tomcat.threads.max=4",
            "server.tomcat.threads.min-spare=4", "server.shutdown=immediate", "spring.main.banner-mode=off").run();
        counters = application.getBean(Counters.class);
        port = ((org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext) application).getWebServer().getPort();
        int downstreamPort = downstream.server.getAddress().getPort();
        boolean enabled = application.getEnvironment().getProperty("triage.sdk.enabled", Boolean.class, false);
        assertThat(seconds).isBetween(2, 7200);
        long started = System.nanoTime();
        long baselineHeap = 0, peakHeap = 0;
        double nextHeap = Math.min(120, seconds / 5.0);
        var driver = new Driver(port, enabled);
        if (downstream.timing != null) downstream.timing.driverStarted(started);
        var businessHttpClient = application.getBean(java.net.http.HttpClient.class);
        long observedRequests = 0; int observedTimeouts = 0, windowRequests = 0, windowTimeouts = 0;
        int expectedWindowRequests, expectedWindowTimeouts;
        WindowCheck previous = null;
        try {
            phase("running", started);
            for (int index = 0; index < seconds * RPS; index++) {
                long scheduled = started + index * 1_000_000_000L / RPS;
                long remaining = scheduled - System.nanoTime();
                if (remaining > 0) TimeUnit.NANOSECONDS.sleep(remaining);
                driver.maxLagMillis.accumulateAndGet(Math.max(0, (System.nanoTime() - scheduled) / 1_000_000L), Math::max);
                if (downstream.timing != null) downstream.timing.driverLag(Math.max(0, System.nanoTime() - scheduled));
                driver.submit(index % RPS, scheduled);
                double elapsed = (System.nanoTime() - started) / 1_000_000_000.0;
                if (elapsed >= nextHeap) {
                    long heap = retainedHeap();
                    if (baselineHeap == 0) baselineHeap = heap;
                    peakHeap = Math.max(peakHeap, heap);
                    assertThat(heap).isLessThanOrEqualTo(baselineHeap + 64L * 1024 * 1024);
                    nextHeap += 30;
                }
                if (index == 2 * RPS - 1) {
                    await().atMost(Duration.ofSeconds(5)).until(() -> counters.completed.get() == 2 * RPS);
                }
                // The probe accepts the first 32 normal requests; those arrive across 11 traffic cycles.
                if (index == 11 * RPS - 1) {
                    await().atMost(Duration.ofSeconds(5)).until(() -> counters.completed.get() == 11 * RPS);
                    assertThat(downstream.healthyConnections.reused()).as("Healthy connection reuse must occur within the probe's first 32 normal requests").isTrue();
                }
                if ((index + 1) % (30 * RPS) == 0 && index + 1 < seconds * RPS) {
                    int issued = index + 1;
                    await().atMost(Duration.ofSeconds(5)).until(() -> counters.completed.get() == issued);
                    previous = checkWindow(enabled, issued, previous);
                    observedTimeouts += previous.newTimeouts();
                }
                if (driver.failure.get() != null) throw new AssertionError("HTTP driver assertion failed", driver.failure.get());
            }
            driver.workers.shutdown();
            assertThat(driver.workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            assertThat(driver.failure.get()).isNull();
            await().atMost(Duration.ofSeconds(5)).until(() -> counters.completed.get() == seconds * RPS && downstream.active.get() == 0);
            assertThat(driver.successes.get()).isEqualTo(seconds * 3);
            assertThat(driver.failures.get()).isEqualTo(seconds * 5);
            assertThat(counters.typedTimeouts.get()).isEqualTo(seconds * 4 + counters.jdkTypedTimeouts.get());
            assertThat(counters.untypedFailures.get()).isEqualTo(seconds - counters.jdkTypedTimeouts.get());
            assertThat(counters.jdkTypedTimeouts.get()).isBetween(0, seconds);
            assertThat(counters.responsesOpened.get()).isEqualTo(seconds * 7);
            assertThat(counters.responsesClosed.get()).isEqualTo(counters.responsesOpened.get());
            assertThat(counters.servletContexts.get()).isZero();
            assertThat(driver.workerContexts.get()).isZero();
            assertThat(downstream.requests.get()).isEqualTo(seconds * RPS);
            assertThat(downstream.payloadErrors.get()).isZero();
            assertThat(downstream.healthyConnections.reused()).as("Healthy requests must exercise connection reuse").isTrue();
            assertThat(downstream.faultConnections.get()).as("Simple fault requests must close their connection").isZero();
            assertThat(driver.maxLagMillis.get()).isLessThanOrEqualTo(2000);
            if (downstream.timing != null) {
                var timing = downstream.timing.snapshot();
                assertThat(timing.active()).isEmpty();
                assertThat(timing.delaySamples()).isEqualTo(seconds * RPS);
            }
            var tail = checkWindow(enabled, seconds * RPS, previous);
            observedRequests = tail.observedRequests();
            observedTimeouts += tail.newTimeouts();
            windowRequests = tail.requests(); windowTimeouts = tail.timeouts();
            expectedWindowRequests = tail.expectedRequests(); expectedWindowTimeouts = tail.expectedTimeouts();
            assertThat(observedTimeouts).isEqualTo(enabled ? counters.typedTimeouts.get() : 0);
        } catch (Exception | AssertionError failure) {
            var timing = downstream.timing == null ? null : downstream.timing.snapshot();
            System.out.println("RESOURCE_FAILURE_AT jvmUptimeMs=" + ManagementFactory.getRuntimeMXBean().getUptime() +
                " wallClockMs=" + System.currentTimeMillis());
            System.out.println("RESOURCE_FAILURE_FRAMES cause=" + failure.getClass().getName() + (timing == null ? "" : " " + timing.summary()));
            if (timing != null && counters.dumpedFailure.compareAndSet(false, true)) dumpResourceTiming(timing);
            for (var frame : Arrays.stream(failure.getStackTrace()).limit(12).toList()) System.out.println("RESOURCE_FRAME " + frame);
            throw failure;
        } finally {
            driver.close();
            application.close();
            downstream.close();
        }
        assertThat(driver.workers.isTerminated()).isTrue();
        assertThat(driver.http.isTerminated()).isTrue();
        assertThat(businessHttpClient.isTerminated()).isTrue();
        assertThat(downstream.workers.isTerminated()).isTrue();
        assertThat(downstream.active.get()).isZero();
        if (downstream.timing != null) {
            assertThat(downstream.timing.snapshot().active()).isEmpty();
            assertThat(downstream.timing.snapshot().recent()).isEmpty();
        }
        assertThat(driver.workers.getQueue()).isEmpty();
        assertThat(downstream.workers.getQueue()).isEmpty();
        assertThat(application.isActive()).isFalse();
        assertPortClosed(port);
        assertPortClosed(downstreamPort);
        long finalHeap = retainedHeap();
        assertThat(finalHeap).isLessThanOrEqualTo(baselineHeap + 64L * 1024 * 1024);
        String component = enabled ? "http-enabled" : "http-disabled";
        System.out.println(String.format(Locale.ROOT, "RESOURCE_RESULT %s seconds=%d cycles=%d rps=8 concurrency=4 connectionPolicy=2 healthyConnectionsReused=true faultConnectionsClosed=true requests=%d successes=%d failures=%d typedTimeouts=%d untypedFailures=%d jdkTypedTimeouts=%d observedRequests=%d observedTimeouts=%d expectedWindowRequests=%d expectedWindowTimeouts=%d windowRequests=%d windowTimeouts=%d responsesOpened=%d responsesClosed=%d downstreamRequests=%d servletContexts=0 workerContexts=0 downstreamActive=0 queues=0 payloadErrors=0 executorsStopped=true servletStopped=true maxLagMillis=%d baselineHeap=%d peakHeap=%d finalHeap=%d",
            component, seconds, seconds, seconds * RPS, driver.successes.get(), driver.failures.get(), counters.typedTimeouts.get(),
            counters.untypedFailures.get(), counters.jdkTypedTimeouts.get(), observedRequests, observedTimeouts, expectedWindowRequests, expectedWindowTimeouts,
            windowRequests, windowTimeouts, counters.responsesOpened.get(), counters.responsesClosed.get(), downstream.requests.get(),
            driver.maxLagMillis.get(), baselineHeap, peakHeap, finalHeap));
        phase("closed", started);
    }
    static void assertPortClosed(int port) {
        assertThatThrownBy(() -> {
            try (var connection = new Socket("127.0.0.1", port)) { fail("Fixture port still accepts connections"); }
        }).isInstanceOf(IOException.class);
    }
    record WindowCheck(Instant end, int requests, int timeouts, int expectedRequests, int expectedTimeouts,
                       long observedRequests, int newTimeouts) { }
    WindowCheck checkWindow(boolean enabled, int issued, WindowCheck previous) throws Exception {
        List<Completion> recent = counters.recent();
        // Keep both minute boundaries away from completion times: audit follows observer by a few microseconds.
        Instant end = HttpWindowBoundary.select(Instant.now(), recent.stream().map(Completion::time).toList());
        Instant start = end.minusSeconds(60);
        var completedWindow = recent.stream().filter(value -> !value.time().isBefore(start)).toList();
        int expectedRequests = completedWindow.size();
        int expectedTimeouts = (int) completedWindow.stream().filter(Completion::timeout).count();
        if (!enabled) {
            assertThat(application.getBeansOfType(ObservationRecorder.class)).isEmpty();
            return new WindowCheck(end, 0, 0, expectedRequests, expectedTimeouts, 0, 0);
        }
        var recorder = application.getBean(ObservationRecorder.class);
        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(1, end);
        assertThat(window.recordedRequestCount()).isEqualTo(issued);
        assertThat(window.requestCount()).isEqualTo(expectedRequests);
        assertThat(window.timeoutCount()).isEqualTo(expectedTimeouts);
        assertThat(window.downstreamP95Ms()).isGreaterThanOrEqualTo(150);
        var endpoints = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(1, end, null);
        assertThat(endpoints.responseStatuses().serverError()).isEqualTo(completedWindow.stream().filter(Completion::failed).count());
        assertThat(endpoints.toString()).doesNotContain(PAYLOAD, downstream.origin(), "incomplete");
        int expired = previous == null ? 0 : (int) recent.stream().filter(event -> event.timeout() &&
            !event.time().isBefore(previous.end().minusSeconds(60)) && event.time().isBefore(start)).count();
        int newTimeouts = window.timeoutCount() - (previous == null ? 0 : previous.timeouts()) + expired;
        return new WindowCheck(end, window.requestCount(), window.timeoutCount(), expectedRequests, expectedTimeouts,
            window.recordedRequestCount(), newTimeouts);
    }
    static long retainedHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }
    static void phase(String phase, long started) throws Exception {
        String marker = System.getProperty("triage.resource.marker");
        if (marker == null) return;
        var destination = Path.of(marker);
        var temporary = destination.resolveSibling(destination.getFileName() + ".tmp");
        Files.writeString(temporary, phase + "," + ProcessHandle.current().pid() + "," +
            (System.nanoTime() - started) / 1_000_000_000.0);
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        if (phase.equals("closed")) {
            int holdSeconds = Integer.getInteger("triage.resource.close-hold-seconds", 15);
            assertThat(holdSeconds).isBetween(15, 75);
            Thread.sleep(holdSeconds * 1_000L);
        }
    }
    static final class Driver implements AutoCloseable {
        final ThreadPoolExecutor workers = new ThreadPoolExecutor(CONCURRENCY, CONCURRENCY, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), namedThreads("resource-driver-"), new BoundedResourceAdmission(Duration.ofSeconds(2)));
        final java.net.http.HttpClient http = java.net.http.HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicInteger successes = new AtomicInteger(), failures = new AtomicInteger(), workerContexts = new AtomicInteger();
        final AtomicLong maxLagMillis = new AtomicLong();
        final int port; final boolean enabled;
        Driver(int port, boolean enabled) { this.port = port; this.enabled = enabled; }
        void submit(int slot, long scheduled) {
            workers.execute(() -> {
                maxLagMillis.accumulateAndGet(Math.max(0, (System.nanoTime() - scheduled) / 1_000_000L), Math::max);
                if (downstream.timing != null) downstream.timing.driverLag(Math.max(0, System.nanoTime() - scheduled));
                if (TriageRequestFilter.CURRENT.get() != null) workerContexts.incrementAndGet();
                try {
                    int scenario = SCENARIOS[slot];
                    String method = scenario == 1 || scenario == 4 ? "POST" : scenario == 2 || scenario == 5 ? "PUT" : "GET";
                    String factory = scenario == 7 ? "jdk" : "simple";
                    String mode = scenario < 3 ? "normal" : scenario < 6 ? "header-timeout" : "body-timeout";
                    var body = method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(PAYLOAD);
                    var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/resource/" + factory + "/" + mode))
                        .timeout(Duration.ofSeconds(5)).method(method, body).build();
                    long requestStarted = System.nanoTime();
                    var response = http.send(request, HttpResponse.BodyHandlers.ofString());
                    int expectedStatus = scenario < 3 ? 200 : 504;
                    if (response.statusCode() != expectedStatus) {
                        var timing = downstream.timing == null ? null : downstream.timing.snapshot();
                        System.out.println(String.format(Locale.ROOT,
                            "RESOURCE_UNEXPECTED_DRIVER_RESPONSE scenario=%d method=%s factory=%s mode=%s status=%d expectedStatus=%d elapsedMs=%.1f jvmUptimeMs=%d wallClockMs=%d%s",
                            scenario, method, factory, mode, response.statusCode(), expectedStatus,
                            (System.nanoTime() - requestStarted) / 1_000_000.0,
                            ManagementFactory.getRuntimeMXBean().getUptime(), System.currentTimeMillis(),
                            timing == null ? "" : " " + timing.summary()));
                    }
                    assertThat(response.statusCode()).isEqualTo(expectedStatus);
                    assertThat(response.body()).isEqualTo(scenario < 3 ? "ok" : "incomplete");
                    assertThat(response.headers().firstValue("X-Triage-Trace-Id").isPresent()).isEqualTo(enabled);
                    if (scenario < 3) successes.incrementAndGet(); else failures.incrementAndGet();
                } catch (Throwable error) { failure.compareAndSet(null, error); }
                finally { if (TriageRequestFilter.CURRENT.get() != null) workerContexts.incrementAndGet(); }
            });
        }
        @Override public void close() {
            workers.shutdownNow(); workers.close(); http.close();
        }
    }
}
