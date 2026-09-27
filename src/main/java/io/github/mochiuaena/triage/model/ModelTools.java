package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.execution.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;

final class ModelTools {
    private final ExecutionSession session;
    private final ObjectMapper json;

    ModelTools(ExecutionSession session, ObjectMapper mapper) {
        this.session = session;
        this.json = ModelOutput.strictMapper(mapper);
    }

    List<ToolCallback> definitions() {
        return List.of(definition("search_runbooks", "检索订单服务的排障文档。query 使用简短关键词，例如：订单 超时。", true),
            definition("read_service_metrics", "读取当前窗口的订单和库存延迟、超时率，返回合成观测数据。", false),
            definition("query_error_logs", "查询当前窗口的错误日志，最多返回三条合成样例。", false));
    }

    private ToolCallback definition(String name, String description, boolean search) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("service", Map.of("type", "string", "const", session.context().service()));
        properties.put("windowMinutes", Map.of("type", "integer", "const", session.context().windowMinutes(), "minimum", 1, "maximum", 60));
        if (search) properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 200));
        String schema;
        try { schema = json.writeValueAsString(Map.of("type", "object", "properties", properties,
            "required", List.copyOf(properties.keySet()), "additionalProperties", false)); }
        catch (Exception e) { throw new IllegalStateException("Cannot build tool schema"); }
        ToolDefinition definition = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return definition; }
            public String call(String arguments) { throw new IllegalStateException("Internal tool execution must remain disabled"); }
        };
    }

    String execute(String name, String arguments) {
        if (!session.toolNames().contains(name)) throw new RunFailure("TOOL_NOT_ALLOWED", "模型请求了未注册的工具。");
        String query = "";
        try {
            if (arguments == null || arguments.length() > 4096) throw new IllegalArgumentException();
            JsonNode input = json.readTree(arguments);
            boolean search = name.equals("search_runbooks");
            Set<String> expected = search ? Set.of("service", "windowMinutes", "query") : Set.of("service", "windowMinutes");
            Set<String> actual = new HashSet<>();
            input.fieldNames().forEachRemaining(actual::add);
            if (!input.isObject() || !actual.equals(expected) || !input.get("service").isTextual()
                || !session.context().service().equals(input.get("service").textValue())
                || !input.get("windowMinutes").isIntegralNumber() || !input.get("windowMinutes").canConvertToInt()
                || input.get("windowMinutes").intValue() != session.context().windowMinutes()) throw new IllegalArgumentException();
            if (search) {
                if (!input.get("query").isTextual()) throw new IllegalArgumentException();
                query = input.get("query").textValue().strip();
                if (query.isEmpty() || query.length() > 200) throw new IllegalArgumentException();
            }
        } catch (Exception e) {
            throw new RunFailure("INVALID_TOOL_ARGUMENTS", "工具参数无效或超出本次排查范围。");
        }
        var evidence = session.callTool(name, query);
        try { return json.writeValueAsString(evidence); }
        catch (Exception e) { throw new RunFailure("INVALID_TOOL_OUTPUT", "无法序列化工具结果。"); }
    }
}
