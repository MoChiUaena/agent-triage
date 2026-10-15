package io.github.mochiuaena.triage.sdk;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import static org.assertj.core.api.Assertions.*;

class FeignSpringCloudObservationTest {
    @FeignClient(name = "inventory-service", url = "${test.inventory-url}")
    interface InventoryClient {
        @GetMapping("/normal") String normal();
        @GetMapping("/header-timeout") String headerTimeout();
        @GetMapping("/body-timeout") String bodyTimeout();
    }

    @SpringBootConfiguration @EnableAutoConfiguration @EnableFeignClients(clients = InventoryClient.class)
    static class Application { }

    @Test void springManagedFeignClientRecordsRealLoopbackCall() throws Exception {
        var downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/normal", exchange -> {
            try {
                try { Thread.sleep(15); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'o', 'k'});
            } finally { exchange.close(); }
        });
        downstream.start();
        String origin = "http://127.0.0.1:" + downstream.getAddress().getPort();
        try (var application = new SpringApplicationBuilder(Application.class).properties(
                "server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off",
                "triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=" + origin,
                "test.inventory-url=" + origin).run()) {
            var recorder = application.getBean(ObservationRecorder.class);
            var client = application.getBean(InventoryClient.class);
            new TriageRequestFilter(recorder).doFilter(new MockHttpServletRequest("GET", "/api/products/item"),
                new MockHttpServletResponse(), (request, response) -> assertThat(client.normal()).isEqualTo("ok"));
            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.requestCount()).isEqualTo(1);
            assertThat(window.downstreamP95Ms()).isGreaterThan(0);
            assertThat(window.toString()).doesNotContain(origin, "/normal");
        } finally { downstream.stop(0); }
    }

    @Test void springManagedFeignClientCountsRealHeaderAndBodyTimeouts() throws Exception {
        var downstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        downstream.createContext("/header-timeout", exchange -> {
            try {
                try { Thread.sleep(500); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write(new byte[]{'o', 'k'});
            } finally { exchange.close(); }
        });
        downstream.createContext("/body-timeout", exchange -> {
            try {
                exchange.sendResponseHeaders(200, 2);
                exchange.getResponseBody().write('o');
                exchange.getResponseBody().flush();
                try { Thread.sleep(500); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new java.io.IOException(failure); }
                exchange.getResponseBody().write('k');
            } finally { exchange.close(); }
        });
        downstream.start();
        String origin = "http://127.0.0.1:" + downstream.getAddress().getPort();
        try (var application = new SpringApplicationBuilder(Application.class).properties(
                "server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off",
                "triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.downstream-id=inventory-service", "triage.sdk.downstream-base-url=" + origin,
                "test.inventory-url=" + origin,
                "spring.cloud.openfeign.client.config.inventory-service.readTimeout=250").run()) {
            var recorder = application.getBean(ObservationRecorder.class);
            var client = application.getBean(InventoryClient.class);
            var filter = new TriageRequestFilter(recorder);
            var headerResponse = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/products/header"), headerResponse,
                (request, response) -> {
                    assertThatThrownBy(client::headerTimeout).isNotNull();
                    headerResponse.setStatus(504);
                });
            var bodyResponse = new MockHttpServletResponse();
            filter.doFilter(new MockHttpServletRequest("GET", "/api/products/body"), bodyResponse,
                (request, response) -> {
                    assertThatThrownBy(client::bodyTimeout).isNotNull();
                    bodyResponse.setStatus(504);
                });
            var window = (ObservationRecorder.HttpWindow) recorder.snapshot(5, Instant.now());
            assertThat(window.requestCount()).isEqualTo(2);
            assertThat(window.timeoutCount()).isEqualTo(2);
        } finally { downstream.stop(0); }
    }
}
