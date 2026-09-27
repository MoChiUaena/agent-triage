package io.github.mochiuaena.triage.settings;

import io.github.mochiuaena.triage.domain.TriageModel.Scenario;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/evaluation")
public class DocumentOnlyEvaluationController {
    public record Input(@NotBlank @Size(max = 200) String question, @NotNull Scenario scenario,
                        @NotBlank @Size(max = 240) String expectedSelection) {}
    private final DocumentOnlyEvaluationService evaluation;
    public DocumentOnlyEvaluationController(DocumentOnlyEvaluationService evaluation) { this.evaluation = evaluation; }

    @PostMapping("/document-only")
    public DocumentOnlyEvaluationService.Result documentOnly(@Valid @RequestBody Input input) {
        return evaluation.evaluate(input.question().strip(), input.scenario(), input.expectedSelection());
    }
}
