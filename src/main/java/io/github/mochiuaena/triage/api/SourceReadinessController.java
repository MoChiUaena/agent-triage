package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.source.SourceReadinessService;
import org.springframework.web.bind.annotation.*;

@RestController
public class SourceReadinessController {
    private final SourceReadinessService readiness;
    public SourceReadinessController(SourceReadinessService readiness) { this.readiness = readiness; }
    @GetMapping("/api/services/{id}/source-check")
    public SourceReadinessService.Check check(@PathVariable String id, @RequestParam(required=false) Integer windowMinutes) {
        return readiness.check(id, windowMinutes);
    }
}
