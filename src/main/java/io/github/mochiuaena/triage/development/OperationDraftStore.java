package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.OperationDraftModels.Draft;

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
public class OperationDraftStore {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public OperationDraftStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }

    public void insert(Draft draft) {
        try {
            jdbc.update("INSERT INTO operation_drafts (id, repository_id, state, revision, created_at, updated_at, payload) VALUES (?, ?, ?, ?, ?, ?, ?)",
                draft.id().toString(), draft.repositoryId().toString(), draft.state(), draft.revision(),
                Timestamp.from(draft.createdAt()), Timestamp.from(draft.updatedAt()), json.writeValueAsString(draft));
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode operation draft", error); }
    }

    /** Atomic optimistic update: the revision must match and the draft must still be active. */
    public boolean update(UUID id, int expectedRevision, Draft next) {
        try {
            return jdbc.update("UPDATE operation_drafts SET revision=?, updated_at=?, payload=? WHERE id=? AND revision=? AND state='DRAFT'",
                next.revision(), Timestamp.from(next.updatedAt()), json.writeValueAsString(next), id.toString(), expectedRevision) == 1;
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode operation draft", error); }
    }

    /** Atomic discard (state plus payload so later reads see DISCARDED); false when missing or already discarded. */
    public boolean discard(UUID id, Draft discarded) {
        try {
            return jdbc.update("UPDATE operation_drafts SET state='DISCARDED', updated_at=?, payload=? WHERE id=? AND state='DRAFT'",
                Timestamp.from(discarded.updatedAt()), json.writeValueAsString(discarded), id.toString()) == 1;
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot encode operation draft", error); }
    }

    public List<Draft> list(UUID repositoryId) {
        return jdbc.query("SELECT payload FROM operation_drafts WHERE repository_id=? AND state='DRAFT' ORDER BY updated_at DESC, id DESC LIMIT 50",
            (row, n) -> decode(row.getString(1)), repositoryId.toString());
    }

    public Draft require(UUID id) {
        return jdbc.query("SELECT payload FROM operation_drafts WHERE id=?", (row, n) -> decode(row.getString(1)), id.toString())
            .stream().findFirst()
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "操作草稿不存在。"));
    }

    private Draft decode(String value) {
        try { return json.readValue(value, Draft.class); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot read operation draft", error); }
    }
}
