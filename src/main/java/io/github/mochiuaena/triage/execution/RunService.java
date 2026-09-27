package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.*;
import jakarta.annotation.PreDestroy;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

@Service
public class RunService {
    private final RunRepository repository;
    private final ExecutionLimits limits;
    private final DemoReasoner reasoner;
    private final List<ReadOnlyTool> tools;
    private final ThreadPoolExecutor coordinators = pool("triage-run-", 4, 16);
    private final ThreadPoolExecutor toolWorkers = pool("triage-tool-", 4, 16);

    @Autowired
    public RunService(RunRepository repository, ExecutionLimits limits, DemoReasoner reasoner,
                      RunbookSearchTool runbooks, MetricsTool metrics, ErrorLogsTool logs) {
        this(repository, limits, reasoner, List.of(runbooks, metrics, logs));
    }

    // Package-visible seam for deterministic deadline and failure tests.
    RunService(RunRepository repository, ExecutionLimits limits, DemoReasoner reasoner, List<ReadOnlyTool> tools) {
        this.repository = repository; this.limits = limits; this.reasoner = reasoner; this.tools = List.copyOf(tools);
    }

    private static ThreadPoolExecutor pool(String prefix, int workers, int queue) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
            Thread.ofPlatform().daemon(true).name(prefix, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public Run submit(String question, ToolContext context) {
        Run run = new Run(UUID.randomUUID(), question, context.service(), context.windowMinutes(), context.scenario(),
            "DEMO", true, Status.QUEUED, context.endTime(), null, 0,
            List.of(new Event(1, Instant.now(), "RUN_QUEUED", null, "已排队；使用确定性规则和合成数据。", List.of())), List.of(), null, null);
        repository.insert(run);
        long deadline = System.nanoTime() + limits.runTimeout().toNanos();
        try { coordinators.execute(() -> execute(run, context, deadline)); }
        catch (RejectedExecutionException e) {
            MutableExecution state = stateFrom(run);
            fail(state, "RUN_QUEUE_FULL", "执行队列已满，请稍后再试。");
            throw new CapacityExceededException();
        }
        return run;
    }

    private MutableExecution stateFrom(Run run) {
        var state = new MutableExecution(run);
        state.events.addAll(run.events());
        return state;
    }

    private void execute(Run run, ToolContext context, long deadline) {
        MutableExecution state = stateFrom(run);
        try {
            checkDeadline(deadline);
            state.status = Status.RUNNING;
            publish(state, "RUN_STARTED", null, "开始收集证据。", List.of());
            if (!reasoner.supports(run.question())) {
                state.diagnosis = new Diagnosis(List.of(), List.of(), List.of("请询问 order-service 的订单延迟、健康状态或下游超时。"),
                    "当前演示只覆盖订单查询和库存下游超时，该问题没有可用证据。");
                finish(state, Status.INSUFFICIENT_EVIDENCE);
                return;
            }
            for (ReadOnlyTool tool : tools) {
                checkDeadline(deadline);
                if (state.toolCalls >= limits.maxToolCalls()) throw new RunFailure("TOOL_CALL_LIMIT", "已达到工具调用次数上限。");
                state.toolCalls++;
                publish(state, "TOOL_STARTED", tool.name(), "工具调用开始。", List.of());
                Future<List<Evidence>> future = toolWorkers.submit(() -> tool.execute(context, run.question()));
                long remaining = deadline - System.nanoTime();
                boolean runBudgetFirst = remaining <= limits.toolTimeout().toNanos();
                try {
                    List<Evidence> evidence = future.get(Math.max(1, Math.min(remaining, limits.toolTimeout().toNanos())), TimeUnit.NANOSECONDS);
                    checkDeadline(deadline);
                    if (evidence.size() > 5) throw new RunFailure("TOOL_OUTPUT_LIMIT", "工具返回超过允许的证据数量。");
                    state.evidence.addAll(evidence);
                    publish(state, "TOOL_COMPLETED", tool.name(), "工具返回 " + evidence.size() + " 条证据。",
                        evidence.stream().map(Evidence::id).toList());
                } catch (TimeoutException e) {
                    future.cancel(true);
                    publish(state, "TOOL_FAILED", tool.name(), "工具等待超时，已请求取消。", List.of());
                    throw new RunFailure(runBudgetFirst ? "RUN_TIMEOUT" : "TOOL_TIMEOUT", "执行超过时间预算。");
                } catch (ExecutionException e) {
                    publish(state, "TOOL_FAILED", tool.name(), "工具执行失败。", List.of());
                    throw new RunFailure("TOOL_ERROR", "工具执行失败；没有生成排障结论。");
                } catch (InterruptedException e) {
                    future.cancel(true);
                    throw e;
                }
            }
            checkDeadline(deadline);
            state.diagnosis = reasoner.diagnose(state.evidence);
            EvidenceValidator.validate(state.diagnosis, state.evidence);
            checkDeadline(deadline);
            finish(state, state.diagnosis.possibleCauses().isEmpty() ? Status.INSUFFICIENT_EVIDENCE : Status.SUCCEEDED);
        } catch (RunFailure e) {
            fail(state, e.code, e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(state, "RUN_INTERRUPTED", "执行已中断。");
        } catch (RejectedExecutionException e) {
            fail(state, "TOOL_CAPACITY", "工具工作队列已满。");
        } catch (Exception e) {
            fail(state, "EXECUTION_ERROR", "执行失败；请检查本地配置和持久化存储。");
        }
    }

    private void checkDeadline(long deadline) {
        if (System.nanoTime() >= deadline) throw new RunFailure("RUN_TIMEOUT", "已达到整体执行时长上限。");
    }

    private void publish(MutableExecution state, String type, String tool, String message, List<String> ids) {
        state.event(type, tool, message, ids);
        repository.save(state.snapshot());
    }

    private void finish(MutableExecution state, Status status) {
        state.status = status;
        state.finishedAt = Instant.now();
        publish(state, "RUN_COMPLETED", null, status == Status.SUCCEEDED ? "排障完成，请核查证据。" : "证据不足，无法支持完整判断。", List.of());
    }

    private void fail(MutableExecution state, String code, String message) {
        state.status = Status.FAILED;
        state.finishedAt = Instant.now();
        state.diagnosis = null;
        state.failure = new Failure(code, message);
        publish(state, "RUN_FAILED", null, message, List.of());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (Run run : repository.unfinished()) {
            var events = new ArrayList<>(run.events());
            events.add(new Event(events.size() + 1, Instant.now(), "RUN_FAILED", null, "服务重启，之前的执行已中断。", List.of()));
            repository.save(new Run(run.id(), run.question(), run.service(), run.windowMinutes(), run.scenario(), run.mode(), run.synthetic(),
                Status.FAILED, run.createdAt(), Instant.now(), run.toolCalls(), List.copyOf(events), run.evidence(), null,
                new Failure("SERVER_RESTARTED", "服务重启；保留已收集证据，请重新执行。")));
        }
    }

    @PreDestroy public void close() {
        coordinators.shutdownNow();
        toolWorkers.shutdownNow();
    }

    private static class RunFailure extends RuntimeException {
        private final String code;
        RunFailure(String code, String message) { super(message); this.code = code; }
    }

    public static class CapacityExceededException extends RuntimeException {}
}
