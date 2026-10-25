package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.GithubModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/development")
public class GithubRepositoryController {
    public record Input(@NotBlank @Size(max = 200) String repository) {}
    public record ActivityInput(@NotNull LocalDate fromDate, @NotNull LocalDate toDate) {}
    private final GithubRepositoryService service;
    private final RepositoryHealthService health;
    private final RepositoryActivityService activity;
    public GithubRepositoryController(GithubRepositoryService service, RepositoryHealthService health,
                                      RepositoryActivityService activity) {
        this.service = service; this.health = health; this.activity = activity;
    }
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
    /** Saved rule-based activity reports grouped by date; generation reads the snapshot and local stores only. */
    @PostMapping("/repositories/{id}/activity-reports")
    public ResponseEntity<ActivityModels.ActivityReport> generateActivity(@PathVariable UUID id, @Valid @RequestBody ActivityInput input) {
        var report = activity.generate(id, input.fromDate(), input.toDate());
        return ResponseEntity.created(URI.create("/api/development/activity-reports/" + report.id())).body(report);
    }
    @GetMapping("/repositories/{id}/activity-reports") public List<ActivityModels.ActivityReport> activityReports(@PathVariable UUID id) { return activity.list(id); }
    @GetMapping("/activity-reports/{id}") public ActivityModels.ActivityReport activityReport(@PathVariable UUID id) { return activity.get(id); }
    /** Local Markdown export of a saved report; content is generated deterministically from the stored payload. */
    @GetMapping(value = "/activity-reports/{id}/markdown", produces = "text/markdown;charset=UTF-8")
    public ResponseEntity<String> activityMarkdown(@PathVariable UUID id) {
        return ResponseEntity.ok()
            .header("Content-Disposition", "attachment; filename=activity-report-" + id + ".md")
            .body(activity.markdown(id));
    }
    @PostMapping("/repositories/{id}/refresh") public Snapshot refresh(@PathVariable UUID id) { return service.refresh(id); }
    @DeleteMapping("/repositories/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam UUID confirmId) {
        service.delete(id, confirmId); return ResponseEntity.noContent().build();
    }
}
