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
    public record EmbeddingJobInput(String scopeKind, UUID scopeId, UUID previewId, boolean documentsConfirmed) {}

    private final KnowledgeService knowledge;
    private final KnowledgeSearchService search;
    private final EmbeddingIndexService embeddings;

    public KnowledgeController(KnowledgeService knowledge, KnowledgeSearchService search, EmbeddingIndexService embeddings) {
        this.knowledge = knowledge;
        this.search = search;
        this.embeddings = embeddings;
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

    @PostMapping("/embeddings/preview")
    public ResponseEntity<EmbeddingModels.IndexPreview> embeddingPreview(@RequestBody Input input) {
        if (input == null) throw KnowledgeFiles.bad("请选择已登记项目或仓库。");
        var preview = embeddings.preview(knowledge.scope(input.scopeKind(), input.scopeId()));
        return ResponseEntity.created(URI.create("/api/knowledge/embeddings/previews/" + preview.id())).body(preview);
    }

    @PostMapping("/embeddings/jobs")
    public ResponseEntity<EmbeddingModels.IndexJob> startEmbedding(@RequestBody EmbeddingJobInput input) {
        if (input == null || input.previewId() == null) throw KnowledgeFiles.bad("请选择向量索引预览。");
        var job = embeddings.submit(knowledge.scope(input.scopeKind(), input.scopeId()), input.previewId(),
            input.documentsConfirmed());
        return ResponseEntity.accepted().location(URI.create("/api/knowledge/embeddings/jobs/" + job.id())).body(job);
    }

    @GetMapping("/embeddings/jobs")
    public List<EmbeddingModels.IndexJob> embeddingJobs(@RequestParam String scopeKind, @RequestParam UUID scopeId) {
        return embeddings.jobs(knowledge.scope(scopeKind, scopeId));
    }

    @GetMapping("/embeddings/jobs/{id}")
    public EmbeddingModels.IndexJob embeddingJob(@PathVariable UUID id) {
        return embeddings.job(id);
    }

    @PostMapping("/embeddings/jobs/{id}/cancel")
    public EmbeddingModels.IndexJob cancelEmbedding(@PathVariable UUID id) {
        return embeddings.cancel(id);
    }
}
