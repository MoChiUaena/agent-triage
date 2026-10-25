package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.GithubModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/development")
public class GithubRepositoryController {
    public record Input(@NotBlank @Size(max = 200) String repository) {}
    private final GithubRepositoryService service;
    private final RepositoryHealthService health;
    public GithubRepositoryController(GithubRepositoryService service, RepositoryHealthService health) { this.service = service; this.health = health; }
    @GetMapping("/settings") public Map<String,Object> settings() { return Map.of("authenticated", service.authenticated()); }
    @GetMapping("/repositories") public List<Registration> list() { return service.list(); }
    @PostMapping("/repositories") public ResponseEntity<Snapshot> create(@Valid @RequestBody Input input) {
        Snapshot result = service.create(input.repository());
        return ResponseEntity.created(URI.create("/api/development/repositories/" + result.registration().id() + "/health")).body(result);
    }
    @GetMapping("/repositories/{id}/health") public Snapshot health(@PathVariable UUID id) { return service.snapshot(id); }
    /** Saved rule-based health summaries; generation reads the snapshot and local stores only. */
    @PostMapping("/repositories/{id}/health-summaries") public ResponseEntity<RepositoryHealthModels.HealthReport> generateHealth(@PathVariable UUID id) {
        var report = health.generate(id);
        return ResponseEntity.created(URI.create("/api/development/health-summaries/" + report.id())).body(report);
    }
    @GetMapping("/repositories/{id}/health-summaries") public List<RepositoryHealthModels.HealthReport> healthSummaries(@PathVariable UUID id) { return health.list(id); }
    @GetMapping("/health-summaries/{id}") public RepositoryHealthModels.HealthReport healthSummary(@PathVariable UUID id) { return health.get(id); }
    @PostMapping("/repositories/{id}/refresh") public Snapshot refresh(@PathVariable UUID id) { return service.refresh(id); }
    @DeleteMapping("/repositories/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam UUID confirmId) {
        service.delete(id, confirmId); return ResponseEntity.noContent().build();
    }
}
