package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.DraftExecutionModels.ExecutionRecord;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Local audit trail of confirmed draft executions; payloads never contain credentials. */
@Repository
@DependsOnDatabaseInitialization
public class DraftExecutionStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public DraftExecutionStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(ExecutionRecord record) {
        try {
            jdbc.update("INSERT INTO draft_executions (id, repository_id, draft_id, created_at, payload) VALUES (?, ?, ?, ?, ?)",
                record.id().toString(), record.repositoryId().toString(), record.draftId().toString(),
                Timestamp.from(record.executedAt()), json.writeValueAsString(record));
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode execution record", error); }
    }

    public List<ExecutionRecord> list(UUID repositoryId) {
        return jdbc.query("SELECT payload FROM draft_executions WHERE repository_id=? ORDER BY created_at DESC, id DESC LIMIT 20",
            (row, n) -> decode(row.getString(1)), repositoryId.toString());
    }

    public ExecutionRecord require(UUID id) {
        return jdbc.query("SELECT payload FROM draft_executions WHERE id=?", (row, n) -> decode(row.getString(1)), id.toString())
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "执行审计记录不存在。"));
    }

    private ExecutionRecord decode(String value) {
        try { return json.readValue(value, ExecutionRecord.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot read execution record", error); }
    }
}
