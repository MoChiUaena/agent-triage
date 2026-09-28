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
    private final TriageEngine engine;
    private final List<ReadOnlyTool> tools;
    private final boolean synthetic;
    private final ServiceRegistry registry;
    private final ConcurrentMap<UUID, RunControl> active = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor coordinators = pool("triage-run-", 4, 16);
    private final ThreadPoolExecutor toolWorkers = pool("triage-tool-", 4, 16);
    private final ThreadPoolExecutor modelWorkers = pool("triage-model-", 4, 16);

    @Autowired
    public RunService(RunRepository repository, ExecutionLimits limits, TriageEngine engine,
                      List<ReadOnlyTool> tools, ObservationSource observation, ServiceRegistry registry) {
        this(repository, limits, engine, tools, observation.synthetic(), registry);
    }

    public RunService(RunRepository repository, ExecutionLimits limits, TriageEngine engine,
                      List<ReadOnlyTool> tools, ObservationSource observation) {
        this(repository, limits, engine, tools, observation.synthetic(), new ServiceRegistry(observation, List.of()));
    }

    RunService(RunRepository repository, ExecutionLimits limits, DemoReasoner reasoner, List<ReadOnlyTool> tools) {
        this(repository, limits, new DemoEngine(reasoner), tools);
    }

    RunService(RunRepository repository, ExecutionLimits limits, TriageEngine engine, List<ReadOnlyTool> tools) {
        this(repository, limits, engine, tools, true,
            new ServiceRegistry(new ObservationSource("SYNTHETIC", "http://127.0.0.1:18082"), List.of()));
    }

    private RunService(RunRepository repository, ExecutionLimits limits, TriageEngine engine,
                       List<ReadOnlyTool> tools, boolean synthetic, ServiceRegistry registry) {
        this.repository = repository;
        this.limits = limits;
        this.engine = engine;
        this.tools = List.copyOf(tools);
        this.synthetic = synthetic;
        this.registry = registry;
    }

    private static ThreadPoolExecutor pool(String prefix, int workers, int queue) {
        return new ThreadPoolExecutor(workers, workers, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(queue),
            Thread.ofPlatform().daemon(true).name(prefix, 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    public Run submit(String question, ToolContext context) {
        return submit(question, context, null);
    }

    public Run submit(String question, ToolContext context, String expectedSelection) {
        ToolContext frozen = registry.freeze(context);
        long deadline = System.nanoTime() + limits.runTimeout().toNanos();
        TriageEngine selectedEngine = engine.snapshot();
        if (expectedSelection != null && !expectedSelection.equals(selectedEngine.selectionToken()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "运行模式或模型配置已变更，请刷新后重新提交。");
        Run run = new Run(UUID.randomUUID(), question, context.service(), context.windowMinutes(), context.scenario(),
            selectedEngine.mode(), synthetic, Status.QUEUED, context.endTime(), null, 0,
            List.of(new Event(1, Instant.now(), "RUN_QUEUED", null, "任务已创建。", List.of())), List.of(), null, null,
            selectedEngine.modelName() == null ? null : new ModelExecution(selectedEngine.modelName(), null, 0, null, selectedEngine.source()), frozen.serviceInfo());
        repository.insert(run);
        RunControl control = new RunControl(stateFrom(run));
        FutureTask<Void> task = new FutureTask<>(() -> { execute(run, frozen, deadline, selectedEngine, control); return null; }) {
            @Override protected void done() { active.remove(run.id(), control); }
        };
        synchronized (control.state) {
            control.coordinator = task;
            active.put(run.id(), control);
        }
        try {
            synchronized (control.state) { if (!control.cancelled) coordinators.execute(task); }
        }
        catch (RejectedExecutionException e) {
            synchronized (control.state) { if (!control.cancelled) fail(control.state, "RUN_QUEUE_FULL", "执行队列已满，请稍后再试。"); }
            active.remove(run.id(), control);
            throw new CapacityExceededException();
        }
        return run;
    }

    private MutableExecution stateFrom(Run run) {
        var state = new MutableExecution(run);
        state.events.addAll(run.events());
        return state;
    }

    public Run cancel(UUID id) {
        RunControl control = active.get(id);
        if (control == null) {
            Run stored = repository.find(id).orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.NOT_FOUND, "执行记录不存在。"));
            if (stored.status().terminal()) return stored;
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "当前执行状态尚未就绪，请刷新后重试。");
        }
        Run result;
        RunControl.Step step;
        synchronized (control.state) {
            MutableExecution state = control.state;
            if (state.status.terminal()) return state.snapshot();
            Status previous = state.status;
            control.cancelled = true;
            state.status = Status.CANCELLED; state.finishedAt = Instant.now();
            state.diagnosis = null; state.failure = null;
            state.event("RUN_CANCELLED", null, "本次排查已取消，保留已采集证据。", List.of());
            result = state.snapshot();
            try { repository.save(result); }
            catch (RuntimeException e) {
                state.events.remove(state.events.size() - 1); state.status = previous; state.finishedAt = null; control.cancelled = false;
                throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                    "取消状态未能保存，请检查本地存储后重试。");
            }
            step = control.step;
        }
        if (step != null) RunControl.stop(step.future(), step.executor());
        RunControl.stop(control.coordinator, coordinators);
        return result;
    }

    private void execute(Run run, ToolContext context, long deadline, TriageEngine selectedEngine, RunControl control) {
        MutableExecution state = control.state;
        ExecutionSession session = new ExecutionSession(run.question(), context, state, repository, limits, deadline, toolWorkers, modelWorkers, tools, control);
        try {
            synchronized (state) {
                session.checkDeadline(); state.status = Status.RUNNING;
                publish(state, "RUN_STARTED", "开始收集证据。");
            }
            TriageEngine.Decision decision = selectedEngine.investigate(session);
            synchronized (state) {
                session.checkDeadline();
                if (decision == null || (decision.status() != Status.SUCCEEDED && decision.status() != Status.INSUFFICIENT_EVIDENCE))
                    throw new RunFailure("INVALID_RESULT", "排查没有返回有效结果。");
                EvidenceValidator.validate(decision.diagnosis(), state.evidence);
                session.checkDeadline(); state.diagnosis = decision.diagnosis(); state.status = decision.status(); state.finishedAt = Instant.now();
                publish(state, "RUN_COMPLETED", state.status == Status.SUCCEEDED ? "排查完成。" : "证据不足，无法支持完整判断。");
            }
        } catch (RunFailure e) {
            synchronized (state) { if (!control.cancelled && !state.status.terminal()) fail(state, e.code(), e.getMessage()); }
        } catch (Exception e) {
            synchronized (state) { if (!control.cancelled && !state.status.terminal()) fail(state, "EXECUTION_ERROR", "执行失败；请检查本地配置和持久化存储。"); }
        }
    }

    private void publish(MutableExecution state, String type, String message) {
        state.event(type, null, message, List.of());
        repository.save(state.snapshot());
    }

    private void fail(MutableExecution state, String code, String message) {
        state.status = Status.FAILED;
        state.finishedAt = Instant.now();
        state.diagnosis = null;
        state.failure = new Failure(code, message);
        publish(state, "RUN_FAILED", message);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recoverInterruptedRuns() {
        for (Run run : repository.unfinished()) {
            var events = new ArrayList<>(run.events());
            events.add(new Event(events.size() + 1, Instant.now(), "RUN_FAILED", null, "服务重启，之前的执行已中断。", List.of()));
            repository.save(new Run(run.id(), run.question(), run.service(), run.windowMinutes(), run.scenario(), run.mode(), run.synthetic(),
                Status.FAILED, run.createdAt(), Instant.now(), run.toolCalls(), List.copyOf(events), run.evidence(), null,
                new Failure("SERVER_RESTARTED", "服务重启；保留已收集证据，请重新执行。"), run.modelExecution(), run.serviceInfo()));
        }
    }

    @PreDestroy public void close() {
        coordinators.shutdownNow();
        toolWorkers.shutdownNow();
        modelWorkers.shutdownNow();
    }

    public static class CapacityExceededException extends RuntimeException {}
}
