package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.CiModels.Report;
import java.time.ZoneOffset;
import java.util.*;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Repository
@DependsOnDatabaseInitialization
public class CiReportStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public CiReportStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public void insert(Report report) {
        try { jdbc.update("INSERT INTO ci_reports (id, repository_id, created_at, payload) VALUES (?, ?, ?, ?)",
            report.id().toString(), report.repositoryId().toString(), report.createdAt().atOffset(ZoneOffset.UTC), json.writeValueAsString(report)); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode CI report", error); }
    }
    public List<Report> list(UUID repository) { return jdbc.query("SELECT payload FROM ci_reports WHERE repository_id=? ORDER BY created_at DESC, id DESC LIMIT 20",
        (row, n) -> decode(row.getString(1)), repository.toString()); }
    public Report require(UUID id) { return jdbc.query("SELECT payload FROM ci_reports WHERE id=?", (row, n) -> decode(row.getString(1)), id.toString())
        .stream().findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "CI 报告不存在。")); }
    private Report decode(String value) {
        try { return json.readValue(value, Report.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot read CI report", error); }
    }
}
