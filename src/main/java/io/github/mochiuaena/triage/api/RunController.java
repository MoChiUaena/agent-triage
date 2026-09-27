package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.RunService;
import io.github.mochiuaena.triage.execution.TriageEngine;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.ToolContext;
import io.github.mochiuaena.triage.tools.ObservationSource;
import io.github.mochiuaena.triage.tools.LiveObservationClient;
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
                            @NotNull @Pattern(regexp = "order-service") String service,
                            @Min(1) @Max(60) int windowMinutes, @NotNull Scenario scenario,
                            @Size(max = 240) String expectedSelection) {}

    private final RunService service;
    private final RunRepository repository;
    private final TriageEngine engine;
    private final ObservationSource observation;
    private final LiveObservationClient live;
    public RunController(RunService service, RunRepository repository, TriageEngine engine,
                         ObservationSource observation, LiveObservationClient live) {
        this.service = service; this.repository = repository; this.engine = engine;
        this.observation = observation; this.live = live;
    }

    @PostMapping("/runs")
    public ResponseEntity<Run> create(@Valid @RequestBody CreateRun request) {
        Scenario scenario;
        try { scenario = observation.synthetic() ? request.scenario() : live.scenario(); }
        catch (RuntimeException e) { throw new ResponseStatusException(SERVICE_UNAVAILABLE, "本地订单样例服务不可用，请先启动样例服务。"); }
        Run run = service.submit(request.question().strip(),
            new ToolContext(request.service(), request.windowMinutes(), scenario, Instant.now()),
            request.expectedSelection());
        return ResponseEntity.accepted().location(URI.create("/api/runs/" + run.id())).body(run);
    }

    @GetMapping("/runs")
    public List<RunSummary> list(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 50) throw new ResponseStatusException(BAD_REQUEST, "limit 必须为 1–50。");
        return repository.recent(limit).stream().map(run -> new RunSummary(run.id(), run.question(), run.scenario(),
            run.status(), run.createdAt(), run.toolCalls(), run.mode())).toList();
    }

    @GetMapping("/runs/{id}")
    public Run get(@PathVariable UUID id) { return repository.find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "执行记录不存在。")); }

    @GetMapping({"/demo", "/config"})
    public Map<String, Object> demo() {
        TriageEngine current = engine.snapshot();
        Map<String, Object> config = new java.util.LinkedHashMap<>(Map.of("mode", current.mode(), "synthetic", observation.synthetic(), "service", "order-service",
            "scenarios", Scenario.values(), "tools", List.of("search_runbooks", "read_service_metrics", "query_error_logs")));
        config.put("observationSource", observation.kind().name());
        if (!observation.synthetic()) {
            try { config.put("scenario", live.scenario()); config.put("observationAvailable", true); }
            catch (RuntimeException e) { config.put("observationAvailable", false); }
        }
        if (current.modelName() != null) config.put("model", current.modelName());
        if (current.source() != null) config.put("provider", current.source());
        config.put("selectionToken", current.selectionToken());
        return config;
    }
}
