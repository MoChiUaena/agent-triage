package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GithubClientTimeoutTest {
    @Test void responseBodyStallAlsoHasAnOverallDeadline() throws Exception {
        var release = new CountDownLatch(1);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var workers = Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).factory());
        server.setExecutor(workers);
        server.createContext("/", exchange -> {
            exchange.sendResponseHeaders(200, 100);
            try (var out = exchange.getResponseBody()) {
                out.write('{'); out.flush();
                try { release.await(15, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            } catch (java.io.IOException ignored) { }
        });
        server.start();
        var client = new GithubClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "");
        var pending = workers.submit(() -> assertThatThrownBy(() -> client.read(new GithubModels.Key("acme", "service")))
            .isInstanceOf(ResponseStatusException.class));
        try { assertThatCode(() -> pending.get(10, TimeUnit.SECONDS)).doesNotThrowAnyException(); }
        finally { release.countDown(); pending.cancel(true); server.stop(0); workers.shutdownNow(); }
    }
}
