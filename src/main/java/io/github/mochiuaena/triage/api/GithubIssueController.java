package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.IssueTriageService;
import io.github.mochiuaena.triage.development.IssueModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/development")
public class GithubIssueController {
    public record PreviewInput(@Min(1) @Max(Integer.MAX_VALUE) long number, @Size(max=3) List<@NotBlank @Size(max=400) String> codePaths) {}
    public record Input(@NotNull UUID previewId, boolean useModel) {}
    private final IssueTriageService triage;
    public GithubIssueController(IssueTriageService triage) { this.triage = triage; }
    @PostMapping("/repositories/{repository}/issue/previews") public ResponseEntity<Preview> preview(@PathVariable UUID repository, @Valid @RequestBody PreviewInput input) {
        return ResponseEntity.status(201).body(triage.preview(repository, input.number(), input.codePaths()));
    }
    @PostMapping("/repositories/{repository}/issue/reports") public ResponseEntity<Report> create(@PathVariable UUID repository, @Valid @RequestBody Input input) {
        Report report = triage.create(repository, input.previewId(), input.useModel());
        return ResponseEntity.created(URI.create("/api/development/issue/reports/" + report.id())).body(report);
    }
    @GetMapping("/repositories/{repository}/issue/reports") public List<Report> list(@PathVariable UUID repository) { return triage.list(repository); }
    @GetMapping("/issue/reports/{id}") public Report report(@PathVariable UUID id) { return triage.get(id); }
}
