package io.github.mochiuaena.triage.sdk;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

/** Leave failed writes visible to HttpServer so it removes the connection from its collections. */
final class ResourceHttpHandler implements HttpHandler {
    private final HttpHandler delegate;
    private final AtomicInteger active, requests;
    ResourceHttpHandler(HttpHandler delegate, AtomicInteger active, AtomicInteger requests) {
        this.delegate = delegate; this.active = active; this.requests = requests;
    }
    @Override public void handle(HttpExchange exchange) throws IOException {
        active.incrementAndGet(); requests.incrementAndGet();
        try { delegate.handle(exchange); }
        finally {
            try { exchange.close(); }
            finally { active.decrementAndGet(); }
        }
    }
}
