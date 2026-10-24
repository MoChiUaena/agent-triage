package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.settings.EmbeddingModels.*;

import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.*;
import java.util.*;

@Repository
@DependsOnDatabaseInitialization
public class SearchModelRepository {
    private final JdbcTemplate jdbc;

    public SearchModelRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public record Stored(String kind, UUID id, String displayName, String baseUrl, String model,
                         String encryptedKey, int timeoutSeconds, long version, Instant createdAt) {
        View view() { return new View(kind, id, displayName, baseUrl, model, true, timeoutSeconds, version); }
        @Override public String toString() { return "StoredSearchModel[kind=" + kind + ", credentials=redacted]"; }
    }

    public Optional<Stored> find(String kind) {
        return jdbc.query("SELECT * FROM search_model_providers WHERE kind=?", (r, n) -> read(r, kind), kind)
            .stream().findFirst();
    }

    public long count() {
        Long value = jdbc.queryForObject("SELECT COUNT(*) FROM search_model_providers", Long.class);
        return value == null ? 0 : value;
    }

    public void insert(Stored s) {
        jdbc.update("INSERT INTO search_model_providers(id,kind,display_name,base_url,model_name,encrypted_key,timeout_seconds,version,created_at)"
                + " VALUES(?,?,?,?,?,?,?,?,?)",
            s.id().toString(), s.kind(), s.displayName(), s.baseUrl(), s.model(), s.encryptedKey(),
            s.timeoutSeconds(), s.version(), Timestamp.from(s.createdAt()));
    }

    public boolean update(Stored s, long expectedVersion) {
        return jdbc.update("UPDATE search_model_providers SET display_name=?,base_url=?,model_name=?,encrypted_key=?,"
                + "timeout_seconds=?,version=? WHERE id=? AND version=?",
            s.displayName(), s.baseUrl(), s.model(), s.encryptedKey(), s.timeoutSeconds(), s.version(),
            s.id().toString(), expectedVersion) == 1;
    }

    public void delete(UUID id) {
        jdbc.update("DELETE FROM search_model_providers WHERE id=?", id.toString());
    }

    private static Stored read(ResultSet r, String kind) throws SQLException {
        return new Stored(kind, UUID.fromString(r.getString("id")), r.getString("display_name"),
            r.getString("base_url"), r.getString("model_name"), r.getString("encrypted_key"),
            r.getInt("timeout_seconds"), r.getLong("version"), r.getTimestamp("created_at").toInstant());
    }
}
