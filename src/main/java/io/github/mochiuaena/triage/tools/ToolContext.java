package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import java.time.Instant;
import java.util.Objects;

/** The scenario and time window are frozen when the run is submitted. */
public record ToolContext(String service, int windowMinutes, Scenario scenario, Instant endTime, ServiceRegistry.Target target,
                          io.github.mochiuaena.triage.domain.TriageModel.RequestEndpoint endpoint) {
    public ToolContext(String service, int windowMinutes, Scenario scenario, Instant endTime, ServiceRegistry.Target target) {
        this(service, windowMinutes, scenario, endTime, target, null);
    }
    public ToolContext(String service, int windowMinutes, Scenario scenario, Instant endTime) {
        this(service, windowMinutes, scenario, endTime, null);
    }
    public ToolContext {
        if (target == null ? !"order-service".equals(service) : !target.info().id().equals(service))
            throw new IllegalArgumentException("Service must match its registered target");
        if (windowMinutes < 1 || windowMinutes > 60) throw new IllegalArgumentException("Window must be 1..60 minutes");
        if (target != null && windowMinutes > target.maxWindowMinutes()) throw new IllegalArgumentException("Window exceeds the service limit");
        if (endpoint != null && (target == null || !target.protocol().supportsEndpoints()
            || endpoint.id() == null || !endpoint.id().matches("EP-[a-f0-9]{32}"))) throw new IllegalArgumentException("Endpoint does not match its observation protocol");
        Objects.requireNonNull(scenario);
        Objects.requireNonNull(endTime);
    }
    public Instant startTime() { return endTime.minusSeconds(windowMinutes * 60L); }
    public io.github.mochiuaena.triage.domain.TriageModel.ServiceInfo serviceInfo() {
        return target == null ? io.github.mochiuaena.triage.domain.TriageModel.ServiceInfo.order() : target.info();
    }
}
