package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.domain.TriageModel.Status;
import io.github.mochiuaena.triage.store.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@RestController
@RequestMapping("/api/history")
public class HistoryController {
    public record DeleteConfirmation(@NotNull UUID confirmId) {}
    private final HistoryRepository history;
    private final RunRepository runs;
    public HistoryController(HistoryRepository history, RunRepository runs) { this.history = history; this.runs = runs; }

    @GetMapping
    public HistoryRepository.Page list(@RequestParam(required = false) String q, @RequestParam(required = false) String service,
        @RequestParam(required = false) Status status, @RequestParam(required = false) String mode,
        @RequestParam(required = false) Instant from, @RequestParam(required = false) Instant until,
        @RequestParam(defaultValue = "20") int pageSize, @RequestParam(required = false) String cursor) {
        return history.page(new HistoryFilter(q, service, status, mode, from, until), pageSize, cursor);
    }
    @GetMapping("/services") public List<HistoryRepository.Service> services() { return history.services(); }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, @Valid @RequestBody DeleteConfirmation confirmation) {
        if (!id.equals(confirmation.confirmId())) throw new ResponseStatusException(BAD_REQUEST, "删除确认与所选记录不一致，请重新选择。");
        if (history.deleteTerminal(id)) return ResponseEntity.noContent().build();
        if (runs.find(id).isEmpty()) throw new ResponseStatusException(NOT_FOUND, "执行记录不存在或已删除。");
        throw new ResponseStatusException(CONFLICT, "排队或运行中的记录不能删除，请等待结束或先取消执行。");
    }
}
