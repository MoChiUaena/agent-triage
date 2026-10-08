package io.github.mochiuaena.triage.sdk;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

class HttpJvmScheduleProbeTest {
    @Test void reportsOnlyNumericWakeupGapsNearTheFailure() {
        var probe = new HttpJvmScheduleProbe();
        probe.tick(0, 1_000);
        probe.tick(200_000_000L, 1_200);
        probe.tick(500_000_000L, 1_500);
        var report = probe.render(1_600);
        assertThat(report).contains("RESOURCE_JVM_SCHEDULE kind=summary ticks=2 peakGapMs=200.000",
            "nearEvents=2 nearPeakGapMs=200.000", "intervalMs=100 thresholdMs=50",
            "RESOURCE_JVM_SCHEDULE kind=near uptimeMs=1200 gapMs=100.000",
            "RESOURCE_JVM_SCHEDULE kind=near uptimeMs=1500 gapMs=200.000");
        assertThat(report).doesNotContain("thread=", "source=", "path=");
    }

    @Test void capsHistoryWithoutLosingPeakAndStopsItsOwnedThread() {
        var probe = new HttpJvmScheduleProbe();
        probe.tick(0, 0);
        long now = 0;
        for (int index = 1; index <= 520; index++) {
            now += index == 1 ? 500_000_000L : 160_000_000L;
            probe.tick(now, index * 160L);
        }
        var report = probe.render(83_200);
        assertThat(report).contains("ticks=520", "peakGapMs=400.000", "recordedGaps=512");
        assertThat(report).doesNotContain("kind=near uptimeMs=160 ");

        var running = HttpJvmScheduleProbe.start();
        await().atMost(Duration.ofSeconds(2)).until(() -> running.tickCount() > 0);
        assertThat(running.stop()).isTrue();
    }
}
