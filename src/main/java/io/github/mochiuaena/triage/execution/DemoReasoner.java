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
        if (EvidenceRules.database(metrics)) return DatabaseDiagnosis.evaluate(evidence, info);
        if (((Number) metrics.data().get("requestCount")).intValue() == 0)
            return new Diagnosis(List.of(), List.of(), List.of("先让" + service + "处理一些请求，再重新排查相同时间窗口。"),
                "该时间窗口没有服务请求，无法判断当前延迟和超时情况。");
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
}
