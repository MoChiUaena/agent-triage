package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.ActivityModels.ActivityReport;

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
public class RepositoryActivityStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RepositoryActivityStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(ActivityReport report) {
        try {
            jdbc.update("INSERT INTO repository_activity_reports (id, repository_id, created_at, payload) VALUES (?, ?, ?, ?)",
                report.id().toString(), report.repositoryId().toString(), Timestamp.from(report.createdAt()),
                json.writeValueAsString(report));
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode activity report", error); }
    }

    public List<ActivityReport> list(UUID repositoryId) {
        return jdbc.query("SELECT payload FROM repository_activity_reports WHERE repository_id=? ORDER BY created_at DESC, id DESC LIMIT 20",
            (row, n) -> decode(row.getString(1)), repositoryId.toString());
    }

    public ActivityReport require(UUID id) {
        return jdbc.query("SELECT payload FROM repository_activity_reports WHERE id=?", (row, n) -> decode(row.getString(1)), id.toString())
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "研发活动报告不存在。"));
    }

    private ActivityReport decode(String value) {
        try { return json.readValue(value, ActivityReport.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot read activity report", error); }
    }
}
