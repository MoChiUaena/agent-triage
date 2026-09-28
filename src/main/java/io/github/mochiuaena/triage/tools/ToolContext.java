package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import java.time.Instant;
import java.util.Objects;

/** The scenario and time window are frozen when the run is submitted. */
public record ToolContext(String service, int windowMinutes, Scenario scenario, Instant endTime, ServiceRegistry.Target target) {
    public ToolContext(String service, int windowMinutes, Scenario scenario, Instant endTime) {
        this(service, windowMinutes, scenario, endTime, null);
    }
    public ToolContext {
        if (target == null ? !"order-service".equals(service) : !target.info().id().equals(service))
            throw new IllegalArgumentException("Service must match its registered target");
        if (windowMinutes < 1 || windowMinutes > 60) throw new IllegalArgumentException("Window must be 1..60 minutes");
        if (target != null && windowMinutes > target.maxWindowMinutes()) throw new IllegalArgumentException("Window exceeds the service limit");
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(endTime);
    }
    public Instant startTime() { return endTime.minusSeconds(windowMinutes * 60L); }
    public io.github.mochiuaena.triage.domain.TriageModel.ServiceInfo serviceInfo() {
        return target == null ? io.github.mochiuaena.triage.domain.TriageModel.ServiceInfo.order() : target.info();
    }
}
