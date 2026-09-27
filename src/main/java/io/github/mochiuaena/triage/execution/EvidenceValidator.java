package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.Collectors;

public final class EvidenceValidator {
    private EvidenceValidator() {}

    public static void validate(Diagnosis diagnosis, List<Evidence> evidence) {
        var ids = evidence.stream().map(Evidence::id).collect(Collectors.toSet());
        if (ids.size() != evidence.size()) throw new IllegalArgumentException("Duplicate evidence IDs");
        Stream.concat(diagnosis.observations().stream(), diagnosis.possibleCauses().stream()).forEach(finding -> {
            if (finding.evidenceIds().isEmpty() || !ids.containsAll(finding.evidenceIds()))
                throw new IllegalArgumentException("Every finding must cite evidence collected in this run");
        });
    }
}
