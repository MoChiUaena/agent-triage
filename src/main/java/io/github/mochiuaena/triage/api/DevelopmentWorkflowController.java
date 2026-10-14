package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.WorkflowModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/development")
public class DevelopmentWorkflowController {
    public record PlanInput(@Min(1) @Max(Integer.MAX_VALUE) long prNumber, @Min(1) @Max(Integer.MAX_VALUE) Long issueNumber, UUID issueReportId, UUID ciReportId) {}
    public record Input(@NotNull UUID planId) {}
    private final WorkflowPlanner planner; private final WorkflowService workflows;
    public DevelopmentWorkflowController(WorkflowPlanner planner, WorkflowService workflows) { this.planner=planner; this.workflows=workflows; }
    @PostMapping("/repositories/{repository}/workflows/plans") public ResponseEntity<Plan> plan(@PathVariable UUID repository,@Valid @RequestBody PlanInput input) { return ResponseEntity.status(201).body(planner.create(repository,input.prNumber(),input.issueNumber(),input.issueReportId(),input.ciReportId())); }
    @PostMapping("/repositories/{repository}/workflows") public ResponseEntity<Snapshot> create(@PathVariable UUID repository,@Valid @RequestBody Input input) { Snapshot result=workflows.create(repository,input.planId()); return ResponseEntity.accepted().location(URI.create("/api/development/workflows/"+result.id())).body(result); }
    @GetMapping("/repositories/{repository}/workflows") public List<Snapshot> list(@PathVariable UUID repository) { return workflows.list(repository); }
    @GetMapping("/workflows/{id}") public Snapshot get(@PathVariable UUID id) { return workflows.get(id); }
    @PostMapping("/workflows/{id}/cancel") public Snapshot cancel(@PathVariable UUID id) { return workflows.cancel(id); }
}
