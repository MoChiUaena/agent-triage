package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.Downstream;
import io.github.mochiuaena.triage.domain.TriageModel.Evidence;
import io.github.mochiuaena.triage.domain.TriageModel.ServiceInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Reads only the bounded, validated downstream rows; error message text does not identify a dependency. */
public final class MultiDownstreamEvidence {
    private MultiDownstreamEvidence() {}

    public static boolean isMulti(Evidence metrics) {
        return metrics != null && "HTTP_DOWNSTREAMS".equals(metrics.data().get("observationType"));
    }

    public static List<Downstream> affected(Evidence metrics, ServiceInfo info) {
        var result = new ArrayList<Downstream>();
        if (!isMulti(metrics) || !(metrics.data().get("downstreams") instanceof List<?> rows)) return List.of();
        for (Downstream identity : info.downstreams()) {
            for (Object item : rows) if (item instanceof Map<?, ?> row && identity.id().equals(row.get("id"))
                && EvidenceRules.count(row, "timeoutCount") > 0) result.add(identity);
        }
        return List.copyOf(result);
    }

    public static boolean matches(Evidence metrics, Evidence logs, ServiceInfo info) {
        if (!isMulti(metrics) || logs == null || !"HTTP_DOWNSTREAMS".equals(logs.data().get("observationType"))
            || !(metrics.data().get("downstreams") instanceof List<?> rows)
            || !rows.equals(logs.data().get("downstreams")) || rows.size() != info.downstreams().size()
            || EvidenceRules.count(metrics.data(), "timeoutCount") != EvidenceRules.count(logs.data(), "timeoutCount")) return false;
        long total = EvidenceRules.count(metrics.data(), "timeoutCount");
        long requests = EvidenceRules.count(metrics.data(), "requestCount");
        long sum = 0;
        for (int i = 0; i < rows.size(); i++) {
            if (!(rows.get(i) instanceof Map<?, ?> row) || !info.downstreams().get(i).id().equals(row.get("id"))) return false;
            long rowRequests = EvidenceRules.count(row, "requestCount");
            long timeouts = EvidenceRules.count(row, "timeoutCount");
            if (rowRequests < 0 || rowRequests > requests || timeouts < 0 || timeouts > rowRequests || timeouts > total) return false;
            sum += timeouts;
        }
        return total >= 0 && sum >= total;
    }
}
