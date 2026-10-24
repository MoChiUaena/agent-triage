package io.github.mochiuaena.triage.conversation;

import static io.github.mochiuaena.triage.conversation.ConversationModels.*;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.github.mochiuaena.triage.material.MaterialModels.Material;

import jakarta.annotation.PostConstruct;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.*;
import java.util.*;

/** Only local storage transactions. Original reports and registrations are never foreign keys. */
@Repository
@DependsOnDatabaseInitialization
public class ConversationStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public ConversationStore(
            JdbcTemplate jdbc, ObjectMapper json, PlatformTransactionManager manager) {
        this.jdbc = jdbc;
        this.json = json;
        this.tx = new TransactionTemplate(manager);
    }

    public synchronized Conversation create(Material original) {
        if (jdbc.queryForObject("SELECT COUNT(*) FROM conversations", Long.class) >= 1000)
            throw new ResponseStatusException(TOO_MANY_REQUESTS, "最多保存 1000 个对话。");
        UUID id = UUID.randomUUID();
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        jdbc.update(
                "INSERT INTO conversations(id,revision,created_at,updated_at,origin_payload)"
                        + " VALUES(?,0,?,?,?)",
                id.toString(),
                now,
                now,
                encode(original));
        return require(id);
    }

    public Conversation require(UUID id) {
        return jdbc
                .query(
                        "SELECT * FROM conversations WHERE id=?",
                        (r, n) ->
                                new Conversation(
                                        id,
                                        r.getLong("revision"),
                                        r.getString("active_attempt") == null
                                                ? null
                                                : UUID.fromString(r.getString("active_attempt")),
                                        r.getObject("created_at", OffsetDateTime.class).toInstant(),
                                        r.getObject("updated_at", OffsetDateTime.class).toInstant(),
                                        decode(r.getString("origin_payload"), Material.class),
                                        messages(id),
                                        attempts(id),
                                        r.getString("budget_payload") == null
                                                ? Budget.defaults()
                                                : decode(
                                                        r.getString("budget_payload"),
                                                        Budget.class)),
                        id.toString())
                .stream()
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "对话不存在。"));
    }

    public List<Summary> list() {
        return jdbc.query(
                "SELECT * FROM conversations ORDER BY updated_at DESC,id DESC LIMIT 1000",
                (r, n) -> {
                    Material m = decode(r.getString("origin_payload"), Material.class);
                    String title = m.text();
                    return new Summary(
                            UUID.fromString(r.getString("id")),
                            r.getLong("revision"),
                            title.substring(0, Math.min(title.length(), 120)),
                            m.reference().type().name(),
                            r.getObject("updated_at", OffsetDateTime.class).toInstant(),
                            r.getString("active_attempt") == null
                                    ? null
                                    : UUID.fromString(r.getString("active_attempt")));
                });
    }

    private List<Message> messages(UUID id) {
        return jdbc.query(
                "SELECT payload FROM conversation_messages WHERE conversation_id=? ORDER BY"
                        + " ordinal",
                (r, n) -> decode(r.getString(1), Message.class),
                id.toString());
    }

    private List<Attempt> attempts(UUID id) {
        return jdbc.query(
                "SELECT payload FROM conversation_attempts WHERE conversation_id=? ORDER BY"
                        + " created_at,id",
                (r, n) -> decode(r.getString(1), Attempt.class),
                id.toString());
    }

    public void budget(UUID id, long revision, Budget budget) {
        if (budget == null) throw new ResponseStatusException(BAD_REQUEST, "请提供上下文预算。");
        if (jdbc.update(
                        "UPDATE conversations SET budget_payload=?,revision=revision+1,updated_at=?"
                            + " WHERE id=? AND revision=? AND active_attempt IS NULL",
                        encode(budget),
                        OffsetDateTime.now(ZoneOffset.UTC),
                        id.toString(),
                        revision)
                != 1) throw conflict();
    }

    public void admit(UUID id, long revision, Message user, Attempt attempt, boolean active) {
        tx.executeWithoutResult(
                status -> {
                    if (jdbc.queryForObject(
                                    "SELECT COUNT(*) FROM conversation_messages WHERE"
                                            + " conversation_id=?",
                                    Long.class,
                                    id.toString())
                            >= (active ? 79 : 80))
                        throw new ResponseStatusException(
                                TOO_MANY_REQUESTS, "本对话已达到 80 条消息上限，请从报告新建对话；未自动裁剪保存记录。");
                    int changed =
                            jdbc.update(
                                    "UPDATE conversations SET"
                                        + " revision=revision+1,active_attempt=?,updated_at=? WHERE"
                                        + " id=? AND revision=? AND active_attempt IS NULL",
                                    active ? attempt.id().toString() : null,
                                    OffsetDateTime.now(ZoneOffset.UTC),
                                    id.toString(),
                                    revision);
                    if (changed != 1) throw conflict();
                    insertMessage(id, user);
                    jdbc.update(
                            "INSERT INTO"
                                + " conversation_attempts(id,conversation_id,created_at,state,payload)"
                                + " VALUES(?,?,?,?,?)",
                            attempt.id().toString(),
                            id.toString(),
                            attempt.createdAt().atOffset(ZoneOffset.UTC),
                            attempt.state(),
                            encode(attempt));
                });
    }

    public boolean finish(UUID id, Attempt attempt, Message assistant) {
        return Boolean.TRUE.equals(
                tx.execute(
                        status -> {
                            if (jdbc.update(
                                            "UPDATE conversations SET"
                                                + " active_attempt=NULL,revision=revision+1,updated_at=?"
                                                + " WHERE id=? AND active_attempt=?",
                                            OffsetDateTime.now(ZoneOffset.UTC),
                                            id.toString(),
                                            attempt.id().toString())
                                    != 1) return false;
                            jdbc.update(
                                    "UPDATE conversation_attempts SET state=?,payload=? WHERE id=?"
                                            + " AND conversation_id=?",
                                    attempt.state(),
                                    encode(attempt),
                                    attempt.id().toString(),
                                    id.toString());
                            if (assistant != null) insertMessage(id, assistant);
                            return true;
                        }));
    }

    public boolean active(UUID id, UUID attempt) {
        return jdbc.queryForObject(
                        "SELECT COUNT(*) FROM conversations WHERE id=? AND active_attempt=?",
                        Integer.class,
                        id.toString(),
                        attempt.toString())
                == 1;
    }

    private void insertMessage(UUID owner, Message message) {
        int ordinal =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM conversation_messages WHERE conversation_id=?",
                        Integer.class,
                        owner.toString());
        jdbc.update(
                "INSERT INTO conversation_messages(id,conversation_id,ordinal,attempt_id,payload)"
                        + " VALUES(?,?,?,?,?)",
                message.id().toString(),
                owner.toString(),
                ordinal,
                message.attemptId().toString(),
                encode(message));
    }

    @PostConstruct
    public void interruptUnfinished() {
        var pending =
                jdbc.query(
                        "SELECT conversation_id,payload FROM conversation_attempts WHERE"
                                + " state='RUNNING'",
                        (r, n) ->
                                Map.entry(
                                        UUID.fromString(r.getString(1)),
                                        decode(r.getString(2), Attempt.class)));
        for (var p : pending) {
            var a = p.getValue();
            finish(
                    p.getKey(),
                    new Attempt(
                            a.id(),
                            "INTERRUPTED",
                            "应用重启，未完成调用已中断；不会重复发送，用量未知。",
                            a.createdAt(),
                            Instant.now(),
                            a.recipient(),
                            null,
                            null,
                            a.payloadSha256()),
                    null);
        }
    }

    public static ResponseStatusException conflict() {
        return new ResponseStatusException(CONFLICT, "对话已变化或已有回答生成中，请刷新后重新预览。");
    }

    private String encode(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot encode conversation", e);
        }
    }

    private <T> T decode(String value, Class<T> type) {
        try {
            return json.readValue(value, type);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot read conversation", e);
        }
    }
}
