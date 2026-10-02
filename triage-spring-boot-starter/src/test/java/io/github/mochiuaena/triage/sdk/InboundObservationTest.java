package io.github.mochiuaena.triage.sdk;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.web.client.RestClientAutoConfiguration;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class InboundObservationTest {
    private WebApplicationContextRunner inbound() {
        return new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(RestClientAutoConfiguration.class,
            TriageObservationAutoConfiguration.class)).withPropertyValues("triage.sdk.enabled=true", "triage.sdk.kind=HTTP_REQUESTS",
                "triage.sdk.service-id=inbound-service", "triage.sdk.endpoint-observations=true", "triage.sdk.response-status-counts=true");
    }
    @RestController static class Controller {
        @GetMapping("/api/inbound/{id}") ResponseEntity<Void> request(@PathVariable String id) {
            return ResponseEntity.status("private-bad".equals(id) ? 500 : 200).build();
        }
    }
    @Test void noDownstreamConfigurationStartsAndReportsOnlyInboundRequestFields() {
        inbound().run(context -> {
            assertThat(context).hasNotFailed().hasSingleBean(ObservationRecorder.class).doesNotHaveBean(TriageRestClientCustomizer.class);
            var mvc = MockMvcBuilders.standaloneSetup(new Controller(), context.getBean(TriageObservationsEndpoint.class))
                .addInterceptors(context.getBean(TriageMvcEndpoints.class))
                .addFilter(context.getBean(FilterRegistrationBean.class).getFilter(), "/api/*").build();
            mvc.perform(get("/api/inbound/private-good")).andExpect(status().isOk());
            mvc.perform(get("/api/inbound/private-bad")).andExpect(status().isInternalServerError());
            String end = Instant.now().toString();
            var result = mvc.perform(get("/triage/request-observations").param("windowMinutes", "1").param("endTime", end))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(4)).andExpect(jsonPath("$.kind").value("HTTP_REQUESTS"))
                .andExpect(jsonPath("$.requestCount").value(2)).andExpect(jsonPath("$.responseStatuses.successful").value(1))
                .andExpect(jsonPath("$.responseStatuses.serverError").value(1)).andExpect(jsonPath("$.errors[0].message").value("HTTP request failed"))
                .andExpect(jsonPath("$.downstreamService").doesNotExist()).andExpect(jsonPath("$.timeoutCount").doesNotExist())
                .andExpect(jsonPath("$.downstreamP95Ms").doesNotExist()).andExpect(jsonPath("$.downstreamTimeoutRate").doesNotExist())
                .andExpect(jsonPath("$.endpoints[0].timeoutCount").doesNotExist()).andReturn();
            assertThat(result.getResponse().getContentAsString()).doesNotContain("private-good", "private-bad", "inventory-service");
            mvc.perform(get("/triage/observations").param("windowMinutes", "1").param("endTime", end)).andExpect(status().isNotFound());
            mvc.perform(get("/triage/endpoint-observations").param("windowMinutes", "1").param("endTime", end)).andExpect(status().isNotFound());
        });
    }
    @Test void inboundModeCanOptIntoJpaWithoutAPlaceholderHttpOrigin() {
        inbound().withPropertyValues("triage.sdk.jpa-observations=true", "triage.sdk.jpa-service-id=inbound-db",
            "triage.sdk.jpa-database-id=test-db").run(context -> assertThat(context).hasNotFailed().hasSingleBean(TriageJpaObserver.class));
    }
    @Test void basicInboundRequestsDoNotRequireEndpointOrResponseClassification() {
        inbound().withPropertyValues("triage.sdk.endpoint-observations=false", "triage.sdk.response-status-counts=false").run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.standaloneSetup(new Controller(), context.getBean(TriageObservationsEndpoint.class))
                .addFilter(context.getBean(FilterRegistrationBean.class).getFilter(), "/api/*").build();
            mvc.perform(get("/api/inbound/private-good")).andExpect(status().isOk());
            mvc.perform(get("/triage/request-observations").param("windowMinutes", "1").param("endTime", Instant.now().toString()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.requestCount").value(1)).andExpect(jsonPath("$.unattributedRequestCount").value(1))
                .andExpect(jsonPath("$.endpoints").isEmpty()).andExpect(jsonPath("$.responseStatuses").doesNotExist());
        });
    }
    @Test void selectedInboundEndpointStillUsesExactWindowsAndRejectsExpiredData() {
        inbound().run(context -> {
            assertThat(context).hasNotFailed();
            var recorder = context.getBean(ObservationRecorder.class);
            var endpoint = new MvcEndpoint("EP-" + "a".repeat(32), "GET", "/api/fixed", "example.Controller", "get", java.util.List.of(), "MVC_SELECTED");
            recorder.recordHttp(20, 0, false, false, "fixture", endpoint, null, 200);
            Instant end = Instant.now();
            var result = recorder.requestSnapshot(1, end, endpoint.id());
            assertThat(result.requestCount()).isEqualTo(1); assertThat(result.windowStart()).isEqualTo(end.minusSeconds(60));
            assertThat(result.windowEnd()).isEqualTo(end); assertThat(result.endpoint()).isEqualTo(endpoint);
            assertThatThrownBy(() -> recorder.requestSnapshot(1, end.minusSeconds(1200), endpoint.id())).hasMessageContaining("422");
        });
    }
    @Test void inboundCapacityLossAndAccessControlKeepTheirExistingBoundaries() {
        String token = "inbound_AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        inbound().withPropertyValues("triage.sdk.capacity=10", "triage.sdk.observation-access-token=" + token).run(context -> {
            assertThat(context).hasNotFailed();
            var recorder = context.getBean(ObservationRecorder.class);
            for (int i = 0; i < 11; i++) recorder.recordHttp(20, 0, false, false, "fixture");
            var mvc = MockMvcBuilders.standaloneSetup(context.getBean(TriageObservationsEndpoint.class)).build();
            var query = get("/triage/request-observations").param("windowMinutes", "1").param("endTime", Instant.now().toString());
            mvc.perform(query).andExpect(status().isForbidden());
            mvc.perform(query.header("X-Triage-Observation-Token", token)).andExpect(status().isUnprocessableEntity());
            mvc.perform(get("/triage/request-observations").param("windowMinutes", "1").param("endTime", Instant.now().toString())
                .header("X-Triage-Observation-Token", token).with(request -> { request.setRemoteAddr("192.0.2.10"); return request; }))
                .andExpect(status().isForbidden());
        });
    }
    @Test void inboundModeRejectsDownstreamConfigurationInsteadOfSilentlyIgnoringIt() {
        inbound().withPropertyValues("triage.sdk.downstream-id=inventory-service").run(context -> assertThat(context).hasFailed());
        inbound().withPropertyValues("triage.sdk.downstream-base-url=http://127.0.0.1:18084").run(context -> assertThat(context).hasFailed());
    }
}
