package io.github.mochiuaena.triage.sdk;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class MultiDownstreamContextTest {
    @Test void repeatedCallsKeepTheHighestObservedResponseClassAndIgnoreLateSignals() {
        var context = new TriageRequestFilter.Context();
        context.recordDownstreamStatus("payment-service", 200);
        context.recordDownstreamStatus("payment-service", 500);
        context.addDownstreamMillis("payment-service", 12);
        var completed = context.finish();
        context.recordDownstreamStatus("payment-service", 200);
        assertThat(completed.downstreams().get("payment-service").responseClass()).isEqualTo(5);
    }
    @Test void oneRequestKeepsTimeoutAndDurationWithTheCorrectDownstream() {
        var context = new TriageRequestFilter.Context();
        context.addDownstreamMillis("inventory-service", 80);
        context.recordFailure("inventory-service", true, () -> null);
        context.addDownstreamMillis("payment-service", 20);
        var completed = context.finish();

        assertThat(completed.downstreamMs()).isEqualTo(100);
        assertThat(completed.timeout()).isTrue();
        assertThat(completed.downstreams()).containsEntry("inventory-service",
            new TriageRequestFilter.Context.DownstreamResult(80, true)).containsEntry("payment-service",
            new TriageRequestFilter.Context.DownstreamResult(20, false));
        context.addDownstreamMillis("payment-service", 500);
        assertThat(completed.downstreams().get("payment-service").millis()).isEqualTo(20);
    }
}
