package io.github.mochiuaena.inventory;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
@RequestMapping("/lab")
public class LabController {
    public record ScenarioRequest(InventoryState.Scenario scenario) {}
    private final InventoryState state;
    public LabController(InventoryState state) { this.state = state; }

    @GetMapping("/scenario")
    public Map<String, Object> scenario() { return Map.of("scenario", state.scenario(), "service", "inventory-service"); }

    @PostMapping("/scenario")
    public Map<String, Object> scenario(@RequestBody ScenarioRequest request) {
        if (request.scenario() == null) throw new ResponseStatusException(BAD_REQUEST, "scenario is required");
        state.scenario(request.scenario());
        return scenario();
    }

    @PostMapping("/reset")
    public Map<String, Object> reset() { state.reset(); return scenario(); }
}
