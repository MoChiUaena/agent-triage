package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.RunFailure;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class InboundModelOutputTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ModelOutput output = new ModelOutput(json);
    @Test void inboundTypeCannotUseFakeZeroMetricsToClaimNoDownstreamTimeout() {
        var metrics = new Evidence("metrics", "read_service_metrics", "请求", "入站请求", Map.of("observationType", "HTTP_REQUESTS",
            "requestCount", 2, "orderP95Ms", 20, "downstreamP95Ms", 0, "downstreamTimeoutRate", 0));
        var logs = new Evidence("logs", "query_error_logs", "事件", "无错误", Map.of("entries", List.of(), "returnedCount", 0, "timeoutCount", 0));
        var rule = new Evidence("DOC-HEALTHY-BASELINE#v3", "search_runbooks", "对照", "对照", Map.of());
        String answer = "{\"assessment\":\"NO_DOWNSTREAM_TIMEOUT_OBSERVED\",\"evidenceIds\":[\"metrics\",\"logs\",\"DOC-HEALTHY-BASELINE#v3\"],\"nextChecks\":[\"FIND_SLOW_REQUEST\"]}";
        assertThatThrownBy(() -> output.parse(answer, List.of(metrics, logs, rule), new ServiceInfo("inbound", "入站", null, null)))
            .isInstanceOfSatisfying(RunFailure.class, error -> assertThat(error.code()).isEqualTo("MODEL_ASSESSMENT_MISMATCH"));
    }
    @Test void schemaAndInsufficientOutputDoNotRequireOrInventADownstream() throws Exception {
        var schema = json.readTree(output.inboundFormat());
        assertThat(schema.at("/properties/assessment/enum")).hasSize(1);
        assertThat(schema.at("/properties/assessment/enum/0").asText()).isEqualTo("INSUFFICIENT_EVIDENCE");
        String answer = "{\"assessment\":\"INSUFFICIENT_EVIDENCE\",\"evidenceIds\":[],\"nextChecks\":[\"COLLECT_RESOURCE_METRICS\"]}";
        var parsed = output.parse(answer, List.of(), new ServiceInfo("inbound", "入站", null, null));
        assertThat(parsed.decision().status()).isEqualTo(Status.INSUFFICIENT_EVIDENCE);
        assertThat(parsed.decision().diagnosis().toString()).contains("本应用", "未采集下游").doesNotContain("null");
    }
}
