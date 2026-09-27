package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Locale;

/** Deterministic rules for the demonstration, deliberately not presented as an LLM. */
@Component
public class DemoReasoner {
    public boolean supports(String question) {
        String q = question.toLowerCase(Locale.ROOT);
        return List.of("订单", "order", "超时", "timeout", "延迟", "latency", "慢", "slow", "健康", "正常")
            .stream().anyMatch(q::contains);
    }

    public Diagnosis diagnose(List<Evidence> evidence) {
        Evidence metrics = evidence.stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
        Evidence logs = evidence.stream().filter(e -> e.source().equals("query_error_logs")).findFirst().orElseThrow();
        boolean timeout = ((Number) metrics.data().get("downstreamTimeoutRate")).doubleValue() > 0;
        var observations = List.of(new Finding(metrics.summary(), List.of(metrics.id())), new Finding(logs.summary(), List.of(logs.id())));
        String requiredDoc = timeout ? "DOC-DOWNSTREAM-TIMEOUT#v1" : "DOC-HEALTHY-BASELINE#v1";
        boolean hasRule = evidence.stream().anyMatch(e -> e.id().equals(requiredDoc));
        var causes = hasRule ? List.of(new Finding(timeout
            ? "库存下游读取超时可能拖慢同步订单查询；目前支持故障路径判断，不能确定下游超时的最终原因。"
            : "本次窗口指标接近合成基线，未发现下游超时证据。",
            List.of(metrics.id(), logs.id(), requiredDoc))) : List.<Finding>of();
        return new Diagnosis(observations, causes,
            timeout ? List.of("核对相同窗口内 inventory-service 的延迟、错误率及资源使用。",
                "用 synthetic-trace-1 对照调用链；真实环境需换成真实 traceId。", "确认客户端 2000ms 读取超时配置，区分网络、连接池与下游处理耗时。")
                : List.of("提供具体慢请求的时间和 traceId，再对照对应窗口。", "补充数据库和其他依赖耗时，避免仅凭无错误日志排除故障。"),
            hasRule ? "全部观测来自合成适配器；未采集数据库、网络和下游资源指标，不能据此确定生产根因。"
                : "未检索到支持该判断的排障规则，保留观测但不输出原因。所有观测均为合成数据。");
    }
}
