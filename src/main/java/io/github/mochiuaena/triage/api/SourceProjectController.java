package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.source.*;
import io.github.mochiuaena.triage.tools.ServiceRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/source-projects")
public class SourceProjectController {
    public record Registration(@NotBlank @Size(max=80) String name, @NotBlank String service, @NotBlank @Size(max=2048) String directory) {}
    public record Sharing(@Min(1) long revision, boolean enabled, UUID providerId, Long providerVersion, String selection) {}
    private final SourceProjectService projects;
    private final ServiceRegistry registry;
    public SourceProjectController(SourceProjectService projects, ServiceRegistry registry) { this.projects = projects; this.registry = registry; }
    @GetMapping public List<SourceModels.View> list() { return projects.list(); }
    @GetMapping("/services") public List<ServiceRegistry.View> services() { return registry.views(); }
    @GetMapping("/disclosure") public Map<String,Object> disclosure() { return projects.disclosure(); }
    @PostMapping public SourceModels.View create(@Valid @RequestBody Registration value) { return projects.create(value.name(), value.service(), value.directory()); }
    @PostMapping("/{id}/reindex") public SourceModels.View reindex(@PathVariable UUID id) { return projects.reindex(id); }
    @PostMapping("/{id}/sharing") public SourceModels.View share(@PathVariable UUID id, @Valid @RequestBody Sharing value) {
        return projects.sharing(id, value.revision(), value.enabled(), value.providerId(), value.providerVersion(), value.selection());
    }
    @GetMapping("/{id}/search") public List<SourceModels.Excerpt> search(@PathVariable UUID id, @RequestParam String q) { return projects.search(id, q); }
    @GetMapping("/{id}/excerpts/{symbol}") public SourceModels.Excerpt excerpt(@PathVariable UUID id, @PathVariable String symbol) { return projects.excerpt(id, symbol); }
}
