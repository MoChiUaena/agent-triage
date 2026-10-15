package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.development.GithubModels.*;
import java.time.*;
import java.util.*;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Repository
@DependsOnDatabaseInitialization
public class GithubRepositoryStore {
    record Stored(Registration registration, Health health) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public GithubRepositoryStore(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public List<Registration> list() {
        return jdbc.query("SELECT * FROM github_repositories ORDER BY registered_at, id", (row, n) -> registration(row));
    }
    boolean exists(Key key) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM github_repositories WHERE owner_name=? AND repository_name=?",
            Long.class, key.owner(), key.name()) > 0;
    }
    Stored require(UUID id) {
        var values = jdbc.query("SELECT * FROM github_repositories WHERE id=?", (row, n) -> {
            try { return new Stored(registration(row), json.readValue(row.getString("snapshot"), Health.class)); }
            catch (java.io.IOException error) { throw new IllegalStateException("Cannot read repository snapshot", error); }
        }, id.toString());
        return values.stream().findFirst().orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "登记的仓库不存在。"));
    }
    void insert(UUID id, Key key, Health health) {
        jdbc.update("INSERT INTO github_repositories (id, owner_name, repository_name, registered_at, checked_at, snapshot) VALUES (?, ?, ?, ?, ?, ?)",
            id.toString(), key.owner(), key.name(), Instant.now().atOffset(ZoneOffset.UTC),
            health.checkedAt().atOffset(ZoneOffset.UTC), encode(health));
    }
    void update(UUID id, Health health) {
        jdbc.update("UPDATE github_repositories SET snapshot=?, checked_at=?, last_error=NULL WHERE id=?", encode(health),
            health.checkedAt().atOffset(ZoneOffset.UTC), id.toString());
    }
    void failure(UUID id, String message) { jdbc.update("UPDATE github_repositories SET last_error=? WHERE id=?", message, id.toString()); }
    void delete(UUID id) { jdbc.update("DELETE FROM github_repositories WHERE id=?", id.toString()); }
    private Registration registration(java.sql.ResultSet row) throws java.sql.SQLException {
        String owner = row.getString("owner_name"), name = row.getString("repository_name");
        return new Registration(UUID.fromString(row.getString("id")), owner, name, new Key(owner, name).url(),
            row.getObject("registered_at", OffsetDateTime.class).toInstant(), row.getObject("checked_at", OffsetDateTime.class).toInstant(),
            row.getString("last_error"));
    }
    private String encode(Health health) {
        try { return json.writeValueAsString(health); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot save repository snapshot", error); }
    }
}
