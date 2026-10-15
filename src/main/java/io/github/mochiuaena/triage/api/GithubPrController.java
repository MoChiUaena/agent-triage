package io.github.mochiuaena.triage.api;

import io.github.mochiuaena.triage.development.*;
import io.github.mochiuaena.triage.development.PrModels.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/development")
public class GithubPrController {
    public record Input(@Min(1) @Max(Integer.MAX_VALUE) long number,
                        @NotNull @Pattern(regexp="[a-f0-9]{40}") String baseSha,
                        @NotNull @Pattern(regexp="[a-f0-9]{40}") String headSha) {}
    private final PrReviewService review;
    public GithubPrController(PrReviewService review) { this.review = review; }
    @GetMapping("/repositories/{repository}/pr/pulls/{number}") public Context inspect(@PathVariable UUID repository, @PathVariable long number) { return review.inspect(repository, number); }
    @GetMapping("/repositories/{repository}/pr/reports") public List<Report> list(@PathVariable UUID repository) { return review.list(repository); }
    @GetMapping("/pr/reports/{id}") public Report report(@PathVariable UUID id) { return review.get(id); }
    @PostMapping("/repositories/{repository}/pr/reports") public ResponseEntity<Report> create(@PathVariable UUID repository, @Valid @RequestBody Input input) {
        Report report = review.create(repository, input.number(), input.baseSha(), input.headSha());
        return ResponseEntity.created(URI.create("/api/development/pr/reports/" + report.id())).body(report);
    }
}
