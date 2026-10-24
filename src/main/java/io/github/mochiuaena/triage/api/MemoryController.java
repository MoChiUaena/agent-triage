package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.material.MaterialModels.ScopeOption;
import io.github.mochiuaena.triage.memory.*;
import io.github.mochiuaena.triage.memory.MemoryModels.*;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/memories")
public class MemoryController {
    public record Input(
            @NotBlank String scopeKind,
            @NotNull UUID scopeId,
            String service,
            @Min(0) long revision,
            @NotBlank @Size(max = 120) String topic,
            @NotBlank @Size(max = 4000) String content,
            @Size(max = 2000) String note,
            @Size(max = 3) List<Reference> references) {}

    public record Action(
            @NotBlank String scopeKind,
            @NotNull UUID scopeId,
            String service,
            @Min(1) long revision,
            boolean confirmed,
            String bodySha256) {}

    public record Candidate(
            @NotNull UUID conversationId, @NotEmpty @Size(max = 3) List<UUID> messageIds) {}

    private final MemoryService service;

    public MemoryController(MemoryService service) {
        this.service = service;
    }

    @GetMapping("/scopes")
    public List<ScopeOption> scopes() {
        return service.scopes();
    }

    @GetMapping
    public List<Summary> list(
            @RequestParam String scopeKind,
            @RequestParam UUID scopeId,
            @RequestParam(required = false) String service,
            @RequestParam(defaultValue = "CONFIRMED") String status,
            @RequestParam(defaultValue = "20") int limit) {
        return this.service.list(scopeKind, scopeId, service, status, limit);
    }

    @PostMapping(consumes = "application/json")
    public ResponseEntity<Version> create(@Valid @RequestBody Input in) {
        return created(
                service.create(
                        in.scopeKind(),
                        in.scopeId(),
                        in.service(),
                        in.topic(),
                        in.content(),
                        in.note(),
                        in.references()));
    }

    @PostMapping(value = "/conversation-candidates", consumes = "application/json")
    public ResponseEntity<Version> candidate(@Valid @RequestBody Candidate in) {
        return created(service.candidate(in.conversationId(), in.messageIds()));
    }

    private ResponseEntity<Version> created(Version v) {
        return ResponseEntity.created(
                        URI.create(
                                "/api/memories/"
                                        + v.id()
                                        + "?scopeKind="
                                        + v.scope().kind()
                                        + "&scopeId="
                                        + v.scope().id()))
                .body(v);
    }

    @GetMapping("/{id}")
    public Version current(
            @PathVariable UUID id,
            @RequestParam String scopeKind,
            @RequestParam UUID scopeId,
            @RequestParam(required = false) String service) {
        return this.service.current(id, scopeKind, scopeId, service);
    }

    @GetMapping("/{id}/history")
    public List<Summary> history(
            @PathVariable UUID id,
            @RequestParam String scopeKind,
            @RequestParam UUID scopeId,
            @RequestParam(required = false) String service) {
        return this.service.history(id, scopeKind, scopeId, service);
    }

    @GetMapping("/{id}/versions/{revision}")
    public Version version(
            @PathVariable UUID id,
            @PathVariable long revision,
            @RequestParam String scopeKind,
            @RequestParam UUID scopeId,
            @RequestParam(required = false) String service) {
        return this.service.version(id, scopeKind, scopeId, service, revision);
    }

    @PostMapping(value = "/{id}/edit", consumes = "application/json")
    public Version edit(@PathVariable UUID id, @Valid @RequestBody Input in) {
        return service.edit(
                id,
                in.scopeKind(),
                in.scopeId(),
                in.service(),
                in.revision(),
                in.topic(),
                in.content(),
                in.note(),
                in.references());
    }

    @PostMapping(value = "/{id}/{action:confirm|disable|delete}", consumes = "application/json")
    public Version action(
            @PathVariable UUID id, @PathVariable String action, @Valid @RequestBody Action in) {
        return service.transition(
                id,
                in.scopeKind(),
                in.scopeId(),
                in.service(),
                in.revision(),
                Map.of("confirm", "CONFIRMED", "disable", "DISABLED", "delete", "DELETED")
                        .get(action),
                in.confirmed(),
                in.bodySha256());
    }
}
