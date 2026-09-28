package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.ServiceInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.util.*;

/** Startup-owned allowlist. Addresses never come from a run or a model tool call. */
@Component
public final class ServiceRegistry {
    public enum Protocol { LAB, OBSERVATIONS_V1, DATABASE_V2 }
    public record Config(String id, String name, String downstreamId, String downstreamName,
                         String baseUrl, Protocol protocol, Integer maxWindowMinutes, Boolean labEnabled) {}
    public record Target(ServiceInfo info, URI baseUrl, Protocol protocol, int maxWindowMinutes, boolean labEnabled) {}
    public record View(String id, String name, String downstreamId, String downstreamName,
                       Protocol protocol, int maxWindowMinutes, boolean labEnabled) {}
    private final Map<String, Target> targets;

    @Autowired
    public ServiceRegistry(ObservationSource source, Environment environment) {
        this(source, Binder.get(environment).bind("triage.services", Bindable.listOf(Config.class)).orElse(List.of()));
    }

    public ServiceRegistry(ObservationSource source, List<Config> configured) {
        var values = new LinkedHashMap<String, Target>();
        if (configured.isEmpty()) {
            Target legacy = new Target(ServiceInfo.order(), source.baseUrl(), Protocol.LAB, 60, !source.synthetic());
            values.put(legacy.info().id(), legacy);
        } else {
            if (source.synthetic()) throw new IllegalArgumentException("Registered services require LIVE observations");
            for (Config config : configured) {
                String id = identifier(config.id());
                String downstream = identifier(config.downstreamId());
                Protocol protocol = config.protocol() == null ? Protocol.OBSERVATIONS_V1 : config.protocol();
                int window = config.maxWindowMinutes() == null ? 60 : config.maxWindowMinutes();
                boolean lab = Boolean.TRUE.equals(config.labEnabled());
                if (window < 1 || window > 60) throw new IllegalArgumentException("Service window must be 1..60 minutes");
                if (protocol == Protocol.LAB && (!id.equals("order-service") || !downstream.equals("inventory-service")))
                    throw new IllegalArgumentException("LAB protocol is reserved for the legacy order sample");
                if (lab && protocol != Protocol.LAB && protocol != Protocol.DATABASE_V2)
                    throw new IllegalArgumentException("Lab controls require an explicit lab protocol");
                Target target = new Target(new ServiceInfo(id, label(config.name(), id), downstream,
                    label(config.downstreamName(), downstream)), ObservationSource.origin(config.baseUrl()), protocol, window, lab);
                if (values.putIfAbsent(id, target) != null) throw new IllegalArgumentException("Duplicate registered service");
            }
        }
        targets = Collections.unmodifiableMap(values);
    }

    private static String identifier(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9-]{0,63}")) throw new IllegalArgumentException("Invalid service identifier");
        return value;
    }
    private static String label(String value, String fallback) {
        if (value == null) return fallback;
        if (value.isBlank() || value.length() > 80 || value.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid service label");
        return value.strip();
    }

    public Target require(String id) {
        Target target = targets.get(id);
        if (target == null) throw new IllegalArgumentException("Service is not registered");
        return target;
    }
    public Target defaultTarget() { return targets.values().iterator().next(); }
    public List<View> views() {
        return targets.values().stream().map(t -> new View(t.info().id(), t.info().name(), t.info().downstreamId(),
            t.info().downstreamName(), t.protocol(), t.maxWindowMinutes(), t.labEnabled())).toList();
    }
    public ToolContext freeze(ToolContext context) {
        Target target = require(context.service());
        if (context.target() != null && !context.target().equals(target)) throw new IllegalArgumentException("Service configuration mismatch");
        if ((target.protocol() != Protocol.LAB) != (context.scenario() == io.github.mochiuaena.triage.domain.TriageModel.Scenario.OBSERVED))
            throw new IllegalArgumentException("Scenario does not match the observation protocol");
        if (target.protocol() == Protocol.LAB && context.scenario() != io.github.mochiuaena.triage.domain.TriageModel.Scenario.NORMAL
            && context.scenario() != io.github.mochiuaena.triage.domain.TriageModel.Scenario.DOWNSTREAM_TIMEOUT)
            throw new IllegalArgumentException("Unsupported legacy lab scenario");
        return new ToolContext(context.service(), context.windowMinutes(), context.scenario(), context.endTime(), target);
    }
}
