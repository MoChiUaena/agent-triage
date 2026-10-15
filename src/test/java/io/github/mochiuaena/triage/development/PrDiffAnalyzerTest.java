package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.PrModels.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class PrDiffAnalyzerTest {
    final PrDiffAnalyzer analyzer = new PrDiffAnalyzer();
    final Key key = new Key("acme", "service");
    final Context pull = new Context(7, "Change", "https://github.com/acme/service/pull/7", "open", false,
        "a".repeat(40), "b".repeat(40), 1, 1, 1, List.of(), false);
    Analysis analyze(PatchFile file) { return analyzer.analyze(key, pull, new Diff("c".repeat(40), List.of(file), List.of())); }

    @Test void deletedMethodInRenamedFileUsesOldPathAndMergeBaseLine() {
        var result = analyze(new PatchFile("src/New Name.java", "src/Old Name.java", "renamed", 1, 1,
            "@@ -30,2 +40,2 @@\n-public String oldMethod() {\n+private String newMethod() {\n return null;"));
        assertThat(result.findings()).hasSize(1);
        var finding = result.findings().getFirst();
        assertThat(finding.line()).isEqualTo(30); assertThat(finding.side()).isEqualTo("LEFT");
        assertThat(finding.file()).isEqualTo("src/Old Name.java");
        assertThat(finding.url()).isEqualTo("https://github.com/acme/service/blob/" + "c".repeat(40) + "/src/Old%20Name.java#L30");
    }
    @Test void configAndSqlChangesProduceRemindersWhileCommentsAndOrdinaryDocsDoNot() {
        var config = analyze(new PatchFile("application.yml", "", "modified", 1, 0, "@@ -0,0 +1 @@\n+  ddl-auto: create-drop"));
        assertThat(config.findings()).extracting(Finding::category).containsExactly("SCHEMA_AUTOMATION_CHANGE");
        var sql = analyze(new PatchFile("db/V3.sql", "", "added", 1, 0, "@@ -0,0 +1 @@\n+DROP TABLE orders;"));
        assertThat(sql.findings()).extracting(Finding::category).containsExactly("DESTRUCTIVE_SQL");
        assertThat(analyze(new PatchFile("Example.java", "", "added", 1, 0, "@@ -0,0 +1 @@\n+// http.permitAll();")).findings()).isEmpty();
        assertThat(analyze(new PatchFile("README.md", "", "added", 1, 0, "@@ -0,0 +1 @@\n+DROP TABLE orders;")).findings()).isEmpty();
    }
    @Test void incompletePatchNeverInventsAnExactLineReference() {
        var result = analyze(new PatchFile("Security.java", "", "modified", 2, 0, "@@ -0,0 +1,2 @@\n+http.permitAll();"));
        assertThat(result.files().getFirst().coverage()).isEqualTo("INCOMPLETE");
        assertThat(result.findings()).isEmpty(); assertThat(result.gaps()).isNotEmpty();
    }
    @Test void overlappingHunksAreNotAcceptedAsValidSourceLocations() {
        var result = analyze(new PatchFile("Security.java", "", "modified", 2, 0,
            "@@ -5,0 +5 @@\n+http.permitAll();\n@@ -5,0 +5 @@\n+new ProcessBuilder(args);"));
        assertThat(result.files().getFirst().coverage()).isEqualTo("INCOMPLETE");
        assertThat(result.findings()).isEmpty();
    }
    @Test void credentialsAreRedactedBeforeEvidenceIsClipped() {
        var result = analyze(new PatchFile("Security.java", "", "added", 1, 0,
            "@@ -0,0 +1 @@\n+http.permitAll(); // password=\"a b c\" api_key=sk-" + "x".repeat(30)));
        assertThat(result.findings().getFirst().excerpt()).contains("[REDACTED]").doesNotContain("a b c", "x".repeat(30));
    }
    @Test void sourceArgumentCredentialsAndUrlPasswordsDoNotEnterFindings() {
        for (String source : List.of(
            "new ProcessBuilder(\"deploy\", \"--token\", \"fixture-pr-token-secret\").start();",
            "http.header(\"Authorization\", \"Bearer fixture-pr-bearer-secret\").permitAll();",
            "new ProcessBuilder(\"curl\", \"https://user:fixture-pr-password@host/path\");")) {
            var result = analyze(new PatchFile("Security.java", "", "added", 1, 0, "@@ -0,0 +1 @@\n+" + source));
            assertThat(result.findings()).isNotEmpty();
            assertThat(result.findings().toString()).doesNotContain("fixture-pr-token-secret", "fixture-pr-bearer-secret", "fixture-pr-password");
        }
    }
    @Test void findingLimitIsReportedAsACoverageGap() {
        var files = new ArrayList<PatchFile>();
        for (int n = 0; n < 15; n++) files.add(new PatchFile("Security" + n + ".java", "", "added", 1, 0, "@@ -0,0 +1 @@\n+http.permitAll();"));
        var result = analyzer.analyze(key, pull, new Diff("c".repeat(40), files, List.of()));
        assertThat(result.findings()).hasSize(12); assertThat(result.gaps().toString()).contains("12");
    }
    @Test void longQuotedSourceCredentialsAreRedactedWithoutStackOverflow() {
        for (String source : List.of("http.permitAll(); String password = \"" + "q".repeat(8000) + "\";",
            "new ProcessBuilder(\"deploy\", \"--token\", \"" + "q".repeat(8000) + "\");")) {
            var result = analyze(new PatchFile("Security.java", "", "added", 1, 0, "@@ -0,0 +1 @@\n+" + source));
            assertThat(result.findings()).hasSize(1);
            assertThat(result.findings().getFirst().excerpt()).contains("[REDACTED]").doesNotContain("q".repeat(50));
        }
    }
}
