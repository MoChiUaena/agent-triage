package io.github.mochiuaena.triage.sdk;

import java.time.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class MvcEndpointTest {
    @RestController static final class AsyncController {
        final DeferredResult<String> pending = new DeferredResult<>();
        @GetMapping("/api/async/{id}") DeferredResult<String> async(@PathVariable String id) { return pending; }
    }
    @RestController static final class Controller {
        @GetMapping("/api/items/{id}") String item(@PathVariable String id) {
            assertThat(TriageRequestFilter.CURRENT.get().handlerClass).isEqualTo(Controller.class);
            return "private-response-body";
        }
        @PostMapping("/api/items/{id}") String update(@PathVariable String id) { return "private-response-body"; }
        @GetMapping("/api/failure/{id}") String fail(@PathVariable String id) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private-exception"); }
    }
    @RestController static final class StatusController {
        @GetMapping("/api/status/{code}") ResponseEntity<Void> statusCode(@PathVariable int code) { return ResponseEntity.status(code).build(); }
        @GetMapping("/api/escape") String escape() { throw new IllegalStateException("private-failure"); }
    }
    @Test void optInStatusCountsDescribeFinalResponsesAndKeepUnfinishedExceptionsUnknown() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true);
        Binder.get(new MockEnvironment().withProperty("triage.sdk.response-status-counts", "true"))
            .bind("triage.sdk", Bindable.ofInstance(properties));
        var recorder = new ObservationRecorder(properties);
        var mvc = MockMvcBuilders.standaloneSetup(new StatusController()).addInterceptors(new TriageMvcEndpoints(properties))
            .addFilters(new TriageRequestFilter(recorder)).build();
        for (int code : new int[]{200, 204, 301, 404, 503}) mvc.perform(get("/api/status/" + code)).andExpect(status().is(code));
        assertThatThrownBy(() -> mvc.perform(get("/api/escape"))).hasRootCauseInstanceOf(IllegalStateException.class);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        var window = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        var counts = mapper.valueToTree(window).path("responseStatuses");
        assertThat(counts.path("successful").asInt()).isEqualTo(2);
        assertThat(counts.path("redirection").asInt()).isEqualTo(1);
        assertThat(counts.path("clientError").asInt()).isEqualTo(1);
        assertThat(counts.path("serverError").asInt()).isEqualTo(1);
        assertThat(counts.path("unknown").asInt()).isEqualTo(1);
        assertThat(counts.path("informational").asInt()).isZero();
        var selected = window.endpoints().stream().filter(item -> item.endpoint().handlerMethod().equals("statusCode")).findFirst().orElseThrow();
        var selectedJson = mapper.valueToTree(recorder.endpointSnapshot(5, window.windowEnd(), selected.endpoint().id()));
        assertThat(selectedJson.at("/responseStatuses/unknown").asInt()).isZero();
        assertThat(selectedJson.at("/responseStatuses/clientError").asInt()).isEqualTo(1);
        assertThat(selectedJson.at("/endpoints/0/responseStatuses/successful").asInt()).isEqualTo(2);
        assertThat(mapper.valueToTree(recorder.snapshot(5, window.windowEnd())).has("responseStatuses")).isFalse();
        assertThat(mapper.writeValueAsString(window)).doesNotContain("private-failure", "/api/status/404");
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
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper().findAndRegisterModules();
        assertThat(mapper.valueToTree(all).has("responseStatuses")).isFalse();
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
    @Test void asyncMvcRequestIsRecordedOnceAfterCompletionWithItsFinalStatus() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true);
        properties.setResponseStatusCounts(true);
        var recorder = new ObservationRecorder(properties);
        var controller = new AsyncController();
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new TriageMvcEndpoints(properties))
            .addFilters(new TriageRequestFilter(recorder)).build();
        var pending = mvc.perform(get("/api/async/private-id")).andExpect(request().asyncStarted()).andReturn();
        assertThat(((ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null)).requestCount()).isZero();
        Thread.sleep(50);
        controller.pending.setErrorResult(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "private-error"));
        mvc.perform(asyncDispatch(pending)).andExpect(status().isServiceUnavailable());
        var observed = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        assertThat(observed.requestCount()).isEqualTo(1);
        assertThat(observed.recordedRequestCount()).isEqualTo(1);
        assertThat(observed.requestP95Ms()).isGreaterThanOrEqualTo(40);
        assertThat(observed.errors()).hasSize(1);
        assertThat(observed.responseStatuses().serverError()).isEqualTo(1);
        assertThat(observed.responseStatuses().successful()).isZero();
        assertThat(observed.endpoints()).singleElement().satisfies(endpoint ->
            assertThat(endpoint.endpoint().routeTemplate()).isEqualTo("/api/async/{id}"));
        assertThat(observed.toString()).doesNotContain("private-id", "private-error");
        assertThat(TriageRequestFilter.CURRENT.get()).isNull();
    }
    @Test void successfulAsyncMvcRequestDoesNotCreateAnError() throws Exception {
        var properties = ObservationRecorderTest.properties(); properties.setEndpointObservations(true);
        var recorder = new ObservationRecorder(properties);
        var controller = new AsyncController();
        var mvc = MockMvcBuilders.standaloneSetup(controller).addInterceptors(new TriageMvcEndpoints(properties))
            .addFilters(new TriageRequestFilter(recorder)).build();
        var pending = mvc.perform(get("/api/async/private-id")).andExpect(request().asyncStarted()).andReturn();
        controller.pending.setResult("private-body");
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
        var observed = (ObservationRecorder.EndpointWindow) recorder.endpointSnapshot(5, Instant.now(), null);
        assertThat(observed.requestCount()).isEqualTo(1);
        assertThat(observed.errors()).isEmpty();
        assertThat(observed.endpoints()).singleElement().satisfies(endpoint ->
            assertThat(endpoint.endpoint().routeTemplate()).isEqualTo("/api/async/{id}"));
        assertThat(observed.toString()).doesNotContain("private-id", "private-body");
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
