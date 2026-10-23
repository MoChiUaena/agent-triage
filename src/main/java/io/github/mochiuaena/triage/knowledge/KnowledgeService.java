package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import static org.springframework.http.HttpStatus.*;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.material.MaterialService;
import io.github.mochiuaena.triage.source.SourceProjectRepository;

import jakarta.annotation.PreDestroy;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

@Service
public class KnowledgeService {
    private final KnowledgeRepository store;
    private final SourceProjectRepository projects;
    private final GithubRepositoryStore repositories;
    private final KnowledgeFiles files;
    private final GithubKnowledgeReader github;
    private final Clock clock;
    private final Map<UUID, Preview> previews = new LinkedHashMap<>();
    private final Semaphore readers = new Semaphore(2);
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(4),
                    r -> {
                        var t = new Thread(r, "knowledge-import");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    @Autowired
    public KnowledgeService(
            KnowledgeRepository store,
            SourceProjectRepository projects,
            GithubRepositoryStore repositories,
            KnowledgeFiles files,
            GithubKnowledgeReader github) {
        this(store, projects, repositories, files, github, Clock.systemUTC());
    }

    KnowledgeService(
            KnowledgeRepository store,
            SourceProjectRepository projects,
            GithubRepositoryStore repositories,
            KnowledgeFiles files,
            GithubKnowledgeReader github,
            Clock clock) {
        this.store = store;
        this.projects = projects;
        this.repositories = repositories;
        this.files = files;
        this.github = github;
        this.clock = clock;
        store.interruptPending();
    }

    public Scope scope(String kind, UUID sid) {
        if (sid == null || !Set.of("PROJECT", "REPOSITORY").contains(kind == null ? "" : kind))
            throw KnowledgeFiles.bad("请选择已登记项目或仓库。");
        return new Scope(kind, sid);
    }

    private String validate(Scope scope) {
        if (scope.kind().equals("PROJECT")) {
            var p = projects.require(scope.id());
            return p.revision() + ":" + p.root();
        }
        var repo = registration(scope.id());
        return repo.owner() + "/" + repo.name();
    }

    private GithubModels.Registration registration(UUID sid) {
        return repositories.list().stream()
                .filter(r -> r.id().equals(sid))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "登记的仓库不存在。"));
    }

    private Read read(Scope scope, String root, String commit, BooleanSupplier cancelled) {
        return read(scope, root, commit, cancelled, count -> {});
    }

    private Read read(
            Scope scope,
            String root,
            String commit,
            BooleanSupplier cancelled,
            java.util.function.IntConsumer progress) {
        if (scope.kind().equals("PROJECT")) return files.read(root, cancelled, progress);
        var repo = registration(scope.id());
        return github.read(
                new GithubModels.Key(repo.owner(), repo.name()), commit, cancelled, progress);
    }

    public Preview preview(Input input) {
        if (input == null) throw KnowledgeFiles.bad("请选择文档来源。");
        Scope scope = scope(input.scopeKind(), input.scopeId());
        String stamp = validate(scope);
        String root, origin, commit = null;
        if (scope.kind().equals("PROJECT")) {
            if (input.commitSha() != null && !input.commitSha().isBlank())
                throw KnowledgeFiles.bad("本机目录不使用 GitHub SHA。");
            origin = "LOCAL";
            root =
                    files.canonical(
                                    input.directory() == null || input.directory().isBlank()
                                            ? projects.require(scope.id()).root()
                                            : input.directory())
                            .toString();
        } else {
            if (input.directory() != null && !input.directory().isBlank())
                throw KnowledgeFiles.bad("仓库来源不能指定本机目录。");
            origin = "GITHUB";
            root = stamp;
            commit = input.commitSha();
            if (commit == null || !commit.matches("[a-fA-F0-9]{40}"))
                throw KnowledgeFiles.bad("请输入完整 40 位提交 SHA。");
            commit = commit.toLowerCase(Locale.ROOT);
        }
        Source existing = store.byLocator(scope, origin, root).orElse(null);
        if (input.sourceId() != null
                && (existing == null
                        || !input.sourceId().equals(existing.id())
                        || input.revision() == null
                        || input.revision() != existing.revision())) throw conflict();
        UUID sid = existing == null ? UUID.randomUUID() : existing.id();
        long revision = existing == null ? 0 : existing.revision();
        synchronized (previews) {
            evict();
            if (previews.size() >= 10) throw capacity();
        }
        if (!readers.tryAcquire()) throw capacity();
        try {
            Read read = read(scope, root, commit, () -> false);
            if (!stamp.equals(validate(scope))) throw conflict();
            Instant now = clock.instant();
            Preview preview =
                    manifest(
                            UUID.randomUUID(),
                            scope,
                            origin,
                            stamp,
                            sid,
                            revision,
                            read,
                            now,
                            now.plus(Duration.ofMinutes(10)));
            synchronized (previews) {
                evict();
                if (previews.size() >= 10) throw capacity();
                previews.put(preview.id(), preview);
            }
            return preview;
        } finally {
            readers.release();
        }
    }

    private Preview manifest(
            UUID pid,
            Scope scope,
            String origin,
            String stamp,
            UUID sid,
            long revision,
            Read read,
            Instant at,
            Instant expiry) {
        var manifest =
                read.documents().stream()
                        .map(
                                d ->
                                        new FileManifest(
                                                d.path(),
                                                d.sha256(),
                                                d.bytes(),
                                                d.title(),
                                                d.text()
                                                        .substring(
                                                                0,
                                                                Math.min(240, d.text().length()))))
                        .toList();
        StringBuilder signature =
                new StringBuilder(read.root())
                        .append('\n')
                        .append(read.commitSha())
                        .append('\n')
                        .append(read.complete())
                        .append('\n')
                        .append(read.visited());
        manifest.forEach(
                f ->
                        signature
                                .append('\n')
                                .append(f.path())
                                .append(':')
                                .append(f.sha256())
                                .append(':')
                                .append(f.bytes()));
        read.exclusions()
                .forEach(
                        e ->
                                signature
                                        .append('\n')
                                        .append(e.path())
                                        .append(':')
                                        .append(e.reason()));
        return new Preview(
                pid,
                scope,
                origin,
                read.root(),
                read.commitSha(),
                stamp,
                sid,
                revision,
                manifest,
                read.exclusions(),
                read.visited(),
                read.documents().stream().mapToInt(Document::bytes).sum(),
                read.documents().stream().mapToInt(d -> KnowledgeFiles.chunkCount(d.text())).sum(),
                read.complete(),
                MaterialService.sha(signature.toString()),
                at,
                expiry,
                limits());
    }

    public Preview getPreview(UUID pid) {
        synchronized (previews) {
            evict();
            var p = previews.get(pid);
            if (p == null) throw new ResponseStatusException(GONE, "预览已过期或不存在，请重新预览。");
            validate(p.scope());
            return p;
        }
    }

    public Job submit(UUID pid) {
        synchronized (previews) {
            var p = getPreview(pid);
            if (!p.scopeStamp().equals(validate(p.scope()))) throw conflict();
            UUID jid = UUID.randomUUID();
            Job job = store.createJob(jid, p);
            try {
                worker.execute(() -> run(jid, p));
            } catch (RejectedExecutionException e) {
                store.finish(jid, "FAILED", "导入队列已满，请稍后重试。");
                throw capacity();
            }
            previews.remove(pid);
            return job;
        }
    }

    private void run(UUID jid, Preview preview) {
        if (!store.start(jid)) return;
        try {
            KnowledgeFiles.check(() -> store.cancelled(jid));
            if (!preview.scopeStamp().equals(validate(preview.scope()))) throw conflict();
            Read read =
                    read(
                            preview.scope(),
                            preview.root(),
                            preview.commitSha(),
                            () -> store.cancelled(jid),
                            count -> store.progress(jid, count));
            var current =
                    manifest(
                            preview.id(),
                            preview.scope(),
                            preview.origin(),
                            preview.scopeStamp(),
                            preview.sourceId(),
                            preview.revision(),
                            read,
                            preview.readAt(),
                            preview.expiresAt());
            if (!current.manifestHash().equals(preview.manifestHash())
                    || !preview.scopeStamp().equals(validate(preview.scope())))
                throw new ResponseStatusException(CONFLICT, "文档或范围在预览后已变化，请重新预览。");
            store.progress(jid, read.documents().size());
            KnowledgeFiles.check(() -> store.cancelled(jid));
            store.activate(jid, preview, read.documents());
        } catch (CancellationException e) {
            store.finish(
                    jid,
                    Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "CANCELLED",
                    "导入已取消或中断，原有效版本保持不变。");
        } catch (Exception e) {
            store.finish(
                    jid,
                    Thread.currentThread().isInterrupted() ? "INTERRUPTED" : "FAILED",
                    e instanceof ResponseStatusException r && r.getStatusCode().value() == 409
                            ? "预览或来源已变化，请重新预览。"
                            : "导入失败；未切换有效版本。请核对目录、固定 SHA、读取权限与上限后重新预览。");
        }
    }

    public List<Source> sources(Scope scope) {
        validate(scope);
        return store.sources(scope);
    }

    public void validateSearchScope(Scope scope, String service) {
        scope(scope.kind(), scope.id());
        validate(scope);
        if (service != null && (!scope.kind().equals("PROJECT")
                || !service.equals(projects.require(scope.id()).service())))
            throw KnowledgeFiles.bad("服务未绑定到所选项目；仓库知识不推断服务范围。");
    }

    public List<VersionInfo> versions(UUID sid, Scope scope) {
        validate(scope);
        assertScope(store.require(sid).scope(), scope);
        return store.versions(sid);
    }

    public Version version(UUID vid, Scope scope) {
        var v = store.version(vid);
        assertScope(v.scope(), scope);
        return v;
    }

    public Version materialVersion(UUID vid, Scope scope) {
        validate(scope);
        var v = version(vid, scope);
        if (!store.require(v.sourceId()).state().equals("ACTIVE"))
            throw new ResponseStatusException(CONFLICT, "知识来源已解绑或删除，仅可阅读历史原文。");
        return v;
    }

    public List<Chunk> chunks(UUID vid, Scope scope) {
        version(vid, scope);
        return store.chunks(vid);
    }

    public Source action(UUID sid, Action action) {
        Scope scope = scope(action.scopeKind(), action.scopeId());
        validate(scope);
        assertScope(store.require(sid).scope(), scope);
        if (!action.confirmed()
                || !Set.of("UNBIND", "DELETE")
                        .contains(action.action() == null ? "" : action.action()))
            throw KnowledgeFiles.bad("请明确确认解绑或删除的影响。");
        return store.action(sid, scope, action.revision(), action.action());
    }

    public List<Job> jobs(Scope scope) {
        validate(scope);
        return store.jobs(scope);
    }

    public Job job(UUID jid) {
        Job j = store.job(jid);
        validate(j.scope());
        return j;
    }

    public Job cancel(UUID jid) {
        job(jid);
        return store.cancel(jid);
    }

    public static void assertScope(Scope actual, Scope selected) {
        if (!actual.equals(selected)) throw new ResponseStatusException(CONFLICT, "知识版本不属于所选范围。");
    }

    private void evict() {
        previews.values().removeIf(p -> !clock.instant().isBefore(p.expiresAt()));
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(CONFLICT, "范围或知识来源已变化，请重新预览。");
    }

    private static ResponseStatusException capacity() {
        return new ResponseStatusException(
                TOO_MANY_REQUESTS, "知识预览或导入已达容量上限，请稍后重试（最多 10 个预览、2 个并发读取、1 个导入及 4 个排队任务）。");
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }
}
