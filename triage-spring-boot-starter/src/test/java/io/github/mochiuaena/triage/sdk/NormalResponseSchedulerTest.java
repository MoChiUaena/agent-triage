package io.github.mochiuaena.triage.sdk;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class NormalResponseSchedulerTest {
    @Test void reportsAResponseWriterBlockedInsideTheTimer() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var scheduler = new NormalResponseScheduler(); var caller = Executors.newSingleThreadExecutor()) {
            var result = CompletableFuture.runAsync(() -> {
                try {
                    scheduler.afterDelay(0, () -> {
                        entered.countDown();
                        try { if (!release.await(2, TimeUnit.SECONDS)) throw new java.io.IOException("Timer was not released"); }
                        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                    });
                } catch (java.io.IOException failure) { throw new java.io.UncheckedIOException(failure); }
            }, caller);
            assertThat(entered.await(1, TimeUnit.SECONDS)).isTrue();
            var state = scheduler.state();
            assertThat(state.poolSize()).isGreaterThanOrEqualTo(1);
            assertThat(state.activeCount()).isEqualTo(1);
            assertThat(state.shutdown()).isFalse();
            release.countDown();
            result.get(1, TimeUnit.SECONDS);
        } finally { release.countDown(); }
    }

    @Test void completeResponseDoesNotWaitForHandlerThreadToResume() throws Exception {
        var releaseHandler = new CountDownLatch(1);
        var handlerWaiting = new CountDownLatch(1);
        try (var workers = Executors.newCachedThreadPool(); var scheduler = new NormalResponseScheduler()) {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(workers);
            server.createContext("/", exchange -> {
                try {
                    scheduler.afterDelay(100, () -> {
                        exchange.sendResponseHeaders(200, 2);
                        exchange.getResponseBody().write(new byte[]{'o', 'k'});
                        exchange.getResponseBody().flush();
                    });
                    handlerWaiting.countDown();
                    if (!releaseHandler.await(2, TimeUnit.SECONDS)) throw new java.io.IOException("Handler stayed blocked");
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt(); throw new java.io.IOException(failure);
                } finally { exchange.close(); }
            });
            server.start();
            try (var client = HttpClient.newHttpClient()) {
                var request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server.getAddress().getPort()))
                    .timeout(Duration.ofSeconds(2)).GET().build();
                var response = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
                assertThat(handlerWaiting.await(1, TimeUnit.SECONDS)).isTrue();
                assertThat(response.get(750, TimeUnit.MILLISECONDS).body()).isEqualTo("ok");
            } finally {
                releaseHandler.countDown();
                server.stop(0);
            }
            assertThat(scheduler.stopped()).isFalse();
        }
    }
}
