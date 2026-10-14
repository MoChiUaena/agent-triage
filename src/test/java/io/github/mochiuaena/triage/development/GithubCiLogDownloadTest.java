package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class GithubCiLogDownloadTest {
    @Test void signedDownloadDoesNotReceiveTheCredentialAndUntrustedTargetsAreRejected() throws Exception {
        var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            if (path.endsWith("/logs")) {
                String target = path.contains("jobs/1/") ? "http://127.0.0.1:" + server.getAddress().getPort() + "/download"
                    : "https://x.blob.core.windows.net.attacker.example/download";
                exchange.getResponseHeaders().set("Location", target); exchange.sendResponseHeaders(302, -1); exchange.close();
            } else {
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = "[ERROR] Could not resolve dependencies".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length); try (var output = exchange.getResponseBody()) { output.write(bytes); }
            }
        }); server.start();
        try {
            var client = new GithubClient(new ObjectMapper(), "http://127.0.0.1:" + server.getAddress().getPort(), "fixture-token");
            assertThat(client.ciLog(new GithubModels.Key("acme", "service"), 1)).contains("resolve dependencies");
            assertThat(auth.get()).isNull();
            assertThatThrownBy(() -> client.ciLog(new GithubModels.Key("acme", "service"), 2))
                .isInstanceOf(ResponseStatusException.class).hasMessageContaining("受信来源");
        } finally { server.stop(0); }
    }
}
