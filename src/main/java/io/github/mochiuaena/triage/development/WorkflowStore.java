package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.WorkflowModels.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
@DependsOnDatabaseInitialization
public class WorkflowStore {
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    public WorkflowStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    void insert(Snapshot value) { jdbc.update("INSERT INTO development_workflows (id, repository_id, created_at, state, payload) VALUES (?, ?, ?, ?, ?)", value.id().toString(), value.repositoryId().toString(), value.createdAt().atOffset(ZoneOffset.UTC), value.state(), encode(value)); }
    boolean update(Snapshot value) { return jdbc.update("UPDATE development_workflows SET state=?, payload=? WHERE id=?", value.state(), encode(value), value.id().toString()) == 1; }
    public Snapshot require(UUID id) { return jdbc.query("SELECT payload FROM development_workflows WHERE id=?", (row,n) -> decode(row.getString(1)), id.toString()).stream().findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "协同工作流不存在。")); }
    public List<Snapshot> list(UUID repository) { return jdbc.query("SELECT payload FROM development_workflows WHERE repository_id=? ORDER BY created_at DESC, id DESC LIMIT 20", (row,n) -> decode(row.getString(1)), repository.toString()); }
    int recover() {
        var values = jdbc.query("SELECT payload FROM development_workflows WHERE state IN ('PLANNED','RUNNING')", (row,n) -> decode(row.getString(1)));
        for (var value : values) {
            Instant now = Instant.now(); var tasks = value.tasks().stream().map(task -> Set.of("PENDING", "RUNNING").contains(task.state()) ? new Task(task.id(), task.title(), task.requires(), task.after(), task.state().equals("RUNNING") ? "FAILED" : "SKIPPED", "应用重启，任务已中断，没有自动重跑。", "RESTART", task.startedAt(), now, task.references()) : task).toList();
            update(new Snapshot(value.id(), value.repositoryId(), value.repository(), value.plan(), "INTERRUPTED", "应用重启，保留已完成结果与证据，需重新创建工作流。", value.createdAt(), now, tasks, value.data()));
        }
        return values.size();
    }
    private String encode(Snapshot value) { try { return json.writeValueAsString(value); } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode workflow", error); } }
    private Snapshot decode(String value) { try { return json.readValue(value, Snapshot.class); } catch (java.io.IOException error) { throw new IllegalStateException("Cannot read workflow", error); } }
}
