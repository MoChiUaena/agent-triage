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

class MultiDownstreamWindowTest {
    @Test void inventoryTimeoutAndHealthyPaymentAppearAsSeparateWindowRows() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(RestTemplateAutoConfiguration.class,
                TriageObservationAutoConfiguration.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.endpoint-observations=true", "triage.sdk.response-status-counts=true",
                "triage.sdk.downstreams[0].id=inventory-service", "triage.sdk.downstreams[0].base-url=http://127.0.0.1:18084",
                "triage.sdk.downstreams[1].id=payment-service", "triage.sdk.downstreams[1].base-url=http://127.0.0.1:18085")
            .run(application -> {
                var recorder = application.getBean(ObservationRecorder.class);
                var client = application.getBean(RestTemplateBuilder.class).build();
                var downstream = MockRestServiceServer.bindTo(client).build();
                downstream.expect(requestTo("http://127.0.0.1:18084/items?secret=private"))
                    .andRespond(request -> { throw new SocketTimeoutException("private-inventory-error"); });
                downstream.expect(requestTo("http://127.0.0.1:18085/payments"))
                    .andRespond(request -> new MockClientHttpResponse("ok".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
                downstream.expect(requestTo("http://127.0.0.1:18085/payments"))
                    .andRespond(request -> new MockClientHttpResponse("ok".getBytes(StandardCharsets.UTF_8), HttpStatus.OK));
                var filter = new TriageRequestFilter(recorder);
                var firstResponse = new MockHttpServletResponse();
                filter.doFilter(new MockHttpServletRequest("GET", "/api/catalog/first"), firstResponse,
                    (request, response) -> {
                        assertThatThrownBy(() -> client.getForObject("http://127.0.0.1:18084/items?secret=private", String.class))
                            .isNotNull();
                        assertThat(client.getForObject("http://127.0.0.1:18085/payments", String.class)).isEqualTo("ok");
                        firstResponse.setStatus(504);
                    });
                filter.doFilter(new MockHttpServletRequest("GET", "/api/catalog/second"), new MockHttpServletResponse(),
                    (request, response) -> assertThat(client.getForObject("http://127.0.0.1:18085/payments", String.class)).isEqualTo("ok"));

                var window = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
                assertThat(window.schemaVersion()).isEqualTo(5);
                assertThat(window.requestCount()).isEqualTo(2);
                assertThat(window.timeoutCount()).isEqualTo(1);
                assertThat(window.downstreams()).hasSize(2);
                var inventory = window.downstreams().stream().filter(value -> value.id().equals("inventory-service")).findFirst().orElseThrow();
                var payment = window.downstreams().stream().filter(value -> value.id().equals("payment-service")).findFirst().orElseThrow();
                assertThat(inventory.requestCount()).isEqualTo(1);
                assertThat(inventory.timeoutCount()).isEqualTo(1);
                assertThat(payment.requestCount()).isEqualTo(2);
                assertThat(payment.timeoutCount()).isZero();
                assertThat(window.errors()).singleElement().satisfies(error ->
                    assertThat(error.message()).isEqualTo("inventory-service request timeout"));
                assertThat(window.toString()).doesNotContain("private-inventory-error", "secret=private", "/api/catalog/");
                assertThatThrownBy(() -> recorder.snapshot(5, Instant.now()))
                    .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
                    .satisfies(error -> assertThat(((org.springframework.web.server.ResponseStatusException) error).getStatusCode().value())
                        .isEqualTo(404));
                var mvc = MockMvcBuilders.standaloneSetup(application.getBean(TriageObservationsEndpoint.class)).build();
                String end = Instant.now().toString();
                var result = mvc.perform(get("/triage/endpoint-observations").param("windowMinutes", "5").param("endTime", end))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(5))
                    .andExpect(jsonPath("$.downstreams[0].id").value("inventory-service"))
                    .andExpect(jsonPath("$.downstreams[0].timeoutCount").value(1))
                    .andExpect(jsonPath("$.downstreams[1].id").value("payment-service"))
                    .andExpect(jsonPath("$.downstreams[1].timeoutCount").value(0))
                    .andReturn();
                assertThat(result.getResponse().getContentAsString()).doesNotContain("private-inventory-error", "secret=private");
                downstream.verify();
            });
    }

    @Test void legacySingleDownstreamKeepsTheV3ResponseShape() {
        new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(TriageObservationAutoConfiguration.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.service-id=catalog-service",
                "triage.sdk.endpoint-observations=true", "triage.sdk.downstream-id=inventory-service",
                "triage.sdk.downstream-base-url=http://127.0.0.1:18084")
            .run(application -> {
                var mvc = MockMvcBuilders.standaloneSetup(application.getBean(TriageObservationsEndpoint.class)).build();
                mvc.perform(get("/triage/endpoint-observations").param("windowMinutes", "5")
                        .param("endTime", Instant.now().toString()))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(3))
                    .andExpect(jsonPath("$.downstreamService").value("inventory-service"))
                    .andExpect(jsonPath("$.downstreams").doesNotExist());
            });
    }
}
