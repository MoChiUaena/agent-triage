package io.github.mochiuaena.triage.source;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;

@Repository
public class SourceProjectRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    public SourceProjectRepository(JdbcTemplate jdbc, ObjectMapper json) { this.jdbc = jdbc; this.json = json; }
    public Stored create(String name, String service, String root, Index index) {
        UUID id = UUID.randomUUID();
        try {
            jdbc.update("INSERT INTO source_projects(id,name,service_id,root_path,revision,snapshot,updated_at) VALUES (?,?,?,?,1,?,?)",
                id.toString(), name, service, root, encode(index), OffsetDateTime.now(ZoneOffset.UTC));
        } catch (org.springframework.dao.DuplicateKeyException e) { throw new ResponseStatusException(CONFLICT, "这个服务已经绑定源码项目。"); }
        return require(id);
    }
    public Optional<Stored> find(UUID id) { return query("SELECT * FROM source_projects WHERE id=?", id.toString()).stream().findFirst(); }
    public Optional<Stored> byService(String service) { return query("SELECT * FROM source_projects WHERE service_id=?", service).stream().findFirst(); }
    public Stored require(UUID id) { return find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "源码项目不存在。")); }
    public List<Stored> list() { return query("SELECT * FROM source_projects ORDER BY updated_at DESC LIMIT 20"); }
    public Stored reindex(Stored previous, Index index) {
        if (jdbc.update("UPDATE source_projects SET revision=revision+1,snapshot=?,updated_at=?,share_provider_id=NULL,share_provider_version=NULL,share_model=NULL,share_selection=NULL WHERE id=? AND revision=?",
            encode(index), OffsetDateTime.now(ZoneOffset.UTC), previous.id().toString(), previous.revision()) != 1)
            throw new ResponseStatusException(CONFLICT, "项目在索引期间已更改，请刷新后重试。");
        return require(previous.id());
    }
    public Stored share(UUID id, long revision, UUID provider, Long version, String model, String selection) {
        if (jdbc.update("UPDATE source_projects SET share_provider_id=?,share_provider_version=?,share_model=?,share_selection=? WHERE id=? AND revision=?",
            provider == null ? null : provider.toString(), version, model, selection, id.toString(), revision) != 1)
            throw new ResponseStatusException(CONFLICT, "源码版本已变化，请刷新后重新确认。");
        return require(id);
    }
    private List<Stored> query(String sql, Object... args) {
        return jdbc.query(sql, (row, n) -> {
            try {
                String provider = row.getString("share_provider_id");
                return new Stored(UUID.fromString(row.getString("id")), row.getString("name"), row.getString("service_id"), row.getString("root_path"),
                    row.getLong("revision"), json.readValue(row.getString("snapshot"), Index.class), row.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    provider == null ? null : UUID.fromString(provider), (Long) row.getObject("share_provider_version"), row.getString("share_model"), row.getString("share_selection"));
            } catch (Exception e) { throw new IllegalStateException("Cannot read local source index"); }
        }, args);
    }
    private String encode(Index index) {
        try { return json.writeValueAsString(index); } catch (Exception e) { throw new IllegalStateException("Cannot store source index"); }
    }
}
