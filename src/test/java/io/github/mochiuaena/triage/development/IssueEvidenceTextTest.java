package io.github.mochiuaena.triage.development;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class IssueEvidenceTextTest {
    @Test void multilineCredentialsAndPrivateKeysAreHiddenWithoutMovingLaterLines() {
        String input = "before\npassword=\"prefix\nfixture-secret-middle\nsuffix\"\nafter\n-----BEGIN PRIVATE KEY-----\nfixture-private-material\n-----END PRIVATE KEY-----\nend";
        String output = IssueEvidenceText.clean(input);
        assertThat(output).doesNotContain("prefix", "fixture-secret-middle", "suffix", "fixture-private-material");
        var lines = output.lines().toList();
        assertThat(lines).hasSize(9); assertThat(lines.get(4)).isEqualTo("after"); assertThat(lines.get(8)).isEqualTo("end");
    }
    @Test void longQuotedValuesAndSourceArgumentPairsRemainRedacted() {
        String input = "new ProcessBuilder(\"deploy\", \"--token\", \"" + "q".repeat(8000) + "\");\nAuthorization: Bearer fixture-bearer-secret";
        assertThat(IssueEvidenceText.clean(input)).contains("[REDACTED]").doesNotContain("q".repeat(50), "fixture-bearer-secret");
    }
}
