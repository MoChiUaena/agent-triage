package io.github.mochiuaena.triage.sdk;

import java.time.*;
import java.util.List;

/** Selects the minute used to compare the observer with the independent completion audit. */
final class HttpWindowBoundary {
    static Instant select(Instant now, List<Instant> completions) {
        if (completions.stream().anyMatch(time -> time.isAfter(now)))
            throw new IllegalArgumentException("Completion audit must precede the captured clock");
        // The producer is quiescent. Move the query time, never the wall clock or the send schedule.
        Instant end = now.plusMillis(50), limit = now.plusSeconds(1);
        for (Instant completion : completions.stream().sorted().toList()) {
            Instant excludedStart = completion.plusSeconds(60).minusMillis(50);
            Instant excludedEnd = completion.plusSeconds(60).plusMillis(50);
            if (end.isAfter(excludedStart) && end.isBefore(excludedEnd)) end = excludedEnd;
            if (end.isAfter(limit)) throw new IllegalStateException("No unambiguous minute boundary within one second");
        }
        return end;
    }
}
