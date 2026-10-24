package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;
import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.knowledge.EmbeddingModels.*;

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
public class EmbeddingRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public EmbeddingRepository(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
        jdbc.update("UPDATE knowledge_embedding_jobs SET state='INTERRUPTED',message=?,updated_at=? WHERE state IN"
            + " ('QUEUED','RUNNING')", "服务重启，向量索引任务未自动重试；请重新预览。", OffsetDateTime.now(ZoneOffset.UTC));
    }

    private static OffsetDateTime now() { return OffsetDateTime.now(ZoneOffset.UTC); }

    private static UUID uuid(ResultSet r, String key) throws SQLException { return UUID.fromString(r.getString(key)); }

    private static Instant instant(ResultSet r, String key) throws SQLException {
        return r.getObject(key, OffsetDateTime.class).toInstant();
    }

    /** Only embeddings of chunks that are still current and active can take part in a search. */
    private static final String ACTIVE_VECTORS =
        " FROM knowledge_chunk_embeddings e"
        + " JOIN knowledge_chunks c ON c.id=e.chunk_id AND c.version_id=e.version_id"
        + " JOIN knowledge_versions v ON v.id=e.version_id"
        + " JOIN knowledge_active_documents a ON a.version_id=v.id AND a.source_id=v.source_id AND a.path=v.path"
        + " JOIN knowledge_sources s ON s.id=v.source_id AND s.scope_kind=v.scope_kind AND s.scope_id=v.scope_id"
        + " WHERE s.state='ACTIVE' AND e.scope_kind=? AND e.scope_id=?";

    /** Any-fingerprint count tells a stale index apart from a missing one. */
    public long countScopeVectors(Scope scope) {
        Long value = jdbc.queryForObject("SELECT COUNT(*)" + ACTIVE_VECTORS, Long.class, scope.kind(), scope.id().toString());
        return value == null ? 0 : value;
    }

    /** Mirrors the keyword scan's document type filter so both stages stay in the same scope. */
    private static String typeFilter(String type) {
        return switch (type) {
            case "MARKDOWN" -> " AND LOWER(v.path) LIKE '%.md'";
            case "TEXT" -> " AND LOWER(v.path) LIKE '%.txt'";
            default -> "";
        };
    }

    public long countVectors(Scope scope, String fingerprint, String type) {
        Long value = jdbc.queryForObject("SELECT COUNT(*)" + ACTIVE_VECTORS + typeFilter(type) + " AND e.fingerprint=?",
            Long.class, scope.kind(), scope.id().toString(), fingerprint);
        return value == null ? 0 : value;
    }

    public List<VectorRow> loadVectors(Scope scope, String fingerprint, String type, int limit) {
        return jdbc.query("SELECT e.chunk_id,e.version_id,e.source_id,e.fingerprint,e.model_name,e.dimensions,e.vector,e.embedded_at"
            + ACTIVE_VECTORS + typeFilter(type) + " AND e.fingerprint=? ORDER BY e.chunk_id LIMIT ?", (r, n) -> new VectorRow(
                uuid(r, "chunk_id"), uuid(r, "version_id"), uuid(r, "source_id"), r.getString("fingerprint"),
                r.getString("model_name"), r.getInt("dimensions"), decode(r.getString("vector")), instant(r, "embedded_at")),
            scope.kind(), scope.id().toString(), fingerprint, limit);
    }

    public void replace(Scope scope, VectorRow row) {
        tx.executeWithoutResult(status -> {
            String id = row.chunkId().toString();
            jdbc.update("DELETE FROM knowledge_chunk_embeddings WHERE chunk_id=?", id);
            jdbc.update("INSERT INTO knowledge_chunk_embeddings(chunk_id,version_id,source_id,scope_kind,scope_id,"
                    + "fingerprint,model_name,dimensions,vector,embedded_at) VALUES(?,?,?,?,?,?,?,?,?,?)",
                id, row.versionId().toString(), row.sourceId().toString(), scope.kind(), scope.id().toString(),
                row.fingerprint(), row.model(), row.dimensions(), encode(row.vector()), Timestamp.from(row.embeddedAt()));
        });
    }

    public void deleteOtherFingerprints(Scope scope, String fingerprint) {
        jdbc.update("DELETE FROM knowledge_chunk_embeddings WHERE scope_kind=? AND scope_id=? AND fingerprint<>?",
            scope.kind(), scope.id().toString(), fingerprint);
    }

    private String encode(float[] vector) {
        try { return json.writeValueAsString(vector); }
        catch (Exception e) { throw new IllegalStateException("Cannot store embedding", e); }
    }

    private float[] decode(String value) {
        try { return json.readValue(value, float[].class); }
        catch (Exception e) { throw new IllegalStateException("Cannot read embedding", e); }
    }

    public IndexJob createJob(UUID jid, Scope scope, String fingerprint, String model, long total) {
        jdbc.update("INSERT INTO knowledge_embedding_jobs(id,scope_kind,scope_id,state,progress,total,cancel_requested,"
                + "message,fingerprint,model_name,usage_calls,usage_tokens,created_at,updated_at)"
                + " VALUES(?,?,?,?,0,?,FALSE,?,?,?,0,0,?,?)",
            jid.toString(), scope.kind(), scope.id().toString(), "QUEUED", total,
            total > EmbeddingModels.MAX_INDEX_CHUNKS ? "等待建立向量索引（当前分段超过单次上限，仅索引前 " + EmbeddingModels.MAX_INDEX_CHUNKS + " 段）"
                : "等待建立向量索引",
            fingerprint, model, now(), now());
        return job(jid);
    }

    public boolean start(UUID jid) {
        return jdbc.update("UPDATE knowledge_embedding_jobs SET state='RUNNING',message=?,updated_at=? WHERE id=? AND"
            + " state='QUEUED' AND cancel_requested=FALSE", "正在发送分段正文到 Embedding 服务", now(), jid.toString()) == 1;
    }

    public void progress(UUID jid, int progress, int calls, long tokens) {
        jdbc.update("UPDATE knowledge_embedding_jobs SET progress=CASE WHEN ?>total THEN total ELSE ? END,usage_calls=?,"
            + "usage_tokens=?,updated_at=? WHERE id=? AND state='RUNNING'",
            progress, progress, calls, tokens, now(), jid.toString());
    }

    public boolean cancelled(UUID jid) {
        IndexJob job = job(jid);
        return job.cancelRequested() || !Set.of("QUEUED", "RUNNING").contains(job.state());
    }

    public IndexJob cancel(UUID jid) {
        jdbc.update("UPDATE knowledge_embedding_jobs SET cancel_requested=TRUE,state=CASE WHEN state='QUEUED' THEN"
            + " 'CANCELLED' ELSE state END,message=?,updated_at=? WHERE id=? AND state IN ('QUEUED','RUNNING')",
            "已请求取消", now(), jid.toString());
        return job(jid);
    }

    public void finish(UUID jid, String state, String message, int calls, long tokens) {
        jdbc.update("UPDATE knowledge_embedding_jobs SET state=?,message=?,usage_calls=?,usage_tokens=?,updated_at=?"
            + " WHERE id=? AND state IN ('QUEUED','RUNNING')", state, message, calls, tokens, now(), jid.toString());
    }

    public IndexJob job(UUID jid) {
        return jdbc.query("SELECT * FROM knowledge_embedding_jobs WHERE id=?", (r, n) -> new IndexJob(
                uuid(r, "id"), new Scope(r.getString("scope_kind"), uuid(r, "scope_id")), r.getString("state"),
                r.getInt("progress"), r.getInt("total"), r.getBoolean("cancel_requested"), r.getString("message"),
                r.getString("fingerprint"), r.getString("model_name"), r.getInt("usage_calls"), r.getLong("usage_tokens"),
                instant(r, "created_at"), instant(r, "updated_at")), jid.toString())
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "向量索引任务不存在。"));
    }

    public List<IndexJob> jobs(Scope scope) {
        return jdbc.query("SELECT * FROM knowledge_embedding_jobs WHERE scope_kind=? AND scope_id=? ORDER BY created_at"
            + " DESC LIMIT 20", (r, n) -> new IndexJob(uuid(r, "id"), new Scope(r.getString("scope_kind"), uuid(r, "scope_id")),
                r.getString("state"), r.getInt("progress"), r.getInt("total"), r.getBoolean("cancel_requested"),
                r.getString("message"), r.getString("fingerprint"), r.getString("model_name"), r.getInt("usage_calls"),
                r.getLong("usage_tokens"), instant(r, "created_at"), instant(r, "updated_at")),
            scope.kind(), scope.id().toString());
    }
}
