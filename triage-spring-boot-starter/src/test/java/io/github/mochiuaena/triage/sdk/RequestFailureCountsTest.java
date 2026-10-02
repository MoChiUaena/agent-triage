package io.github.mochiuaena.triage.sdk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

class RequestFailureCountsTest {
    private WebApplicationContextRunner enabled() {
        return new WebApplicationContextRunner().withConfiguration(AutoConfigurations.of(TriageObservationAutoConfiguration.class))
            .withPropertyValues("triage.sdk.enabled=true", "triage.sdk.kind=HTTP_REQUESTS", "triage.sdk.service-id=request-service",
                "triage.sdk.endpoint-observations=true", "triage.sdk.response-status-counts=true", "triage.sdk.request-failure-counts=true");
    }
    @RestController static class Controller {
        @GetMapping("/api/plain") ResponseEntity<Void> plain() { return ResponseEntity.status(503).build(); }
        @GetMapping("/api/escape") void escape() { throw new IllegalStateException("private-error"); }
        @GetMapping("/api/handled") void handled() { throw new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND, "private-not-found"); }
    }
    @Test void distinguishesEscapingExecutionFailureFromPlainServerResponseWithoutStackCapture() {
        enabled().run(context -> {
            assertThat(context).hasNotFailed();
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(context.getBean(TriageMvcEndpoints.class))
                .addFilter(context.getBean(FilterRegistrationBean.class).getFilter(), "/api/*").build();
            mvc.perform(get("/api/plain"));
            assertThatThrownBy(() -> mvc.perform(get("/api/escape"))).hasRootCauseInstanceOf(IllegalStateException.class);
            var value = new ObjectMapper().findAndRegisterModules().valueToTree(context.getBean(ObservationRecorder.class).requestSnapshot(1, Instant.now(), null));
            assertThat(value.at("/requestFailures/executionFailures").asInt(-1)).isEqualTo(1);
            assertThat(value.at("/requestFailures/serverErrorResponses").asInt(-1)).isEqualTo(1);
            assertThat(value.at("/responseStatuses/serverError").asInt()).isEqualTo(1);
            assertThat(value.at("/responseStatuses/unknown").asInt()).isEqualTo(1);
            assertThat(value.path("errors").toString()).contains("REQUEST_EXECUTION_FAILED", "HTTP_SERVER_ERROR_RESPONSE").doesNotContain("private-error");
        });
    }
    @Test void handledClientExceptionIsNotAnExecutionFailure() {
        enabled().run(context -> {
            var observer = context.getBean(TriageHandledExceptionObserver.class);
            var mvc = MockMvcBuilders.standaloneSetup(new Controller()).addInterceptors(context.getBean(TriageMvcEndpoints.class))
                .setHandlerExceptionResolvers(observer, new org.springframework.web.servlet.mvc.annotation.ResponseStatusExceptionResolver())
                .addFilter(context.getBean(FilterRegistrationBean.class).getFilter(), "/api/*").build();
            mvc.perform(get("/api/handled"));
            var value = new ObjectMapper().findAndRegisterModules().valueToTree(context.getBean(ObservationRecorder.class).requestSnapshot(1, Instant.now(), null));
            assertThat(value.at("/requestFailures/executionFailures").asInt(-1)).isZero();
            assertThat(value.at("/requestFailures/handledExceptions").asInt(-1)).isEqualTo(1);
            assertThat(value.at("/errors/0/code").asText()).isEqualTo("REQUEST_EXCEPTION_HANDLED");
            assertThat(value.at("/errors/0/responseClass").asInt()).isEqualTo(4);
        });
    }
    @Test void classificationRequiresInboundModeAndResponseCounts() {
        enabled().withPropertyValues("triage.sdk.response-status-counts=false").run(context -> assertThat(context).hasFailed());
        enabled().withPropertyValues("triage.sdk.kind=HTTP", "triage.sdk.downstream-id=inventory", "triage.sdk.downstream-base-url=http://127.0.0.1:18084")
            .run(context -> assertThat(context).hasFailed());
    }
    @Test void asyncTimeoutAndAsyncErrorDoNotBecomeExecutionFailuresAndLateSignalsAreIgnored() {
        enabled().run(context -> {
            var recorder = context.getBean(ObservationRecorder.class);
            for (boolean timeout : new boolean[]{true, false}) {
                var request = new org.springframework.mock.web.MockHttpServletRequest("GET", "/api/async"); request.setAsyncSupported(true);
                var response = new org.springframework.mock.web.MockHttpServletResponse();
                new TriageRequestFilter(recorder).doFilter(request, response, (req, res) -> req.startAsync(req, res));
                var async = (org.springframework.mock.web.MockAsyncContext) request.getAsyncContext();
                for (var listener : async.getListeners()) {
                    var event = new jakarta.servlet.AsyncEvent(async);
                    if (timeout) listener.onTimeout(event); else listener.onError(event);
                }
                async.complete();
                var observed = (TriageRequestFilter.Context) request.getAttribute(TriageRequestFilter.CONTEXT_ATTRIBUTE);
                observed.recordRequestException(() -> null);
            }
            var value = new ObjectMapper().findAndRegisterModules().valueToTree(recorder.requestSnapshot(1, Instant.now(), null));
            assertThat(value.at("/requestFailures/executionFailures").asInt()).isZero();
            assertThat(value.at("/requestFailures/asyncTimeouts").asInt()).isEqualTo(1);
            assertThat(value.at("/requestFailures/asyncErrors").asInt()).isEqualTo(1);
            assertThat(value.path("requestCount").asInt()).isEqualTo(2);
        });
    }
}
