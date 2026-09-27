package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.Run;
import io.github.mochiuaena.triage.execution.RunService.CapacityExceededException;
import io.github.mochiuaena.triage.execution.ExecutionLimits;
import io.github.mochiuaena.triage.store.RunRepository;
import jakarta.annotation.PreDestroy;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.springframework.http.HttpStatus.*;

/** Poll persisted events to eliminate the subscribe/complete race, including reconnect replay. */
@RestController
public class RunEventController {
    private final RunRepository repository;
    private final long connectionTimeout;
    private final ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(2,
        Thread.ofPlatform().daemon(true).name("triage-sse-", 0).factory());
    private final Semaphore connections = new Semaphore(64);

    public RunEventController(RunRepository repository, ExecutionLimits limits) {
        this.repository = repository;
        this.connectionTimeout = limits.runTimeout().toMillis() + 5_000;
        scheduler.setRemoveOnCancelPolicy(true);
    }

    @GetMapping(value = "/api/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@PathVariable UUID id,
                             @RequestHeader(value = "Last-Event-ID", defaultValue = "0") int lastEventId) {
        Run initial = repository.find(id).orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "执行记录不存在。"));
        if (lastEventId < 0 || lastEventId > initial.events().size()) throw new ResponseStatusException(BAD_REQUEST, "事件序号无效。");
        if (!connections.tryAcquire()) throw new CapacityExceededException();
        SseEmitter emitter = new SseEmitter(connectionTimeout);
        AtomicInteger cursor = new AtomicInteger(lastEventId);
        AtomicBoolean closed = new AtomicBoolean();
        AtomicReference<ScheduledFuture<?>> scheduled = new AtomicReference<>();
        Runnable cleanup = () -> {
            if (closed.compareAndSet(false, true)) connections.release();
            ScheduledFuture<?> task = scheduled.get();
            if (task != null) task.cancel(false);
        };
        emitter.onCompletion(cleanup);
        emitter.onTimeout(() -> { cleanup.run(); emitter.complete(); });
        emitter.onError(error -> cleanup.run());
        ScheduledFuture<?> task = scheduler.scheduleWithFixedDelay(() -> {
            if (closed.get()) return;
            try {
                Run run = repository.find(id).orElseThrow();
                for (var event : run.events()) {
                    if (event.sequence() > cursor.get()) {
                        emitter.send(SseEmitter.event().id(Integer.toString(event.sequence())).name("progress").data(event));
                        cursor.set(event.sequence());
                    }
                }
                if (run.status().terminal()) {
                    emitter.send(SseEmitter.event().name("complete").data(run));
                    cleanup.run();
                    emitter.complete();
                }
            } catch (IOException | RuntimeException e) {
                cleanup.run();
                emitter.completeWithError(e);
            }
        }, 0, 200, TimeUnit.MILLISECONDS);
        scheduled.set(task);
        if (closed.get()) task.cancel(false);
        return emitter;
    }

    @PreDestroy public void close() { scheduler.shutdownNow(); }
}
