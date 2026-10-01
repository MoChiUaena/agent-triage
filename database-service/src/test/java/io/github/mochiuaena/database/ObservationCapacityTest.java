package io.github.mochiuaena.database;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ObservationCapacityTest {
    @TempDir Path directory;
    private HikariDataSource pool;
    private SimpleMeterRegistry metrics;
    private ObservationStore store;
    private MockMvc mvc;

    @BeforeEach void createStore() {
        pool = new HikariDataSource();
        pool.setJdbcUrl("jdbc:h2:mem:" + UUID.randomUUID()); pool.setMaximumPoolSize(2);
        metrics = new SimpleMeterRegistry();
        store = new ObservationStore(pool, metrics, new ObjectMapper().findAndRegisterModules(),
            "account-service", "accounts-db", directory.resolve("errors.jsonl").toString());
        mvc = MockMvcBuilders.standaloneSetup(new ObservationsController(store)).build();
    }
    @AfterEach void close() { pool.close(); metrics.close(); }

    @Test void exactRequestCapacityIsReadableAndOneMoreRequestRejectsTheWindow() throws Exception {
        Instant end = Instant.parse("2026-10-01T00:00:00Z");
        for (int i = 0; i < 10_000; i++) store.record(sample(end));
        mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requestCount").value(10_000))
            .andExpect(jsonPath("$.databasePool.queryCount").value(10_000));
        store.record(sample(end));
        mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.toString()))
            .andExpect(status().isUnprocessableEntity());
    }

    @Test void includedDroppedBoundaryIsRejectedButAnUnaffectedNewerWindowIsReadable() throws Exception {
        Instant end = Instant.parse("2026-10-01T00:00:00Z");
        store.record(sample(end.minusSeconds(60)));
        for (int i = 0; i < 10_000; i++) store.record(sample(end));
        mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.toString()))
            .andExpect(status().isUnprocessableEntity());
        mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.plusNanos(1).toString()))
            .andExpect(status().isOk()).andExpect(jsonPath("$.requestCount").value(10_000));
    }

    @Test void evictingPoolSamplesAlsoRejectsTheWindowEvenWhenNoRequestWasEvicted() throws Exception {
        try (var connection = pool.getConnection()) {
            for (int i = 0; i < 72_000; i++) store.samplePool();
            mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", Instant.now().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.databasePool.poolSamples").value(72_000));
            store.samplePool();
            mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", Instant.now().toString()))
                .andExpect(status().isUnprocessableEntity());
        }
    }

    private ObservationStore.RequestSample sample(Instant time) {
        return new ObservationStore.RequestSample(time, 4, 1, 3, false, false, false, null);
    }
}
