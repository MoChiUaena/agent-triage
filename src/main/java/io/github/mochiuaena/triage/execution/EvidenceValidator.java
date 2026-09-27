package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.List;
import java.util.stream.Stream;
import java.util.stream.Collectors;

public final class EvidenceValidator {
    private EvidenceValidator() {}

    public static void validate(Diagnosis diagnosis, List<Evidence> evidence) {
        if (diagnosis == null || diagnosis.observations() == null || diagnosis.possibleCauses() == null
            || diagnosis.nextSteps() == null || diagnosis.observations().size() > 10 || diagnosis.possibleCauses().size() > 5
            || diagnosis.nextSteps().isEmpty() || diagnosis.nextSteps().size() > 10)
            throw new IllegalArgumentException("Invalid diagnosis structure");
        text(diagnosis.uncertainty(), 1000);
        diagnosis.nextSteps().forEach(step -> text(step, 500));
        var ids = evidence.stream().map(Evidence::id).collect(Collectors.toSet());
        if (ids.size() != evidence.size()) throw new IllegalArgumentException("Duplicate evidence IDs");
        Stream.concat(diagnosis.observations().stream(), diagnosis.possibleCauses().stream()).forEach(finding -> {
            if (finding == null) throw new IllegalArgumentException("Missing finding");
            text(finding.text(), 1500);
            if (finding.evidenceIds() == null || finding.evidenceIds().isEmpty() || finding.evidenceIds().size() > 15
                || !ids.containsAll(finding.evidenceIds()))
                throw new IllegalArgumentException("Every finding must cite evidence collected in this run");
        });
    }

    private static void text(String value, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) throw new IllegalArgumentException("Invalid diagnosis text");
    }
}
