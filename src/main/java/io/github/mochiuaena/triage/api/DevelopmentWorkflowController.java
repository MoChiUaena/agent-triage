package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.WorkflowModels.*;
import io.github.mochiuaena.triage.development.WorkflowTemplateService.TaskInput;
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
    public record TemplateTaskInput(@NotBlank String id, List<String> requires, List<String> after) {}
    public record TemplateInput(@NotBlank String name, @NotNull List<TemplateTaskInput> tasks) {}
    public record TemplateUpdateInput(@NotNull Integer revision, String name, List<TemplateTaskInput> tasks) {}
    public record TemplatePlanInput(@Min(1) @Max(Integer.MAX_VALUE) long prNumber, @Min(1) @Max(Integer.MAX_VALUE) Long issueNumber, UUID issueReportId, UUID ciReportId) {}
    private final WorkflowPlanner planner; private final WorkflowService workflows; private final WorkflowTemplateService templates;
    public DevelopmentWorkflowController(WorkflowPlanner planner, WorkflowService workflows, WorkflowTemplateService templates) {
        this.planner=planner; this.workflows=workflows; this.templates=templates;
    }
    @PostMapping("/repositories/{repository}/workflows/plans") public ResponseEntity<Plan> plan(@PathVariable UUID repository,@Valid @RequestBody PlanInput input) { return ResponseEntity.status(201).body(planner.create(repository,input.prNumber(),input.issueNumber(),input.issueReportId(),input.ciReportId())); }
    @PostMapping("/repositories/{repository}/workflows") public ResponseEntity<Snapshot> create(@PathVariable UUID repository,@Valid @RequestBody Input input) { Snapshot result=workflows.create(repository,input.planId()); return ResponseEntity.accepted().location(URI.create("/api/development/workflows/"+result.id())).body(result); }
    @GetMapping("/repositories/{repository}/workflows") public List<Snapshot> list(@PathVariable UUID repository) { return workflows.list(repository); }
    @GetMapping("/workflows/{id}") public Snapshot get(@PathVariable UUID id) { return workflows.get(id); }
    @PostMapping("/workflows/{id}/cancel") public Snapshot cancel(@PathVariable UUID id) { return workflows.cancel(id); }

    /** Configurable workflow templates (F1): form-driven task lists over the fixed read-only catalog. */
    @GetMapping("/workflow-task-catalog") public List<Map<String, Object>> catalog() { return templates.catalog(); }
    @PostMapping("/repositories/{repository}/workflow-templates")
    public ResponseEntity<Template> createTemplate(@PathVariable UUID repository, @Valid @RequestBody TemplateInput input) {
        var template = templates.create(repository, input.name(), tasks(input.tasks()));
        return ResponseEntity.created(URI.create("/api/development/workflow-templates/" + template.id())).body(template);
    }
    @GetMapping("/repositories/{repository}/workflow-templates") public List<Template> listTemplates(@PathVariable UUID repository) { return templates.list(repository); }
    @GetMapping("/workflow-templates/{id}") public Template template(@PathVariable UUID id) { return templates.get(id); }
    @PutMapping("/workflow-templates/{id}") public Template updateTemplate(@PathVariable UUID id, @Valid @RequestBody TemplateUpdateInput input) {
        return templates.update(id, input.revision(), input.name(), tasks(input.tasks()));
    }
    @DeleteMapping("/workflow-templates/{id}") public ResponseEntity<Void> deleteTemplate(@PathVariable UUID id) {
        templates.delete(id); return ResponseEntity.noContent().build();
    }
    /** Freezes the template's task list into a plan; later template edits never affect this plan. */
    @PostMapping("/repositories/{repository}/workflow-templates/{templateId}/plans")
    public ResponseEntity<Plan> templatePlan(@PathVariable UUID repository, @PathVariable UUID templateId, @Valid @RequestBody TemplatePlanInput input) {
        var template = templates.requireValid(repository, templateId);
        return ResponseEntity.status(201).body(planner.createCustom(repository, template, input.prNumber(), input.issueNumber(), input.issueReportId(), input.ciReportId()));
    }
    private static List<TaskInput> tasks(List<TemplateTaskInput> values) {
        if (values == null) return null;
        return values.stream().map(value -> new TaskInput(value.id(), value.requires(), value.after())).toList();
    }
}
