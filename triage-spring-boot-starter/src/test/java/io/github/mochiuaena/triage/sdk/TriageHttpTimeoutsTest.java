package io.github.mochiuaena.triage.sdk;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class TriageHttpTimeoutsTest {
    @Test void recognizesOnlyTypedTimeoutCausesAcrossSupportedClients() {
        assertThat(TriageHttpTimeouts.isTimeout(new RuntimeException(new SocketTimeoutException()))).isTrue();
        assertThat(TriageHttpTimeouts.isTimeout(new HttpTimeoutException("timeout"))).isTrue();
        assertThat(TriageHttpTimeouts.isTimeout(io.netty.handler.timeout.ReadTimeoutException.INSTANCE)).isTrue();
        assertThat(TriageHttpTimeouts.isTimeout(new java.util.concurrent.TimeoutException())).isTrue();
        assertThat(TriageHttpTimeouts.isTimeout(new ConnectException("refused"))).isFalse();
        assertThat(TriageHttpTimeouts.isTimeout(new IllegalStateException("timeout"))).isFalse();
    }
}
