package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.PrModelModels.Advice;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
@DependsOnDatabaseInitialization
public class PrModelStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public PrModelStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(Advice advice) {
        try {
            jdbc.update("INSERT INTO pr_model_analyses (id, report_id, repository_id, created_at, payload) VALUES (?, ?, ?, ?, ?)",
                advice.id().toString(), advice.reportId().toString(), advice.repositoryId().toString(),
                Timestamp.from(advice.createdAt()), json.writeValueAsString(advice));
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode PR model advice", error); }
    }

    public List<Advice> list(UUID reportId) {
        return jdbc.query("SELECT payload FROM pr_model_analyses WHERE report_id=? ORDER BY created_at DESC, id DESC LIMIT 20",
            (row, n) -> decode(row.getString(1)), reportId.toString());
    }

    public Advice require(UUID id) {
        return jdbc.query("SELECT payload FROM pr_model_analyses WHERE id=?", (row, n) -> decode(row.getString(1)), id.toString())
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "PR 模型建议记录不存在。"));
    }

    private Advice decode(String value) {
        try { return json.readValue(value, Advice.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot read PR model advice", error); }
    }
}
