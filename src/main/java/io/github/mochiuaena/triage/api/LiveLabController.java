package io.github.mochiuaena.triage.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import io.github.mochiuaena.triage.tools.LiveObservationClient;
import io.github.mochiuaena.triage.tools.ObservationSource;
import io.github.mochiuaena.triage.tools.ToolContext;
import io.github.mochiuaena.triage.tools.ServiceRegistry;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Explicit lab controls. Agent tools remain read-only and never generate traffic. */
@RestController
@RequestMapping("/api/live-lab")
@ConditionalOnProperty(name = "triage.observation.source", havingValue = "LIVE")
public class LiveLabController {
    public record TrafficRequest(Scenario scenario, int count, String service) {}
    private final ServiceRegistry registry;
    private final LiveObservationClient observations;
    private final CheckoutDemo checkoutDemo;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).build();

    public LiveLabController(ServiceRegistry registry, LiveObservationClient observations, CheckoutDemo checkoutDemo, ObjectMapper json) {
        this.registry = registry;
        this.observations = observations;
        this.checkoutDemo = checkoutDemo;
        this.json = json;
    }

    @PostMapping("/traffic")
    public synchronized Map<String, Object> traffic(@RequestBody TrafficRequest request, HttpServletRequest servletRequest) {
        if (!allowed(servletRequest)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅允许从本机页面生成样例流量。");
        ServiceRegistry.Target target = request.service() == null ? registry.defaultTarget() : registry.require(request.service());
        if (!target.labEnabled() && !checkoutDemo.enabledFor(target))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该服务未开放演示流量控制。");
        if (request.scenario() == null || request.count() < 1 || request.count() > 10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景无效或请求数超出 1–10。");
        if (checkoutDemo.enabledFor(target)) return checkoutTraffic(target, request);
        if (target.protocol() == ServiceRegistry.Protocol.DATABASE_V2) return databaseTraffic(target, request);
        if (request.scenario() != Scenario.NORMAL && request.scenario() != Scenario.DOWNSTREAM_TIMEOUT)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "该服务只支持正常与下游超时场景。");
        try {
            send(target, "POST", "/lab/reset", "{}", 200);
            send(target, "POST", "/lab/scenario", "{\"scenario\":\"" + request.scenario().name() + "\"}", 200);
            int expected = request.scenario() == Scenario.NORMAL ? 200 : 504;
            for (int i = 0; i < request.count(); i++)
                send(target, "GET", "/api/orders/" + UUID.randomUUID(), null, expected);
            var snapshot = observations.snapshot(new ToolContext(target.info().id(), Math.min(15, target.maxWindowMinutes()), request.scenario(), Instant.now(), target));
            return Map.of("scenario", snapshot.scenario(), "requestCount", snapshot.requestCount(),
                "timeoutCount", snapshot.timeoutCount(), "orderP95Ms", snapshot.orderP95Ms(), "synthetic", false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "样例服务请求失败，请检查服务是否运行。");
        }
    }

    private Map<String, Object> checkoutTraffic(ServiceRegistry.Target catalog, TrafficRequest request) {
        if (request.scenario() != Scenario.NORMAL && request.scenario() != Scenario.DOWNSTREAM_TIMEOUT)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "商品结算只支持正常与库存超时场景。");
        ServiceRegistry.Target order = checkoutDemo.inventoryControls();
        try {
            send(order, "POST", "/lab/reset", "{}", 200);
            send(order, "POST", "/lab/scenario", "{\"scenario\":\"" + request.scenario().name() + "\"}", 200);
            int inventoryTimeouts = 0;
            for (int i = 0; i < request.count(); i++)
                if (sendCheckout(catalog, request.scenario())) inventoryTimeouts++;
            return Map.of("scenario", Scenario.OBSERVED, "generatedRequests", request.count(),
                "inventoryTimeoutCount", inventoryTimeouts, "synthetic", false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "商品结算样例请求失败，请检查服务是否运行。");
        } finally {
            try { send(order, "POST", "/lab/scenario", "{\"scenario\":\"NORMAL\"}", 200); }
            catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "库存场景复位失败，请检查样例服务。");
            }
        }
    }

    private boolean sendCheckout(ServiceRegistry.Target catalog, Scenario scenario) throws IOException, InterruptedException {
        URI uri = URI.create(catalog.baseUrl().toString().replaceAll("/$", "") + "/api/checkouts/demo");
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
            HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "商品结算样例返回了意外状态，请重试。");
        var result = json.readTree(response.body());
        String expected = scenario == Scenario.DOWNSTREAM_TIMEOUT ? "timeout" : "available";
        if (!expected.equals(result.path("inventory").asText()) || !"assigned".equals(result.path("assignment").asText()))
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "商品结算结果与场景不符，请检查样例服务。");
        return scenario == Scenario.DOWNSTREAM_TIMEOUT;
    }

    private Map<String, Object> databaseTraffic(ServiceRegistry.Target target, TrafficRequest request) {
        if (!java.util.Set.of(Scenario.NORMAL, Scenario.DB_POOL_EXHAUSTED, Scenario.DB_POOL_RECOVERY, Scenario.DB_QUERY_LOCK_WAIT).contains(request.scenario()))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "数据库场景无效。");
        boolean held = false;
        try {
            send(target, "POST", "/lab/reset", "{}", 200);
            if (request.scenario() != Scenario.NORMAL) {
                held = true;
                String scenario = request.scenario() == Scenario.DB_QUERY_LOCK_WAIT ? "DB_QUERY_LOCK_WAIT" : "DB_POOL_EXHAUSTED";
                send(target, "POST", "/lab/scenario", "{\"scenario\":\"" + scenario + "\"}", 200);
            }
            if (request.scenario() == Scenario.DB_POOL_RECOVERY) {
                send(target, "GET", "/api/accounts/1", null, 503);
                var proof = observations.snapshot(new ToolContext(target.info().id(), Math.min(15, target.maxWindowMinutes()), Scenario.OBSERVED, Instant.now(), target));
                if (proof.databasePool().acquisitionTimeoutCount() < 1 || proof.databasePool().exhaustedSamples() < 1)
                    throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "恢复检查未观察到连接获取超时。");
                send(target, "POST", "/lab/scenario", "{\"scenario\":\"NORMAL\"}", 200); held = false;
                send(target, "POST", "/lab/reset", "{}", 200);
            }
            int expected = request.scenario() == Scenario.DB_POOL_EXHAUSTED ? 503 : request.scenario() == Scenario.DB_QUERY_LOCK_WAIT ? 500 : 200;
            for (int i = 0; i < request.count(); i++) send(target, "GET", "/api/accounts/1", null, expected);
            var snapshot = observations.snapshot(new ToolContext(target.info().id(), Math.min(15, target.maxWindowMinutes()), Scenario.OBSERVED, Instant.now(), target));
            return Map.of("scenario", Scenario.OBSERVED, "requestCount", snapshot.requestCount(), "requestP95Ms", snapshot.orderP95Ms(),
                "acquisitionTimeoutCount", snapshot.databasePool().acquisitionTimeoutCount(), "queryErrorCount", snapshot.databasePool().queryErrorCount(),
                "recoveryVerified", request.scenario() == Scenario.DB_POOL_RECOVERY, "synthetic", false);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "数据库样例请求失败，请检查服务是否运行。");
        } finally {
            if (held) {
                try { send(target, "POST", "/lab/scenario", "{\"scenario\":\"NORMAL\"}", 200); }
                catch (IOException | InterruptedException | RuntimeException e) {
                    if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                    // The fixture's ten-second lease also returns connections if cleanup cannot reach it.
                }
            }
        }
    }

    private void send(ServiceRegistry.Target target, String method, String path, String body, int expected) throws IOException, InterruptedException {
        URI uri = URI.create(target.baseUrl().toString().replaceAll("/$", "") + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2));
        if ("POST".equals(method)) builder.header("Content-Type", "application/json").header("X-Triage-Lab", "1").POST(HttpRequest.BodyPublishers.ofString(body));
        else builder.GET();
        HttpResponse<Void> response = http.send(builder.build(), HttpResponse.BodyHandlers.discarding());
        if (response.statusCode() != expected)
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "样例服务返回了意外状态，请重试。");
    }

    private boolean allowed(HttpServletRequest request) {
        try {
            if (!InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return false;
        } catch (Exception e) { return false; }
        if (!"1".equals(request.getHeader("X-Triage-Lab"))) return false;
        String site = request.getHeader("Sec-Fetch-Site");
        if (site != null && !"same-origin".equals(site) && !"none".equals(site)) return false;
        String origin = request.getHeader("Origin");
        return origin == null || origin.equals(request.getScheme() + "://" + request.getHeader("Host"));
    }
}
