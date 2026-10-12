package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.springframework.stereotype.Component;
import java.util.List;

/** Deterministic rules for the demonstration, deliberately not presented as an LLM. */
@Component
public class DemoReasoner {
    public boolean supports(String question) {
        return QuestionScope.supports(question);
    }

    public Diagnosis diagnose(List<Evidence> evidence) {
        return diagnose(evidence, ServiceInfo.order());
    }

    public Diagnosis diagnose(List<Evidence> evidence, ServiceInfo info) {
        String service = info.name();
        String downstream = info.downstreamName();
        Evidence metrics = evidence.stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
        Evidence logs = evidence.stream().filter(e -> e.source().equals("query_error_logs")).findFirst().orElseThrow();
        if (EvidenceRules.inbound(metrics)) return RequestExecutionDiagnosis.supported(evidence)
            ? RequestExecutionDiagnosis.evaluate(evidence, info) : HttpResponseDiagnosis.incomplete(metrics, logs);
        if (EvidenceRules.database(metrics)) return DatabaseDiagnosis.evaluate(evidence, info);
        if (((Number) metrics.data().get("requestCount")).intValue() == 0)
            return new Diagnosis(List.of(), List.of(), List.of("先让" + service + "处理一些请求，再重新排查相同时间窗口。"),
                "该时间窗口没有服务请求，无法判断当前延迟和超时情况。");
        if (MultiDownstreamEvidence.isMulti(metrics)) return multiDownstream(evidence, info, metrics, logs);
        boolean timeout = ((Number) metrics.data().get("downstreamTimeoutRate")).doubleValue() > 0;
        if (!timeout && EvidenceRules.responseStatusGap(metrics)) return HttpResponseDiagnosis.incomplete(metrics, logs);
        var observations = List.of(new Finding(metrics.summary(), List.of(metrics.id())), new Finding(logs.summary(), List.of(logs.id())));
        if (!timeout && ((Number) logs.data().get("returnedCount")).intValue() > 0)
            return new Diagnosis(observations, List.of(), List.of("先确认请求错误的类型与影响范围，再补充对应排障规则。"),
                "已看到请求错误，但当前证据不能确认下游归因，也不能判定为读取超时或健康状态。");
        String requiredDoc = timeout ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#";
        Evidence rule = evidence.stream().filter(e -> e.id().startsWith(requiredDoc)).findFirst().orElse(null);
        boolean hasRule = rule != null;
        var causes = hasRule ? List.of(new Finding(timeout
            ? service + "请求可能受" + downstream + "调用超时影响，需要进一步检查下游服务和网络。"
            : "本次窗口未发现下游超时证据；其他慢因尚需检查。",
            List.of(metrics.id(), logs.id(), rule.id()))) : List.<Finding>of();
        return new Diagnosis(observations, causes,
            timeout ? List.of("查看同一时间段内 " + info.downstreamId() + " 的延迟、错误率和资源使用情况。",
                "用日志中的 traceId 对照 " + info.id() + " 与 " + info.downstreamId() + " 的调用耗时。", "核对客户端请求超时设置，检查连接池等待和网络延迟。")
                : List.of("找到具体慢请求的时间和 traceId，缩小查询范围。", "补充数据库和其他依赖的调用耗时。"),
            hasRule ? "尚未采集数据库、网络和下游服务的资源指标，无法进一步判断原因。"
                : "已获取观测数据，但没有检索到对应的排障文档，暂时无法判断原因。");
    }

    private Diagnosis multiDownstream(List<Evidence> evidence, ServiceInfo info, Evidence metrics, Evidence logs) {
        boolean timeout = EvidenceRules.count(metrics.data(), "timeoutCount") > 0;
        var affected = MultiDownstreamEvidence.affected(metrics, info);
        var observations = List.of(new Finding(metrics.summary(), List.of(metrics.id())), new Finding(logs.summary(), List.of(logs.id())));
        long events = EvidenceRules.count(logs.data(), "returnedCount");
        if (timeout && affected.isEmpty() || !timeout && EvidenceRules.responseStatusGap(metrics))
            return new Diagnosis(observations, List.of(), List.of("核对同一窗口的请求与下游分项指标。"), "现有证据不能完成下游归因。");
        if (timeout && events <= 0 || !timeout && events > 0)
            return new Diagnosis(observations, List.of(), List.of("核对错误事件类型与下游分项计数，再补充同窗口证据。"),
                "指标与错误事件不足以支持本次下游状态判断。");
        String prefix = timeout ? "DOC-DOWNSTREAM-TIMEOUT#" : "DOC-HEALTHY-BASELINE#";
        Evidence rule = evidence.stream().filter(item -> item.id().startsWith(prefix)).findFirst().orElse(null);
        String names = String.join("、", affected.stream().map(Downstream::name).toList());
        var causes = rule == null ? List.<Finding>of() : List.of(new Finding(timeout
            ? "本窗口观察到" + names + "调用超时，可能影响" + info.name() + "请求；其他下游不能据此判为故障。"
            : "本窗口未发现已配置下游调用超时；其他慢因仍需检查。", List.of(metrics.id(), logs.id(), rule.id())));
        var next = timeout ? List.of("查看同一窗口内 " + String.join("、", affected.stream().map(Downstream::id).toList()) + " 的延迟、错误率和资源使用情况。",
            "用已有 traceId 核对" + info.name() + "请求与超时下游调用的耗时。")
            : List.of("找到具体慢请求的时间和 traceId，缩小查询范围。", "补充数据库和其他依赖的调用耗时。");
        return new Diagnosis(observations, causes, next, rule == null
            ? "未检索到匹配的排障规则，暂时不能形成状态判断。"
            : "分项只覆盖本窗口已采集的调用，未采集下游内部和网络指标，不能确认内部根因或服务整体健康。");
    }
}
