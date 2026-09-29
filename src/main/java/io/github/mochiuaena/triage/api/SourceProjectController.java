package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.source.*;
import io.github.mochiuaena.triage.tools.ServiceRegistry;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/source-projects")
public class SourceProjectController {
    public record Registration(@NotBlank @Size(max=80) String name, @NotBlank String service, @NotBlank @Size(max=2048) String directory) {}
    public record Sharing(@Min(1) long revision, boolean enabled, UUID providerId, Long providerVersion, String selection) {}
    public record Update(@Min(1) long revision, @NotBlank @Size(max=80) String name, @Size(max=64) String service, @NotBlank @Size(max=2048) String directory) {}
    public record Deletion(@NotNull UUID confirmId, @Min(1) long revision) {}
    private final SourceProjectService projects;
    private final ServiceRegistry registry;
    public SourceProjectController(SourceProjectService projects, ServiceRegistry registry) { this.projects = projects; this.registry = registry; }
    @GetMapping public List<SourceModels.View> list() { return projects.list(); }
    @GetMapping("/services") public List<ServiceRegistry.View> services() { return registry.views(); }
    @GetMapping("/disclosure") public Map<String,Object> disclosure() { return projects.disclosure(); }
    @PostMapping public SourceModels.View create(@Valid @RequestBody Registration value) { return projects.create(value.name(), value.service(), value.directory()); }
    @PutMapping("/{id}") public SourceModels.View update(@PathVariable UUID id, @Valid @RequestBody Update value) {
        return projects.update(id, value.revision(), value.name(), value.service(), value.directory());
    }
    @DeleteMapping("/{id}") public ResponseEntity<Void> delete(@PathVariable UUID id, @Valid @RequestBody Deletion value) {
        if (!id.equals(value.confirmId())) throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "删除确认与所选项目不一致。");
        projects.delete(id, value.revision());
        return ResponseEntity.noContent().build();
    }
    @PostMapping("/{id}/reindex") public SourceModels.View reindex(@PathVariable UUID id) { return projects.reindex(id); }
    @PostMapping("/{id}/sharing") public SourceModels.View share(@PathVariable UUID id, @Valid @RequestBody Sharing value) {
        return projects.sharing(id, value.revision(), value.enabled(), value.providerId(), value.providerVersion(), value.selection());
    }
    @GetMapping("/{id}/search") public List<SourceModels.Excerpt> search(@PathVariable UUID id, @RequestParam String q) { return projects.search(id, q); }
    @GetMapping("/{id}/excerpts/{symbol}") public SourceModels.Excerpt excerpt(@PathVariable UUID id, @PathVariable String symbol) { return projects.excerpt(id, symbol); }
    @GetMapping("/{id}/chains/{symbol}") public SourceModels.CallGraph chain(@PathVariable UUID id, @PathVariable String symbol) { return projects.chain(id, symbol); }
}
