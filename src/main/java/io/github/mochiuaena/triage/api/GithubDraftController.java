package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.DraftExecutionModels;
import io.github.mochiuaena.triage.development.DraftExecutionService;
import io.github.mochiuaena.triage.development.OperationDraftModels;
import io.github.mochiuaena.triage.development.OperationDraftService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** Local operation drafts plus their confirmed executions and audit trail; writes need a dedicated credential. */
@RestController
@RequestMapping("/api/development")
public class GithubDraftController {
    public record CreateInput(@NotNull UUID basisReportId, @NotBlank String kind) {}
    public record UpdateInput(@NotNull Integer revision, String body, List<String> addLabels, List<String> removeLabels) {}
    public record ExecutionInput(@NotNull UUID previewId, @NotNull Boolean confirmed) {}
    private final OperationDraftService drafts;
    private final DraftExecutionService executions;
    public GithubDraftController(OperationDraftService drafts, DraftExecutionService executions) {
        this.drafts = drafts; this.executions = executions;
    }

    @PostMapping("/repositories/{id}/drafts")
    public ResponseEntity<OperationDraftModels.Draft> create(@PathVariable UUID id, @Valid @RequestBody CreateInput input) {
        var draft = drafts.create(id, input.kind(), input.basisReportId());
        return ResponseEntity.created(URI.create("/api/development/drafts/" + draft.id())).body(draft);
    }
    @GetMapping("/repositories/{id}/drafts") public List<OperationDraftModels.Draft> list(@PathVariable UUID id) { return drafts.list(id); }
    @GetMapping("/drafts/{id}") public OperationDraftModels.Draft get(@PathVariable UUID id) { return drafts.get(id); }
    @PutMapping("/drafts/{id}") public OperationDraftModels.Draft update(@PathVariable UUID id, @Valid @RequestBody UpdateInput input) {
        return drafts.update(id, input.revision(), input.body(), input.addLabels(), input.removeLabels());
    }
    @PostMapping("/drafts/{id}/discard") public OperationDraftModels.Draft discard(@PathVariable UUID id) { return drafts.discard(id); }
    @GetMapping("/drafts/write-option") public DraftExecutionModels.WriteOption writeOption() { return executions.writeOption(); }
    /** Target, content, impact, permission and version check; required before any confirmed execution. */
    @PostMapping("/drafts/{id}/execution-previews") public DraftExecutionModels.ExecutionPreview executionPreview(@PathVariable UUID id) {
        return executions.preview(id);
    }
    @PostMapping("/drafts/{id}/executions")
    public ResponseEntity<DraftExecutionModels.ExecutionRecord> execute(@PathVariable UUID id, @Valid @RequestBody ExecutionInput input) {
        var record = executions.execute(id, input.previewId(), input.confirmed());
        return ResponseEntity.created(URI.create("/api/development/draft-executions/" + record.id())).body(record);
    }
    @GetMapping("/repositories/{id}/draft-executions") public List<DraftExecutionModels.ExecutionRecord> executions(@PathVariable UUID id) {
        return executions.list(id);
    }
    @GetMapping("/draft-executions/{id}") public DraftExecutionModels.ExecutionRecord execution(@PathVariable UUID id) { return executions.get(id); }
}
