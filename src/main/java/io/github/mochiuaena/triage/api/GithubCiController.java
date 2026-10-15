package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.CiModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/development")
public class GithubCiController {
    public record Input(@Min(1) long runId, @Min(1) @Max(1000) int attempt, @Min(1) long jobId,
                        @NotNull @Pattern(regexp="[a-f0-9]{40}") String headSha, @Size(max=200_000) String logText) {}
    private final CiTriageService ci;
    public GithubCiController(CiTriageService ci) { this.ci = ci; }
    @GetMapping("/repositories/{repository}/ci/runs/{runId}") public Context inspect(@PathVariable UUID repository, @PathVariable long runId) { return ci.inspect(repository, runId); }
    @GetMapping("/repositories/{repository}/ci/reports") public List<Report> list(@PathVariable UUID repository) { return ci.list(repository); }
    @GetMapping("/ci/reports/{id}") public Report report(@PathVariable UUID id) { return ci.get(id); }
    @PostMapping("/repositories/{repository}/ci/reports") public ResponseEntity<Report> create(@PathVariable UUID repository, @Valid @RequestBody Input input) {
        Report report = ci.create(repository, input.runId(), input.attempt(), input.jobId(), input.headSha(), input.logText());
        return ResponseEntity.created(URI.create("/api/development/ci/reports/" + report.id())).body(report);
    }
}
