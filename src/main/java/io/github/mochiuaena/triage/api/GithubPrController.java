package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.PrModels.*;
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
public class GithubPrController {
    public record Input(@Min(1) @Max(Integer.MAX_VALUE) long number,
                        @NotNull @Pattern(regexp="[a-f0-9]{40}") String baseSha,
                        @NotNull @Pattern(regexp="[a-f0-9]{40}") String headSha) {}
    public record ModelAnalysisInput(UUID previewId, boolean confirmed, boolean codeConfirmed) {}
    private final PrReviewService review;
    private final PrModelService model;
    public GithubPrController(PrReviewService review, PrModelService model) { this.review = review; this.model = model; }
    @GetMapping("/repositories/{repository}/pr/pulls/{number}") public Context inspect(@PathVariable UUID repository, @PathVariable long number) { return review.inspect(repository, number); }
    @GetMapping("/repositories/{repository}/pr/reports") public List<Report> list(@PathVariable UUID repository) { return review.list(repository); }
    @GetMapping("/pr/reports/{id}") public Report report(@PathVariable UUID id) { return review.get(id); }
    @PostMapping("/repositories/{repository}/pr/reports") public ResponseEntity<Report> create(@PathVariable UUID repository, @Valid @RequestBody Input input) {
        Report report = review.create(repository, input.number(), input.baseSha(), input.headSha());
        return ResponseEntity.created(URI.create("/api/development/pr/reports/" + report.id())).body(report);
    }

    @GetMapping("/pr/model-option") public PrModelModels.ModelOption modelOption() { return model.option(); }
    /** Preview of the exact redacted diff scope one model call may receive; PR updates invalidate it. */
    @PostMapping("/pr/reports/{id}/model/previews")
    public ResponseEntity<PrModelModels.Preview> modelPreview(@PathVariable UUID id) {
        var preview = model.preview(id);
        return ResponseEntity.created(URI.create("/api/development/pr/model-analyses/" + preview.id())).body(preview);
    }
    /** Saved separately from the rule report; patches are source code and need codeConfirmed. */
    @PostMapping("/pr/reports/{id}/model/analyses")
    public ResponseEntity<PrModelModels.Advice> modelAnalysis(@PathVariable UUID id, @RequestBody ModelAnalysisInput input) {
        if (input == null || input.previewId() == null)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择模型预览。");
        var advice = model.analyze(id, input.previewId(), input.confirmed(), input.codeConfirmed());
        return ResponseEntity.created(URI.create("/api/development/pr/model-analyses/" + advice.id())).body(advice);
    }
    @GetMapping("/pr/reports/{id}/model/analyses") public List<PrModelModels.Advice> modelAnalyses(@PathVariable UUID id) { return model.list(id); }
    @GetMapping("/pr/model-analyses/{id}") public PrModelModels.Advice modelAnalysis(@PathVariable UUID id) { return model.get(id); }
}
