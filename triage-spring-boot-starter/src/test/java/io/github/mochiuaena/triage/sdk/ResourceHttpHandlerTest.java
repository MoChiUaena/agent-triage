package io.github.mochiuaena.triage.sdk;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ResourceHttpHandlerTest {
    @Test void failedWriteClosesExchangeAndPropagatesOriginalFailureToServerCleanup() throws Exception {
        var exchange = mock(HttpExchange.class);
        var active = new AtomicInteger(); var requests = new AtomicInteger();
        var failure = new IOException("controlled response write failure");
        var handler = new ResourceHttpHandler(value -> { throw failure; }, active, requests);
        assertThatThrownBy(() -> handler.handle(exchange)).isSameAs(failure);
        verify(exchange).close();
        assertThat(active.get()).isZero();
        assertThat(requests.get()).isEqualTo(1);
    }
    @Test void successfulResponseAlsoReturnsExchangeAndActiveCount() throws Exception {
        var exchange = mock(HttpExchange.class);
        var active = new AtomicInteger(); var requests = new AtomicInteger();
        var handler = new ResourceHttpHandler(value -> assertThat(active.get()).isEqualTo(1), active, requests);
        handler.handle(exchange);
        verify(exchange).close();
        assertThat(active.get()).isZero();
    }
}
