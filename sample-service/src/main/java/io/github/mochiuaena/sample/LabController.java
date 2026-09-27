package io.github.mochiuaena.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.Map;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/lab")
public class LabController {
    public record ScenarioRequest(String scenario) {}
    private final ObservationStore store;
    public LabController(ObservationStore store) { this.store = store; }

    @GetMapping("/scenario")
    public Map<String, Object> scenario() { return Map.of("scenario", store.scenario(), "synthetic", false); }

    @PostMapping("/scenario")
    public Map<String, Object> scenario(@RequestBody ScenarioRequest request) {
        try { store.scenario(ObservationStore.Scenario.valueOf(request.scenario())); }
        catch (RuntimeException e) { throw new ResponseStatusException(BAD_REQUEST, "scenario must be NORMAL or DOWNSTREAM_TIMEOUT"); }
        return scenario();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() {
        store.reset();
        return scenario();
    }

    @GetMapping("/observations")
    public ObservationStore.Snapshot observations(@RequestParam(defaultValue = "15") int windowMinutes,
                                                  @RequestParam(required = false) Instant endTime) {
        if (windowMinutes < 1 || windowMinutes > 60) throw new ResponseStatusException(BAD_REQUEST, "windowMinutes must be 1..60");
        return store.snapshot(windowMinutes, endTime == null ? Instant.now() : endTime);
    }
}
