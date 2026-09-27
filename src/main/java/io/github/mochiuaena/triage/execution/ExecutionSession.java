package io.github.mochiuaena.triage.execution;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.store.RunRepository;
import io.github.mochiuaena.triage.tools.*;
import java.util.*;
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
    private final Map<String, ReadOnlyTool> tools = new LinkedHashMap<>();
    private final Set<String> requests = new HashSet<>();

    ExecutionSession(String question, ToolContext context, MutableExecution state, RunRepository repository,
                     ExecutionLimits limits, long deadline, ExecutorService workers, List<ReadOnlyTool> tools) {
        this.question = question;
        this.context = context;
        this.state = state;
        this.repository = repository;
        this.limits = limits;
        this.deadline = deadline;
        this.workers = workers;
        for (ReadOnlyTool tool : tools) {
            if (this.tools.putIfAbsent(tool.name(), tool) != null) throw new IllegalArgumentException("Duplicate tool registration");
        }
    }

    public String question() { return question; }
    public ToolContext context() { return context; }
    public List<String> toolNames() { return List.copyOf(tools.keySet()); }
    public List<Evidence> evidence() { return List.copyOf(state.evidence); }

    public void checkDeadline() {
        if (Thread.currentThread().isInterrupted()) throw new RunFailure("RUN_INTERRUPTED", "执行已中断。");
        if (System.nanoTime() >= deadline) throw new RunFailure("RUN_TIMEOUT", "已达到整体执行时长上限。");
    }

    public List<Evidence> callTool(String name, String query) {
        checkDeadline();
        ReadOnlyTool tool = tools.get(name);
        if (tool == null) throw new RunFailure("TOOL_NOT_ALLOWED", "请求了未注册的工具。");
        if (state.toolCalls >= limits.maxToolCalls()) throw new RunFailure("TOOL_CALL_LIMIT", "已达到工具调用次数上限。");
        String normalized = query == null ? "" : query.strip();
        if (!requests.add(name + "\0" + normalized.toLowerCase(Locale.ROOT)))
            throw new RunFailure("DUPLICATE_TOOL_CALL", "同一工具收到重复参数，已停止执行。");
        state.toolCalls++;
        publish("TOOL_STARTED", name, "工具调用开始。", List.of());
        Future<List<Evidence>> future;
        try { future = workers.submit(() -> tool.execute(context, normalized)); }
        catch (RejectedExecutionException e) { throw new RunFailure("TOOL_CAPACITY", "工具工作队列已满。"); }
        long remaining = deadline - System.nanoTime();
        boolean overallFirst = remaining <= limits.toolTimeout().toNanos();
        try {
            List<Evidence> result = future.get(Math.max(1, Math.min(remaining, limits.toolTimeout().toNanos())), TimeUnit.NANOSECONDS);
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
        } catch (TimeoutException e) {
            future.cancel(true);
            publish("TOOL_FAILED", name, "工具等待超时，已请求取消。", List.of());
            throw new RunFailure(overallFirst ? "RUN_TIMEOUT" : "TOOL_TIMEOUT", "执行超过时间预算。");
        } catch (ExecutionException e) {
            publish("TOOL_FAILED", name, "工具执行失败。", List.of());
            throw new RunFailure("TOOL_ERROR", "工具执行失败；没有生成排障结论。");
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new RunFailure("RUN_INTERRUPTED", "执行已中断。");
        }
    }

    private void publish(String type, String tool, String message, List<String> ids) {
        state.event(type, tool, message, ids);
        repository.save(state.snapshot());
    }
}
