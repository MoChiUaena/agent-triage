package io.github.mochiuaena.triage.api;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import io.github.mochiuaena.triage.knowledge.*;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.*;

@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {
    public record ImportInput(UUID previewId) {}

    private final KnowledgeService knowledge;
    private final KnowledgeSearchService search;

    public KnowledgeController(KnowledgeService knowledge, KnowledgeSearchService search) {
        this.knowledge = knowledge;
        this.search = search;
    }

    @GetMapping("/search")
    public KnowledgeSearchService.SearchResult search(@RequestParam String scopeKind, @RequestParam UUID scopeId,
            @RequestParam String query, @RequestParam(required=false) String docType,
            @RequestParam(required=false) String service, @RequestParam(defaultValue="10") int limit) {
        return search.search(knowledge.scope(scopeKind, scopeId), query, docType, service, limit);
    }

    @GetMapping("/versions/{versionId}/chunks/{chunkId}")
    public KnowledgeSearchService.Citation chunk(@PathVariable UUID versionId, @PathVariable UUID chunkId,
            @RequestParam String scopeKind, @RequestParam UUID scopeId) {
        return search.chunk(knowledge.scope(scopeKind, scopeId), versionId, chunkId);
    }

    @PostMapping("/previews")
    public ResponseEntity<Preview> preview(@RequestBody Input input) {
        var p = knowledge.preview(input);
        return ResponseEntity.created(URI.create("/api/knowledge/previews/" + p.id())).body(p);
    }

    @GetMapping("/previews/{id}")
    public Preview preview(@PathVariable UUID id) {
        return knowledge.getPreview(id);
    }

    @PostMapping("/imports")
    public ResponseEntity<Job> start(@RequestBody ImportInput input) {
        if (input.previewId() == null) throw KnowledgeFiles.bad("请选择预览。");
        var j = knowledge.submit(input.previewId());
        return ResponseEntity.accepted()
                .location(URI.create("/api/knowledge/jobs/" + j.id()))
                .body(j);
    }

    @GetMapping("/sources")
    public List<Source> sources(@RequestParam String scopeKind, @RequestParam UUID scopeId) {
        return knowledge.sources(knowledge.scope(scopeKind, scopeId));
    }

    @GetMapping("/sources/{id}/versions")
    public List<VersionInfo> versions(
            @PathVariable UUID id, @RequestParam String scopeKind, @RequestParam UUID scopeId) {
        return knowledge.versions(id, knowledge.scope(scopeKind, scopeId));
    }

    @PostMapping("/sources/{id}")
    public Source action(@PathVariable UUID id, @RequestBody Action input) {
        return knowledge.action(id, input);
    }

    @GetMapping("/versions/{id}")
    public Version version(
            @PathVariable UUID id, @RequestParam String scopeKind, @RequestParam UUID scopeId) {
        return knowledge.version(id, knowledge.scope(scopeKind, scopeId));
    }

    @GetMapping("/versions/{id}/chunks")
    public List<Chunk> chunks(
            @PathVariable UUID id, @RequestParam String scopeKind, @RequestParam UUID scopeId) {
        return knowledge.chunks(id, knowledge.scope(scopeKind, scopeId));
    }

    @GetMapping("/jobs")
    public List<Job> jobs(@RequestParam String scopeKind, @RequestParam UUID scopeId) {
        return knowledge.jobs(knowledge.scope(scopeKind, scopeId));
    }

    @GetMapping("/jobs/{id}")
    public Job job(@PathVariable UUID id) {
        return knowledge.job(id);
    }

    @PostMapping("/jobs/{id}/cancel")
    public Job cancel(@PathVariable UUID id) {
        return knowledge.cancel(id);
    }
}
