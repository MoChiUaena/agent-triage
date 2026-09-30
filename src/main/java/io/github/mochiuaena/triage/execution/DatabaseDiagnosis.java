package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import java.util.*;

/** Shared bounded wording for the database rules and validated model selections. */
public final class DatabaseDiagnosis {
    public enum Stage { POOL_EXHAUSTED, SQL_EXECUTION_FAILED, NO_POOL_TIMEOUT }
    private DatabaseDiagnosis() {}
    public static Diagnosis evaluate(List<Evidence> evidence, ServiceInfo info) {
        Evidence metrics = evidence.stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
        Evidence logs = evidence.stream().filter(e -> e.source().equals("query_error_logs")).findFirst().orElseThrow();
        boolean requests = EvidenceRules.count(metrics.data(), "requestCount") > 0;
        boolean exhausted = requests && EvidenceRules.exhausted(metrics, logs);
        boolean sqlFailed = requests && !exhausted && EvidenceRules.sqlExecutionFailed(metrics, logs);
        boolean noTimeout = requests && EvidenceRules.noDatabaseTimeout(metrics, logs);
        boolean rule = evidence.stream().anyMatch(e -> e.source().equals("search_runbooks") && e.id().startsWith(EvidenceRules.required(metrics)));
        Stage stage = exhausted ? Stage.POOL_EXHAUSTED : sqlFailed ? Stage.SQL_EXECUTION_FAILED : Stage.NO_POOL_TIMEOUT;
        List<String> steps = !requests ? List.of("先产生数据库请求，再查询相同时间窗口。") : exhausted
            ? List.of("用错误 traceId 对照连接获取阶段，检查哪些请求和事务持有连接。", "核对连接是否及时关闭及池容量，再验证请求恢复。")
            : sqlFailed ? List.of("按错误 traceId 对照失败请求的 SQL 和事务信息。", "核对数据库错误与执行计划，修复后查询新的请求窗口。")
            : List.of("核对 SQL 查询耗时、锁等待和事务状态。", "补充数据库与连接池资源指标，缩小慢请求范围。");
        return render(evidence, info, stage, rule && (exhausted || sqlFailed || noTimeout), steps);
    }
    public static Diagnosis render(List<Evidence> selected, ServiceInfo info, Stage stage, boolean success, List<String> steps) {
        var observations = selected.stream().filter(e -> Set.of("read_service_metrics", "query_error_logs").contains(e.source()))
            .map(e -> new Finding(e.summary(), List.of(e.id()))).toList();
        var causes = List.<Finding>of();
        if (success) {
            String required = switch (stage) {
                case POOL_EXHAUSTED -> EvidenceRules.DB_TIMEOUT;
                case SQL_EXECUTION_FAILED -> EvidenceRules.DB_SQL_FAILURE;
                case NO_POOL_TIMEOUT -> EvidenceRules.DB_BASELINE;
            };
            var metrics = selected.stream().filter(e -> e.source().equals("read_service_metrics")).findFirst().orElseThrow();
            var logs = selected.stream().filter(e -> e.source().equals("query_error_logs")).findFirst().orElseThrow();
            var rule = selected.stream().filter(e -> e.source().equals("search_runbooks") && e.id().startsWith(required)).findFirst().orElseThrow();
            String statement = switch (stage) {
                case POOL_EXHAUSTED -> "本窗口发生数据库连接获取超时，采样也记录了连接池满载与等待线程；" + info.name() + "的操作可能受连接池耗尽影响。";
                case SQL_EXECUTION_FAILED -> "本窗口记录到 SQL 执行阶段失败，错误发生在获取连接之后；不能据此认定连接池耗尽或具体 SQL 根因。";
                case NO_POOL_TIMEOUT -> "本窗口未发现连接池耗尽的超时证据；仍需检查 SQL 耗时和其他延迟来源。";
            };
            causes = List.of(new Finding(statement, List.of(metrics.id(), logs.id(), rule.id())));
        }
        return new Diagnosis(observations, causes, steps, success
            ? stage == Stage.SQL_EXECUTION_FAILED
                ? "本次只确认查询窗口内的 SQL 执行失败。未保存 SQL 文本、参数或执行计划，不能判断语法、锁等待或数据库内部根因。"
                : "判断只覆盖本次窗口。尚未采集持有连接的具体请求、事务内部和 SQL 执行计划，不能确认连接泄漏或数据库内部根因。"
            : "现有证据不足以判断当前数据库阶段。SQL 查询失败与获取连接超时须分开核对，无请求窗口也不能支持状态判断。");
    }
}
