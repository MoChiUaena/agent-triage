package io.github.mochiuaena.triage.sdk;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MvcEndpointTest {
    @RestController static final class Controller {
        @GetMapping("/api/items/{id}") String item(@PathVariable String id) { return "private-response-body"; }
        @PostMapping("/api/items/{id}") String update(@PathVariable String id) { return "private-response-body"; }
        @GetMapping("/api/failure/{id}") String fail(@PathVariable String id) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private-exception"); }
    }
    @Test void actualMvcSelectionKeepsTemplatesAndSignaturesWithoutRetainingClientInputs() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true);
        var recorder = new ObservationRecorder(properties);
        var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(new TriageMvcEndpoints(properties)).addFilters(new TriageRequestFilter(recorder)).build();
        mvc.perform(get("/api/items/private-item").queryParam("token", "private-query").header("Authorization", "private-header")).andExpect(status().isOk());
        mvc.perform(get("/api/items/another-private-item")).andExpect(status().isOk());
        mvc.perform(post("/api/items/private-item").content("private-body")).andExpect(status().isOk());
        mvc.perform(get("/api/failure/private-item")).andExpect(status().isServiceUnavailable());
        var all = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        assertThat(all.requestCount()).isEqualTo(4); assertThat(all.endpoints()).hasSize(3);
        var get = all.endpoints().stream().filter(value -> value.endpoint().handlerMethod().equals("item")).findFirst().orElseThrow();
        assertThat(get.requestCount()).isEqualTo(2);
        assertThat(get.endpoint().id()).matches("EP-[a-f0-9]{32}");
        assertThat(get.endpoint().routeTemplate()).isEqualTo("/api/items/{id}");
        assertThat(get.endpoint().parameterTypes()).containsExactly("java.lang.String");
        assertThat(get.endpoint().handlerClass()).isEqualTo(Controller.class.getCanonicalName());
        assertThat(get.endpoint().stage()).isEqualTo("MVC_SELECTED");
        assertThat(all.toString()).doesNotContain("private-item", "private-query", "private-header", "private-body", "private-response-body", "private-exception");
        var selected = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, all.windowEnd(), get.endpoint().id());
        assertThat(selected.requestCount()).isEqualTo(2); assertThat(selected.errors()).isEmpty();
        assertThat(selected.endpoint()).isEqualTo(get.endpoint());
        var legacy = (ObservationRecorder.HttpWindow) recorder.snapshot(5, all.windowEnd());
        assertThat(legacy.requestCount()).isEqualTo(4); assertThat(legacy.schemaVersion()).isEqualTo(1);
        assertThat(legacy.toString()).doesNotContain("handlerClass", "/api/items/{id}");
        assertThat(TriageRequestFilter.CURRENT.get()).isNull();
    }
    @Test void unmatchedRequestIsCountedWithoutAnInventedHandler() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true);
        var recorder = new ObservationRecorder(properties);
        var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(new TriageMvcEndpoints(properties)).addFilters(new TriageRequestFilter(recorder)).build();
        mvc.perform(get("/api/unknown/private-item")).andExpect(status().isNotFound());
        var value = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        assertThat(value.requestCount()).isEqualTo(1); assertThat(value.unattributedRequestCount()).isEqualTo(1);
        assertThat(value.endpoints()).isEmpty(); assertThat(value.toString()).doesNotContain("private-item");
    }
    @Test void endpointIdentityMustBeObservedAndTopListIsBoundedWithoutLosingCounts() {
        var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true);
        Instant time = Instant.parse("2026-09-29T00:00:00Z"); var recorder = new ObservationRecorder(properties, Clock.fixed(time, ZoneOffset.UTC));
        for (int i = 0; i < 12; i++) {
            var endpoint = new MvcEndpoint("EP-" + "%032x".formatted(i), "GET", "/api/endpoint" + i, "example.Controller", "item" + i, java.util.List.of(), "MVC_SELECTED");
            recorder.recordHttp(20, 10, i == 11, false, "fixture", endpoint);
        }
        var all = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, time, null);
        assertThat(all.endpoints()).hasSize(8); assertThat(all.otherEndpointRequestCount()).isEqualTo(4);
        assertThat(all.endpoints().getFirst().timeoutCount()).isEqualTo(1);
        assertThatThrownBy(() -> recorder.endpointSnapshot(5, time, "EP-" + "f".repeat(32))).isInstanceOfSatisfying(ResponseStatusException.class, value -> assertThat(value.getStatusCode().value()).isEqualTo(409));
        properties.setEndpointObservations(false);
        assertThatThrownBy(() -> recorder.endpointSnapshot(5, time, null)).isInstanceOfSatisfying(ResponseStatusException.class, value -> assertThat(value.getStatusCode().value()).isEqualTo(404));
    }
}
