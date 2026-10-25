package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.WorkflowModels.Template;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

import static org.springframework.http.HttpStatus.NOT_FOUND;

/** Saved workflow templates; each repository keeps at most ten, removed with its registration. */
@Repository
@DependsOnDatabaseInitialization
public class WorkflowTemplateStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public WorkflowTemplateStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(Template template) {
        try {
            jdbc.update("INSERT INTO workflow_templates (id, repository_id, created_at, updated_at, payload) VALUES (?, ?, ?, ?, ?)",
                template.id().toString(), template.repositoryId().toString(), Timestamp.from(template.createdAt()),
                Timestamp.from(template.updatedAt()), json.writeValueAsString(template));
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode workflow template", error); }
    }

    public void update(Template template) {
        try {
            jdbc.update("UPDATE workflow_templates SET updated_at=?, payload=? WHERE id=?",
                Timestamp.from(template.updatedAt()), json.writeValueAsString(template), template.id().toString());
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode workflow template", error); }
    }

    public void delete(UUID id) { jdbc.update("DELETE FROM workflow_templates WHERE id=?", id.toString()); }

    public List<Template> list(UUID repositoryId) {
        return jdbc.query("SELECT payload FROM workflow_templates WHERE repository_id=? ORDER BY updated_at DESC, id DESC LIMIT 10",
            (row, n) -> decode(row.getString(1)), repositoryId.toString());
    }

    public int count(UUID repositoryId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM workflow_templates WHERE repository_id=?", Integer.class, repositoryId.toString());
    }

    public Template require(UUID id) {
        return jdbc.query("SELECT payload FROM workflow_templates WHERE id=?", (row, n) -> decode(row.getString(1)), id.toString())
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "工作流模板不存在。"));
    }

    private Template decode(String value) {
        try { return json.readValue(value, Template.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot read workflow template", error); }
    }
}
