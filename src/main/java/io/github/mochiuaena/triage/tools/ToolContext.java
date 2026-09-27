package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import java.time.Instant;
import java.util.Objects;

/** The scenario and time window are frozen when the run is submitted. */
public record ToolContext(String service, int windowMinutes, Scenario scenario, Instant endTime) {
    public ToolContext {
        if (!"order-service".equals(service)) throw new IllegalArgumentException("Only order-service is supported");
        if (windowMinutes < 1 || windowMinutes > 60) throw new IllegalArgumentException("Window must be 1..60 minutes");
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(endTime);
    }
    public Instant startTime() { return endTime.minusSeconds(windowMinutes * 60L); }
}
