package io.github.mochiuaena.triage.api;

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

/** Local operation drafts. All endpoints read and write local stores only; publishing is not implemented (W2). */
@RestController
@RequestMapping("/api/development")
public class GithubDraftController {
    public record CreateInput(@NotNull UUID basisReportId, @NotBlank String kind) {}
    public record UpdateInput(@NotNull Integer revision, String body, List<String> addLabels, List<String> removeLabels) {}
    private final OperationDraftService drafts;
    public GithubDraftController(OperationDraftService drafts) { this.drafts = drafts; }

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
}
