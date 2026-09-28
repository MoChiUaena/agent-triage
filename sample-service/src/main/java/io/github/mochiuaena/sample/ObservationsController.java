package io.github.mochiuaena.sample;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.List;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

/** V1 adapter example: reads collected observations without changing application state. */
@RestController
public class ObservationsController {
    public record Observations(int schemaVersion, String service, String downstreamService, Instant windowStart, Instant windowEnd,
                               int requestCount, int timeoutCount, long recordedRequestCount, double requestP95Ms,
                               double downstreamP95Ms, double downstreamTimeoutRate, Double baselineRequestP95Ms,
                               List<ObservationStore.ErrorEntry> errors, boolean synthetic) {}
    private final ObservationStore store;
    public ObservationsController(ObservationStore store) { this.store = store; }

    @GetMapping("/triage/observations")
    public Observations observations(@RequestParam int windowMinutes, @RequestParam Instant endTime) {
        if (windowMinutes < 1 || windowMinutes > 60) throw new ResponseStatusException(BAD_REQUEST, "windowMinutes must be 1..60");
        var value = store.snapshot(windowMinutes, endTime);
        return new Observations(1, store.serviceId(), store.downstreamService(), value.windowStart(), value.windowEnd(),
            value.requestCount(), value.timeoutCount(), value.recordedRequestCount(), value.orderP95Ms(), value.downstreamP95Ms(),
            value.downstreamTimeoutRate(), value.baselineOrderP95Ms(), value.errors(), false);
    }
}
