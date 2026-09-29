package io.github.mochiuaena.triage.tools;

import jakarta.annotation.PreDestroy;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.stereotype.Service;

/** Bounded readonly checks, independent of model selection and credentials. */
@Service
public class ObservationStatusService implements AutoCloseable {
    public record Check(ServiceRegistry.View service, String source, String state, Integer requestCount, Integer windowMinutes,
                        Instant checkedAt, Long responseMillis, String errorCode, String message) {}
    private final ServiceRegistry registry;
    private final ObservationSource source;
    private final LiveObservationClient live;
    private final ConcurrentMap<String, Check> cache = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CompletableFuture<Check>> pending = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(4, 4, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(32),
        Thread.ofPlatform().daemon(true).name("triage-service-check-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    public ObservationStatusService(ServiceRegistry registry, ObservationSource source, LiveObservationClient live) {
        this.registry = registry; this.source = source; this.live = live;
    }
    public List<Check> checks() {
        var views = registry.views(); var futures = new ArrayList<CompletableFuture<Check>>();
        for (var view : views) {
            Check saved = cache.get(view.id());
            if (saved != null && saved.checkedAt().plusSeconds(5).isAfter(Instant.now())) { futures.add(CompletableFuture.completedFuture(saved)); continue; }
            try {
                var future = pending.computeIfAbsent(view.id(), key -> CompletableFuture.supplyAsync(() -> {
                    Check value = probe(view); cache.put(view.id(), value); return value;
                }, workers));
                future.whenComplete((result, error) -> pending.remove(view.id(), future)); futures.add(future);
            } catch (RejectedExecutionException e) {
                futures.add(CompletableFuture.completedFuture(failed(view, "OBSERVATION_CAPACITY", "接口检查暂时繁忙，请稍后刷新。", null)));
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var result = new ArrayList<Check>();
        for (int i = 0; i < futures.size(); i++) {
            try { result.add(futures.get(i).get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS)); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Service checks interrupted"); }
            catch (ExecutionException | TimeoutException e) { result.add(failed(views.get(i), "OBSERVATION_CHECK_TIMEOUT", "接口检查尚未完成，请稍后刷新。", null)); }
        }
        return List.copyOf(result);
    }
    private Check probe(ServiceRegistry.View view) {
        if (source.synthetic()) return new Check(view, source.kind().name(), "SYNTHETIC", null, null, Instant.now(), null, null, "使用固定演示观测。");
        long start = System.nanoTime();
        try {
            var target = registry.require(view.id());
            var scenario = live.scenario(target);
            int minutes = Math.min(15, view.maxWindowMinutes());
            var snapshot = live.snapshot(new ToolContext(view.id(), minutes, scenario, Instant.now(), target));
            return new Check(view, source.kind().name(), snapshot.requestCount() == 0 ? "EMPTY" : "AVAILABLE", snapshot.requestCount(),
                minutes, Instant.now(), elapsed(start), null, snapshot.requestCount() == 0 ? "窗口内没有请求，请先访问业务接口。" : "已读取本机服务的实际观测。");
        } catch (ObservationFailure e) { return failed(view, e.code(), e.getMessage(), elapsed(start)); }
        catch (RuntimeException e) { return failed(view, "OBSERVATION_CHECK_FAILED", "观测检查未完成，请确认服务配置后刷新。", elapsed(start)); }
    }
    private long elapsed(long start) { return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start); }
    private Check failed(ServiceRegistry.View view, String code, String message, Long elapsed) {
        return new Check(view, source.kind().name(), "UNAVAILABLE", null, Math.min(15, view.maxWindowMinutes()), Instant.now(), elapsed, code, message);
    }
    @Override @PreDestroy public void close() { workers.shutdownNow(); }
}
