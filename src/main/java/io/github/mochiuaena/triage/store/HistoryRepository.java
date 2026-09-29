package io.github.mochiuaena.triage.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.DeserializationFeature;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import jakarta.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;
import org.springframework.boot.sql.init.dependency.DependsOnDatabaseInitialization;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.*;

@Repository
@DependsOnDatabaseInitialization
public class HistoryRepository {
    public record Entry(UUID id, String question, String service, ServiceInfo serviceInfo, String mode, Scenario scenario,
                        Status status, Instant createdAt, Instant finishedAt, int toolCalls, Long durationMillis, int modelCalls) {}
    public record Page(List<Entry> items, String nextCursor, long total, int pageSize) {}
    public record Service(String id, String name) {}
    private record Cursor(Instant time, UUID id, String filters) {}
    private record Row(Run run, Instant indexedTime) {}
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final RunRepository runs;
    public HistoryRepository(JdbcTemplate jdbc, ObjectMapper json, RunRepository runs) {
        this.jdbc = jdbc; this.json = json.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS); this.runs = runs;
    }
    @PostConstruct public void indexLegacy() { runs.indexLegacy(); }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page page(HistoryFilter filter, int size, String cursorText) {
        if (size < 1 || size > 50) throw new IllegalArgumentException("Page size must be 1..50");
        var base = filter.sql(); var arguments = new ArrayList<>(base.arguments());
        long total = jdbc.queryForObject("SELECT COUNT(*) FROM triage_runs" + base.clause(), Long.class, arguments.toArray());
        String hash = fingerprint(filter);
        String clause = base.clause();
        if (cursorText != null && !cursorText.isBlank()) {
            Cursor cursor = cursor(cursorText, hash);
            clause += clause.isEmpty() ? " WHERE " : " AND ";
            clause += "(created_at < ? OR (created_at = ? AND id < ?))";
            var time = OffsetDateTime.ofInstant(cursor.time(), ZoneOffset.UTC);
            arguments.addAll(List.of(time, time, cursor.id().toString()));
        }
        arguments.add(size + 1);
        List<Row> rows = jdbc.query("SELECT payload, created_at FROM triage_runs" + clause + " ORDER BY created_at DESC, id DESC LIMIT ?",
            (row, n) -> new Row(runs.decode(row.getString("payload")), row.getObject("created_at", OffsetDateTime.class).toInstant()), arguments.toArray());
        String next = rows.size() > size ? encode(new Cursor(rows.get(size - 1).indexedTime(), rows.get(size - 1).run().id(), hash)) : null;
        return new Page(rows.stream().limit(size).map(value -> entry(value.run())).toList(), next, total, size);
    }
    private Entry entry(Run run) {
        var p = HistoryProjection.of(run);
        return new Entry(run.id(), run.question(), p.service(), run.serviceInfo(), p.mode(), run.scenario(), run.status(),
            run.createdAt(), run.finishedAt(), run.toolCalls(), p.durationMillis(), p.modelCalls());
    }
    public List<Service> services() {
        return jdbc.query("SELECT service_id, service_name FROM (SELECT service_id, service_name, "
            + "ROW_NUMBER() OVER (PARTITION BY service_id ORDER BY created_at DESC, id DESC) AS rn "
            + "FROM triage_runs WHERE service_id IS NOT NULL) history_services WHERE rn = 1 ORDER BY service_name, service_id LIMIT 200",
            (row, n) -> new Service(row.getString(1), row.getString(2)));
    }
    public boolean deleteTerminal(UUID id) {
        return jdbc.update("DELETE FROM triage_runs WHERE id = ? AND status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED')", id.toString()) == 1;
    }
    private String fingerprint(HistoryFilter filter) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(filter))); }
        catch (Exception e) { throw new IllegalStateException("Cannot fingerprint history filters"); }
    }
    private String encode(Cursor cursor) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(json.writeValueAsBytes(cursor)); }
        catch (Exception e) { throw new IllegalStateException("Cannot encode history cursor"); }
    }
    private Cursor cursor(String text, String hash) {
        try {
            if (text.length() > 512) throw new IllegalArgumentException();
            var value = json.readValue(Base64.getUrlDecoder().decode(text.getBytes(StandardCharsets.US_ASCII)), Cursor.class);
            if (value == null || value.time() == null || value.id() == null || !hash.equals(value.filters())) throw new IllegalArgumentException();
            return value;
        } catch (Exception e) { throw new IllegalArgumentException("Invalid history cursor"); }
    }
}
