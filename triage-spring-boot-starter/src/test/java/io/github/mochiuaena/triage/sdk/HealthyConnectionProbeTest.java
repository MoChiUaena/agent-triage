package io.github.mochiuaena.triage.sdk;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HealthyConnectionProbeTest {
    @Test void earlyRepeatedPortIsConnectionReuseEvidence() {
        var probe = new HealthyConnectionProbe();
        probe.accept(49152); probe.accept(49153); probe.accept(49152);
        assertThat(probe.reused()).isTrue();
    }
    @Test void reallocatedPortAfterTheInitialProbeDoesNotCountAsReuse() {
        var probe = new HealthyConnectionProbe();
        for (int i = 0; i < 32; i++) probe.accept(49152 + i);
        assertThat(probe.reused()).isFalse();
        probe.accept(49152);
        assertThat(probe.reused()).isFalse();
    }
}
