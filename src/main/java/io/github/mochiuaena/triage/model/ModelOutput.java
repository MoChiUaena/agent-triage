package io.github.mochiuaena.triage.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.exc.*;
import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.*;
import java.util.*;
import java.util.stream.Collectors;

/** The model selects claims and evidence; only application code writes diagnostic text. */
final class ModelOutput {
    enum Assessment { DOWNSTREAM_TIMEOUT_OBSERVED, NO_DOWNSTREAM_TIMEOUT_OBSERVED,
        DB_POOL_EXHAUSTION_OBSERVED, NO_DB_POOL_EXHAUSTION_OBSERVED,
        DB_SQL_EXECUTION_FAILURE_OBSERVED, INSUFFICIENT_EVIDENCE }
    enum Check {
        INSPECT_INVENTORY_LATENCY, CORRELATE_TRACE, VERIFY_REQUEST_TIMEOUT, COLLECT_RESOURCE_METRICS,
        FIND_SLOW_REQUEST, COLLECT_OBSERVATIONS, SEARCH_MATCHING_RULE,
        INSPECT_DB_CONNECTION_HOLDERS, VERIFY_DB_POOL_LIMITS, INSPECT_DB_QUERIES
    }
    record Response(Assessment assessment, List<String> evidenceIds, List<Check> nextChecks) {}
    record Parsed(Assessment assessment, List<Check> requestedNextChecks, List<Check> nextChecks, TriageEngine.Decision decision) {}

    private final ObjectMapper json;
    private final String format;

    ModelOutput(ObjectMapper mapper) {
        json = strictMapper(mapper);
        var properties = new LinkedHashMap<String, Object>();
        properties.put("assessment", Map.of("type", "string", "enum", Arrays.stream(Assessment.values()).map(Enum::name).toList()));
        properties.put("evidenceIds", Map.of("type", "array", "items", Map.of("type", "string"),
            "maxItems", 15, "uniqueItems", true,
            "description", "成功判断须同时选择一条当前指标、一条当前日志及至少一条与判断对应的规则 ID。"));
        properties.put("nextChecks", Map.of("type", "array", "items", Map.of("type", "string",
            "enum", Arrays.stream(Check.values()).map(Enum::name).toList()), "minItems", 1, "maxItems", 5, "uniqueItems", true,
            "description", "只能选择当前允许的检查项；已完成的观测或规则查询不得重复建议，关联 trace 需要现有日志 traceId。"));
        try {
            format = json.writeValueAsString(Map.of("type", "object", "properties", properties,
                "required", List.copyOf(properties.keySet()), "additionalProperties", false));
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot build assessment schema"); }
    }

    static ObjectMapper strictMapper(ObjectMapper mapper) {
        ObjectMapper copy = mapper.copy().enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
            DeserializationFeature.FAIL_ON_TRAILING_TOKENS, DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
            DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        var textCoercion = copy.coercionConfigFor(com.fasterxml.jackson.databind.type.LogicalType.Textual);
        for (var shape : List.of(com.fasterxml.jackson.databind.cfg.CoercionInputShape.Integer,
            com.fasterxml.jackson.databind.cfg.CoercionInputShape.Float, com.fasterxml.jackson.databind.cfg.CoercionInputShape.Boolean))
            textCoercion.setCoercion(shape, com.fasterxml.jackson.databind.cfg.CoercionAction.Fail);
        copy.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
        copy.getFactory().setStreamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20)
            .maxStringLength(20_000).maxNumberLength(20).build());
        return copy;
    }

    String format() { return format(false); }
    String format(boolean database) {
        try {
            var schema = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(format);
            var assessments = ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties").path("assessment")).putArray("enum");
            Arrays.stream(Assessment.values()).filter(value -> value == Assessment.INSUFFICIENT_EVIDENCE || database == databaseAssessment(value))
                .forEach(value -> assessments.add(value.name()));
            var checks = ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties").path("nextChecks").path("items")).putArray("enum");
            Arrays.stream(Check.values()).filter(check -> database ? check != Check.INSPECT_INVENTORY_LATENCY && check != Check.VERIFY_REQUEST_TIMEOUT
                : !Set.of(Check.INSPECT_DB_CONNECTION_HOLDERS, Check.VERIFY_DB_POOL_LIMITS, Check.INSPECT_DB_QUERIES).contains(check)).forEach(check -> checks.add(check.name()));
            return json.writeValueAsString(schema);
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot build observation-specific schema", e); }
    }
    String inboundFormat() {
        try {
            var schema = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree(format(false));
            ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties").path("assessment")).putArray("enum").add("INSUFFICIENT_EVIDENCE");
            var checks = ((com.fasterxml.jackson.databind.node.ObjectNode) schema.path("properties").path("nextChecks").path("items")).putArray("enum");
            for (Check check : List.of(Check.COLLECT_OBSERVATIONS, Check.SEARCH_MATCHING_RULE, Check.CORRELATE_TRACE,
                Check.FIND_SLOW_REQUEST, Check.COLLECT_RESOURCE_METRICS)) checks.add(check.name());
            return json.writeValueAsString(schema);
        } catch (JsonProcessingException e) { throw new IllegalStateException("Cannot build inbound request schema", e); }
    }
    static boolean databaseAssessment(Assessment assessment) {
        return assessment == Assessment.DB_POOL_EXHAUSTION_OBSERVED || assessment == Assessment.NO_DB_POOL_EXHAUSTION_OBSERVED
            || assessment == Assessment.DB_SQL_EXECUTION_FAILURE_OBSERVED;
    }

    Parsed parse(String text, List<Evidence> evidence) {
        return parse(text, evidence, ServiceInfo.order());
    }

    Parsed parse(String text, List<Evidence> evidence, ServiceInfo info) {
        try {
            if (text == null || text.length() > 16_000) throw new IllegalArgumentException();
            String content = text.strip();
            if ((content.startsWith("```json\n") || content.startsWith("```\n")) && content.endsWith("```"))
                content = content.substring(content.indexOf('\n') + 1, content.length() - 3).strip();
            Response response = json.readValue(content, Response.class);
            if (response == null || response.assessment() == null || response.evidenceIds() == null
                || response.evidenceIds().size() > 15 || new HashSet<>(response.evidenceIds()).size() != response.evidenceIds().size())
                throw invalid("模型缺少判断类型，或证据列表超出限制，未保存为排查结果。");
            if (response.nextChecks() == null || response.nextChecks().isEmpty() || response.nextChecks().size() > 5
                || response.nextChecks().contains(null)
                || new HashSet<>(response.nextChecks()).size() != response.nextChecks().size())
                throw invalid("模型检查项列表为空、重复或超出限制，未保存为排查结果。");
            Map<String, Evidence> byId = evidence.stream().collect(Collectors.toMap(Evidence::id, item -> item));
            if (!byId.keySet().containsAll(response.evidenceIds()))
                throw invalid("模型选择了不属于本次执行的证据 ID，未保存为排查结果。");
            List<Evidence> selected = response.evidenceIds().stream().map(byId::get).toList();
            if (response.assessment() != Assessment.INSUFFICIENT_EVIDENCE) validateAssessment(response.assessment(), selected);
            if (!allowedChecks(response.assessment(), evidence).containsAll(response.nextChecks()))
                throw new RunFailure("MODEL_CHECKS_MISMATCH", "模型检查项重复已有查询，或缺少对应观测，未保存为排查结果。");
            List<Check> prioritized = prioritize(response.assessment(), response.nextChecks());
            Diagnosis diagnosis = render(response, selected, prioritized, info);
            EvidenceValidator.validate(diagnosis, evidence);
            return new Parsed(response.assessment(), List.copyOf(response.nextChecks()), prioritized, new TriageEngine.Decision(response.assessment() == Assessment.INSUFFICIENT_EVIDENCE
                ? Status.INSUFFICIENT_EVIDENCE : Status.SUCCEEDED, diagnosis));
        } catch (RunFailure e) { throw e; }
        catch (UnrecognizedPropertyException e) {
            throw invalid("模型回复包含契约之外的字段，未保存为排查结果。");
        }
        catch (InvalidFormatException e) {
            throw invalid("模型判断类型或检查项不在允许列表中，未保存为排查结果。");
        }
        catch (MismatchedInputException e) {
            throw invalid("模型 JSON 字段类型或结构不符合契约，未保存为排查结果。");
        }
        catch (JsonProcessingException e) {
            throw invalid("模型回复不是有效的单个 JSON 对象，未保存为排查结果。");
        }
        catch (Exception e) {
            throw invalid("模型判断类型、检查项或证据选择无效，未保存为排查结果。");
        }
    }

    private RunFailure invalid(String message) { return new RunFailure("INVALID_MODEL_OUTPUT", message); }

    private List<Check> prioritize(Assessment assessment, List<Check> requested) {
        List<Check> order = switch (assessment) {
            case DOWNSTREAM_TIMEOUT_OBSERVED -> List.of(Check.CORRELATE_TRACE, Check.INSPECT_INVENTORY_LATENCY,
                Check.VERIFY_REQUEST_TIMEOUT, Check.COLLECT_RESOURCE_METRICS, Check.FIND_SLOW_REQUEST);
            case NO_DOWNSTREAM_TIMEOUT_OBSERVED -> List.of(Check.COLLECT_RESOURCE_METRICS, Check.FIND_SLOW_REQUEST,
                Check.INSPECT_INVENTORY_LATENCY);
            case DB_POOL_EXHAUSTION_OBSERVED -> List.of(Check.CORRELATE_TRACE, Check.INSPECT_DB_CONNECTION_HOLDERS,
                Check.VERIFY_DB_POOL_LIMITS, Check.INSPECT_DB_QUERIES, Check.COLLECT_RESOURCE_METRICS, Check.FIND_SLOW_REQUEST);
            case DB_SQL_EXECUTION_FAILURE_OBSERVED -> List.of(Check.CORRELATE_TRACE, Check.INSPECT_DB_QUERIES,
                Check.COLLECT_RESOURCE_METRICS, Check.FIND_SLOW_REQUEST);
            case NO_DB_POOL_EXHAUSTION_OBSERVED -> List.of(Check.INSPECT_DB_QUERIES, Check.COLLECT_RESOURCE_METRICS,
                Check.FIND_SLOW_REQUEST, Check.VERIFY_DB_POOL_LIMITS);
            case INSUFFICIENT_EVIDENCE -> List.of(Check.COLLECT_OBSERVATIONS, Check.SEARCH_MATCHING_RULE,
                Check.CORRELATE_TRACE, Check.INSPECT_DB_QUERIES, Check.FIND_SLOW_REQUEST, Check.COLLECT_RESOURCE_METRICS);
        };
        return order.stream().filter(requested::contains).limit(2).toList();
    }

    static Set<Check> allowedChecks(Assessment assessment, List<Evidence> evidence) {
        EnumSet<Check> checks = EnumSet.of(Check.FIND_SLOW_REQUEST, Check.COLLECT_RESOURCE_METRICS);
        Evidence metrics = evidence.stream().filter(item -> item.source().equals("read_service_metrics")).findFirst().orElse(null);
        Evidence logs = evidence.stream().filter(item -> item.source().equals("query_error_logs")).findFirst().orElse(null);
        boolean database = EvidenceRules.database(metrics);
        if (assessment != Assessment.INSUFFICIENT_EVIDENCE && database != databaseAssessment(assessment)) return Set.of();
        boolean hasRequests = metrics != null && metrics.data().get("requestCount") instanceof Number count && count.longValue() > 0;
        if (assessment != Assessment.INSUFFICIENT_EVIDENCE) {
            if (database) {
                checks.add(Check.INSPECT_DB_QUERIES);
                if (assessment != Assessment.DB_SQL_EXECUTION_FAILURE_OBSERVED) checks.add(Check.VERIFY_DB_POOL_LIMITS);
                if (assessment == Assessment.DB_POOL_EXHAUSTION_OBSERVED) checks.add(Check.INSPECT_DB_CONNECTION_HOLDERS);
            } else {
                checks.add(Check.INSPECT_INVENTORY_LATENCY);
                if (assessment == Assessment.DOWNSTREAM_TIMEOUT_OBSERVED) checks.add(Check.VERIFY_REQUEST_TIMEOUT);
            }
        } else {
            if (!hasRequests || logs == null) checks.add(Check.COLLECT_OBSERVATIONS);
            String rule = EvidenceRules.required(metrics);
            boolean matched = evidence.stream().anyMatch(item -> item.source().equals("search_runbooks")
                && (rule == null ? item.id().startsWith("DOC-DOWNSTREAM-TIMEOUT#") || item.id().startsWith("DOC-HEALTHY-BASELINE#")
                    : item.id().startsWith(rule)));
            if (!matched) checks.add(Check.SEARCH_MATCHING_RULE);
            if (database && EvidenceRules.pool(metrics).get("queryP95Ms") instanceof Number query && query.doubleValue() > 0)
                checks.add(Check.INSPECT_DB_QUERIES);
        }
        if (assessment != Assessment.NO_DOWNSTREAM_TIMEOUT_OBSERVED && assessment != Assessment.NO_DB_POOL_EXHAUSTION_OBSERVED && logs != null
            && logs.data().get("entries") instanceof List<?> entries
            && entries.stream().anyMatch(entry -> entry instanceof Map<?, ?> data
                && data.get("traceId") instanceof String trace && !trace.isBlank())) checks.add(Check.CORRELATE_TRACE);
        return Collections.unmodifiableSet(checks);
    }

    private void validateAssessment(Assessment assessment, List<Evidence> selected) {
        Evidence metrics = one(selected, "read_service_metrics");
        if (EvidenceRules.inbound(metrics))
            throw new RunFailure("MODEL_ASSESSMENT_MISMATCH", "本次只采集入站请求，不能选择下游或数据库成功判断。");
        Evidence logs = one(selected, "query_error_logs");
        if (!(metrics.data().get("requestCount") instanceof Number count) || count.longValue() <= 0)
            throw new RunFailure("MODEL_NO_OBSERVATIONS", "模型试图在无请求窗口生成成功判断，已拒绝。");
        if (EvidenceRules.database(metrics)) {
            boolean supported = switch (assessment) {
                case DB_POOL_EXHAUSTION_OBSERVED -> EvidenceRules.exhausted(metrics, logs);
                case DB_SQL_EXECUTION_FAILURE_OBSERVED -> EvidenceRules.sqlExecutionFailed(metrics, logs);
                case NO_DB_POOL_EXHAUSTION_OBSERVED -> EvidenceRules.noDatabaseTimeout(metrics, logs);
                default -> false;
            };
            if (!databaseAssessment(assessment) || !"DATABASE_POOL".equals(logs.data().get("observationType"))
                || !(logs.data().get("entries") instanceof List<?> entries) || EvidenceRules.count(logs.data(), "returnedCount") != entries.size()
                || !supported)
                throw new RunFailure("MODEL_ASSESSMENT_MISMATCH", "模型判断与数据库阶段、计数或错误事件不符，已拒绝。");
            number(metrics, "requestP95Ms");
            String rule = switch (assessment) {
                case DB_POOL_EXHAUSTION_OBSERVED -> EvidenceRules.DB_TIMEOUT;
                case DB_SQL_EXECUTION_FAILURE_OBSERVED -> EvidenceRules.DB_SQL_FAILURE;
                default -> EvidenceRules.DB_BASELINE;
            };
            if (selected.stream().noneMatch(item -> item.source().equals("search_runbooks") && item.id().startsWith(rule)))
                throw new RunFailure("MODEL_MISSING_EVIDENCE", "模型没有选择与数据库观测对应的排障规则。");
            return;
        }
        if (databaseAssessment(assessment)) throw new RunFailure("MODEL_ASSESSMENT_MISMATCH", "HTTP 观测不能支持数据库阶段判断。");
        double rate = number(metrics, "downstreamTimeoutRate");
        if (rate > 1) throw new IllegalArgumentException();
        number(metrics, "orderP95Ms");
        number(metrics, "downstreamP95Ms");
        if (!(logs.data().get("entries") instanceof List<?> entries)
            || !(logs.data().get("returnedCount") instanceof Number returned)
            || returned.intValue() != entries.size()
            || !(logs.data().get("timeoutCount") instanceof Number timeouts)) throw new IllegalArgumentException();
        boolean timeout = assessment == Assessment.DOWNSTREAM_TIMEOUT_OBSERVED;
        if ((timeout && (rate <= 0 || timeouts.longValue() <= 0 || entries.isEmpty()))
            || (!timeout && (rate != 0 || timeouts.longValue() != 0 || !entries.isEmpty() || EvidenceRules.responseStatusGap(metrics))))
            throw new RunFailure("MODEL_ASSESSMENT_MISMATCH", "模型判断与本窗口指标或错误事件不符，已拒绝。");
        String rule = timeout ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#";
        if (selected.stream().noneMatch(item -> item.source().equals("search_runbooks") && item.id().startsWith(rule)))
            throw new RunFailure("MODEL_MISSING_EVIDENCE", "模型没有选择与本窗口观测匹配的排障规则，未保存为排查结果。");
    }

    private Evidence one(List<Evidence> selected, String source) {
        List<Evidence> matches = selected.stream().filter(item -> item.source().equals(source)).toList();
        if (matches.size() != 1)
            throw new RunFailure("MODEL_MISSING_EVIDENCE", "成功判断必须各选择一条指标证据和一条日志证据，未保存为排查结果。");
        return matches.getFirst();
    }

    private double number(Evidence evidence, String name) {
        if (!(evidence.data().get(name) instanceof Number number)) throw new IllegalArgumentException();
        double value = number.doubleValue();
        if (!Double.isFinite(value) || value < 0) throw new IllegalArgumentException();
        return value;
    }

    private Diagnosis render(Response response, List<Evidence> selected, List<Check> prioritized, ServiceInfo info) {
        String service = info.equals(ServiceInfo.order()) ? "订单" : info.name();
        String downstream = info.equals(ServiceInfo.order()) ? "库存" : info.downstreamName();
        if (info.downstreamId() == null) {
            var observations = selected.stream().filter(item -> Set.of("read_service_metrics", "query_error_logs").contains(item.source()))
                .map(item -> new Finding(item.summary(), List.of(item.id()))).toList();
            var next = prioritized.stream().map(check -> switch (check) {
                case CORRELATE_TRACE -> "用错误事件中的 traceId 核对本应用请求与业务日志。";
                case COLLECT_RESOURCE_METRICS -> "补充本应用的 CPU、线程与数据库等资源指标。";
                default -> checkText(check, service, service, service);
            }).toList();
            return new Diagnosis(observations, List.of(), next, "本次仅采集入站请求，未采集下游调用；现有观测不能确认下游状态或内部根因。");
        }
        if (selected.stream().anyMatch(EvidenceRules::database)) {
            DatabaseDiagnosis.Stage stage = switch (response.assessment()) {
                case DB_POOL_EXHAUSTION_OBSERVED -> DatabaseDiagnosis.Stage.POOL_EXHAUSTED;
                case DB_SQL_EXECUTION_FAILURE_OBSERVED -> DatabaseDiagnosis.Stage.SQL_EXECUTION_FAILED;
                default -> DatabaseDiagnosis.Stage.NO_POOL_TIMEOUT;
            };
            return DatabaseDiagnosis.render(selected, info, stage, response.assessment() != Assessment.INSUFFICIENT_EVIDENCE,
                prioritized.stream().map(check -> check == Check.CORRELATE_TRACE
                    ? stage == DatabaseDiagnosis.Stage.SQL_EXECUTION_FAILED
                        ? "使用错误事件中的 traceId 核对 SQL 执行失败的请求与事务。"
                        : "使用错误事件中的 traceId 核对请求的连接获取阶段耗时。"
                    : checkText(check, service, downstream, info.downstreamName())).toList());
        }
        List<Finding> observations = selected.stream()
            .filter(item -> Set.of("read_service_metrics", "query_error_logs").contains(item.source()))
            .map(item -> new Finding(item.summary(), List.of(item.id()))).toList();
        List<Finding> causes = List.of();
        String uncertainty = "本次证据仍不足以支持当前状态判断。模型只选择了证据和检查项，关键措辞由应用生成。";
        if (response.assessment() != Assessment.INSUFFICIENT_EVIDENCE) {
            boolean timeout = response.assessment() == Assessment.DOWNSTREAM_TIMEOUT_OBSERVED;
            String required = timeout ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#";
            Evidence rule = selected.stream().filter(item -> item.source().equals("search_runbooks") && item.id().startsWith(required))
                .findFirst().orElseThrow();
            List<String> ids = List.of(one(selected, "read_service_metrics").id(), one(selected, "query_error_logs").id(), rule.id());
            causes = List.of(new Finding(timeout
                ? "本窗口存在" + downstream + "调用超时，可能影响" + service + "请求；建议优先验证下游调用路径。"
                : "本窗口未发现" + downstream + "调用超时证据；其他延迟来源仍需补充观测。", ids));
            uncertainty = "本次只覆盖查询窗口内已采集的服务请求、下游调用和错误事件。未采集下游内部、网络、数据库与连接池指标，"
                + "不能确认内部根因或服务整体健康。模型选择判断类型与证据，关键结论由应用按证据生成。";
        }
        List<String> nextSteps = prioritized.stream().map(check -> checkText(check, service, downstream, info.downstreamName())).toList();
        return new Diagnosis(observations, causes, nextSteps, uncertainty);
    }

    private String checkText(Check check, String service, String downstream, String downstreamName) {
        return switch (check) {
            case INSPECT_INVENTORY_LATENCY -> "核对同一窗口内" + downstream + "接口的实际处理耗时和错误率。";
            case CORRELATE_TRACE -> "使用错误事件中已有的 traceId 对照" + service + "与" + downstream + "请求的调用耗时。";
            case VERIFY_REQUEST_TIMEOUT -> "核对" + service + "客户端的请求总时限，并结合下游接口耗时验证。";
            case COLLECT_RESOURCE_METRICS -> "补充" + downstreamName + "的 CPU、连接池、网络及数据库指标后再判断内部原因。";
            case FIND_SLOW_REQUEST -> "找到具体慢请求的时间和 traceId，缩小查询范围。";
            case COLLECT_OBSERVATIONS -> "补充当前窗口的" + service + "指标和错误日志后再判断。";
            case SEARCH_MATCHING_RULE -> "检索与本次正常或超时观测匹配的排障规则。";
            case INSPECT_DB_CONNECTION_HOLDERS -> "对照获取连接超时的 traceId，检查持有连接的请求、长事务和连接释放情况。";
            case VERIFY_DB_POOL_LIMITS -> "核对池容量、获取连接时限和实际并发量，验证释放连接后请求是否恢复。";
            case INSPECT_DB_QUERIES -> "核对 SQL 执行耗时、锁等待和事务状态，区分查询阶段与获取连接阶段。";
        };
    }
}
