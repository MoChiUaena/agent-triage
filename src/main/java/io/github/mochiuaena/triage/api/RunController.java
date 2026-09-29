package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.RunService;
import io.github.mochiuaena.triage.execution.TriageEngine;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.ToolContext;
import io.github.mochiuaena.triage.tools.ObservationSource;
import io.github.mochiuaena.triage.tools.LiveObservationClient;
import io.github.mochiuaena.triage.tools.ServiceRegistry;
import io.github.mochiuaena.triage.tools.ObservationFailure;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api")
public class RunController {
    public record CreateRun(@NotBlank @Size(max = 200) String question,
                            @NotBlank @Pattern(regexp = "[a-z][a-z0-9-]{0,63}") String service,
                            @Min(1) @Max(60) int windowMinutes, Scenario scenario,
                            @Size(max = 240) String expectedSelection) {}

    private final RunService service;
    private final RunRepository repository;
    private final TriageEngine engine;
    private final ObservationSource observation;
    private final LiveObservationClient live;
    private final ServiceRegistry registry;
    public RunController(RunService service, RunRepository repository, TriageEngine engine,
                         ObservationSource observation, LiveObservationClient live, ServiceRegistry registry) {
        this.service = service; this.repository = repository; this.engine = engine;
        this.observation = observation; this.live = live;
        this.registry = registry;
    }

    @PostMapping("/runs")
    public ResponseEntity<Run> create(@Valid @RequestBody CreateRun request) {
        ServiceRegistry.Target target = registry.require(request.service());
        if (request.windowMinutes() > target.maxWindowMinutes())
            throw new ResponseStatusException(BAD_REQUEST, "时间窗口超过所选服务允许的 " + target.maxWindowMinutes() + " 分钟。");
        if (observation.synthetic() && request.scenario() != Scenario.NORMAL && request.scenario() != Scenario.DOWNSTREAM_TIMEOUT)
            throw new ResponseStatusException(BAD_REQUEST, "请选择 NORMAL 或 DOWNSTREAM_TIMEOUT 演示场景。");
        Scenario scenario;
        try { scenario = observation.synthetic() ? request.scenario() : live.scenario(target); }
        catch (ObservationFailure e) { throw new ResponseStatusException(SERVICE_UNAVAILABLE, e.getMessage()); }
        catch (RuntimeException e) { throw new ResponseStatusException(SERVICE_UNAVAILABLE, "所选服务的观测接口不可用，请检查服务是否启动。"); }
        Run run = service.submit(request.question().strip(),
            new ToolContext(request.service(), request.windowMinutes(), scenario, Instant.now(), target),
            request.expectedSelection());
        return ResponseEntity.accepted().location(URI.create("/api/runs/" + run.id())).body(run);
    }

    @GetMapping("/runs")
    public List<RunSummary> list(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 50) throw new ResponseStatusException(BAD_REQUEST, "limit 必须为 1–50。");
        return repository.recent(limit).stream().map(run -> new RunSummary(run.id(), run.question(), run.scenario(),
            run.status(), run.createdAt(), run.toolCalls(), run.mode(), run.service(), run.serviceInfo())).toList();
    }

    @GetMapping("/runs/{id}")
    public Run get(@PathVariable UUID id) { return repository.find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "执行记录不存在。")); }

    @PostMapping("/runs/{id}/cancel")
    public Run cancel(@PathVariable UUID id) { return service.cancel(id); }

    @GetMapping({"/demo", "/config"})
    public Map<String, Object> demo(@RequestParam(required = false, name = "service") String serviceId) {
        ServiceRegistry.Target target = serviceId == null ? registry.defaultTarget() : registry.require(serviceId);
        TriageEngine current = engine.snapshot();
        Map<String, Object> config = new java.util.LinkedHashMap<>(Map.of("mode", current.mode(), "synthetic", observation.synthetic(), "service", target.info().id(),
            "scenarios", List.of(Scenario.NORMAL, Scenario.DOWNSTREAM_TIMEOUT), "tools", List.of("search_runbooks", "read_service_metrics", "query_error_logs")));
        config.put("services", registry.views());
        config.put("serviceInfo", target.info());
        config.put("protocol", target.protocol());
        config.put("maxWindowMinutes", target.maxWindowMinutes());
        config.put("labEnabled", target.labEnabled());
        config.put("observationSource", observation.kind().name());
        if (!observation.synthetic()) {
            try {
                Scenario scenario = live.scenario(target);
                int minutes = Math.min(15, target.maxWindowMinutes());
                var snapshot = live.snapshot(new ToolContext(target.info().id(), minutes, scenario, Instant.now(), target));
                config.put("scenario", scenario); config.put("observationAvailable", true);
                config.put("observationRequestCount", snapshot.requestCount());
                config.put("observationWindowMinutes", minutes);
                if (snapshot.requestCount() == 0) config.put("observationMessage", "最近 " + minutes + " 分钟未记录到请求，请先访问所选服务的业务接口。");
            }
            catch (ObservationFailure e) {
                config.put("observationAvailable", false); config.put("observationErrorCode", e.code());
                config.put("observationMessage", e.getMessage());
            }
            catch (RuntimeException e) { config.put("observationAvailable", false); }
        }
        if (current.modelName() != null) config.put("model", current.modelName());
        if (current.source() != null) config.put("provider", current.source());
        config.put("selectionToken", current.selectionToken());
        return config;
    }
}
