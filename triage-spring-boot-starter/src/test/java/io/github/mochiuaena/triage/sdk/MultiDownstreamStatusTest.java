package io.github.mochiuaena.triage.sdk;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.client.MockClientHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class MultiDownstreamStatusTest {
    @Test void v5SeparatesPayment200And500WhileInventoryTimesOut() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class,
                TriageObservationAutoConfiguration.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=checkout-service",
                "triage.sdk.endpoint-observations=true",
                "triage.sdk.downstreams[0].id=inventory-service", "triage.sdk.downstreams[0].base-url=http://127.0.0.1:18084",
                "triage.sdk.downstreams[1].id=payment-service", "triage.sdk.downstreams[1].base-url=http://127.0.0.1:18085")
            .run(application -> {
                var client = application.getBean(RestTemplateBuilder.class).build();
                var downstream = MockRestServiceServer.bindTo(client).build();
                downstream.expect(requestTo("http://127.0.0.1:18084/stock"))
                    .andRespond(request -> { throw new SocketTimeoutException("private-stock-timeout"); });
                downstream.expect(requestTo("http://127.0.0.1:18085/pay"))
                    .andRespond(request -> new MockClientHttpResponse("ok".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
                downstream.expect(requestTo("http://127.0.0.1:18085/pay"))
                    .andRespond(request -> new MockClientHttpResponse("failed".getBytes(StandardCharsets.UTF_8), HttpStatus.INTERNAL_SERVER_ERROR));
                var filter = new TriageRequestFilter(application.getBean(ObservationRecorder.class));
                filter.doFilter(new MockHttpServletRequest("GET", "/api/checkout/first"), new MockHttpServletResponse(),
                    (request, response) -> {
                        assertThatThrownBy(() -> client.getForObject("http://127.0.0.1:18084/stock", String.class)).isNotNull();
                        assertThat(client.getForObject("http://127.0.0.1:18085/pay", String.class)).isEqualTo("ok");
                    });
                filter.doFilter(new MockHttpServletRequest("GET", "/api/checkout/second"), new MockHttpServletResponse(),
                    (request, response) -> assertThatThrownBy(() -> client.getForObject("http://127.0.0.1:18085/pay", String.class)).isNotNull());
                var mvc = MockMvcBuilders.standaloneSetup(application.getBean(TriageObservationsEndpoint.class)).build();
                mvc.perform(get("/triage/endpoint-observations").param("windowMinutes", "5").param("endTime", Instant.now().toString()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.downstreams[0].responseStatuses.unknown").value(1))
                    .andExpect(jsonPath("$.downstreams[1].responseStatuses.successful").value(1))
                    .andExpect(jsonPath("$.downstreams[1].responseStatuses.serverError").value(1))
                    .andExpect(jsonPath("$.downstreams[1].timeoutCount").value(0));
                downstream.verify();
            });
    }
}
