package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.Status;
import io.github.mochiuaena.triage.store.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/history")
public class HistoryController {
    public record DeleteConfirmation(@NotNull UUID confirmId) {}
    public record RetentionConfirmation(@NotNull Instant cutoff, @Min(1) long expectedCount, String confirmation) {}
    public record RetentionResult(Instant cutoff, long deletedCount) {}
    private final HistoryRepository history;
    private final RunRepository runs;
    public HistoryController(HistoryRepository history, RunRepository runs) { this.history = history; this.runs = runs; }

    @GetMapping
    public HistoryRepository.Page list(@RequestParam(required = false) String q, @RequestParam(required = false) String service,
        @RequestParam(required = false) Status status, @RequestParam(required = false) String mode,
        @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant until,
        @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor,
        @RequestParam(required = false) String endpointId) {
        return history.page(new HistoryFilter(q, service, status, mode, from, until, endpointId), pageSize, cursor);
    }
    @GetMapping("/services") public List<HistoryRepository.Service> services() { return history.services(); }
    @GetMapping("/retention") public HistoryRepository.RetentionPreview retention(@RequestParam(defaultValue = "90") int days) {
        if (days < 30 || days > 3650) throw new ResponseStatusException(BAD_REQUEST, "留存天数须为 30–3650。");
        return history.retentionPreview(Instant.now().minus(days, ChronoUnit.DAYS));
    }
    @PostMapping("/retention") public RetentionResult deleteOld(@Valid @RequestBody RetentionConfirmation confirmation) {
        if (!"删除旧记录".equals(confirmation.confirmation()) || confirmation.cutoff().isAfter(Instant.now().minus(30, ChronoUnit.DAYS)))
            throw new ResponseStatusException(BAD_REQUEST, "清理确认或截止时间无效，请重新预览。");
        try { return new RetentionResult(confirmation.cutoff(), history.deleteOldTerminal(confirmation.cutoff(), confirmation.expectedCount())); }
        catch (HistoryRepository.RetentionChanged ignored) { throw new ResponseStatusException(CONFLICT, "记录数量已变化，请重新预览后确认。"); }
    }
    @GetMapping("/endpoints") public List<io.github.mochiuaena.triage.domain.TriageModel.RequestEndpoint> endpoints(@RequestParam String service) {
        return history.endpoints(service);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @Valid @RequestBody DeleteConfirmation confirmation) {
        if (!id.equals(confirmation.confirmId())) throw new ResponseStatusException(BAD_REQUEST, "删除确认与所选记录不一致，请重新选择。");
        if (history.deleteTerminal(id)) return ResponseEntity.noContent().build();
        if (runs.find(id).isEmpty()) throw new ResponseStatusException(NOT_FOUND, "执行记录不存在或已删除。");
        throw new ResponseStatusException(CONFLICT, "排队或运行中的记录不能删除，请等待结束或先取消执行。");
    }
}
