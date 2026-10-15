package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.CiModels.*;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeout;

class CiLogAnalyzerTest {
    final CiLogAnalyzer analyzer = new CiLogAnalyzer();
    Job job = new Job(1, 2, 1, "a".repeat(40), "Build", "completed", "failure", List.of(), "https://github.com/acme/service/actions/runs/2/job/1");
    @Test void unknownOrPassingLogDoesNotInventACause() {
        var report = analyzer.analyze("[INFO] All tasks completed\nNo error", job);
        assertThat(report.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.evidence()).isEmpty();
    }
    @Test void dependencyFailureIncludesEvidenceAndUsefulChecks() {
        var report = analyzer.analyze("[ERROR] Could not find artifact acme:library:1.0 in central\n[ERROR] Build failed", job);
        assertThat(report.category()).isEqualTo("DEPENDENCY_RESOLUTION");
        assertThat(report.evidence().getFirst().startLine()).isEqualTo(1);
        assertThat(report.checks()).isNotEmpty();
    }
    @Test void credentialsAndPrivateKeysAreRedactedBeforeEvidenceIsSaved() {
        String secret = "z".repeat(32);
        var report = analyzer.analyze("[ERROR] Permission denied\napi-key=" + secret + "\n-----BEGIN RSA PRIVATE KEY-----\nprivate-material\n-----END RSA PRIVATE KEY-----", job);
        assertThat(report.evidence().toString()).doesNotContain(secret, "private-material").contains("REDACTED");
        assertThat(report.evidence().getFirst().endLine()).isEqualTo(5);
    }
    @Test void passingStepErrorsAreExcludedFromClassification() {
        var scoped = new Job(1, 2, 1, "a".repeat(40), "Build", "completed", "failure",
            List.of(new Step(2, "Compile", "failure", Instant.parse("2026-10-14T00:01:00Z"), Instant.parse("2026-10-14T00:02:00Z"))), job.url());
        var report = analyzer.analyze("2026-10-14T00:00:10Z AssertionError from passing fixture\n2026-10-14T00:01:10Z [ERROR] COMPILATION ERROR\n2026-10-14T00:01:11Z [ERROR] Foo.java:[17,4] cannot find symbol", scoped);
        assertThat(report.category()).isEqualTo("COMPILATION_FAILURE");
        assertThat(report.scope()).contains("Compile");
        assertThat(report.evidence().toString()).doesNotContain("passing fixture");
        assertThat(report.locations()).contains(new Location("Foo.java", 17));
    }
    @Test void quotedJsonCredentialsAreRedactedIncludingSpaces() {
        var report = analyzer.analyze("[ERROR] failed request\n{\"password\":\"private value with spaces\",\"apiKey\":\"private-value\"}", job);
        assertThat(report.evidence().toString()).doesNotContain("private value", "private-value").contains("REDACTED");
    }
    @Test void quotedEnvironmentAssignmentsDoNotLeaveSecretSuffixes() {
        var report = analyzer.analyze("[ERROR] Permission denied\nPASSWORD=\"fixture secret suffix\"\nTOKEN='another secret suffix'", job);
        assertThat(report.evidence().toString()).doesNotContain("secret suffix").contains("REDACTED");
    }
    @Test void completionSecondIncludesSubsecondErrorsButNotTheFollowingStep() {
        var scoped = new Job(1, 2, 1, "a".repeat(40), "Build", "completed", "failure", List.of(
            new Step(2, "Compile", "failure", Instant.parse("2026-10-14T00:01:00Z"), Instant.parse("2026-10-14T00:01:01Z")),
            new Step(3, "Passing fixture", "success", Instant.parse("2026-10-14T00:01:02Z"), Instant.parse("2026-10-14T00:01:03Z"))), job.url());
        var report = analyzer.analyze("2026-10-14T00:01:01.100Z [ERROR] COMPILATION ERROR\n2026-10-14T00:01:01.200Z Foo.java:[17,4] cannot find symbol\n2026-10-14T00:01:02.100Z AssertionError in Passing fixture", scoped);
        assertThat(report.category()).isEqualTo("COMPILATION_FAILURE");
        assertThat(report.evidence().toString()).doesNotContain("Passing fixture");
    }
    @Test void longEncodedLinesDoNotCauseCredentialPatternBacktracking() {
        assertTimeout(Duration.ofSeconds(2), () -> analyzer.analyze("[ERROR] Build failed\n" + "A".repeat(16000), job));
    }
    @Test void longQuotedCredentialsAreRedactedWithoutOverflowBeforeClipping() {
        var result = analyzer.analyze("[ERROR] Permission denied\nPASSWORD=\"" + "q".repeat(8000) + "\"", job);
        assertThat(result.evidence().toString()).contains("[REDACTED]").doesNotContain("q".repeat(50));
    }
    @Test void sharedCompletionSecondCannotAttributeTheNextStepsErrorToTheFailedStep() {
        var scoped = new Job(1, 2, 1, "a".repeat(40), "Build", "completed", "failure", List.of(
            new Step(2, "Compile", "failure", Instant.parse("2026-10-14T00:01:00Z"), Instant.parse("2026-10-14T00:01:01Z")),
            new Step(3, "Passing fixture", "success", Instant.parse("2026-10-14T00:01:01Z"), Instant.parse("2026-10-14T00:01:02Z"))), job.url());
        var report = analyzer.analyze("2026-10-14T00:01:01.100Z [ERROR] COMPILATION ERROR\n2026-10-14T00:01:01.600Z Could not find artifact in Passing fixture", scoped);
        assertThat(report.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(report.evidence()).isEmpty();
        assertThat(report.scope()).contains("重叠");
    }
}
