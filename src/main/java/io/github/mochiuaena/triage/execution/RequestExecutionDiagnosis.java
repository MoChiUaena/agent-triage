package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.*;

public final class RequestExecutionDiagnosis {
    private RequestExecutionDiagnosis() {}
    public static boolean supported(List<Evidence> evidence) {
        var metrics = evidence.stream().filter(value -> value.source().equals("read_service_metrics")).findFirst().orElse(null);
        var logs = evidence.stream().filter(value -> value.source().equals("query_error_logs")).findFirst().orElse(null);
        return EvidenceRules.requestExecutionFailed(metrics, logs) && evidence.stream().anyMatch(value -> value.source().equals("search_runbooks")
            && value.id().startsWith(EvidenceRules.REQUEST_EXECUTION));
    }
    public static Diagnosis render(List<Evidence> evidence, ServiceInfo info, List<String> next) {
        Evidence metrics = evidence.stream().filter(value -> value.source().equals("read_service_metrics")).findFirst().orElseThrow();
        Evidence logs = evidence.stream().filter(value -> value.source().equals("query_error_logs")).findFirst().orElseThrow();
        Evidence rule = evidence.stream().filter(value -> value.source().equals("search_runbooks") && value.id().startsWith(EvidenceRules.REQUEST_EXECUTION)).findFirst().orElseThrow();
        return new Diagnosis(List.of(new Finding(metrics.summary(), List.of(metrics.id())), new Finding(logs.summary(), List.of(logs.id()))),
            List.of(new Finding("本窗口观察到" + info.name() + "请求执行阶段发生异常；当前证据只确认这一阶段，未确认内部根因。",
                List.of(metrics.id(), logs.id(), rule.id()))), next,
            "本次核对了同窗口执行异常计数、5xx或未知响应、对应错误事件与规则。没有采集下游调用，不能归因为下游超时、连接池耗尽或具体数据库问题。");
    }
    public static Diagnosis evaluate(List<Evidence> evidence, ServiceInfo info) {
        return render(evidence, info, List.of("用错误事件的 traceId 和请求时间核对本应用处理过程。", "核查本机异常位置及业务错误处理，补充证据后继续定位。"));
    }
}
