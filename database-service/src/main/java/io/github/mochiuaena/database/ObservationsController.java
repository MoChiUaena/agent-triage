package io.github.mochiuaena.database;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@RestController
public class ObservationsController {
    private final ObservationStore store;
    public ObservationsController(ObservationStore store) { this.store = store; }
    @GetMapping("/triage/observations")
    public ObservationStore.Snapshot read(@RequestParam int windowMinutes, @RequestParam Instant endTime) {
        if (windowMinutes < 1 || windowMinutes > 60) throw new ResponseStatusException(BAD_REQUEST, "Window must be 1..60 minutes");
        return store.snapshot(windowMinutes, endTime);
    }
}
