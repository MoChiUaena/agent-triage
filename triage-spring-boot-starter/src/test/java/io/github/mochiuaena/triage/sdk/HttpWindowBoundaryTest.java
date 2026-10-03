package io.github.mochiuaena.triage.sdk;

import java.time.*;
import java.util.*;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class HttpWindowBoundaryTest {
    @Test void denseCompletionTimesFailPromptlyInsteadOfPausingTheProducer() {
        Instant now = Instant.now();
        var completions = IntStream.rangeClosed(0, 200)
            .mapToObj(index -> now.minusSeconds(60).plusMillis(index * 10L)).toList();
        assertTimeoutPreemptively(Duration.ofMillis(250), () ->
            assertThatThrownBy(() -> HttpWindowBoundary.select(now, completions))
                .isInstanceOf(IllegalStateException.class));
    }

    @Test void selectsTheFirstSafeBoundaryWithoutWaitingForTheClock() throws Exception {
        Instant now = Instant.parse("2026-10-03T00:01:30Z");
        var completions = List.of(now.minusMillis(1), now.minusSeconds(60).plusMillis(75));
        assertThat(HttpWindowBoundary.select(now, completions)).isEqualTo(now.plusMillis(125));
    }

    @Test void unsortedOverlappingGuardsStillLeaveEveryCompletionAwayFromBothEdges() throws Exception {
        Instant now = Instant.parse("2026-10-03T00:01:30Z");
        var completions = List.of(now.minusSeconds(60).plusMillis(150), now.minusMillis(1),
            now.minusSeconds(60).plusMillis(75), now.minusSeconds(60).plusMillis(125));
        Instant end = HttpWindowBoundary.select(now, completions);
        assertThat(end).isEqualTo(now.plusMillis(200));
        for (Instant completion : completions) {
            assertThat(Duration.between(completion, end).abs()).isGreaterThanOrEqualTo(Duration.ofMillis(50));
            assertThat(Duration.between(completion, end.minusSeconds(60)).abs()).isGreaterThanOrEqualTo(Duration.ofMillis(50));
        }
    }

    @Test void refusesACompletionAfterTheCapturedClock() {
        Instant now = Instant.parse("2026-10-03T00:01:30Z");
        assertThatThrownBy(() -> HttpWindowBoundary.select(now, List.of(now.plusNanos(1))))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
