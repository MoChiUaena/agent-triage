package io.github.mochiuaena.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;

@RestController
@RequestMapping("/lab")
@ConditionalOnProperty(name = "sample.lab-enabled", havingValue = "true", matchIfMissing = true)
public class LabController {
    public record ScenarioRequest(String scenario) {}
    private final ObservationStore store;
    private final InventoryClient inventory;
    private final ErrorJournal errors;
    public LabController(ObservationStore store, InventoryClient inventory, ErrorJournal errors) {
        this.store = store; this.inventory = inventory; this.errors = errors;
    }

    @GetMapping("/scenario")
    public Map<String, Object> scenario() {
        try { return Map.of("scenario", inventory.scenario(), "synthetic", false); }
        catch (IOException | InterruptedException e) { throw unavailable(e); }
    }

    @PostMapping("/scenario")
    public Map<String, Object> scenario(@RequestBody ScenarioRequest request) {
        ObservationStore.Scenario value;
        try { value = ObservationStore.Scenario.valueOf(request.scenario()); }
        catch (RuntimeException e) { throw new ResponseStatusException(BAD_REQUEST, "scenario must be NORMAL or DOWNSTREAM_TIMEOUT"); }
        try { inventory.scenario(value); }
        catch (IOException | InterruptedException e) { throw unavailable(e); }
        store.scenario(value);
        return scenario();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        try { inventory.reset(); }
        catch (IOException | InterruptedException e) { throw unavailable(e); }
        errors.reset();
        store.reset();
        return scenario();
    }

    @GetMapping("/observations")
    public ObservationStore.Snapshot observations(@RequestParam(defaultValue = "15") int windowMinutes,
                                                  @RequestParam(required = false) Instant endTime) {
        if (windowMinutes < 1 || windowMinutes > 60) throw new ResponseStatusException(BAD_REQUEST, "windowMinutes must be 1..60");
        return store.snapshot(windowMinutes, endTime == null ? Instant.now() : endTime);
    }

    @GetMapping("/errors")
    public List<ObservationStore.ErrorEntry> errors(@RequestParam(defaultValue = "15") int windowMinutes,
                                                    @RequestParam(required = false) Instant endTime) {
        if (windowMinutes < 1 || windowMinutes > 60) throw new ResponseStatusException(BAD_REQUEST, "windowMinutes must be 1..60");
        Instant end = endTime == null ? Instant.now() : endTime;
        return errors.recent(end.minusSeconds(windowMinutes * 60L), end, 3);
    }

    private ResponseStatusException unavailable(Exception e) {
        if (e instanceof InterruptedException) Thread.currentThread().interrupt();
        return new ResponseStatusException(BAD_GATEWAY, "库存样例服务不可用，请先启动 inventory-service。");
    }
}
