package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.tools.ObservationStatusService;
import java.util.List;
import org.springframework.web.bind.annotation.*;

@RestController
public class ServiceStatusController {
    private final ObservationStatusService status;
    public ServiceStatusController(ObservationStatusService status) { this.status = status; }
    @GetMapping("/api/services/status") public List<ObservationStatusService.Check> checks() { return status.checks(); }
}
