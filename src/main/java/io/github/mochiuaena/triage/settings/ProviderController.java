package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.settings.ProviderConfig.*;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/settings")
public class ProviderController {
    public record SelectInput(String mode, UUID providerId) {}
    private final ProviderRegistry providers;
    private final ProviderProbe probe;
    public ProviderController(ProviderRegistry providers, ProviderProbe probe) { this.providers = providers; this.probe = probe; }

    @GetMapping public State state() { return providers.state(); }
    @PostMapping("/providers") public ResponseEntity<View> create(@Valid @RequestBody Input input) {
        View result = providers.create(input);
        return ResponseEntity.created(URI.create("/api/settings/providers/" + result.id())).body(result);
    }
    @PutMapping("/providers/{id}") public View update(@PathVariable UUID id, @Valid @RequestBody Input input) { return providers.update(id, input); }
    @DeleteMapping("/providers/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id, @RequestParam long version) {
        providers.delete(id, version);
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/providers/{id}/test") public TestResult test(@PathVariable UUID id, @RequestParam long version) { return probe.test(id, version); }
    @PutMapping("/selection") public Selection select(@RequestBody SelectInput input) { return providers.select(input.mode(), input.providerId()); }
}
