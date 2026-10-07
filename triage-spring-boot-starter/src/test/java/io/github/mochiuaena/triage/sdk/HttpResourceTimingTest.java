package io.github.mochiuaena.triage.sdk;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class HttpResourceTimingTest {
    @Test void finishedDelayReportsRequestedWallOvershootAndCpu() {
        var clock = new Clock();
        var timing = new HttpResourceTiming(clock::wall, clock::cpu);
        timing.begin(11, HttpResourceTiming.Scenario.NORMAL);
        clock.advance(10, 1);
        timing.phase(11, HttpResourceTiming.Phase.DELAY_HEADERS, 350);
        clock.advance(425, 3);
        timing.end(11);

        var snapshot = timing.snapshot();
        assertThat(snapshot.active()).isEmpty();
        assertThat(snapshot.recent()).hasSize(2);
        var delay = snapshot.recent().getLast();
        assertThat(delay.requestedNanos()).isEqualTo(350_000_000L);
        assertThat(delay.elapsedNanos()).isEqualTo(425_000_000L);
        assertThat(delay.cpuNanos()).isEqualTo(3_000_000L);
        assertThat(snapshot.delaySamples()).isEqualTo(1);
        assertThat(snapshot.maxDelayElapsedNanos()).isEqualTo(425_000_000L);
        assertThat(snapshot.maxDelayOvershootNanos()).isEqualTo(75_000_000L);
    }

    @Test void snapshotReportsCurrentActivePhaseAndRequestElapsed() {
        var clock = new Clock();
        var timing = new HttpResourceTiming(clock::wall, clock::cpu);
        timing.begin(11, HttpResourceTiming.Scenario.HEADER_TIMEOUT);
        clock.advance(20, 2);
        timing.phase(11, HttpResourceTiming.Phase.DELAY_HEADERS, 1000);
        clock.advance(1300, 4);

        var snapshot = timing.snapshot();
        assertThat(snapshot.active()).hasSize(1);
        var active = snapshot.active().getFirst();
        assertThat(active.threadId()).isEqualTo(11);
        assertThat(active.scenario()).isEqualTo(HttpResourceTiming.Scenario.HEADER_TIMEOUT);
        assertThat(active.phase()).isEqualTo(HttpResourceTiming.Phase.DELAY_HEADERS);
        assertThat(active.elapsedNanos()).isEqualTo(1_300_000_000L);
        assertThat(active.requestElapsedNanos()).isEqualTo(1_320_000_000L);
        assertThat(active.cpuNanos()).isEqualTo(4_000_000L);
        assertThat(timing.snapshot().maxActiveDelayElapsedNanos()).isEqualTo(1_300_000_000L);
    }

    @Test void recentPhaseHistoryStaysBoundedAndWorkersReleaseCapacity() {
        var clock = new Clock();
        var timing = new HttpResourceTiming(clock::wall, clock::cpu);
        for (int thread = 1; thread <= 9; thread++) timing.begin(thread, HttpResourceTiming.Scenario.NORMAL);
        assertThat(timing.snapshot().active()).hasSize(8);
        assertThat(timing.snapshot().untrackedRequests()).isEqualTo(1);
        for (int thread = 1; thread <= 9; thread++) timing.end(thread);
        assertThat(timing.snapshot().active()).isEmpty();

        for (int request = 0; request < 80; request++) {
            timing.begin(20, HttpResourceTiming.Scenario.BODY_TIMEOUT);
            clock.advance(1, 0);
            timing.end(20);
        }
        var snapshot = timing.snapshot();
        assertThat(snapshot.active()).isEmpty();
        assertThat(snapshot.recent()).hasSize(64);
        assertThat(snapshot.recent().getFirst().requestId()).isEqualTo(25);
        assertThat(snapshot.recent().getLast().requestId()).isEqualTo(88);
    }

    @Test void closeReleasesRetainedStateAndRejectsMoreTracking() {
        var clock = new Clock();
        var timing = new HttpResourceTiming(clock::wall, clock::cpu);
        timing.begin(1, HttpResourceTiming.Scenario.NORMAL);
        timing.phase(1, HttpResourceTiming.Phase.WRITE_HEADERS, 0);
        assertThat(timing.snapshot().active()).hasSize(1);
        assertThat(timing.snapshot().recent()).hasSize(1);
        timing.close();
        timing.begin(2, HttpResourceTiming.Scenario.NORMAL);
        timing.phase(2, HttpResourceTiming.Phase.DELAY_HEADERS, 350);
        timing.end(2);
        assertThat(timing.snapshot().active()).isEmpty();
        assertThat(timing.snapshot().recent()).isEmpty();
    }

    @Test void unavailableCpuNeverAppearsAsZero() {
        var clock = new Clock();
        var timing = new HttpResourceTiming(clock::wall, threadId -> -1);
        timing.begin(1, HttpResourceTiming.Scenario.NORMAL);
        clock.advance(10, 0);
        assertThat(timing.snapshot().active()).hasSize(1);
        assertThat(timing.snapshot().active().getFirst().cpuNanos()).isEqualTo(-1);
        timing.end(1);
        assertThat(timing.snapshot().recent().getFirst().cpuNanos()).isEqualTo(-1);

        var initiallyAvailable = new HttpResourceTiming(clock::wall, clock::cpu);
        initiallyAvailable.begin(1, HttpResourceTiming.Scenario.NORMAL);
        clock.cpuNanos = -1;
        initiallyAvailable.end(1);
        assertThat(initiallyAvailable.snapshot().recent().getFirst().cpuNanos()).isEqualTo(-1);
    }

    @Test void driverSnapshotRetainsMaximumLagAndCurrentElapsed() {
        var clock = new Clock();
        var timing = new HttpResourceTiming(clock::wall, clock::cpu);
        timing.driverStarted(clock.wall());
        timing.driverLag(15_000_000L);
        timing.driverLag(5_000_000L);
        clock.advance(2500, 0);
        var snapshot = timing.snapshot();
        assertThat(snapshot.driverMaxLagNanos()).isEqualTo(15_000_000L);
        assertThat(snapshot.driverElapsedNanos()).isEqualTo(2_500_000_000L);
    }

    static final class Clock {
        long wallNanos, cpuNanos;
        long wall() { return wallNanos; }
        long cpu(long threadId) { return cpuNanos; }
        void advance(long wallMillis, long cpuMillis) {
            wallNanos += wallMillis * 1_000_000L;
            cpuNanos += cpuMillis * 1_000_000L;
        }
    }
}
