package io.github.mochiuaena.triage.api;

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
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(600)).build();

    public LiveLabController(ServiceRegistry registry, LiveObservationClient observations) {
        this.registry = registry;
        this.observations = observations;
    }

    @PostMapping("/traffic")
    public Map<String, Object> traffic(@RequestBody TrafficRequest request, HttpServletRequest servletRequest) {
        if (!allowed(servletRequest)) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "仅允许从本机页面生成样例流量。");
        ServiceRegistry.Target target = request.service() == null ? registry.defaultTarget() : registry.require(request.service());
        if (!target.labEnabled()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "该服务未开放演示流量控制。");
        if (request.scenario() == null || request.scenario() == Scenario.OBSERVED || request.count() < 1 || request.count() > 10)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "场景无效或请求数超出 1–10。");
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

    private void send(ServiceRegistry.Target target, String method, String path, String body, int expected) throws IOException, InterruptedException {
        URI uri = URI.create(target.baseUrl().toString().replaceAll("/$", "") + path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2));
        if ("POST".equals(method)) builder.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body));
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
