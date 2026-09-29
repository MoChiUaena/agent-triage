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
import java.util.ArrayList;
import java.util.Arrays;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;

/** One atomic row per bounded execution snapshot, including ordered events. */
@Repository
@DependsOnDatabaseInitialization
public class RunRepository {
    private static final String HISTORY_COLUMNS = "service_id,service_name,execution_mode,question_text,duration_ms,tool_calls,model_calls,known_input_tokens,known_output_tokens,known_total_tokens,usage_complete";
    private static final String HISTORY_UPDATES = String.join(",", Arrays.stream(HISTORY_COLUMNS.split(",")).map(name -> name + " = ?").toList());
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RunRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(Run run) {
        var values = new ArrayList<Object>(List.of(run.id().toString(), OffsetDateTime.ofInstant(run.createdAt(), ZoneOffset.UTC), run.status().name(), encode(run)));
        values.addAll(Arrays.asList(HistoryProjection.of(run).fields()));
        jdbc.update("INSERT INTO triage_runs (id, created_at, status, payload," + HISTORY_COLUMNS + ") VALUES ("
            + String.join(",", java.util.Collections.nCopies(values.size(), "?")) + ")", values.toArray());
    }

    public void save(Run run) {
        var values = new ArrayList<Object>(List.of(run.status().name(), encode(run)));
        values.addAll(Arrays.asList(HistoryProjection.of(run).fields())); values.add(run.id().toString());
        if (jdbc.update("UPDATE triage_runs SET status = ?, payload = ?," + HISTORY_UPDATES + " WHERE id = ?", values.toArray()) != 1) {
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

    Run decode(String value) {
        try { return json.readValue(value, Run.class); }
        catch (JsonProcessingException e) { throw new IllegalStateException("Cannot decode execution record", e); }
    }

    public void indexLegacy() {
        while (true) {
            List<Run> batch = jdbc.query("SELECT payload FROM triage_runs WHERE service_id IS NULL ORDER BY created_at, id LIMIT 100",
                (row, n) -> decode(row.getString(1)));
            if (batch.isEmpty()) return;
            for (Run run : batch) {
                var values = new ArrayList<Object>(Arrays.asList(HistoryProjection.of(run).fields())); values.add(run.id().toString());
                jdbc.update("UPDATE triage_runs SET " + HISTORY_UPDATES + " WHERE id = ? AND service_id IS NULL", values.toArray());
            }
        }
    }
}
