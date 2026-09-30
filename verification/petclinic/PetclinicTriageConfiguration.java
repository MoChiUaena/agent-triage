package org.springframework.samples.petclinic.triage;

import com.zaxxer.hikari.HikariDataSource;
import io.github.mochiuaena.triage.sdk.TriageJpaObserver;
import jakarta.servlet.http.HttpServletRequest;
import java.net.InetAddress;
import java.sql.SQLException;
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
    }
}
