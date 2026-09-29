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
                        Status status, Instant createdAt, Instant finishedAt, int toolCalls, Long durationMillis, int modelCalls,
                        RequestEndpoint endpoint) {}
    public record Page(List<Entry> items, String nextCursor, long total, int pageSize) {}
    public record Service(String id, String name) {}
    public record Durations(long samples, Double averageMillis, Long p95Millis, Long maximumMillis) {}
    public record Usage(long calledRuns, long completeRuns, long partialRuns, long missingRuns, TokenUsage knownUsage) {}
    public record Statistics(Instant from, Instant until, String service, long total, Map<Status, Long> statuses,
                             long toolCalls, long modelCalls, Durations durations, Usage usage, String endpointId) {}
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
            run.createdAt(), run.finishedAt(), run.toolCalls(), p.durationMillis(), p.modelCalls(), run.endpoint());
    }
    public List<Service> services() {
        return jdbc.query("SELECT service_id, service_name FROM (SELECT service_id, service_name, "
            + "ROW_NUMBER() OVER (PARTITION BY service_id ORDER BY created_at DESC, id DESC) AS rn "
            + "FROM triage_runs WHERE service_id IS NOT NULL) history_services WHERE rn = 1 ORDER BY service_name, service_id LIMIT 200",
            (row, n) -> new Service(row.getString(1), row.getString(2)));
    }
    public List<RequestEndpoint> endpoints(String service) {
        String selected = new HistoryFilter(null, service, null, null, null, null).service();
        if (selected == null) throw new IllegalArgumentException("History endpoint choices require a service");
        return jdbc.query("SELECT payload FROM (SELECT payload, endpoint_http_method, endpoint_route, endpoint_id, "
            + "ROW_NUMBER() OVER (PARTITION BY endpoint_id ORDER BY created_at DESC, id DESC) AS rn "
            + "FROM triage_runs WHERE service_id = ? AND endpoint_id IS NOT NULL) history_endpoints "
            + "WHERE rn = 1 ORDER BY endpoint_route, endpoint_http_method, endpoint_id LIMIT 200",
            (row, n) -> runs.decode(row.getString(1)).endpoint(), selected);
    }
    public boolean deleteTerminal(UUID id) {
        return jdbc.update("DELETE FROM triage_runs WHERE id = ? AND status IN ('SUCCEEDED','INSUFFICIENT_EVIDENCE','FAILED','CANCELLED')", id.toString()) == 1;
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Statistics statistics(HistoryFilter filter) {
        var where = filter.sql(); var arguments = where.arguments().toArray();
        Map<String, Object> values = jdbc.queryForMap("SELECT COUNT(*) AS total, COALESCE(SUM(tool_calls),0) AS tools, "
            + "COALESCE(SUM(model_calls),0) AS models, SUM(CASE WHEN model_calls > 0 THEN 1 ELSE 0 END) AS called, "
            + "SUM(CASE WHEN model_calls > 0 AND usage_complete = TRUE THEN 1 ELSE 0 END) AS complete, "
            + "SUM(CASE WHEN model_calls > 0 AND known_total_tokens IS NOT NULL AND usage_complete = FALSE THEN 1 ELSE 0 END) AS partial, "
            + "SUM(CASE WHEN model_calls > 0 AND known_total_tokens IS NULL THEN 1 ELSE 0 END) AS missing, "
            + "COUNT(known_total_tokens) AS known, SUM(known_input_tokens) AS input_tokens, SUM(known_output_tokens) AS output_tokens, "
            + "SUM(known_total_tokens) AS total_tokens FROM triage_runs" + where.clause(), arguments);
        Map<Status, Long> statuses = new LinkedHashMap<>(); for (Status status : Status.values()) statuses.put(status, 0L);
        jdbc.query("SELECT status, COUNT(*) FROM triage_runs" + where.clause() + " GROUP BY status", row -> {
            statuses.put(Status.valueOf(row.getString(1)), row.getLong(2));
        }, arguments);
        String durationsWhere = where.clause() + (where.clause().isEmpty() ? " WHERE " : " AND ") + "duration_ms IS NOT NULL";
        var durations = jdbc.queryForObject("SELECT COUNT(*) AS samples, AVG(duration_ms) AS average, "
            + "PERCENTILE_DISC(0.95) WITHIN GROUP (ORDER BY duration_ms) AS p95, MAX(duration_ms) AS maximum "
            + "FROM triage_runs" + durationsWhere, (row, n) -> new Durations(row.getLong("samples"),
                row.getObject("average") == null ? null : ((Number) row.getObject("average")).doubleValue(),
                row.getObject("p95") == null ? null : ((Number) row.getObject("p95")).longValue(),
                row.getObject("maximum") == null ? null : ((Number) row.getObject("maximum")).longValue()), arguments);
        TokenUsage known = number(values, "known") == 0 ? null : new TokenUsage(number(values, "input_tokens"), number(values, "output_tokens"), number(values, "total_tokens"));
        return new Statistics(filter.from(), filter.until(), filter.service(), number(values, "total"), Collections.unmodifiableMap(statuses),
            number(values, "tools"), number(values, "models"), durations,
            new Usage(number(values, "called"), number(values, "complete"), number(values, "partial"), number(values, "missing"), known), filter.endpointId());
    }
    private long number(Map<String, Object> values, String name) { return values.get(name) == null ? 0 : ((Number) values.get(name)).longValue(); }
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
