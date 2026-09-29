package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.store.*;
import java.time.Instant;
import org.springframework.web.bind.annotation.*;

@RestController
public class StatisticsController {
    private final HistoryRepository history;
    public StatisticsController(HistoryRepository history) { this.history = history; }
    @GetMapping("/api/statistics")
    public HistoryRepository.Statistics statistics(@RequestParam(defaultValue = "7") int days,
                                                   @RequestParam(required = false) String service,
                                                   @RequestParam(required = false) String endpointId) {
        if (days < 1 || days > 90) throw new IllegalArgumentException("Statistics days must be 1..90");
        Instant until = Instant.now();
        return history.statistics(new HistoryFilter(null, service, null, null, until.minusSeconds(days * 86400L), until, endpointId));
    }
}
