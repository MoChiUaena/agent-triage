package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.CiModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/development")
public class GithubCiController {
    public record Input(@Min(1) long runId, @Min(1) @Max(1000) int attempt, @Min(1) long jobId,
                        @NotNull @Pattern(regexp="[a-f0-9]{40}") String headSha, @Size(max=200_000) String logText) {}
    public record ModelAnalysisInput(UUID previewId, boolean confirmed) {}
    private final CiTriageService ci;
    private final CiModelService model;
    public GithubCiController(CiTriageService ci, CiModelService model) { this.ci = ci; this.model = model; }
    @GetMapping("/repositories/{repository}/ci/runs/{runId}") public Context inspect(@PathVariable UUID repository, @PathVariable long runId) { return ci.inspect(repository, runId); }
    @GetMapping("/repositories/{repository}/ci/reports") public List<Report> list(@PathVariable UUID repository) { return ci.list(repository); }
    @GetMapping("/ci/reports/{id}") public Report report(@PathVariable UUID id) { return ci.get(id); }
    @PostMapping("/repositories/{repository}/ci/reports") public ResponseEntity<Report> create(@PathVariable UUID repository, @Valid @RequestBody Input input) {
        Report report = ci.create(repository, input.runId(), input.attempt(), input.jobId(), input.headSha(), input.logText());
        return ResponseEntity.created(URI.create("/api/development/ci/reports/" + report.id())).body(report);
    }

    @GetMapping("/ci/model-option") public CiModelModels.ModelOption modelOption() { return model.option(); }
    /** Preview of the exact redacted scope one model call may receive for this report. */
    @PostMapping("/ci/reports/{id}/model/previews")
    public ResponseEntity<CiModelModels.Preview> modelPreview(@PathVariable UUID id) {
        var preview = model.preview(id);
        return ResponseEntity.created(URI.create("/api/development/ci/model-analyses/" + preview.id())).body(preview);
    }
    /** Saved separately from the rule report; a failed attempt records its state without a suggestion. */
    @PostMapping("/ci/reports/{id}/model/analyses")
    public ResponseEntity<CiModelModels.Advice> modelAnalysis(@PathVariable UUID id, @RequestBody ModelAnalysisInput input) {
        if (input == null || input.previewId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择模型预览。");
        var advice = model.analyze(id, input.previewId(), input.confirmed());
        return ResponseEntity.created(URI.create("/api/development/ci/model-analyses/" + advice.id())).body(advice);
    }
    @GetMapping("/ci/reports/{id}/model/analyses") public List<CiModelModels.Advice> modelAnalyses(@PathVariable UUID id) { return model.list(id); }
    @GetMapping("/ci/model-analyses/{id}") public CiModelModels.Advice modelAnalysis(@PathVariable UUID id) { return model.get(id); }
}
