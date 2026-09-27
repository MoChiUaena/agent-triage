package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.settings.ProviderConfig.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;

@Repository
public class ProviderRepository {
    private final JdbcTemplate jdbc;
    public ProviderRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    List<Stored> list() { return jdbc.query("SELECT * FROM model_providers ORDER BY created_at, id", (row, index) -> read(row)); }
    Optional<Stored> find(UUID id) {
        return jdbc.query("SELECT * FROM model_providers WHERE id = ?", (row, index) -> read(row), id.toString()).stream().findFirst();
    }

    void insert(Stored p) {
        jdbc.update("""
            INSERT INTO model_providers (id, display_name, protocol, base_url, model_name, encrypted_key,
                temperature, timeout_seconds, max_rounds, max_tokens, version, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """, p.id().toString(), p.displayName(), p.protocol().name(), p.baseUrl(), p.model(), p.encryptedKey(),
            p.temperature(), p.timeoutSeconds(), p.maxRounds(), p.maxTokens(), p.version(), p.createdAt().atOffset(ZoneOffset.UTC));
    }

    boolean update(Stored p, long expectedVersion) {
        return jdbc.update("""
            UPDATE model_providers SET display_name=?, protocol=?, base_url=?, model_name=?, encrypted_key=?,
                temperature=?, timeout_seconds=?, max_rounds=?, max_tokens=?, version=? WHERE id=? AND version=?
            """, p.displayName(), p.protocol().name(), p.baseUrl(), p.model(), p.encryptedKey(), p.temperature(),
            p.timeoutSeconds(), p.maxRounds(), p.maxTokens(), p.version(), p.id().toString(), expectedVersion) == 1;
    }

    void delete(UUID id) { jdbc.update("DELETE FROM model_providers WHERE id = ?", id.toString()); }

    Optional<Selection> selection() {
        return jdbc.query("SELECT mode, provider_id FROM model_selection WHERE id=1", (row, index) ->
            new Selection(row.getString(1), row.getString(2) == null ? null : UUID.fromString(row.getString(2)), "PAGE"))
            .stream().findFirst();
    }

    void select(String mode, UUID id) {
        String value = id == null ? null : id.toString();
        if (jdbc.update("UPDATE model_selection SET mode=?, provider_id=? WHERE id=1", mode, value) == 0)
            jdbc.update("INSERT INTO model_selection (id, mode, provider_id) VALUES (1, ?, ?)", mode, value);
    }

    private Stored read(ResultSet row) throws SQLException {
        return new Stored(UUID.fromString(row.getString("id")), row.getString("display_name"), Protocol.valueOf(row.getString("protocol")),
            row.getString("base_url"), row.getString("model_name"), row.getString("encrypted_key"), row.getDouble("temperature"),
            row.getInt("timeout_seconds"), row.getInt("max_rounds"), row.getInt("max_tokens"), row.getLong("version"),
            row.getObject("created_at", OffsetDateTime.class).toInstant());
    }
}
