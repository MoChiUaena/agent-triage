package io.github.mochiuaena.triage.memory;

import static io.github.mochiuaena.triage.memory.MemoryModels.*;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.material.MaterialService;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

@Repository
@DependsOnDatabaseInitialization
public class MemoryStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public MemoryStore(JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public synchronized Version create(Scope scope, Body body) {
        validate(body);
        return tx.execute(
                s -> {
                    if (jdbc.queryForObject("SELECT COUNT(*) FROM memory_heads", Long.class) >= 1000
                            || jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM memory_heads WHERE scope_kind=?"
                                                + " AND scope_id=?",
                                            Long.class,
                                            scope.kind(),
                                            scope.id().toString())
                                    >= 100)
                        throw new ResponseStatusException(
                                TOO_MANY_REQUESTS, "记忆数量上限：总计 1000 / 每范围 100（含删除）。");
                    var now = Instant.now();
                    var id = UUID.randomUUID();
                    String hash = MaterialService.sha(encode(body));
                    var v =
                            new Version(
                                    id, scope, 1, "CANDIDATE", body, hash, now, now, null, null);
                    jdbc.update(
                            "INSERT INTO"
                                + " memory_heads(id,scope_kind,scope_id,revision,status,topic,body_sha256,updated_at)"
                                + " VALUES(?,?,?,1,'CANDIDATE',?,?,?)",
                            id.toString(),
                            scope.kind(),
                            scope.id().toString(),
                            body.topic(),
                            hash,
                            now.atOffset(ZoneOffset.UTC));
                    insert(v);
                    return v;
                });
    }

    public Version current(UUID id, Scope scope) {
        Long revision =
                jdbc
                        .query(
                                "SELECT revision FROM memory_heads WHERE id=?",
                                (r, n) -> r.getLong(1),
                                id.toString())
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "记忆不存在。"));
        return version(id, scope, revision);
    }

    public Version version(UUID id, Scope scope, long revision) {
        var v =
                jdbc
                        .query(
                                "SELECT payload FROM memory_versions WHERE memory_id=? AND"
                                    + " revision=?",
                                (r, n) -> decode(r.getString(1)),
                                id.toString(),
                                revision)
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "记忆版本不存在。"));
        if (!v.scope().kind().equals(scope.kind())
                || !v.scope().id().equals(scope.id())
                || (scope.service() != null
                        && !Objects.equals(scope.service(), v.scope().service())))
            throw new ResponseStatusException(CONFLICT, "记忆不属于所选范围。");
        return v;
    }

    public List<Summary> list(Scope scope, String status, int limit) {
        if (limit < 1
                || limit > 100
                || !Set.of("ALL", "CANDIDATE", "CONFIRMED", "DISABLED", "DELETED").contains(status))
            throw bad("状态或条数无效；条数上限 100。");
        return jdbc.query(
                "SELECT id,revision,status,topic,body_sha256,updated_at FROM memory_heads WHERE"
                    + " scope_kind=? AND scope_id=?"
                        + (status.equals("ALL") ? "" : " AND status=?")
                        + " ORDER BY updated_at DESC,id LIMIT ?",
                (r, n) ->
                        new Summary(
                                UUID.fromString(r.getString(1)),
                                scope,
                                r.getLong(2),
                                r.getString(3),
                                r.getString(4),
                                r.getString(5),
                                r.getObject(6, OffsetDateTime.class).toInstant()),
                status.equals("ALL")
                        ? new Object[] {scope.kind(), scope.id().toString(), limit}
                        : new Object[] {scope.kind(), scope.id().toString(), status, limit});
    }

    public List<Summary> history(UUID id, Scope scope) {
        current(id, scope);
        return jdbc.query(
                "SELECT payload FROM memory_versions WHERE memory_id=? ORDER BY revision DESC",
                (r, n) -> {
                    var v = decode(r.getString(1));
                    return new Summary(
                            v.id(),
                            v.scope(),
                            v.revision(),
                            v.status(),
                            v.body().topic(),
                            v.bodySha256(),
                            v.updatedAt());
                },
                id.toString());
    }

    public Version edit(UUID id, Scope scope, long revision, Body body) {
        validate(body);
        return change(id, scope, revision, body, "CANDIDATE", false, null);
    }

    public Version transition(
            UUID id, Scope scope, long revision, String state, boolean confirmed, String hash) {
        if (!Set.of("CONFIRMED", "DISABLED", "DELETED").contains(state)) throw bad("状态操作无效。");
        if (state.equals("CONFIRMED") && !confirmed) throw bad("请明确确认已审阅此版本及历史依据。");
        return change(id, scope, revision, null, state, confirmed, hash);
    }

    private Version change(
            UUID id,
            Scope scope,
            long revision,
            Body body,
            String state,
            boolean confirmed,
            String hash) {
        return tx.execute(
                s -> {
                    var old = current(id, scope);
                    if (old.status().equals("DELETED"))
                        throw new ResponseStatusException(CONFLICT, "已删除记忆只能阅读历史。");
                    if (old.revision() != revision
                            || (state.equals("CONFIRMED")
                                    && !Objects.equals(hash, old.bodySha256()))) throw conflict();
                    if (revision >= 20)
                        throw new ResponseStatusException(
                                TOO_MANY_REQUESTS, "每条记忆最多 20 个版本（含状态操作）。");
                    Body next = body == null ? old.body() : body;
                    String nextHash = MaterialService.sha(encode(next));
                    var now = Instant.now();
                    if (jdbc.update(
                                    "UPDATE memory_heads SET"
                                        + " revision=revision+1,status=?,topic=?,body_sha256=?,updated_at=?"
                                        + " WHERE id=? AND revision=?",
                                    state,
                                    next.topic(),
                                    nextHash,
                                    now.atOffset(ZoneOffset.UTC),
                                    id.toString(),
                                    revision)
                            != 1) throw conflict();
                    var v =
                            new Version(
                                    id,
                                    old.scope(),
                                    revision + 1,
                                    state,
                                    next,
                                    nextHash,
                                    old.createdAt(),
                                    now,
                                    state.equals("CONFIRMED") ? now : null,
                                    state.equals("CONFIRMED")
                                            ? "USER_EXACT_REVISION:"
                                                    + revision
                                                    + ":"
                                                    + hash
                                                    + "; 历史依据，未经独立验证；不授权外发。"
                                            : null);
                    insert(v);
                    return v;
                });
    }

    private void validate(Body body) {
        if (body == null
                || body.topic() == null
                || body.topic().isBlank()
                || body.topic().length() > 120
                || body.content() == null
                || body.content().isBlank()
                || body.content().length() > 4000
                || body.basis() == null
                || body.basis().note() == null
                || body.basis().note().length() > 2000
                || body.basis().references() == null
                || body.basis().references().size() > 3
                || encode(body.basis()).length() > 48000)
            throw bad("记忆正文或依据超出上限（主题120 / 正文4000 / 用户陈述2000 / 引用3 / 依据48000字符）。");
    }

    private void insert(Version v) {
        jdbc.update(
                "INSERT INTO memory_versions(memory_id,revision,payload) VALUES(?,?,?)",
                v.id().toString(),
                v.revision(),
                encode(v));
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private Version decode(String value) {
        try {
            return json.readValue(value, Version.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static ResponseStatusException bad(String text) {
        return new ResponseStatusException(BAD_REQUEST, text);
    }

    private static ResponseStatusException conflict() {
        return new ResponseStatusException(CONFLICT, "记忆版本或正文已变化，请重新打开并审阅。");
    }
}
