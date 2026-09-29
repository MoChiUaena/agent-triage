package io.github.mochiuaena.triage.model;

import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import java.util.*;

/** Routing descriptors are local evidence; source consent does not widen the runtime model payload. */
public final class ModelEvidence {
    private ModelEvidence() {}
    public static Evidence project(Evidence evidence) {
        if (!evidence.data().containsKey("requestDetails")) return evidence;
        var data = new LinkedHashMap<>(evidence.data()); data.remove("requestDetails");
        return new Evidence(evidence.id(), evidence.source(), evidence.title(), evidence.summary(), Collections.unmodifiableMap(data));
    }
    public static List<Evidence> project(List<Evidence> evidence) { return evidence.stream().map(ModelEvidence::project).toList(); }
}
