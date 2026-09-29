package io.github.mochiuaena.triage.sdk;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JdbcObserverTest {
    @Test void sqlRowLockTimeoutIsNotReportedAsConnectionAcquisitionTimeout() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setKind(TriageObservationProperties.Kind.DATABASE);
        var recorder = new ObservationRecorder(properties);
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:starter-lock;DB_CLOSE_DELAY=-1"); pool.setMaximumPoolSize(2);
            try (var observer = new TriageJdbcObserver(pool, recorder); var held = pool.getConnection()) {
                try (var statement = held.createStatement()) {
                    statement.execute("CREATE TABLE balances (id INT PRIMARY KEY, amount INT)");
                    statement.execute("INSERT INTO balances VALUES (1, 10)");
                    held.setAutoCommit(false);
                    statement.executeUpdate("UPDATE balances SET amount = 11 WHERE id = 1");
                }
                assertThatThrownBy(() -> observer.query(connection -> {
                    try (var statement = connection.createStatement()) {
                        statement.execute("SET LOCK_TIMEOUT 100");
                        return statement.executeUpdate("UPDATE balances SET amount = 12 WHERE id = 1");
                    }
                })).isInstanceOf(SQLException.class);
                held.rollback();
                var window = (ObservationRecorder.DatabaseWindow) recorder.snapshot(5, Instant.now());
                assertThat(window.databasePool().acquisitionTimeoutCount()).isZero();
                assertThat(window.databasePool().queryErrorCount()).isEqualTo(1);
                assertThat(window.errors().getFirst().code()).isEqualTo("SQL_QUERY_FAILED");
            }
        }
    }
    @Test void realAcquisitionTimeoutAndQueryFailureRemainSeparateAndConnectionsRecover() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setKind(TriageObservationProperties.Kind.DATABASE);
        var recorder = new ObservationRecorder(properties);
        try (var pool = new HikariDataSource()) {
            pool.setJdbcUrl("jdbc:h2:mem:starter;DB_CLOSE_DELAY=-1"); pool.setMaximumPoolSize(1); pool.setConnectionTimeout(250);
            try (var observer = new TriageJdbcObserver(pool, recorder)) {
                try (var held = pool.getConnection()) {
                    AtomicBoolean invoked = new AtomicBoolean();
                    assertThatThrownBy(() -> observer.query(connection -> { invoked.set(true); return null; }))
                        .isInstanceOf(SQLTransientConnectionException.class);
                    assertThat(invoked).isFalse();
                }
                assertThatThrownBy(() -> observer.query(connection -> {
                    try (var statement = connection.createStatement()) { return statement.execute("SELECT * FROM private_missing_table"); }
                })).isInstanceOf(SQLException.class);
                int answer = observer.query(connection -> {
                    try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT 42")) {
                        result.next(); return result.getInt(1);
                    }
                });
                assertThat(answer).isEqualTo(42);
                var value = (ObservationRecorder.DatabaseWindow) recorder.snapshot(5, Instant.now());
                assertThat(value.requestCount()).isEqualTo(3);
                assertThat(value.databasePool().acquisitionTimeoutCount()).isEqualTo(1);
                assertThat(value.databasePool().acquisitionErrorCount()).isZero();
                assertThat(value.databasePool().queryCount()).isEqualTo(2);
                assertThat(value.databasePool().queryErrorCount()).isEqualTo(1);
                assertThat(value.databasePool().exhaustedSamples()).isGreaterThan(0);
                assertThat(value.errors()).extracting(ObservationRecorder.DatabaseError::code)
                    .containsExactly("SQL_QUERY_FAILED", "DB_CONNECTION_ACQUIRE_TIMEOUT");
                assertThat(value.toString()).doesNotContain("private_missing_table", "jdbc:");
                assertThat(pool.getHikariPoolMXBean().getActiveConnections()).isZero();
            }
        }
    }
}
