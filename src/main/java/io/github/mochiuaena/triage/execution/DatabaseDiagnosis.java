package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.*;

/** Shared bounded wording for the database rules and validated model selections. */
public final class DatabaseDiagnosis {
    private DatabaseDiagnosis() {}
    public static Diagnosis evaluate(List<Evidence> evidence, ServiceInfo info) {
        Evidence metrics = evidence.stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
        Evidence logs = evidence.stream().filter(e -> e.source().equals("query_error_logs")).findFirst().orElseThrow();
        boolean requests = EvidenceRules.count(metrics.data(), "requestCount") > 0;
        boolean exhausted = requests && EvidenceRules.exhausted(metrics, logs);
        boolean noTimeout = requests && EvidenceRules.noDatabaseTimeout(metrics, logs);
        boolean rule = evidence.stream().anyMatch(e -> e.source().equals("search_runbooks") && e.id().startsWith(EvidenceRules.required(metrics)));
        List<String> steps = !requests ? List.of("先产生数据库请求，再查询相同时间窗口。") : exhausted
            ? List.of("用错误 traceId 对照连接获取阶段，检查哪些请求和事务持有连接。", "核对连接是否及时关闭及池容量，再验证请求恢复。")
            : List.of("核对 SQL 查询耗时、锁等待和事务状态。", "补充数据库与连接池资源指标，缩小慢请求范围。");
        return render(evidence, info, exhausted, rule && (exhausted || noTimeout), steps);
    }
    public static Diagnosis render(List<Evidence> selected, ServiceInfo info, boolean exhausted, boolean success, List<String> steps) {
        var observations = selected.stream().filter(e -> Set.of("read_service_metrics", "query_error_logs").contains(e.source()))
            .map(e -> new Finding(e.summary(), List.of(e.id()))).toList();
        var causes = List.<Finding>of();
        if (success) {
            String required = exhausted ? EvidenceRules.DB_TIMEOUT : EvidenceRules.DB_BASELINE;
            var metrics = selected.stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
            var logs = selected.stream().filter(e -> e.source().equals("query_error_logs")).findFirst().orElseThrow();
            var rule = selected.stream().filter(e -> e.source().equals("search_runbooks") && e.id().startsWith(required)).findFirst().orElseThrow();
            causes = List.of(new Finding(exhausted
                ? "本窗口发生数据库连接获取超时，采样也记录了连接池满载与等待线程；" + info.name() + "请求可能受连接池耗尽影响。"
                : "本窗口未发现连接池耗尽的超时证据；仍需检查 SQL 耗时和其他延迟来源。", List.of(metrics.id(), logs.id(), rule.id())));
        }
        return new Diagnosis(observations, causes, steps, success
            ? "判断只覆盖本次窗口。尚未采集持有连接的具体请求、事务内部和 SQL 执行计划，不能确认连接泄漏或数据库内部根因。"
            : "现有证据不足以判断连接池耗尽。SQL 查询失败与获取连接超时须分开核对，无请求窗口也不能支持状态判断。");
    }
}
