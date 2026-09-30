package io.github.mochiuaena.triage.sdk;

import com.zaxxer.hikari.HikariDataSource;
import java.sql.*;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class JpaObserverTest {
    private TriageObservationProperties properties() {
        var properties = ObservationRecorderTest.properties();
        properties.setJpaObservations(true);
        properties.setJpaServiceId("catalog-db-service");
        properties.setJpaDatabaseId("catalog-db");
        properties.validate();
        return properties;
    }

    @Test void observesOnlyScopedJdbcOperationsAndKeepsPoolAndSqlFailuresSeparate() throws Exception {
        try (var observer = new TriageJpaObserver(properties())) {
            var pool = new HikariDataSource();
            pool.setJdbcUrl("jdbc:h2:mem:jpa-observer;DB_CLOSE_DELAY=-1");
            pool.setMaximumPoolSize(1); pool.setConnectionTimeout(250);
            try (var source = observer.wrap(pool)) {
                try (var unobserved = source.getConnection(); var statement = unobserved.createStatement()) {
                    statement.execute("SELECT 1");
                }
                var empty = (ObservationRecorder.DatabaseWindow) observer.snapshot(5, Instant.now());
                assertThat(empty.requestCount()).isZero();
                try (var held = source.getConnection()) {
                    TriageRequestFilter.CURRENT.set(new TriageRequestFilter.Context());
                    try { assertThatThrownBy(source::getConnection).isInstanceOf(SQLTransientConnectionException.class); }
                    finally { TriageRequestFilter.CURRENT.remove(); }
                }
                TriageRequestFilter.CURRENT.set(new TriageRequestFilter.Context());
                try {
                    try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                        assertThatThrownBy(() -> statement.execute("SELECT * FROM private_missing_table")).isInstanceOf(SQLException.class);
                        try (var result = statement.executeQuery("SELECT 42")) {
                            assertThat(result.next()).isTrue();
                            assertThat(result.getInt(1)).isEqualTo(42);
                        }
                    }
                } finally { TriageRequestFilter.CURRENT.remove(); }
                var window = (ObservationRecorder.DatabaseWindow) observer.snapshot(5, Instant.now());
                assertThat(window.service()).isEqualTo("catalog-db-service");
                assertThat(window.database()).isEqualTo("catalog-db");
                assertThat(window.requestCount()).isEqualTo(3);
                assertThat(window.databasePool().acquisitionTimeoutCount()).isEqualTo(1);
                assertThat(window.databasePool().queryCount()).isEqualTo(2);
                assertThat(window.databasePool().queryErrorCount()).isEqualTo(1);
                assertThat(window.databasePool().exhaustedSamples()).isGreaterThan(0);
                assertThat(window.errors()).extracting(ObservationRecorder.DatabaseError::code)
                    .containsExactlyInAnyOrder("SQL_QUERY_FAILED", "DB_CONNECTION_ACQUIRE_TIMEOUT");
                assertThat(window.toString()).doesNotContain("private_missing_table", "jdbc:");
                assertThat(source.toString()).doesNotContain("jdbc:");
            }
        }
    }
}
