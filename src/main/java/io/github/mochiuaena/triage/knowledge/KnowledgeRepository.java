package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.material.MaterialService;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
@DependsOnDatabaseInitialization
public class KnowledgeRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public KnowledgeRepository(
            JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    private static String id(UUID id) {
        return id.toString();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static UUID uuid(ResultSet r, String key) throws SQLException {
        return UUID.fromString(r.getString(key));
    }

    private static Instant instant(ResultSet r, String key) throws SQLException {
        return r.getObject(key, OffsetDateTime.class).toInstant();
    }

    private static Scope scope(ResultSet r) throws SQLException {
        return new Scope(r.getString("scope_kind"), uuid(r, "scope_id"));
    }

    private Source source(ResultSet r, int n) throws SQLException {
        UUID sid = uuid(r, "id");
        return new Source(
                sid,
                scope(r),
                r.getString("origin"),
                r.getString("root_path"),
                r.getString("commit_sha"),
                r.getLong("revision"),
                r.getString("state"),
                r.getString("manifest_hash"),
                instant(r, "imported_at"),
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM knowledge_active_documents WHERE source_id=?",
                        Integer.class,
                        id(sid)),
                decode(r.getString("coverage"), Preview.class));
    }

    public Optional<Source> find(UUID sid) {
        return jdbc
                .query("SELECT * FROM knowledge_sources WHERE id=?", this::source, id(sid))
                .stream()
                .findFirst();
    }

    public Source require(UUID sid) {
        return find(sid).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "知识来源不存在。"));
    }

    public Optional<Source> byLocator(Scope scope, String origin, String root) {
        return jdbc
                .query(
                        "SELECT * FROM knowledge_sources WHERE scope_kind=? AND scope_id=? AND"
                                + " origin=? AND locator_hash=?",
                        this::source,
                        scope.kind(),
                        id(scope.id()),
                        origin,
                        MaterialService.sha(root))
                .stream()
                .findFirst();
    }

    public List<Source> sources(Scope scope) {
        return jdbc.query(
                "SELECT * FROM knowledge_sources WHERE scope_kind=? AND scope_id=? ORDER BY"
                        + " imported_at DESC LIMIT 100",
                this::source,
                scope.kind(),
                id(scope.id()));
    }

    public List<VersionInfo> versions(UUID sid) {
        return jdbc.query(
                "SELECT v.*,CASE WHEN a.version_id IS NULL THEN FALSE ELSE TRUE END AS active FROM"
                    + " knowledge_versions v LEFT JOIN knowledge_active_documents a ON"
                    + " a.version_id=v.id WHERE v.source_id=? ORDER BY v.imported_at DESC,v.path"
                    + " LIMIT 500",
                (r, n) ->
                        new VersionInfo(
                                uuid(r, "id"),
                                uuid(r, "source_id"),
                                r.getString("path"),
                                r.getString("content_hash"),
                                r.getString("title"),
                                r.getString("commit_sha"),
                                instant(r, "imported_at"),
                                r.getBoolean("active")),
                id(sid));
    }

    public boolean activeVersion(UUID vid) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM knowledge_active_documents WHERE version_id=?",Integer.class,id(vid))==1;
    }

    public Version version(UUID vid) {
        return jdbc
                .query(
                        "SELECT * FROM knowledge_versions WHERE id=?",
                        (r, n) ->
                                new Version(
                                        uuid(r, "id"),
                                        uuid(r, "source_id"),
                                        scope(r),
                                        r.getString("origin"),
                                        r.getString("root_path"),
                                        r.getString("commit_sha"),
                                        r.getString("path"),
                                        r.getString("content_hash"),
                                        r.getString("title"),
                                        r.getString("document_text"),
                                        instant(r, "imported_at")),
                        id(vid))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "知识原文版本不存在。"));
    }

    public List<Chunk> chunks(UUID vid) {
        return jdbc.query(
                "SELECT * FROM knowledge_chunks WHERE version_id=? ORDER BY ordinal",
                (r, n) ->
                        new Chunk(
                                uuid(r, "id"),
                                uuid(r, "version_id"),
                                r.getInt("ordinal"),
                                r.getInt("start_line"),
                                r.getInt("end_line"),
                                r.getInt("start_offset"),
                                r.getInt("end_offset"),
                                r.getString("content_hash"),
                                r.getString("chunk_text")),
                id(vid));
    }

    public record SearchChunk(Version version, Chunk chunk) {}

    private static final String CURRENT_CHUNKS =
            " FROM knowledge_chunks c JOIN knowledge_versions v ON v.id=c.version_id"
            + " JOIN knowledge_active_documents a ON a.version_id=v.id AND a.source_id=v.source_id AND a.path=v.path"
            + " JOIN knowledge_sources s ON s.id=v.source_id AND s.scope_kind=v.scope_kind AND s.scope_id=v.scope_id"
            + " WHERE s.state='ACTIVE' AND v.scope_kind=? AND v.scope_id=?";

    private static String typeFilter(String type) {
        return switch (type) {
            case "MARKDOWN" -> " AND LOWER(v.path) LIKE '%.md'";
            case "TEXT" -> " AND LOWER(v.path) LIKE '%.txt'";
            default -> "";
        };
    }

    public long searchCount(Scope scope, String type) {
        return jdbc.queryForObject("SELECT COUNT(*)" + CURRENT_CHUNKS + typeFilter(type),
                Long.class, scope.kind(), id(scope.id()));
    }

    public List<SearchChunk> searchChunks(Scope scope, String type, int limit) {
        return jdbc.query("SELECT v.id AS vid,v.source_id,v.scope_kind,v.scope_id,v.origin,v.root_path,v.commit_sha,v.path,v.content_hash AS version_hash,v.title,v.imported_at,c.*"
                + CURRENT_CHUNKS + typeFilter(type) + " ORDER BY v.source_id,v.path,v.id,c.ordinal,c.id LIMIT ?",
            (r, n) -> new SearchChunk(new Version(uuid(r, "vid"), uuid(r, "source_id"), scope(r),
                r.getString("origin"), r.getString("root_path"), r.getString("commit_sha"), r.getString("path"),
                r.getString("version_hash"), r.getString("title"), null, instant(r, "imported_at")), readChunk(r)),
            scope.kind(), id(scope.id()), limit);
    }

    private static Chunk readChunk(ResultSet r) throws SQLException {
        return new Chunk(uuid(r, "id"), uuid(r, "version_id"), r.getInt("ordinal"), r.getInt("start_line"),
            r.getInt("end_line"), r.getInt("start_offset"), r.getInt("end_offset"), r.getString("content_hash"), r.getString("chunk_text"));
    }

    public Chunk chunk(UUID versionId, UUID chunkId) {
        return jdbc.query("SELECT * FROM knowledge_chunks WHERE id=? AND version_id=?", (r,n) -> readChunk(r),
                id(chunkId), id(versionId)).stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "此版本中不存在所选知识分段。"));
    }

    public Source action(UUID sid, Scope scope, long revision, String action) {
        return tx.execute(
                status -> {
                    String state = action.equals("DELETE") ? "DELETED" : "UNBOUND";
                    if (jdbc.update(
                                    "UPDATE knowledge_sources SET state=?,revision=revision+1 WHERE"
                                        + " id=? AND scope_kind=? AND scope_id=? AND revision=? AND"
                                        + " state<>'DELETED'",
                                    state,
                                    id(sid),
                                    scope.kind(),
                                    id(scope.id()),
                                    revision)
                            != 1) throw conflict();
                    jdbc.update(
                            "DELETE FROM knowledge_active_documents WHERE source_id=?", id(sid));
                    return require(sid);
                });
    }

    public void interruptPending() {
        jdbc.update(
                "UPDATE knowledge_jobs SET state='INTERRUPTED',message=?,updated_at=? WHERE state"
                        + " IN ('QUEUED','RUNNING')",
                "服务重启，任务未自动重试；请重新预览。",
                now());
    }

    private Job readJob(ResultSet r, int n) throws SQLException {
        return new Job(
                uuid(r, "id"),
                uuid(r, "source_id"),
                scope(r),
                r.getString("state"),
                r.getInt("progress"),
                r.getInt("total"),
                r.getBoolean("cancel_requested"),
                r.getString("message"),
                instant(r, "created_at"),
                instant(r, "updated_at"));
    }

    public Job job(UUID jid) {
        return jdbc
                .query("SELECT * FROM knowledge_jobs WHERE id=?", this::readJob, id(jid))
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "知识导入任务不存在。"));
    }

    public List<Job> jobs(Scope scope) {
        return jdbc.query(
                "SELECT * FROM knowledge_jobs WHERE scope_kind=? AND scope_id=? ORDER BY created_at"
                        + " DESC LIMIT 20",
                this::readJob,
                scope.kind(),
                id(scope.id()));
    }

    public Job createJob(UUID jid, Preview p) {
        jdbc.update(
                "INSERT INTO"
                    + " knowledge_jobs(id,source_id,scope_kind,scope_id,state,progress,total,cancel_requested,message,created_at,updated_at)"
                    + " VALUES(?,?,?,?,'QUEUED',0,?,FALSE,?,?,?)",
                id(jid),
                id(p.sourceId()),
                p.scope().kind(),
                id(p.scope().id()),
                p.files().size(),
                "等待导入",
                now(),
                now());
        return job(jid);
    }

    public boolean start(UUID jid) {
        return jdbc.update(
                        "UPDATE knowledge_jobs SET state='RUNNING',message=?,updated_at=? WHERE"
                                + " id=? AND state='QUEUED' AND cancel_requested=FALSE",
                        "重新读取并校验预览文件",
                        now(),
                        id(jid))
                == 1;
    }

    public void progress(UUID jid, int progress) {
        jdbc.update(
                "UPDATE knowledge_jobs SET progress=CASE WHEN ? > total THEN total ELSE ?"
                    + " END,updated_at=? WHERE id=? AND state='RUNNING'",
                progress,
                progress,
                now(),
                id(jid));
    }

    public boolean cancelled(UUID jid) {
        var job = job(jid);
        return job.cancelRequested() || !Set.of("QUEUED", "RUNNING").contains(job.state());
    }

    public Job cancel(UUID jid) {
        jdbc.update(
                "UPDATE knowledge_jobs SET cancel_requested=TRUE,state=CASE WHEN state='QUEUED'"
                    + " THEN 'CANCELLED' ELSE state END,message=?,updated_at=? WHERE id=? AND state"
                    + " IN ('QUEUED','RUNNING')",
                "已请求取消",
                now(),
                id(jid));
        return job(jid);
    }

    public void finish(UUID jid, String state, String message) {
        jdbc.update(
                "UPDATE knowledge_jobs SET state=?,message=?,updated_at=? WHERE id=? AND state IN"
                        + " ('QUEUED','RUNNING')",
                state,
                message,
                now(),
                id(jid));
    }

    /** Only this short transaction changes current mappings. No disk/network reads occur here. */
    public void activate(UUID jid, Preview p, List<Document> documents) {
        tx.executeWithoutResult(
                status -> {
                    if (jdbc.update(
                                    "UPDATE knowledge_jobs SET"
                                        + " state='SUCCEEDED',progress=total,message=?,updated_at=?"
                                        + " WHERE id=? AND state='RUNNING' AND"
                                        + " cancel_requested=FALSE",
                                    "导入完成；未调用模型",
                                    now(),
                                    id(jid))
                            != 1) throw new java.util.concurrent.CancellationException();
                    // Lock live identity through activation; deletion or root edits cannot race
                    // this final check.
                    List<String> scopeStamps =
                            p.scope().kind().equals("PROJECT")
                                    ? jdbc.query(
                                            "SELECT revision,root_path FROM source_projects WHERE"
                                                    + " id=? FOR UPDATE",
                                            (r, n) ->
                                                    r.getLong("revision")
                                                            + ":"
                                                            + r.getString("root_path"),
                                            id(p.scope().id()))
                                    : jdbc.query(
                                            "SELECT owner_name,repository_name FROM"
                                                    + " github_repositories WHERE id=? FOR UPDATE",
                                            (r, n) ->
                                                    r.getString("owner_name")
                                                            + "/"
                                                            + r.getString("repository_name"),
                                            id(p.scope().id()));
                    if (scopeStamps.size() != 1 || !scopeStamps.getFirst().equals(p.scopeStamp()))
                        throw conflict();
                    if (p.revision() == 0) {
                        try {
                            jdbc.update(
                                    "INSERT INTO"
                                        + " knowledge_sources(id,scope_kind,scope_id,origin,root_path,locator_hash,commit_sha,revision,state,manifest_hash,imported_at,coverage)"
                                        + " VALUES(?,?,?,?,?,?,?,1,'ACTIVE',?,?,?)",
                                    id(p.sourceId()),
                                    p.scope().kind(),
                                    id(p.scope().id()),
                                    p.origin(),
                                    p.root(),
                                    MaterialService.sha(p.root()),
                                    p.commitSha(),
                                    p.manifestHash(),
                                    now(),
                                    encode(p));
                        } catch (org.springframework.dao.DuplicateKeyException e) {
                            throw conflict();
                        }
                    } else if (jdbc.update(
                                    "UPDATE knowledge_sources SET"
                                        + " commit_sha=?,revision=revision+1,state='ACTIVE',manifest_hash=?,imported_at=?,coverage=?"
                                        + " WHERE id=? AND revision=?",
                                    p.commitSha(),
                                    p.manifestHash(),
                                    now(),
                                    encode(p),
                                    id(p.sourceId()),
                                    p.revision())
                            != 1) throw conflict();
                    jdbc.update(
                            "DELETE FROM knowledge_active_documents WHERE source_id=?",
                            id(p.sourceId()));
                    for (Document d : documents) {
                        List<String> prior =
                                jdbc.queryForList(
                                        "SELECT id FROM knowledge_versions WHERE source_id=? AND"
                                                + " path=? AND content_hash=?",
                                        String.class,
                                        id(p.sourceId()),
                                        d.path(),
                                        d.sha256());
                        UUID vid;
                        if (prior.isEmpty()) {
                            vid = UUID.randomUUID();
                            jdbc.update(
                                    "INSERT INTO"
                                        + " knowledge_versions(id,source_id,scope_kind,scope_id,origin,root_path,commit_sha,path,content_hash,title,document_text,imported_at)"
                                        + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                                    id(vid),
                                    id(p.sourceId()),
                                    p.scope().kind(),
                                    id(p.scope().id()),
                                    p.origin(),
                                    p.root(),
                                    p.commitSha(),
                                    d.path(),
                                    d.sha256(),
                                    d.title(),
                                    d.text(),
                                    now());
                            for (Chunk c : KnowledgeFiles.chunks(vid, d.text()))
                                jdbc.update(
                                        "INSERT INTO"
                                            + " knowledge_chunks(id,version_id,ordinal,start_line,end_line,start_offset,end_offset,content_hash,chunk_text)"
                                            + " VALUES(?,?,?,?,?,?,?,?,?)",
                                        id(c.id()),
                                        id(vid),
                                        c.ordinal(),
                                        c.startLine(),
                                        c.endLine(),
                                        c.startOffset(),
                                        c.endOffset(),
                                        c.sha256(),
                                        c.text());
                        } else vid = UUID.fromString(prior.getFirst());
                        jdbc.update(
                                "INSERT INTO knowledge_active_documents(source_id,path,version_id)"
                                        + " VALUES(?,?,?)",
                                id(p.sourceId()),
                                d.path(),
                                id(vid));
                    }
                });
    }

    private String encode(Object v) {
        try {
            return json.writeValueAsString(v);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot store knowledge", e);
        }
    }

    private <T> T decode(String v, Class<T> type) {
        try {
            return json.readValue(v, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read knowledge", e);
        }
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(CONFLICT, "知识来源已更改，请重新预览。");
    }
}
