package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.RunService;
import io.github.mochiuaena.triage.execution.TriageEngine;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.ToolContext;
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
                            @Min(1) @Max(60) int windowMinutes, @NotNull Scenario scenario) {}

    private final RunService service;
    private final RunRepository repository;
    private final TriageEngine engine;
    public RunController(RunService service, RunRepository repository, TriageEngine engine) {
        this.service = service; this.repository = repository; this.engine = engine;
    }

    @PostMapping("/runs")
    public ResponseEntity<Run> create(@Valid @RequestBody CreateRun request) {
        Run run = service.submit(request.question().strip(),
            new ToolContext(request.service(), request.windowMinutes(), request.scenario(), Instant.now()));
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
        Map<String, Object> config = new java.util.LinkedHashMap<>(Map.of("mode", engine.mode(), "synthetic", true, "service", "order-service",
            "scenarios", Scenario.values(), "tools", List.of("search_runbooks", "read_service_metrics", "query_error_logs")));
        if (engine.modelName() != null) config.put("model", engine.modelName());
        return config;
    }
}
