package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.execution.*;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Primary
@Component
public final class EngineRouter implements TriageEngine {
    private final ProviderRegistry providers;
    public EngineRouter(ProviderRegistry providers) { this.providers = providers; }
    @Override public TriageEngine snapshot() { return providers.current(); }
    @Override public String mode() { return snapshot().mode(); }
    @Override public String modelName() { return snapshot().modelName(); }
    @Override public Decision investigate(ExecutionSession session) { throw new IllegalStateException("Select an engine before submitting a run"); }
}
