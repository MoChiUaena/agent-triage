package io.github.mochiuaena.triage.store;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.Run;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** One atomic row per bounded execution snapshot, including ordered events. */
@Repository
public class RunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RunRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(Run run) {
        jdbc.update("INSERT INTO triage_runs (id, created_at, status, payload) VALUES (?, ?, ?, ?)",
            run.id().toString(), OffsetDateTime.ofInstant(run.createdAt(), ZoneOffset.UTC), run.status().name(), encode(run));
    }

    public void save(Run run) {
        if (jdbc.update("UPDATE triage_runs SET status = ?, payload = ? WHERE id = ?",
            run.status().name(), encode(run), run.id().toString()) != 1) {
            throw new IllegalStateException("Execution record is missing");
        }
    }

    public Optional<Run> find(UUID id) {
        return jdbc.query("SELECT payload FROM triage_runs WHERE id = ?", (row, n) -> decode(row.getString(1)), id.toString())
            .stream().findFirst();
    }

    public List<Run> recent(int limit) {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("Limit must be 1..50");
        return jdbc.query("SELECT payload FROM triage_runs ORDER BY created_at DESC, id DESC LIMIT ?",
            (row, n) -> decode(row.getString(1)), limit);
    }

    public List<Run> unfinished() {
        return jdbc.query("SELECT payload FROM triage_runs WHERE status IN ('QUEUED', 'RUNNING')",
            (row, n) -> decode(row.getString(1)));
    }

    private String encode(Run run) {
        try { return json.writeValueAsString(run); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot encode execution record", e); }
    }

    private Run decode(String value) {
        try { return json.readValue(value, Run.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot decode execution record", e); }
    }
}
