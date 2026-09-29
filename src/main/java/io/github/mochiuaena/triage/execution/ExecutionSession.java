package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.*;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;

/** Per-run gateway. Engines cannot write state or bypass tool budgets. */
public final class ExecutionSession {
    private final String question;
    private final ToolContext context;
    private final MutableExecution state;
    private final RunRepository repository;
    private final ExecutionLimits limits;
    private final long deadline;
    private final ExecutorService workers;
    private final ExecutorService modelWorkers;
    private final RunControl control;
    private TokenUsage totalUsage = new TokenUsage(0, 0, 0);
    private boolean usageComplete = true;
    private int completedCalls;
    private int usageReportedCalls;
    private final Map<String, ReadOnlyTool> tools = new LinkedHashMap<>();
    private final Set<String> requests = new HashSet<>();

    ExecutionSession(String question, ToolContext context, MutableExecution state, RunRepository repository,
                     ExecutionLimits limits, long deadline, ExecutorService workers, ExecutorService modelWorkers, List<ReadOnlyTool> tools) {
        this(question, context, state, repository, limits, deadline, workers, modelWorkers, tools, new RunControl(state));
    }

    ExecutionSession(String question, ToolContext context, MutableExecution state, RunRepository repository,
                     ExecutionLimits limits, long deadline, ExecutorService workers, ExecutorService modelWorkers, List<ReadOnlyTool> tools, RunControl control) {
        this.question = question;
        this.context = context;
        this.state = state;
        this.repository = repository;
        this.limits = limits;
        this.deadline = deadline;
        this.workers = workers;
        this.modelWorkers = modelWorkers;
        this.control = control;
        for (ReadOnlyTool tool : tools) {
            if (this.tools.putIfAbsent(tool.name(), tool) != null) throw new IllegalArgumentException("Duplicate tool registration");
        }
    }

    public String question() { return question; }
    public boolean synthetic() { return state.synthetic(); }
    public ToolContext context() { return context; }
    public List<String> toolNames() { return List.copyOf(tools.keySet()); }
    public List<Evidence> evidence() { synchronized (state) { return List.copyOf(state.evidence); } }
    public int remainingToolCalls() { synchronized (state) { return Math.max(0, limits.maxToolCalls() - state.toolCalls); } }
    public int remainingModelRounds(int maxRounds) { synchronized (state) { return state.modelExecution == null ? 0 : Math.max(0, maxRounds - state.modelExecution.calls()); } }

    public void recordNoDataGate() {
        publish("EVIDENCE_GATE", null, "窗口没有服务请求，应用返回证据不足，跳过最终模型生成。", List.of());
    }

    public void recordScopeGate() {
        publish("SCOPE_GATE", null, "问题超出所选服务的排障范围，应用未请求模型。", List.of());
    }

    public void recordRuleGapGate() {
        publish("EVIDENCE_GATE", null, "当前观测缺少对应排障规则，应用返回证据不足。", List.of());
    }

    public void recordStructuredConclusion(String assessment, List<String> requestedNextChecks, List<String> nextChecks) {
        synchronized (state) {
            control.checkCancelled();
            ModelExecution previous = state.modelExecution;
            state.modelExecution = new ModelExecution(previous.configuredModel(), previous.responseModel(), previous.calls(),
                previous.usage(), previous.source(), assessment, List.copyOf(nextChecks), List.copyOf(requestedNextChecks),
                previous.knownUsage(), previous.completedCalls(), previous.usageReportedCalls());
            publish("CHECKS_PRIORITIZED", null, "应用按当前证据排列模型选中的检查项，展示前两项；原始选择已保留。", List.of());
            publish("CONCLUSION_RENDERED", null, "模型选择判断类型、证据和检查项，关键结论由应用按证据生成。", List.of());
        }
    }

    public void recordArgumentRejection(String tool, String reason, String message) {
        checkDeadline();
        publish("TOOL_ARGUMENTS_REJECTED", tool, reason + ": " + message + "整批工具尚未执行。", List.of());
    }

    public void recordEvidenceFeedback() {
        checkDeadline();
        publish("EVIDENCE_FEEDBACK", null, "模型提前回答时缺少必需证据或引用，应用请求补齐一次；未保存该回答。", List.of());
    }

    public void checkDeadline() {
        control.checkCancelled();
        if (Thread.currentThread().isInterrupted()) throw new RunFailure("RUN_INTERRUPTED", "执行已中断。");
        if (System.nanoTime() >= deadline) throw new RunFailure("RUN_TIMEOUT", "已达到整体执行时长上限。");
    }

    public List<Evidence> callTool(String name, String query) {
        Future<List<Evidence>> future;
        synchronized (state) {
            checkDeadline();
            ReadOnlyTool tool = tools.get(name);
            if (tool == null) throw new RunFailure("TOOL_NOT_ALLOWED", "请求了未注册的工具。");
            if (state.toolCalls >= limits.maxToolCalls()) throw new RunFailure("TOOL_CALL_LIMIT", "已达到工具调用次数上限。");
            String normalized = query == null ? "" : query.strip();
            if (!requests.add(name + "\0" + normalized.toLowerCase(Locale.ROOT)))
                throw new RunFailure("DUPLICATE_TOOL_CALL", "同一工具收到重复参数，已停止执行。");
            state.toolCalls++;
            publish("TOOL_STARTED", name, "工具调用开始。", List.of());
            try { future = workers.submit(() -> { control.checkCancelled(); return tool.execute(context, normalized); }); control.attach(future, workers); }
            catch (RejectedExecutionException e) { throw new RunFailure("TOOL_CAPACITY", "工具工作队列已满。"); }
        }
        long remaining = deadline - System.nanoTime();
        boolean overallFirst = remaining <= limits.toolTimeout().toNanos();
        try {
            List<Evidence> result = future.get(Math.max(1, Math.min(remaining, limits.toolTimeout().toNanos())), TimeUnit.NANOSECONDS);
            synchronized (state) {
                checkDeadline();
                if (result == null || result.size() > 5) throw new RunFailure("TOOL_OUTPUT_LIMIT", "工具返回超过允许的证据数量。");
                for (Evidence item : result) {
                    if (item == null || item.id() == null || item.id().isBlank() || item.summary() == null || item.data() == null)
                        throw new RunFailure("INVALID_TOOL_OUTPUT", "工具返回了无效证据。");
                    Evidence previous = state.evidence.stream().filter(e -> e.id().equals(item.id())).findFirst().orElse(null);
                    if (previous != null && !previous.equals(item))
                        throw new RunFailure("INVALID_TOOL_OUTPUT", "同一个证据 ID 对应了不同内容。");
                    if (previous == null) state.evidence.add(item);
                }
                publish("TOOL_COMPLETED", name, "工具返回 " + result.size() + " 条证据。", result.stream().map(Evidence::id).toList());
                return List.copyOf(result);
            }
        } catch (TimeoutException e) {
            RunControl.stop(future, workers);
            publish("TOOL_FAILED", name, "工具等待超时，已请求取消。", List.of());
            throw new RunFailure(overallFirst ? "RUN_TIMEOUT" : "TOOL_TIMEOUT", "执行超过时间预算。");
        } catch (ExecutionException e) {
            if (e.getCause() instanceof io.github.mochiuaena.triage.tools.ObservationFailure failure) {
                publish("TOOL_FAILED", name, failure.getMessage(), List.of());
                throw new RunFailure(failure.code(), failure.getMessage());
            }
            publish("TOOL_FAILED", name, "工具执行失败。", List.of());
            throw new RunFailure("TOOL_ERROR", "工具执行失败；没有生成排障结论。");
        } catch (InterruptedException e) {
            RunControl.stop(future, workers);
            Thread.currentThread().interrupt();
            throw new RunFailure("RUN_INTERRUPTED", "执行已中断。");
        } finally { control.detach(future); }
    }

    private void publish(String type, String tool, String message, List<String> ids) {
        synchronized (state) {
            control.checkCancelled();
            state.event(type, tool, message, ids);
            repository.save(state.snapshot());
        }
    }

    public <T> T callModel(Callable<T> action, Duration timeout, int maxRounds) {
        return callModel(action, timeout, maxRounds, reply -> {});
    }

    public <T> T callModel(Callable<T> action, Duration timeout, int maxRounds, java.util.function.Consumer<T> onReply) {
        Future<T> future;
        synchronized (state) {
            checkDeadline();
            ModelExecution previous = state.modelExecution;
            if (previous == null) throw new RunFailure("MODEL_NOT_CONFIGURED", "没有配置模型。");
            if (previous.calls() >= maxRounds) throw new RunFailure("MODEL_ROUND_LIMIT", "模型调用已达到轮次上限。");
            state.modelExecution = new ModelExecution(previous.configuredModel(), previous.responseModel(), previous.calls() + 1, null, previous.source(),
                null, null, null, previous.knownUsage(), completedCalls, usageReportedCalls);
            publish("MODEL_STARTED", null, "请求模型，第 " + state.modelExecution.calls() + " 轮。", List.of());
            try { future = modelWorkers.submit(() -> { control.checkCancelled(); return action.call(); }); control.attach(future, modelWorkers); }
            catch (RejectedExecutionException e) { throw new RunFailure("MODEL_CAPACITY", "模型工作队列已满。"); }
        }
        long remaining = deadline - System.nanoTime();
        boolean overallFirst = remaining <= timeout.toNanos();
        try {
            T result = future.get(Math.max(1, Math.min(remaining, timeout.toNanos())), TimeUnit.NANOSECONDS);
            synchronized (state) {
                checkDeadline();
                completedCalls++;
                ModelExecution previous = state.modelExecution;
                state.modelExecution = new ModelExecution(previous.configuredModel(), previous.responseModel(), previous.calls(), null, previous.source(),
                    null, null, null, previous.knownUsage(), completedCalls, usageReportedCalls);
                onReply.accept(result);
                publish("MODEL_COMPLETED", null, "模型已返回。", List.of());
                return result;
            }
        } catch (TimeoutException e) {
            RunControl.stop(future, modelWorkers);
            publish("MODEL_FAILED", null, "等待模型返回超时。", List.of());
            throw new RunFailure(overallFirst ? "RUN_TIMEOUT" : "MODEL_TIMEOUT", "模型调用超过时间限制。");
        } catch (ExecutionException e) {
            publish("MODEL_FAILED", null, "模型请求失败。", List.of());
            if (e.getCause() instanceof RunFailure failure) throw failure;
            Throwable cause = e.getCause();
            for (int depth = 0; cause != null && depth < 8; depth++, cause = cause.getCause()) {
                if (cause instanceof java.net.http.HttpTimeoutException || cause instanceof java.net.SocketTimeoutException)
                    throw new RunFailure("MODEL_TIMEOUT", "模型调用超过时间限制。");
            }
            throw new RunFailure("MODEL_ERROR", "模型请求失败，请检查服务地址、凭据和模型配置。");
        } catch (InterruptedException e) {
            RunControl.stop(future, modelWorkers);
            Thread.currentThread().interrupt();
            throw new RunFailure("RUN_INTERRUPTED", "执行已中断。");
        } finally { control.detach(future); }
    }

    public void recordModelUsage(String responseModel, TokenUsage usage) {
        synchronized (state) {
            control.checkCancelled();
            if (usage != null && (usage.inputTokens() < 0 || usage.outputTokens() < 0 || usage.totalTokens() < 0)) usage = null;
            if (usage == null) usageComplete = false;
            else {
                try {
                    totalUsage = new TokenUsage(Math.addExact(totalUsage.inputTokens(), usage.inputTokens()),
                        Math.addExact(totalUsage.outputTokens(), usage.outputTokens()), Math.addExact(totalUsage.totalTokens(), usage.totalTokens()));
                    usageReportedCalls++;
                } catch (ArithmeticException e) { usageComplete = false; }
            }
            ModelExecution previous = state.modelExecution;
            String reported = responseModel != null && responseModel.matches("[A-Za-z0-9._:/-]{1,120}") ? responseModel : null;
            state.modelExecution = new ModelExecution(previous.configuredModel(), reported, previous.calls(),
                usageComplete && usageReportedCalls == previous.calls() && completedCalls == previous.calls() ? totalUsage : null, previous.source(),
                previous.assessment(), previous.nextChecks(), previous.requestedNextChecks(), usageReportedCalls == 0 ? null : totalUsage, completedCalls, usageReportedCalls);
            repository.save(state.snapshot());
        }
    }
}
