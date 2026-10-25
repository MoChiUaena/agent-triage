package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.RepositoryHealthModels.HealthReport;

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
public class RepositoryHealthStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public RepositoryHealthStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(HealthReport report) {
        try {
            jdbc.update("INSERT INTO repository_health_reports (id, repository_id, created_at, payload) VALUES (?, ?, ?, ?)",
                report.id().toString(), report.repositoryId().toString(), Timestamp.from(report.createdAt()),
                json.writeValueAsString(report));
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode health report", error); }
    }

    public List<HealthReport> list(UUID repositoryId) {
        return jdbc.query("SELECT payload FROM repository_health_reports WHERE repository_id=? ORDER BY created_at DESC, id DESC LIMIT 20",
            (row, n) -> decode(row.getString(1)), repositoryId.toString());
    }

    public HealthReport require(UUID id) {
        return jdbc.query("SELECT payload FROM repository_health_reports WHERE id=?", (row, n) -> decode(row.getString(1)), id.toString())
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "仓库健康摘要不存在。"));
    }

    private HealthReport decode(String value) {
        try { return json.readValue(value, HealthReport.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot read health report", error); }
    }
}
