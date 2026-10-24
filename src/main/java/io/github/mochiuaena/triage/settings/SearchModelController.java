package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.settings.EmbeddingModels.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/search-models")
public class SearchModelController {
    private final SearchModelSettingsService service;

    public SearchModelController(SearchModelSettingsService service) { this.service = service; }

    @GetMapping public ConfigState state() { return service.state(); }

    @PutMapping("/{kind}") public View save(@PathVariable String kind, @Valid @RequestBody Input input) {
        return service.save(kind, input);
    }

    @DeleteMapping("/{kind}") public ResponseEntity<Void> delete(@PathVariable String kind, @RequestParam long version) {
        service.delete(kind, version);
        return ResponseEntity.noContent().build();
    }
}
