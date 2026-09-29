package io.github.mochiuaena.triage.sdk;

import java.net.URI;
import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;

class ObservationRecorderTest {
    static TriageObservationProperties properties() {
        var value = new TriageObservationProperties();
        value.setServiceId("catalog-service"); value.setDownstreamId("inventory-service");
        value.setDownstreamBaseUrl(URI.create("http://127.0.0.1:18084"));
        value.validate(); return value;
    }
    @Test void windowIsExactBaselineIsUnknownAndCapacityLossIsRejected() {
        Instant time = Instant.parse("2026-09-29T00:00:00Z");
        var properties = properties(); properties.setCapacity(10);
        var recorder = new ObservationRecorder(properties, Clock.fixed(time, ZoneOffset.UTC));
        for (int i = 0; i < 10; i++) recorder.recordHttp(50, 40, true, true, "fixture");
        var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, time);
        assertThat(window.windowStart()).isEqualTo(time.minusSeconds(300));
        assertThat(window.requestCount()).isEqualTo(10);
        assertThat(window.timeoutCount()).isEqualTo(10);
        assertThat(window.errors()).hasSize(3);
        assertThat(window.baselineRequestP95Ms()).isNull();
        recorder.recordHttp(10, 0, false, false, "fixture");
        assertThatThrownBy(() -> recorder.snapshot(5, time)).isInstanceOfSatisfying(ResponseStatusException.class,
            e -> assertThat(e.getStatusCode().value()).isEqualTo(422));
    }
    @Test void onlyTheConfiguredOriginIsObserved() {
        var p = properties();
        assertThat(p.matches(URI.create("http://127.0.0.1:18084/api/inventory/a?private=value"))).isTrue();
        assertThat(p.matches(URI.create("http://127.0.0.1:18085/api/inventory/a"))).isFalse();
        assertThat(p.matches(URI.create("https://127.0.0.1:18084/api/inventory/a"))).isFalse();
        p.setDownstreamBaseUrl(URI.create("http://user:private@127.0.0.1:18084"));
        assertThatThrownBy(p::validate).isInstanceOf(IllegalArgumentException.class).hasMessageNotContaining("private");
    }
}
