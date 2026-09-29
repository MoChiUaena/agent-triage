package io.github.mochiuaena.triage.source;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.tools.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static io.github.mochiuaena.triage.source.SourceModels.*;

class SourceReadinessServiceTest {
    private final LiveObservationClient live = mock(LiveObservationClient.class);
    private final SourceProjectRepository projects = mock(SourceProjectRepository.class);
    private final SourceProjectService sources = mock(SourceProjectService.class);
    private SourceReadinessService service(String source) {
        var observation = new ObservationSource(source, "http://127.0.0.1:19000");
        var registry = new ServiceRegistry(observation, observation.synthetic() ? List.of() : List.of(new ServiceRegistry.Config(
            "fixture-service", "服务", "stock-service", "库存", "http://127.0.0.1:19000", ServiceRegistry.Protocol.OBSERVATIONS_V1, 2, false)));
        when(projects.byService(anyString())).thenReturn(Optional.empty()); when(live.scenario(any())).thenReturn(Scenario.OBSERVED);
        return new SourceReadinessService(registry, observation, live, projects, sources);
    }
    private LiveObservationClient.Snapshot empty() {
        Instant end = Instant.now(); return new LiveObservationClient.Snapshot("fixture-service", Scenario.OBSERVED, end.minusSeconds(120), end,
            0, 0, 0, 0, 0, 0, 0, null, List.of(), false);
    }
    @Test void syntheticAndEmptyTrafficAreNotReportedAsLiveOrReadyAndDefaultWindowRespectsServiceLimit() {
        var synthetic = service("SYNTHETIC").check("order-service", null);
        assertThat(synthetic.state()).isEqualTo("SYNTHETIC"); assertThat(synthetic.observationsAvailable()).isFalse();
        verifyNoInteractions(live, sources);
        var actual = service("LIVE"); when(live.snapshot(any())).thenReturn(empty());
        var check = actual.check("fixture-service", null);
        assertThat(check.state()).isEqualTo("EMPTY"); assertThat(check.windowMinutes()).isEqualTo(2);
        assertThat(check.requestCount()).isZero(); assertThat(check.steps().get(1).state()).isEqualTo("WAIT");
    }
    @Test void observationFailureReturnsSafeStepsAndDoesNotReadSourceFiles() {
        var service = service("LIVE"); when(live.snapshot(any())).thenThrow(new ObservationFailure("OBSERVATION_UNAVAILABLE", "观测接口不可用。"));
        var result = service.check("fixture-service", 1);
        assertThat(result.state()).isEqualTo("OBSERVATION_UNAVAILABLE"); assertThat(result.observationsAvailable()).isFalse();
        assertThat(result.steps().getFirst().state()).isEqualTo("BLOCKED");
        assertThat(result.toString()).doesNotContain("19000"); verifyNoInteractions(sources);
    }
    @Test void inspectionDetectsAProjectAddedDuringTheCheck() {
        var service = service("LIVE"); when(live.snapshot(any())).thenReturn(empty());
        var added = new Stored(UUID.randomUUID(), "fixture", "fixture-service", "/private/root", 1,
            new Index("fixture", Instant.now(), 0, 0, 0, List.of()), Instant.now(), null, null, null, null);
        when(projects.byService("fixture-service")).thenReturn(Optional.empty(), Optional.of(added));
        var result = service.check("fixture-service", 1);
        assertThat(result.state()).isEqualTo("PROJECT_CHANGED"); assertThat(result.endpoints()).isEmpty();
        assertThat(result.steps().get(2).message()).contains("已变化");
    }
    @Test void parallelChecksAreBoundedAndCapacityReturnsAfterCompletion() throws Exception {
        var service = service("LIVE"); var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        when(live.snapshot(any())).thenAnswer(input -> { entered.countDown(); assertThat(release.await(4, TimeUnit.SECONDS)).isTrue(); return empty(); });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.check("fixture-service", 1)); var second = pool.submit(() -> service.check("fixture-service", 1));
            try {
                assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> service.check("fixture-service", 1)).isInstanceOfSatisfying(ResponseStatusException.class,
                    error -> assertThat(error.getStatusCode().value()).isEqualTo(429));
            } finally { release.countDown(); }
            assertThat(first.get(2, TimeUnit.SECONDS).state()).isEqualTo("EMPTY"); assertThat(second.get(2, TimeUnit.SECONDS).state()).isEqualTo("EMPTY");
        }
        assertThat(service.check("fixture-service", 1).state()).isEqualTo("EMPTY");
    }
}
