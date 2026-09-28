package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.databind.*;
import io.github.mochiuaena.triage.execution.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import java.util.*;

final class ModelTools {
    enum ArgumentReason { SIZE, JSON, FIELDS, SERVICE, WINDOW, QUERY }
    record PreparedCall(String name, String query) {}
    static final class RejectedArguments extends RuntimeException {
        private final ArgumentReason reason;
        RejectedArguments(ArgumentReason reason, String message) { super(message); this.reason = reason; }
        ArgumentReason reason() { return reason; }
    }
    private final ExecutionSession session;
    private final ObjectMapper json;

    ModelTools(ExecutionSession session, ObjectMapper mapper) {
        this.session = session;
        this.json = ModelOutput.strictMapper(mapper);
    }

    List<ToolCallback> definitions() {
        String observation = session.synthetic() ? "合成观测数据" : "已登记服务实际请求的窗口观测";
        String logs = session.synthetic() ? "合成样例" : "已登记服务写入的";
        return List.of(definition("search_runbooks", "检索所选服务排障文档。query 使用简短关键词，例如：正常 超时。LIVE 检索还提供本服务基础参考规则；文档不能证明当前状态。"),
            definition("read_service_metrics", "读取当前窗口的服务请求和下游延迟、超时率，返回" + observation + "。"),
            definition("query_error_logs", "查询当前窗口的错误日志，最多返回三条" + logs + "错误事件。"));
    }

    private ToolCallback definition(String name, String description) {
        String schema;
        try { schema = json.writeValueAsString(argumentSchema(name)); }
        catch (Exception e) { throw new IllegalStateException("Cannot build tool schema"); }
        ToolDefinition definition = ToolDefinition.builder().name(name).description(description).inputSchema(schema).build();
        return new ToolCallback() {
            public ToolDefinition getToolDefinition() { return definition; }
            public String call(String arguments) { throw new IllegalStateException("Internal tool execution must remain disabled"); }
        };
    }

    private Map<String, Object> argumentSchema(String name) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("service", Map.of("type", "string", "const", session.context().service(),
            "enum", List.of(session.context().service()), "description", "Copy this exact service ID; do not translate it."));
        properties.put("windowMinutes", Map.of("type", "integer", "const", session.context().windowMinutes(),
            "enum", List.of(session.context().windowMinutes()), "minimum", 1, "maximum", 60,
            "description", "Use this exact JSON integer, without quotes or a decimal point."));
        if (name.equals("search_runbooks")) properties.put("query", Map.of("type", "string", "minLength", 1, "maxLength", 200));
        return Map.of("type", "object", "properties", properties,
            "required", List.copyOf(properties.keySet()), "additionalProperties", false);
    }

    PreparedCall prepare(String name, String arguments) {
        if (!session.toolNames().contains(name)) throw new RunFailure("TOOL_NOT_ALLOWED", "模型请求了未注册的工具。");
        if (arguments == null || arguments.length() > 4096) throw rejected(ArgumentReason.SIZE);
        JsonNode input;
        try {
            input = json.readTree(arguments);
        } catch (Exception e) { throw rejected(ArgumentReason.JSON); }
        if (input == null || !input.isObject()) throw rejected(ArgumentReason.FIELDS);
        boolean search = name.equals("search_runbooks");
        Set<String> expected = search ? Set.of("service", "windowMinutes", "query") : Set.of("service", "windowMinutes");
        Set<String> actual = new HashSet<>();
        input.fieldNames().forEachRemaining(actual::add);
        if (!actual.equals(expected)) throw rejected(ArgumentReason.FIELDS);
        if (!input.get("service").isTextual() || !session.context().service().equals(input.get("service").textValue()))
            throw rejected(ArgumentReason.SERVICE);
        if (!input.get("windowMinutes").isIntegralNumber() || !input.get("windowMinutes").canConvertToInt()
            || input.get("windowMinutes").intValue() != session.context().windowMinutes()) throw rejected(ArgumentReason.WINDOW);
        String query = "";
        if (search) {
            if (!input.get("query").isTextual()) throw rejected(ArgumentReason.QUERY);
            query = input.get("query").textValue().strip();
            if (query.isEmpty() || query.length() > 200) throw rejected(ArgumentReason.QUERY);
        }
        return new PreparedCall(name, query);
    }

    private RejectedArguments rejected(ArgumentReason reason) {
        String message = switch (reason) {
            case SIZE -> "工具参数缺失或超过 4096 字符。";
            case JSON -> "工具参数必须是单个有效 JSON 对象，不能有重复字段或尾随内容。";
            case FIELDS -> "工具参数字段必须与该工具 schema 完全一致，不能缺失或增加字段。";
            case SERVICE -> "service 必须是字符串 " + session.context().service() + "，不能改名或翻译。";
            case WINDOW -> "windowMinutes 必须是 JSON 整数 " + session.context().windowMinutes() + "，不能使用字符串、小数或其他窗口。";
            case QUERY -> "query 必须是 1–200 字符的非空字符串。";
        };
        return new RejectedArguments(reason, message);
    }

    String correctionFeedback(String name, RejectedArguments rejection) {
        var error = rejection == null
            ? Map.of("code", "TOOL_BATCH_DEFERRED", "message", "This batch was not executed. Reissue all calls after correcting rejected arguments, using new call IDs.")
            : Map.of("code", "INVALID_TOOL_ARGUMENTS", "reason", rejection.reason().name(), "message", rejection.getMessage());
        try { return json.writeValueAsString(Map.of("error", error, "expectedSchema", argumentSchema(name))); }
        catch (Exception e) { throw new RunFailure("INVALID_TOOL_OUTPUT", "无法生成参数校验反馈。"); }
    }

    String execute(PreparedCall call) {
        var evidence = session.callTool(call.name(), call.query());
        try { return json.writeValueAsString(evidence); }
        catch (Exception e) { throw new RunFailure("INVALID_TOOL_OUTPUT", "无法序列化工具结果。"); }
    }
}
