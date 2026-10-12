package io.github.mochiuaena.triage.tools;

import io.github.mochiuaena.triage.domain.TriageModel.ServiceInfo;
import io.github.mochiuaena.triage.domain.TriageModel.Downstream;
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
    public enum Protocol {
        LAB, OBSERVATIONS_V1, DATABASE_V2, OBSERVATIONS_V3, HTTP_REQUESTS_V4, OBSERVATIONS_V5;
        public boolean supportsEndpoints() { return this == OBSERVATIONS_V3 || this == HTTP_REQUESTS_V4 || this == OBSERVATIONS_V5; }
    }
    public record DownstreamConfig(String id, String name) {}
    public record Config(String id, String name, String downstreamId, String downstreamName,
                         String baseUrl, Protocol protocol, Integer maxWindowMinutes, Boolean labEnabled, Boolean databaseAlias,
                         String accessToken, List<DownstreamConfig> downstreams) {
        @org.springframework.boot.context.properties.bind.ConstructorBinding
        public Config { downstreams = downstreams == null ? List.of() : List.copyOf(downstreams); }
        public Config(String id, String name, String downstreamId, String downstreamName,
                      String baseUrl, Protocol protocol, Integer maxWindowMinutes, Boolean labEnabled) {
            this(id, name, downstreamId, downstreamName, baseUrl, protocol, maxWindowMinutes, labEnabled, null, null, List.of());
        }
        public Config(String id, String name, String downstreamId, String downstreamName,
                      String baseUrl, Protocol protocol, Integer maxWindowMinutes, Boolean labEnabled, Boolean databaseAlias) {
            this(id, name, downstreamId, downstreamName, baseUrl, protocol, maxWindowMinutes, labEnabled, databaseAlias, null, List.of());
        }
        public Config(String id, String name, String downstreamId, String downstreamName,
                      String baseUrl, Protocol protocol, Integer maxWindowMinutes, Boolean labEnabled, Boolean databaseAlias,
                      String accessToken) {
            this(id, name, downstreamId, downstreamName, baseUrl, protocol, maxWindowMinutes, labEnabled, databaseAlias, accessToken, List.of());
        }
        @Override public String toString() { return "ServiceConfig[id=" + id + ",accessToken=redacted]"; }
    }
    public record Target(ServiceInfo info, URI baseUrl, Protocol protocol, int maxWindowMinutes, boolean labEnabled, boolean databaseAlias) {
        public Target(ServiceInfo info, URI baseUrl, Protocol protocol, int maxWindowMinutes, boolean labEnabled) {
            this(info, baseUrl, protocol, maxWindowMinutes, labEnabled, false);
        }
        @Override public String toString() { return "ObservationTarget[id=" + info.id() + ",protocol=" + protocol + "]"; }
    }
    public record View(String id, String name, String downstreamId, String downstreamName,
                       Protocol protocol, int maxWindowMinutes, boolean labEnabled,
                       @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_EMPTY)
                       List<Downstream> downstreams) {
        public View(String id, String name, String downstreamId, String downstreamName,
                    Protocol protocol, int maxWindowMinutes, boolean labEnabled) {
            this(id, name, downstreamId, downstreamName, protocol, maxWindowMinutes, labEnabled, List.of());
        }
    }
    private final Map<String, Target> targets;
    private final Map<String, String> accessTokens;

    @Autowired
    public ServiceRegistry(ObservationSource source, Environment environment) {
        this(source, Binder.get(environment).bind("triage.services", Bindable.listOf(Config.class)).orElse(List.of()));
    }

    public ServiceRegistry(ObservationSource source, List<Config> configured) {
        var values = new LinkedHashMap<String, Target>();
        var tokens = new HashMap<String, String>();
        if (configured.isEmpty()) {
            Target legacy = new Target(ServiceInfo.order(), source.baseUrl(), Protocol.LAB, 60, !source.synthetic());
            values.put(legacy.info().id(), legacy);
        } else {
            if (source.synthetic()) throw new IllegalArgumentException("Registered services require LIVE observations");
            for (Config config : configured) {
                String id = identifier(config.id());
                Protocol protocol = config.protocol() == null ? Protocol.OBSERVATIONS_V1 : config.protocol();
                boolean inbound = protocol == Protocol.HTTP_REQUESTS_V4;
                boolean multi = protocol == Protocol.OBSERVATIONS_V5;
                if (inbound && (config.downstreamId() != null || config.downstreamName() != null))
                    throw new IllegalArgumentException("Inbound request services do not accept downstream identities");
                if (multi && (config.downstreamId() != null || config.downstreamName() != null
                    || config.downstreams().isEmpty() || config.downstreams().size() > 8)
                    || !multi && !config.downstreams().isEmpty())
                    throw new IllegalArgumentException("V5 requires 1..8 downstream identities without legacy downstream fields");
                String downstream = inbound || multi ? null : identifier(config.downstreamId());
                var downstreams = new ArrayList<Downstream>();
                var seenDownstreams = new HashSet<String>();
                if (multi) for (var value : config.downstreams()) {
                    if (value == null || !seenDownstreams.add(identifier(value.id())))
                        throw new IllegalArgumentException("Duplicate downstream identity");
                    downstreams.add(new Downstream(value.id(), label(value.name(), value.id())));
                }
                int window = config.maxWindowMinutes() == null ? 60 : config.maxWindowMinutes();
                boolean lab = Boolean.TRUE.equals(config.labEnabled());
                if (window < 1 || window > 60) throw new IllegalArgumentException("Service window must be 1..60 minutes");
                if (protocol == Protocol.LAB && (!id.equals("order-service") || !downstream.equals("inventory-service")))
                    throw new IllegalArgumentException("LAB protocol is reserved for the legacy order sample");
                if (lab && protocol != Protocol.LAB && protocol != Protocol.DATABASE_V2)
                    throw new IllegalArgumentException("Lab controls require an explicit lab protocol");
                boolean databaseAlias = Boolean.TRUE.equals(config.databaseAlias());
                if (databaseAlias && protocol != Protocol.DATABASE_V2)
                    throw new IllegalArgumentException("Database alias requires DATABASE_V2");
                Target target = new Target(new ServiceInfo(id, label(config.name(), id), downstream,
                    inbound || multi ? null : label(config.downstreamName(), downstream), downstreams),
                    ObservationSource.registeredBase(config.baseUrl()), protocol, window, lab, databaseAlias);
                if (values.putIfAbsent(id, target) != null) throw new IllegalArgumentException("Duplicate registered service");
                if (config.accessToken() != null) {
                    if (protocol == Protocol.LAB || !config.accessToken().matches("[A-Za-z0-9_-]{32,128}"))
                        throw new IllegalArgumentException("Invalid observation access token");
                    tokens.put(id, config.accessToken());
                }
            }
        }
        targets = Collections.unmodifiableMap(values);
        accessTokens = Map.copyOf(tokens);
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
    String accessToken(Target target) {
        if (!require(target.info().id()).equals(target)) throw new IllegalArgumentException("Unregistered observation target");
        return accessTokens.get(target.info().id());
    }
    public List<View> views() {
        return targets.values().stream().map(t -> new View(t.info().id(), t.info().name(), t.info().downstreamId(),
            t.info().downstreamName(), t.protocol(), t.maxWindowMinutes(), t.labEnabled(), t.info().downstreams())).toList();
    }
    public ToolContext freeze(ToolContext context) {
        Target target = require(context.service());
        if (context.target() != null && !context.target().equals(target)) throw new IllegalArgumentException("Service configuration mismatch");
        if ((target.protocol() != Protocol.LAB) != (context.scenario() == io.github.mochiuaena.triage.domain.TriageModel.Scenario.OBSERVED))
            throw new IllegalArgumentException("Scenario does not match the observation protocol");
        if (target.protocol() == Protocol.LAB && context.scenario() != io.github.mochiuaena.triage.domain.TriageModel.Scenario.NORMAL
            && context.scenario() != io.github.mochiuaena.triage.domain.TriageModel.Scenario.DOWNSTREAM_TIMEOUT)
            throw new IllegalArgumentException("Unsupported legacy lab scenario");
        return new ToolContext(context.service(), context.windowMinutes(), context.scenario(), context.endTime(), target, context.endpoint());
    }
}
