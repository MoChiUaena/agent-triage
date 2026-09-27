package io.github.mochiuaena.triage.settings;

import com.fasterxml.jackson.databind.*;
import com.sun.net.httpserver.HttpServer;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.TriageEngine;
import io.github.mochiuaena.triage.settings.ProviderConfig.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.*;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
    "triage.mode=DEMO", "spring.datasource.url=${TRIAGE_TEST_DB_URL:jdbc:h2:mem:provider-http;DB_CLOSE_DELAY=-1}",
    "spring.datasource.username=${TRIAGE_TEST_DB_USER:sa}", "spring.datasource.password=${TRIAGE_TEST_DB_PASSWORD:}"
})
@ExtendWith(OutputCaptureExtension.class)
class ProviderSettingsTest {
    static final ObjectMapper JSON = new ObjectMapper();
    static final Path KEY_DIRECTORY = temporaryDirectory();
    static final Path KEY = KEY_DIRECTORY.resolve("settings.key");
    static final Stub MODEL = new Stub();
    @Autowired TestRestTemplate http;
    @Autowired JdbcTemplate jdbc;
    @Autowired ProviderRegistry registry;
    @Autowired ProviderRepository repository;
    @Autowired TriageEngine router;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry properties) { properties.add("triage.settings.key-file", KEY::toString); }
    @BeforeEach void reset() {
        jdbc.update("DELETE FROM model_selection");
        jdbc.update("DELETE FROM model_providers");
        MODEL.requests.clear(); MODEL.status = 200; MODEL.entered = null; MODEL.release = null;
    }
    @AfterAll static void shutdown() throws Exception { MODEL.close(); Files.deleteIfExists(KEY); Files.deleteIfExists(KEY_DIRECTORY); }

    private static Path temporaryDirectory() {
        try { return Files.createTempDirectory("triage-settings-test-"); }
        catch (IOException e) { throw new IllegalStateException(e); }
    }
    private Map<String, Object> input(String key, long version) {
        Map<String, Object> body = new LinkedHashMap<>(Map.of("displayName", "本地测试", "protocol", "OPENAI_COMPATIBLE",
            "baseUrl", MODEL.url(), "model", "test-one", "temperature", 0.2, "timeoutSeconds", 4,
            "maxRounds", 4, "maxTokens", 1600, "version", version));
        if (key != null) body.put("apiKey", key);
        return body;
    }
    private <T> ResponseEntity<T> mutate(HttpMethod method, String path, Object body, Class<T> type) {
        HttpHeaders headers = new HttpHeaders(); headers.setContentType(MediaType.APPLICATION_JSON); headers.set("X-Triage-Settings", "1");
        return http.exchange(path, method, new HttpEntity<>(body, headers), type);
    }
    private View create() {
        var response = mutate(HttpMethod.POST, "/api/settings/providers", input("test-provider-secret", 0), View.class);
        assertThat(response.getStatusCode().value()).isEqualTo(201);
        return response.getBody();
    }
    private void activate(View p) {
        assertThat(mutate(HttpMethod.PUT, "/api/settings/selection", Map.of("mode", "MODEL", "providerId", p.id()), String.class)
            .getStatusCode().value()).isEqualTo(200);
    }
    private Run submit() { return http.postForObject("/api/runs", Map.of("question", "订单延迟", "service", "order-service", "windowMinutes", 15, "scenario", "NORMAL"), Run.class); }
    private Run finished(UUID id) {
        await().atMost(Duration.ofSeconds(8)).until(() -> http.getForObject("/api/runs/" + id, Run.class).status().terminal());
        return http.getForObject("/api/runs/" + id, Run.class);
    }

    @Test void createsEncryptedConfigurationWithoutReturningTheSecret(CapturedOutput output) throws Exception {
        View created = create();
        String state = http.getForObject("/api/settings", String.class);
        assertThat(state).contains("keyConfigured", "本地测试").doesNotContain("test-provider-secret", "encryptedKey");
        assertThat(created.keyConfigured()).isTrue();
        String encrypted = jdbc.queryForObject("SELECT encrypted_key FROM model_providers WHERE id=?", String.class, created.id().toString());
        assertThat(encrypted).startsWith("v1:").doesNotContain("test-provider-secret");
        assertThat(new CredentialCipher(KEY.toString()).decrypt(created.id(), encrypted)).isEqualTo("test-provider-secret");
        assertThat(output.getAll()).doesNotContain("test-provider-secret");
    }

    @Test void blankKeyPreservesSavedValueAndVersionConflictsAreRejected() {
        View p = create();
        var edit = input("", p.version()); edit.put("displayName", "新名称");
        var updated = mutate(HttpMethod.PUT, "/api/settings/providers/" + p.id(), edit, View.class);
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(updated.getBody().version()).isEqualTo(2);
        String encrypted = jdbc.queryForObject("SELECT encrypted_key FROM model_providers WHERE id=?", String.class, p.id().toString());
        assertThat(new CredentialCipher(KEY.toString()).decrypt(p.id(), encrypted)).isEqualTo("test-provider-secret");
        assertThat(mutate(HttpMethod.PUT, "/api/settings/providers/" + p.id(), edit, String.class).getStatusCode().value()).isEqualTo(409);
    }

    @Test void changingAddressRequiresAReplacementKey() {
        View p = create(); var edit = input("", p.version()); edit.put("baseUrl", "https://model.example/v1");
        assertThat(mutate(HttpMethod.PUT, "/api/settings/providers/" + p.id(), edit, String.class).getStatusCode().value()).isEqualTo(400);
        assertThat(repository.find(p.id()).orElseThrow().baseUrl()).isEqualTo(MODEL.url());
    }

    @Test void probeUsesOneShortRequestWithoutActivatingOrReturningProviderText() {
        View p = create();
        var response = mutate(HttpMethod.POST, "/api/settings/providers/" + p.id() + "/test?version=1", null, TestResult.class);
        assertThat(response.getBody().success()).isTrue();
        assertThat(response.getBody().message()).doesNotContain("mock-private-response");
        assertThat(registry.selection().mode()).isEqualTo("DEMO");
        assertThat(MODEL.requests).hasSize(1);
        assertThat(MODEL.requests.getFirst().has("thinking")).isFalse();
        assertThat(MODEL.requests.getFirst().path("max_tokens").asInt()).isEqualTo(32);
    }

    @Test void probeFailuresDoNotExposeRemoteErrorBodiesOrKeys(CapturedOutput output) {
        View p = create(); MODEL.status = 401;
        var result = mutate(HttpMethod.POST, "/api/settings/providers/" + p.id() + "/test?version=1", null, TestResult.class).getBody();
        assertThat(result.success()).isFalse();
        assertThat(result.message()).contains("401").doesNotContain("mock-private-response", "test-provider-secret");
        assertThat(output.getAll()).doesNotContain("mock-private-response", "test-provider-secret");
    }

    @Test void deepSeekOptionsAreOnlySentForTheDeepSeekProtocol() {
        var body = input("test-provider-secret", 0); body.put("protocol", "DEEPSEEK");
        View p = mutate(HttpMethod.POST, "/api/settings/providers", body, View.class).getBody();
        mutate(HttpMethod.POST, "/api/settings/providers/" + p.id() + "/test?version=1", null, TestResult.class);
        assertThat(MODEL.requests.getFirst().at("/thinking/type").asText()).isEqualTo("disabled");
    }

    @Test void changingActiveConfigurationDoesNotAlterAnAlreadySubmittedRun() throws Exception {
        View p = create(); activate(p);
        MODEL.entered = new CountDownLatch(1); MODEL.release = new CountDownLatch(1);
        Run queued = submit();
        assertThat(MODEL.entered.await(3, TimeUnit.SECONDS)).isTrue();
        var edit = input("", p.version()); edit.put("model", "test-two");
        assertThat(mutate(HttpMethod.PUT, "/api/settings/providers/" + p.id(), edit, View.class).getStatusCode().value()).isEqualTo(200);
        assertThat(router.snapshot().modelName()).isEqualTo("test-two");
        MODEL.release.countDown();
        Run old = finished(queued.id());
        assertThat(old.status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(old.modelExecution().configuredModel()).isEqualTo("test-one");
        assertThat(old.modelExecution().source().version()).isEqualTo(1);
        Run next = finished(submit().id());
        assertThat(next.modelExecution().configuredModel()).isEqualTo("test-two");
        assertThat(next.modelExecution().source().version()).isEqualTo(2);
    }

    @Test void selectionIsPersistedAndActiveProviderCannotBeDeleted() {
        View p = create(); activate(p);
        assertThat(new ProviderRepository(jdbc).selection().orElseThrow().providerId()).isEqualTo(p.id());
        assertThat(mutate(HttpMethod.DELETE, "/api/settings/providers/" + p.id() + "?version=1", null, String.class).getStatusCode().value()).isEqualTo(409);
        mutate(HttpMethod.PUT, "/api/settings/selection", Map.of("mode", "DEMO"), String.class);
        assertThat(mutate(HttpMethod.DELETE, "/api/settings/providers/" + p.id() + "?version=1", null, String.class).getStatusCode().value()).isEqualTo(204);
        assertThat(registry.list()).isEmpty();
        assertThat(router.snapshot().mode()).isEqualTo("DEMO");
    }

    @Test void missingCipherKeyDoesNotSilentlyReplaceIt() throws Exception {
        View p = create(); Path backup = KEY.resolveSibling("backup.key"); Files.move(KEY, backup);
        try {
            assertThat(mutate(HttpMethod.PUT, "/api/settings/providers/" + p.id(), input("new-test-key", 1), String.class).getStatusCode().value()).isEqualTo(503);
            assertThat(mutate(HttpMethod.POST, "/api/settings/providers", input("another-key", 0), String.class).getStatusCode().value()).isEqualTo(503);
            assertThat(KEY).doesNotExist();
        } finally { Files.move(backup, KEY); }
    }

    @Test void providerCountIsBounded() {
        for (int i = 0; i < 10; i++) create();
        assertThat(mutate(HttpMethod.POST, "/api/settings/providers", input("another-key", 0), String.class).getStatusCode().value()).isEqualTo(409);
    }

    @Test void crossOriginAndHeaderlessWritesAreRejected() {
        assertThat(http.postForEntity("/api/settings/providers", input("test-provider-secret", 0), String.class).getStatusCode().value()).isEqualTo(403);
        HttpHeaders headers = new HttpHeaders(); headers.set("X-Triage-Settings", "1"); headers.setOrigin("https://unrelated.example");
        assertThat(http.exchange("/api/settings/providers", HttpMethod.POST, new HttpEntity<>(input("test-provider-secret", 0), headers), String.class)
            .getStatusCode().value()).isEqualTo(403);
        assertThat(registry.list()).isEmpty();
    }

    private static class Stub implements AutoCloseable {
        final HttpServer server;
        final ExecutorService workers = Executors.newCachedThreadPool(Thread.ofPlatform().daemon(true).factory());
        final List<JsonNode> requests = new CopyOnWriteArrayList<>();
        volatile int status = 200;
        volatile CountDownLatch entered, release;
        Stub() {
            try {
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.setExecutor(workers);
                server.createContext("/chat/completions", exchange -> {
                    try (exchange) {
                        JsonNode request = JSON.readTree(exchange.getRequestBody()); requests.add(request);
                        if (entered != null) entered.countDown();
                        if (release != null) { try { release.await(3, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); } }
                        String content = request.has("tools") ? JSON.writeValueAsString(Map.of("status", "INSUFFICIENT_EVIDENCE", "diagnosis",
                            new Diagnosis(List.of(), List.of(), List.of("需要更多观测。"), "本地协议测试。"))) : "mock-private-response";
                        byte[] body = (status == 200 ? JSON.writeValueAsString(Map.of("id", "settings-test", "created", 1, "model", request.path("model").asText(),
                            "choices", List.of(Map.of("index", 0, "finish_reason", "stop", "message", Map.of("role", "assistant", "content", content)))))
                            : "mock-private-response").getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(status, body.length); exchange.getResponseBody().write(body);
                    } catch (IOException ignored) {}
                }); server.start();
            } catch (IOException e) { throw new IllegalStateException(e); }
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        public void close() { server.stop(0); workers.shutdownNow(); }
    }
}
