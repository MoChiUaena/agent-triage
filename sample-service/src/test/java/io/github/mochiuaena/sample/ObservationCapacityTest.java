package io.github.mochiuaena.sample;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ObservationCapacityTest {
    @TempDir Path directory;

    @Test void exactCapacityIsReadableButEvictingAnIncludedRequestRejectsTheWindow() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try {
            var store = store(metrics);
            var mvc = MockMvcBuilders.standaloneSetup(new ObservationsController(store)).build();
            Instant end = Instant.parse("2026-10-01T00:00:00Z");
            for (int i = 0; i < 10_000; i++) store.record(sample(end, i));
            mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestCount").value(10_000));
            store.record(sample(end, 10_000));
            mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.toString()))
                .andExpect(status().isUnprocessableEntity());
        } finally { metrics.close(); }
    }

    @Test void droppedTimestampAtTheInclusiveStartIsRejectedAndANewerWindowRecovers() throws Exception {
        var metrics = new SimpleMeterRegistry();
        try {
            var store = store(metrics);
            var mvc = MockMvcBuilders.standaloneSetup(new ObservationsController(store)).build();
            Instant end = Instant.parse("2026-10-01T00:00:00Z");
            store.record(sample(end.minusSeconds(60), 0));
            for (int i = 1; i <= 10_000; i++) store.record(sample(end, i));
            mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.toString()))
                .andExpect(status().isUnprocessableEntity());
            mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end.plusNanos(1).toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestCount").value(10_000))
                .andExpect(jsonPath("$.timeoutCount").value(0));
        } finally { metrics.close(); }
    }

    private ObservationStore store(SimpleMeterRegistry metrics) {
        var errors = new ErrorJournal(directory.resolve("errors.jsonl").toString(), new ObjectMapper().findAndRegisterModules());
        return new ObservationStore(metrics, errors, "order-service", "inventory-service");
    }
    private ObservationStore.RequestSample sample(Instant time, int index) {
        return new ObservationStore.RequestSample(time, ObservationStore.Scenario.NORMAL, 20, 10, "ok", "fixture-" + index);
    }
}
