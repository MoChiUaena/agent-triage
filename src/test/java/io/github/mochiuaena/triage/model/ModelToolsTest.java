package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.json.JsonMapper;
import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import io.github.mochiuaena.triage.execution.*;
import io.github.mochiuaena.triage.tools.ToolContext;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ModelToolsTest {
    private final ExecutionSession session = mock(ExecutionSession.class);
    private final ModelTools tools = new ModelTools(session, JsonMapper.builder().build());

    @BeforeEach void setup() {
        when(session.toolNames()).thenReturn(List.of("search_runbooks", "read_service_metrics", "query_error_logs"));
        when(session.context()).thenReturn(new ToolContext("order-service", 15, Scenario.NORMAL, Instant.parse("2026-09-28T00:00:00Z")));
    }

    static Stream<Arguments> rejections() {
        return Stream.of(
            Arguments.of("read_service_metrics", null, ModelTools.ArgumentReason.SIZE),
            Arguments.of("read_service_metrics", "x".repeat(4097), ModelTools.ArgumentReason.SIZE),
            Arguments.of("read_service_metrics", "private-value", ModelTools.ArgumentReason.JSON),
            Arguments.of("read_service_metrics", "{} {}", ModelTools.ArgumentReason.JSON),
            Arguments.of("read_service_metrics", "{\"service\":\"order-service\",\"windowMinutes\":15,\"windowMinutes\":60}", ModelTools.ArgumentReason.JSON),
            Arguments.of("read_service_metrics", "null", ModelTools.ArgumentReason.FIELDS),
            Arguments.of("read_service_metrics", "[]", ModelTools.ArgumentReason.FIELDS),
            Arguments.of("read_service_metrics", "{\"service\":\"order-service\",\"windowMinutes\":15,\"private-value\":true}", ModelTools.ArgumentReason.FIELDS),
            Arguments.of("read_service_metrics", "{\"service\":\"private-value\",\"windowMinutes\":15}", ModelTools.ArgumentReason.SERVICE),
            Arguments.of("read_service_metrics", "{\"service\":\"order-service\",\"windowMinutes\":\"15\"}", ModelTools.ArgumentReason.WINDOW),
            Arguments.of("search_runbooks", "{\"service\":\"order-service\",\"windowMinutes\":15,\"query\":false}", ModelTools.ArgumentReason.QUERY),
            Arguments.of("search_runbooks", "{\"service\":\"order-service\",\"windowMinutes\":15,\"query\":\"  \"}", ModelTools.ArgumentReason.QUERY)
        );
    }

    @ParameterizedTest @MethodSource("rejections")
    void invalidArgumentsHaveSafeReasonsAndNeverExecute(String name, String arguments, ModelTools.ArgumentReason reason) {
        assertThatThrownBy(() -> tools.prepare(name, arguments)).isInstanceOfSatisfying(ModelTools.RejectedArguments.class, error -> {
            assertThat(error.reason()).isEqualTo(reason);
            assertThat(error.getMessage()).doesNotContain("private-value");
            String feedback = tools.correctionFeedback(name, error);
            assertThat(feedback).contains("INVALID_TOOL_ARGUMENTS", "expectedSchema", reason.name(), "order-service")
                .doesNotContain("private-value");
        });
        verify(session, never()).callTool(anyString(), anyString());
    }

    @Test void aValidSearchIsPreparedWithoutExecutingOrChangingItsScope() {
        var prepared = tools.prepare("search_runbooks", "{\"service\":\"order-service\",\"windowMinutes\":15,\"query\":\"  订单 超时  \"}");
        assertThat(prepared.name()).isEqualTo("search_runbooks");
        assertThat(prepared.query()).isEqualTo("订单 超时");
        verify(session, never()).callTool(anyString(), anyString());
    }

    @Test void unknownToolsAreFatalInsteadOfReceivingCorrectionFeedback() {
        assertThatThrownBy(() -> tools.prepare("execute_shell", "{}"))
            .isInstanceOfSatisfying(RunFailure.class, error -> assertThat(error.code()).isEqualTo("TOOL_NOT_ALLOWED"));
        verify(session, never()).callTool(anyString(), anyString());
    }
}
