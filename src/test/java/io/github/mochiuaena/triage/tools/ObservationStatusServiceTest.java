package io.github.mochiuaena.triage.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import static org.assertj.core.api.Assertions.*;

class ObservationStatusServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicInteger count = new AtomicInteger();
    private final AtomicInteger version = new AtomicInteger(1);
    private HttpServer server;
    private String origin;
    @BeforeEach void fixture() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/triage/observations", exchange -> {
            requests.incrementAndGet(); var params = new HashMap<String, String>();
            for (String value : exchange.getRequestURI().getRawQuery().split("&")) { var parts = value.split("=", 2); params.put(parts[0], URLDecoder.decode(parts[1], java.nio.charset.StandardCharsets.UTF_8)); }
            Instant end = Instant.parse(params.get("endTime"));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("schemaVersion", version.get()); body.put("service", "observed-service"); body.put("downstreamService", "stock-service");
            body.put("windowStart", end.minusSeconds(Integer.parseInt(params.get("windowMinutes")) * 60L)); body.put("windowEnd", end);
            body.put("requestCount", count.get()); body.put("timeoutCount", 0); body.put("recordedRequestCount", count.get());
            body.put("requestP95Ms", count.get() == 0 ? 0 : 50); body.put("downstreamP95Ms", count.get() == 0 ? 0 : 40);
            body.put("downstreamTimeoutRate", 0); body.put("baselineRequestP95Ms", null); body.put("errors", List.of()); body.put("synthetic", false);
            byte[] data = json.writeValueAsBytes(body); exchange.sendResponseHeaders(200, data.length);
            try (var out = exchange.getResponseBody()) { out.write(data); }
        }); server.start();
    }
    @AfterEach void stop() { server.stop(0); }
    private ObservationStatusService service(String kind, String target) {
        var source = new ObservationSource(kind, target);
        var registry = new ServiceRegistry(source, source.synthetic() ? List.of() : List.of(new ServiceRegistry.Config(
            "observed-service", "服务", "stock-service", "库存", target, ServiceRegistry.Protocol.OBSERVATIONS_V1, 5, false)));
        return new ObservationStatusService(registry, source, new LiveObservationClient(registry, json));
    }
    @Test void emptyWindowAndAvailableObservationsAreSeparateAndChecksAreCached() {
        try (var guard = service("LIVE", origin)) {
            var value = guard.checks().getFirst();
            assertThat(value.state()).isEqualTo("EMPTY"); assertThat(value.requestCount()).isZero();
            assertThat(value.windowMinutes()).isEqualTo(5);
            assertThat(value.toString()).doesNotContain(origin, "baseUrl");
            guard.checks(); assertThat(requests).hasValue(1);
        }
        count.set(3);
        try (var guard = service("LIVE", origin)) {
            assertThat(guard.checks().getFirst().state()).isEqualTo("AVAILABLE");
            assertThat(guard.checks().getFirst().requestCount()).isEqualTo(3);
        }
    }
    @Test void contractVersionAndConnectionFailuresReturnSafeCauses() throws Exception {
        version.set(99);
        try (var guard = service("LIVE", origin)) {
            assertThat(guard.checks().getFirst().errorCode()).isEqualTo("OBSERVATION_VERSION");
        }
        int unused; try (var socket = new java.net.ServerSocket(0)) { unused = socket.getLocalPort(); }
        try (var guard = service("LIVE", "http://127.0.0.1:" + unused)) {
            var check = guard.checks().getFirst(); assertThat(check.state()).isEqualTo("UNAVAILABLE");
            assertThat(check.errorCode()).isEqualTo("OBSERVATION_UNAVAILABLE");
        }
    }
    @Test void syntheticModeNeverContactsTheRegisteredOriginOrClaimsLiveRequests() {
        try (var guard = service("SYNTHETIC", origin)) {
            var value = guard.checks().getFirst();
            assertThat(value.state()).isEqualTo("SYNTHETIC"); assertThat(value.requestCount()).isNull();
            assertThat(value.responseMillis()).isNull(); assertThat(requests).hasValue(0);
        }
    }
}
