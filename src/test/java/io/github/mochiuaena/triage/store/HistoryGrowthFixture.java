package io.github.mochiuaena.triage.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Owns only a freshly created file database or a randomly named private schema. */
final class HistoryGrowthFixture implements AutoCloseable {
    static final Instant CUTOFF = Instant.parse("2026-07-01T00:00:00Z");
    static final String ENDPOINT_ID = "EP-" + "a".repeat(32);
    static final HistoryFilter ALL = new HistoryFilter(null, null, null, null, null, null);
    static final HistoryFilter SELECTED = new HistoryFilter("synthetic triage", "synthetic-orders", Status.SUCCEEDED,
        "MODEL", Instant.parse("2026-01-01T00:00:00Z"), CUTOFF, ENDPOINT_ID);
    private static final Pattern POSTGRES = Pattern.compile("jdbc:postgresql://(?:localhost|127\\.0\\.0\\.1):([0-9]{1,5})/history_growth_ci");
    private final String url;
    private final String user;
    private final String password;
    private final String schema;
    private final Path h2File;
    final int backend;
    private HikariDataSource source;
    JdbcTemplate jdbc;
    RunRepository runs;
    HistoryRepository history;
    private TransactionTemplate read;
    private TransactionTemplate write;
    private long loadNanos;

    static void validatePostgresUrl(String url) {
        var matcher = POSTGRES.matcher(url == null ? "" : url);
        if (!matcher.matches() || Integer.parseInt(matcher.group(1)) < 1 || Integer.parseInt(matcher.group(1)) > 65535)
            throw new IllegalArgumentException("Use the dedicated loopback history_growth_ci database without URL parameters");
    }

    static int[] checkpoints(String value) {
        if (value == null) return new int[]{120, 240, 360};
        String[] parts = value.split(",", -1);
        if (parts.length < 3 || parts.length > 5) throw new IllegalArgumentException("Use 3..5 increasing checkpoints");
        int[] counts = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                counts[i] = Integer.parseInt(parts[i]);
                if (counts[i] < 24 || counts[i] > 10000 || i > 0 && counts[i] <= counts[i - 1])
                    throw new IllegalArgumentException("Checkpoints must increase within 24..10000 rows");
            }
        } catch (NumberFormatException e) { throw new IllegalArgumentException("Checkpoints must be integers"); }
        return counts;
    }

    static HistoryGrowthFixture h2(Path directory) throws IOException {
        Path base = directory.toRealPath().resolve("history");
        return new HistoryGrowthFixture("jdbc:h2:file:" + base.toString().replace('\\', '/') + ";DB_CLOSE_ON_EXIT=FALSE",
            "sa", "", null, Path.of(base + ".mv.db"), 1);
    }

    static HistoryGrowthFixture postgres(String url, String user, String password) {
        validatePostgresUrl(url);
        String schema = "history_growth_" + UUID.randomUUID().toString().replace("-", "");
        try (var admin = dataSource(url, user, password, null)) {
            var jdbc = new JdbcTemplate(admin);
            Integer version = jdbc.queryForObject("SELECT current_setting('server_version_num')::integer", Integer.class);
            if (version == null || version < 160000 || version >= 170000)
                throw new IllegalArgumentException("This fixture requires PostgreSQL 16");
            if (!"UTF8".equals(jdbc.queryForObject("SHOW server_encoding", String.class)))
                throw new IllegalArgumentException("This fixture requires a UTF-8 database");
            jdbc.execute("CREATE SCHEMA " + schema);
        }
        try { return new HistoryGrowthFixture(url, user, password, schema, null, 2); }
        catch (RuntimeException e) { dropSchema(url, user, password, schema); throw e; }
    }

    private HistoryGrowthFixture(String url, String user, String password, String schema, Path h2File, int backend) {
        this.url = url; this.user = user; this.password = password; this.schema = schema; this.h2File = h2File; this.backend = backend;
        reopen();
        try {
            new ResourceDatabasePopulator(new ClassPathResource("db/migration/V1__execution_records.sql"),
                new ClassPathResource("db/migration/V3__history_projection.sql"),
                new ClassPathResource("db/migration/V6__endpoint_history.sql")).execute(source);
        } catch (RuntimeException e) { source.close(); throw e; }
    }

    private static HikariDataSource dataSource(String url, String user, String password, String schema) {
        var config = new HikariConfig();
        config.setJdbcUrl(url); config.setUsername(user); config.setPassword(password);
        config.setMaximumPoolSize(2); config.setMinimumIdle(0); config.setConnectionTimeout(10000);
        config.setPoolName("history-growth");
        if (schema != null) config.setSchema(schema);
        return new HikariDataSource(config);
    }

    void reopen() {
        if (source != null) source.close();
        source = dataSource(url, user, password, schema);
        jdbc = new JdbcTemplate(source);
        var json = new ObjectMapper().findAndRegisterModules();
        runs = new RunRepository(jdbc, json); history = new HistoryRepository(jdbc, json, runs);
        var manager = new DataSourceTransactionManager(source);
        read = new TransactionTemplate(manager);
        read.setReadOnly(true); read.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        write = new TransactionTemplate(manager); write.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
    }

    void append(int from, int until) {
        long started = System.nanoTime();
        for (int first = from; first < until; first += 100) {
            final int start = first, end = Math.min(until, first + 100);
            write.executeWithoutResult(status -> { for (int i = start; i < end; i++) runs.insert(synthetic(i)); });
        }
        loadNanos += System.nanoTime() - started;
    }

    HistoryRepository.Page page(HistoryFilter filter, String cursor) {
        return read.execute(status -> history.page(filter, 50, cursor));
    }

    HistoryRepository.Statistics statistics(HistoryFilter filter) {
        return read.execute(status -> history.statistics(filter));
    }

    long cleanup(long expected) { return write.execute(status -> history.deleteOldTerminal(CUTOFF, expected)); }

    long physicalBytes() throws IOException {
        if (backend == 2) return jdbc.queryForObject("SELECT pg_total_relation_size('triage_runs')", Long.class);
        jdbc.execute("CHECKPOINT SYNC");
        return Files.size(h2File);
    }

    Measurement measure(int checkpoint, int phase, long deleted, long cleanupNanos) throws IOException {
        String length = backend == 1 ? "OCTET_LENGTH(STRINGTOUTF8(payload))" : "OCTET_LENGTH(payload)";
        var payload = jdbc.queryForMap("SELECT COUNT(*) AS rows, COALESCE(SUM(" + length + "),0) AS bytes, "
            + "COALESCE(MIN(" + length + "),0) AS minimum, COALESCE(MAX(" + length + "),0) AS maximum FROM triage_runs");
        long projected = jdbc.queryForObject("SELECT COUNT(*) FROM triage_runs WHERE history_version = 1 "
            + "AND service_id IS NOT NULL AND question_text IS NOT NULL", Long.class);
        String cursor = page(ALL, null).nextCursor();
        return new Measurement(backend, checkpoint, phase, number(payload, "rows"), number(payload, "bytes"),
            number(payload, "minimum"), number(payload, "maximum"), physicalBytes(), projected, loadNanos, deleted,
            cleanupNanos, timing(() -> page(ALL, null)), cursor == null ? new Timing(0, 0, 0, 0) : timing(() -> page(ALL, cursor)),
            timing(() -> page(SELECTED, null)),
            timing(() -> statistics(ALL)), timing(() -> statistics(SELECTED)));
    }

    private static long number(Map<String, Object> values, String key) { return ((Number) values.get(key)).longValue(); }

    private static Timing timing(Supplier<?> query) {
        query.get();
        long[] samples = new long[5];
        for (int i = 0; i < samples.length; i++) { long start = System.nanoTime(); query.get(); samples[i] = System.nanoTime() - start; }
        Arrays.sort(samples);
        return new Timing(samples[0], samples[2], samples[4], samples.length);
    }

    record Timing(long minimumNanos, long medianNanos, long maximumNanos, int samples) {
        String csv() { return minimumNanos + "," + medianNanos + "," + maximumNanos; }
    }
    record Measurement(int backend, int checkpointRows, int phase, long rows, long payloadBytes, long minPayloadBytes,
                       long maxPayloadBytes, long physicalBytes, long projectedRows, long loadNanos, long deletedRows,
                       long cleanupNanos, Timing page, Timing cursorPage, Timing filteredPage, Timing statistics, Timing filteredStatistics) {
        String csv() {
            return backend + "," + checkpointRows + "," + phase + "," + rows + "," + payloadBytes + "," + minPayloadBytes + ","
                + maxPayloadBytes + "," + physicalBytes + "," + projectedRows + "," + loadNanos + "," + deletedRows + "," + cleanupNanos
                + "," + page.csv() + "," + cursorPage.csv() + "," + cursorPage.samples() + "," + filteredPage.csv() + ","
                + statistics.csv() + "," + filteredStatistics.csv();
        }
    }

    static void report(String name, List<Measurement> measurements) throws IOException {
        Path directory = Path.of("target", "history-growth"); Files.createDirectories(directory);
        String header = "backend,checkpoint_rows,phase,rows,payload_bytes,min_payload_bytes,max_payload_bytes,physical_bytes,projected_rows,"
            + "load_ns,deleted_rows,cleanup_ns";
        for (String query : List.of("page", "cursor_page", "filtered_page", "statistics", "filtered_statistics")) {
            header += "," + query + "_min_ns," + query + "_median_ns," + query + "_max_ns";
            if (query.equals("cursor_page")) header += ",cursor_page_samples";
        }
        var lines = new ArrayList<String>(); lines.add(header); measurements.forEach(value -> lines.add(value.csv()));
        Files.write(directory.resolve(name + ".csv"), lines, StandardCharsets.UTF_8);
    }

    static UUID id(int index) { return new UUID(0, index + 1L); }

    static Run synthetic(int index) {
        int slot = index % 12, profile = index % 3, group = index / 12;
        Status status = switch (slot) {
            case 0 -> Status.QUEUED; case 1 -> Status.RUNNING; case 2, 6, 10 -> Status.SUCCEEDED;
            case 3, 7 -> Status.INSUFFICIENT_EVIDENCE; case 4, 8, 11 -> Status.FAILED; default -> Status.CANCELLED;
        };
        Instant created = slot < 2 || slot >= 6 && slot <= 9
            ? Instant.parse("2026-01-01T00:00:00.123456789Z").plusSeconds(group)
            : slot == 10 ? CUTOFF : Instant.parse("2026-08-01T00:00:00.123456789Z").plusSeconds(group);
        String service = index % 2 == 0 ? "synthetic-orders" : "synthetic-inventory";
        var endpoint = index % 2 == 0 ? new RequestEndpoint(ENDPOINT_ID, "GET", "/synthetic/orders/{id}",
            "fixture.OrderController", "findOrder", List.of("java.lang.String"), "MVC_HANDLER_SELECTED") : null;
        var events = new ArrayList<Event>(); var evidence = new ArrayList<Evidence>();
        int eventCount = new int[]{4, 8, 16}[profile], evidenceCount = new int[]{2, 4, 8}[profile];
        int summaryLength = new int[]{160, 512, 2048}[profile];
        for (int i = 0; i < eventCount; i++) events.add(new Event(i + 1, created.plusMillis(i), "TOOL_RESULT", "fixture_metrics",
            "Synthetic metric observation " + index + "/" + i, List.of("E-" + (i % evidenceCount))));
        for (int i = 0; i < evidenceCount; i++) evidence.add(new Evidence("E-" + i, "SYNTHETIC", "Synthetic request metrics",
            text(index, i, summaryLength), Map.of("requests", 100 + index % 100, "requestP95Ms", 75 + index % 900,
                "timeoutCount", index % 7, "sample", i)));
        ModelExecution model = null;
        if (slot % 3 == 0) {
            TokenUsage usage = slot == 3 ? new TokenUsage(1200, 180, 1380) : null;
            TokenUsage known = slot == 6 ? new TokenUsage(1000, 120, 1120) : usage;
            model = new ModelExecution("synthetic-model", "synthetic-model", 2, usage, null,
                "Synthetic assessment from fixture metrics", List.of("Check synthetic request latency"), List.of(),
                known, status.terminal() ? 2 : 0, known == null ? 0 : usage == null ? 1 : 2);
        }
        var diagnosis = status.terminal() ? new Diagnosis(List.of(new Finding("Synthetic latency sample", List.of("E-0"))),
            List.of(new Finding("Synthetic downstream delay", List.of("E-1"))), List.of("Inspect synthetic metrics"),
            "Fixture evidence cannot describe a real incident") : null;
        return new Run(id(index), "Synthetic triage request " + index, service, 5, Scenario.OBSERVED, model == null ? "DEMO" : "MODEL",
            true, status, created, status.terminal() ? created.plusMillis(500 + slot * 100L) : null, 6, events, evidence,
            diagnosis, status == Status.FAILED ? new Failure("SYNTHETIC_FAILURE", "Synthetic fixture failure") : null,
            model, new ServiceInfo(service, service, "synthetic-stock", "Synthetic stock"), null, endpoint);
    }

    private static String text(int index, int evidence, int length) {
        var random = new Random(31L * index + evidence);
        String alphabet = "abcdefghijklmnopqrstuvwxyz0123456789 "; var value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(alphabet.charAt(random.nextInt(alphabet.length())));
        return value.toString();
    }

    private static void dropSchema(String url, String user, String password, String schema) {
        if (!schema.matches("history_growth_[a-f0-9]{32}")) throw new IllegalArgumentException("Invalid owned schema");
        try (var admin = dataSource(url, user, password, null)) { new JdbcTemplate(admin).execute("DROP SCHEMA " + schema + " CASCADE"); }
    }

    @Override public void close() {
        source.close();
        if (schema != null) dropSchema(url, user, password, schema);
    }
}
