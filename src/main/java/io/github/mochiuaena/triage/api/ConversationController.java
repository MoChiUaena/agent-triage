package io.github.mochiuaena.triage.api;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;

import io.github.mochiuaena.triage.conversation.*;
import io.github.mochiuaena.triage.material.MaterialModels.Selection;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/conversations")
public class ConversationController {
    public record Create(@NotNull @Valid Selection origin) {}

    public record Question(@Min(0) long revision, @NotBlank @Size(max = 4000) String question) {}

    public record PreviewInput(
            @Min(0) long revision,
            @NotBlank @Size(max = 4000) String question,
            @Size(max = 3) List<ChunkSelection> selectedChunks) {}

    public record BudgetInput(@Min(0) long revision, @NotNull @Valid Budget budget) {}

    public record Send(@NotNull UUID previewId, boolean confirmed, boolean documentsConfirmed) {}

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @GetMapping
    public List<Summary> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<Detail> create(@Valid @RequestBody Create input) {
        var result = service.create(input.origin());
        return ResponseEntity.created(URI.create("/api/conversations/" + result.id())).body(result);
    }

    @GetMapping("/{id}")
    public Detail get(@PathVariable UUID id) {
        return service.detail(id);
    }

    @PostMapping("/{id}/references")
    public Detail reference(@PathVariable UUID id, @Valid @RequestBody Question input) {
        return service.reference(id, input.revision(), input.question());
    }

    @PostMapping("/{id}/context")
    public Context context(@PathVariable UUID id, @Valid @RequestBody PreviewInput input) {
        return service.review(id, input.revision(), input.question(), input.selectedChunks());
    }

    @PostMapping("/{id}/budget")
    public Detail budget(@PathVariable UUID id, @Valid @RequestBody BudgetInput input) {
        return service.budget(id, input.revision(), input.budget());
    }

    @PostMapping("/{id}/previews")
    public ResponseEntity<Prepared> preview(
            @PathVariable UUID id, @Valid @RequestBody PreviewInput input) {
        var p = service.preview(id, input.revision(), input.question(), input.selectedChunks());
        return ResponseEntity.created(URI.create("/api/materials/previews/" + p.preview().id()))
                .body(p);
    }

    @PostMapping("/{id}/attempts")
    public ResponseEntity<Detail> send(@PathVariable UUID id, @Valid @RequestBody Send input) {
        return ResponseEntity.accepted()
                .body(
                        service.send(
                                id,
                                input.previewId(),
                                input.confirmed(),
                                input.documentsConfirmed()));
    }

    @PostMapping("/{id}/attempts/{attempt}/cancel")
    public Detail cancel(@PathVariable UUID id, @PathVariable UUID attempt) {
        return service.cancel(id, attempt);
    }
}
