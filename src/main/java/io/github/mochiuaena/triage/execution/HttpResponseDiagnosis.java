package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.List;
import java.util.Map;

/** Response classes describe a window's impact, not the cause of a particular status code. */
public final class HttpResponseDiagnosis {
    private HttpResponseDiagnosis() {}

    public static boolean requiresGate(String question, Evidence metrics) {
        if (metrics == null || EvidenceRules.database(metrics) || EvidenceRules.count(metrics.data(), "requestCount") < 1) return false;
        if (EvidenceRules.inbound(metrics)) return true;
        return QuestionScope.responseStatusQuestion(question)
            || metrics.data().get("downstreamTimeoutRate") instanceof Number rate && rate.doubleValue() == 0
                && EvidenceRules.responseStatusGap(metrics);
    }
    public static Diagnosis incomplete(Evidence metrics, Evidence logs) {
        if (EvidenceRules.inbound(metrics)) return new Diagnosis(List.of(new Finding(metrics.summary(), List.of(metrics.id())),
                new Finding(logs.summary(), List.of(logs.id()))), List.of(),
            List.of("核对同一接口的响应分布、错误事件和业务处理记录。", "补充本应用的 CPU、线程、数据库等资源指标后继续排查。"),
            "本次仅采集入站请求，未采集下游调用。请求数量、响应分类和普通错误不能证明下游无超时，也不能单独确认请求失败的根因。");
        boolean collected = metrics.data().get("responseStatuses") instanceof Map<?, ?>;
        return new Diagnosis(List.of(new Finding(metrics.summary(), List.of(metrics.id())), new Finding(logs.summary(), List.of(logs.id()))),
            List.of(), collected ? List.of("核对客户端看到的具体状态码、请求时间和所选接口。", "结合应用错误处理、访问权限和业务记录补充证据。")
                : List.of("在 Starter 中开启响应状态分类，再检查相同接口的窗口。", "核对客户端看到的具体状态码和请求时间。"),
            collected ? "响应分类只能说明窗口内的响应分布，不能确认下游归因，也不能确定某个 4xx 或 5xx 的根因。具体状态码、业务条件及完整调用链仍需核实。"
                : "该服务未采集响应状态分类；请求数和下游超时率不能说明某个 HTTP 状态码的原因。");
    }
}
