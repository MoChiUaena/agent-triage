package io.github.mochiuaena.triage.sdk;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;

/** Records one connection acquisition plus the callback as one operation; never retains SQL. */
public final class TriageJdbcObserver implements AutoCloseable {
    @FunctionalInterface public interface Query<T> { T execute(Connection connection) throws SQLException; }
    private final HikariDataSource pool;
    private final ObservationRecorder recorder;
    private final ScheduledExecutorService sampler = Executors.newSingleThreadScheduledExecutor(
        Thread.ofPlatform().daemon(true).name("triage-pool-sampler").factory());
    TriageJdbcObserver(DataSource source, ObservationRecorder recorder) throws SQLException {
        this.pool = source instanceof HikariDataSource hikari ? hikari : source.unwrap(HikariDataSource.class);
        this.recorder = recorder;
        if (pool.getMaximumPoolSize() < 1 || pool.getMaximumPoolSize() > 64)
            throw new IllegalArgumentException("triage.sdk DATABASE requires a HikariCP pool with maximum 1..64");
        recorder.configurePool(pool.getMaximumPoolSize());
        sampler.scheduleWithFixedDelay(() -> {
            var bean = pool.getHikariPoolMXBean();
            if (bean != null) recorder.pool(pool.getMaximumPoolSize(), bean.getActiveConnections(), bean.getThreadsAwaitingConnection());
        }, 0, 50, TimeUnit.MILLISECONDS);
    }
    public <T> T query(Query<T> query) throws SQLException {
        java.util.Objects.requireNonNull(query);
        long start = System.nanoTime();
        long queryStart = 0;
        double acquisitionMs = 0;
        double queryMs = 0;
        String code = null;
        try {
            Connection connection = pool.getConnection();
            acquisitionMs = ObservationRecorder.elapsed(start);
            queryStart = System.nanoTime();
            try (connection) {
                try { return query.execute(connection); }
                finally { queryMs = ObservationRecorder.elapsed(queryStart); }
            }
        } catch (SQLException | RuntimeException e) {
            code = queryStart != 0 ? "SQL_QUERY_FAILED" : e instanceof SQLTransientConnectionException
                ? "DB_CONNECTION_ACQUIRE_TIMEOUT" : "DB_CONNECTION_ACQUIRE_FAILED";
            throw e;
        } finally {
            if (queryStart == 0) acquisitionMs = ObservationRecorder.elapsed(start);
            recorder.recordDatabase(ObservationRecorder.elapsed(start), acquisitionMs,
                queryMs, code, UUID.randomUUID().toString());
        }
    }
    @Override public void close() { sampler.shutdownNow(); }
}
