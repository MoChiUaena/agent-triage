package org.springframework.samples.petclinic.triage;

import com.zaxxer.hikari.HikariDataSource;
import io.github.mochiuaena.triage.sdk.TriageJpaObserver;
import io.github.mochiuaena.triage.sdk.RuntimeClassAgent;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** Acceptance-only wiring: the upstream business classes are left unchanged. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "triage.sdk.jpa-observations", havingValue = "true")
public class PetclinicTriageConfiguration {
    @Bean(destroyMethod = "close")
    TriageJpaObserver.ObservedDataSource dataSource(DataSourceProperties properties, TriageJpaObserver observer) throws SQLException {
        HikariDataSource pool = properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
        pool.setMaximumPoolSize(1);
        pool.setConnectionTimeout(300);
        return observer.wrap(pool);
    }

    /** Transient, loopback-only operations used to verify the two database failure stages. */
    @RestController
    @ConditionalOnProperty(name = "triage.verification.lab-enabled", havingValue = "true")
    public static class LocalDatabaseCheck {
        private final DataSource source;
        public LocalDatabaseCheck(DataSource source) { this.source = source; }

        private void local(HttpServletRequest request, String header) {
            try {
                if ("1".equals(header) && InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()) return;
            } catch (Exception ignored) { /* Reject inaccessible or malformed addresses. */ }
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }

        @PostMapping("/verification/pool-hold")
        public ResponseEntity<Void> hold(@RequestHeader(value = "X-Triage-Lab", required = false) String header,
                                         HttpServletRequest request) throws Exception {
            local(request, header);
            try (var held = source.getConnection()) { Thread.sleep(1200); }
            return ResponseEntity.noContent().build();
        }

        @PostMapping("/owners/verification-sql")
        public ResponseEntity<Void> failedSql(@RequestHeader(value = "X-Triage-Lab", required = false) String header,
                                              HttpServletRequest request) throws SQLException {
            local(request, header);
            try (var connection = source.getConnection(); var statement = connection.createStatement()) {
                statement.executeQuery("SELECT missing_column FROM owners");
                return ResponseEntity.noContent().build();
            } catch (SQLException expected) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
        }

        @PostMapping("/owners/verification-class-error")
        public ResponseEntity<Void> classError(@RequestHeader(value = "X-Triage-Lab", required = false) String header,
                                               HttpServletRequest request) {
            local(request, header);
            ProbeFailure.fail();
            return ResponseEntity.noContent().build();
        }

        static final class ProbeFailure {
            static void fail() { throw new IllegalStateException("verification-only failure"); }
        }

        @PostMapping("/verification/class-lookup-benchmark")
        public Map<String, Long> classLookupBenchmark(@RequestHeader(value = "X-Triage-Lab", required = false) String header,
                                                       HttpServletRequest request) {
            local(request, header);
            if (!RuntimeClassAgent.active()) throw new IllegalStateException("Class lookup Agent is not active");
            String[] names = {LocalDatabaseCheck.class.getName(), ProbeFailure.class.getName()};
            for (int i = 0; i < 50; i++) verifyLookup(names);
            long[] elapsed = new long[200];
            for (int i = 0; i < elapsed.length; i++) {
                long started = System.nanoTime();
                verifyLookup(names);
                elapsed[i] = System.nanoTime() - started;
            }
            Arrays.sort(elapsed);
            return Map.of("samples", (long) elapsed.length, "p50Nanos", elapsed[elapsed.length / 2],
                "p95Nanos", elapsed[(int) (elapsed.length * 0.95)], "maxNanos", elapsed[elapsed.length - 1]);
        }

        private void verifyLookup(String[] names) {
            Class<?>[] found = RuntimeClassAgent.uniqueLoadedClasses(names);
            if (found.length != 2 || found[0] != LocalDatabaseCheck.class || found[1] != ProbeFailure.class)
                throw new IllegalStateException("The runtime class lookup is not unique");
        }
    }
}
