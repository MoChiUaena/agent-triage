package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.material.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import static io.github.mochiuaena.triage.material.MaterialModels.*;

@RestController
@RequestMapping("/api/materials")
public class MaterialController {
    public record PreviewInput(@NotEmpty @Size(max=4) List<@NotNull @Valid Selection> references) {}
    public record Confirmation(boolean confirmed, boolean documentsConfirmed) {}
    private final MaterialService materials;
    private final SendPreviewService previews;
    public MaterialController(MaterialService materials,SendPreviewService previews) { this.materials=materials;this.previews=previews; }
    @GetMapping("/scopes") public List<ScopeOption> scopes() { return materials.scopes(); }
    @PostMapping("/resolve") public Material resolve(@Valid @RequestBody Selection input) { return materials.resolve(input); }
    @PostMapping("/previews") public ResponseEntity<Preview> preview(@Valid @RequestBody PreviewInput input) {
        var preview=previews.create(input.references());
        return ResponseEntity.created(URI.create("/api/materials/previews/"+preview.id())).body(preview);
    }
    @GetMapping("/previews/{id}") public Preview get(@PathVariable UUID id) { return previews.get(id); }
    @PostMapping("/previews/{id}/consume") public Consumed consume(@PathVariable UUID id,@RequestBody Confirmation input) { return previews.consume(id,input.confirmed(),input.documentsConfirmed()); }
}
