package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.List;
import java.util.Map;

/** Response classes describe a window's impact, not the cause of a particular status code. */
public final class HttpResponseDiagnosis {
    private HttpResponseDiagnosis() {}

    public static boolean requiresGate(String question, Evidence metrics) {
        if (metrics == null || EvidenceRules.database(metrics) || EvidenceRules.count(metrics.data(), "requestCount") < 1) return false;
        return QuestionScope.responseStatusQuestion(question)
            || metrics.data().get("downstreamTimeoutRate") instanceof Number rate && rate.doubleValue() == 0
                && EvidenceRules.responseStatusGap(metrics);
    }
    public static Diagnosis incomplete(Evidence metrics, Evidence logs) {
        boolean collected = metrics.data().get("responseStatuses") instanceof Map<?, ?>;
        return new Diagnosis(List.of(new Finding(metrics.summary(), List.of(metrics.id())), new Finding(logs.summary(), List.of(logs.id()))),
            List.of(), collected ? List.of("核对客户端看到的具体状态码、请求时间和所选接口。", "结合应用错误处理、访问权限和业务记录补充证据。")
                : List.of("在 Starter 中开启响应状态分类，再检查相同接口的窗口。", "核对客户端看到的具体状态码和请求时间。"),
            collected ? "响应分类只能说明窗口内的响应分布，不能确认下游归因，也不能确定某个 4xx 或 5xx 的根因。具体状态码、业务条件及完整调用链仍需核实。"
                : "该服务未采集响应状态分类；请求数和下游超时率不能说明某个 HTTP 状态码的原因。");
    }
}
