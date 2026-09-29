package io.github.mochiuaena.triage.source;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class JavaSourceIndexerTest {
    @TempDir Path root;
    private Path write(String file, String code) throws Exception { Path path = root.resolve(file); Files.createDirectories(path.getParent()); Files.writeString(path, code); return path; }
    @Test void parsesJavaSyntaxAndRoutesWithoutLoadingOrRunningTheProject() throws Exception {
        write("src/example/TicketController.java", """
            package example;
            import org.springframework.web.bind.annotation.*;
            @RequestMapping("/api/tickets")
            class TicketController {
              static { throw new IllegalStateException("must never run"); }
              @GetMapping("/{id}")
              Object ticket(String id) { return client.retrieve(id); }
            }
            """);
        var index = new JavaSourceIndexer().index(root.toRealPath());
        assertThat(index.files()).hasSize(1);
        var method = index.files().getFirst().symbols().stream().filter(s -> s.method().equals("ticket")).findFirst().orElseThrow();
        assertThat(method.className()).isEqualTo("example.TicketController");
        assertThat(method.route()).isEqualTo("/api/tickets/{id}");
        assertThat(method.httpMethods()).containsExactly("GET");
        assertThat(method.calls()).contains("client.retrieve");
        assertThat(method.startLine()).isEqualTo(6); assertThat(method.endLine()).isEqualTo(7);
    }
    @Test void doesNotInventDynamicRoutesAndReadsRequestMappingVerbs() throws Exception {
        write("Routes.java", """
            @RequestMapping("/api")
            class Routes {
              @GetMapping(PATH) void dynamic() {}
              @RequestMapping(path="/fixed", method={RequestMethod.GET, RequestMethod.POST}) void fixed() {}
              @GetMapping void root() {}
              void plain() {}
            }
            """);
        var symbols = new JavaSourceIndexer().index(root.toRealPath()).files().getFirst().symbols();
        assertThat(symbols.stream().filter(s -> s.method().equals("dynamic")).findFirst().orElseThrow().route()).isEmpty();
        assertThat(symbols.stream().filter(s -> s.method().equals("plain")).findFirst().orElseThrow().route()).isEmpty();
        var fixed = symbols.stream().filter(s -> s.method().equals("fixed")).findFirst().orElseThrow();
        assertThat(fixed.route()).isEqualTo("/api/fixed");
        assertThat(fixed.httpMethods()).containsExactly("GET", "POST");
        assertThat(symbols.stream().filter(s -> s.method().equals("root")).findFirst().orElseThrow().route()).isEqualTo("/api/");
    }
    @Test void excludesBuildOutputCredentialsAndMalformedFilesWithoutReturningTheirText() throws Exception {
        write("src/Good.java", "class Good { void run() {} }");
        write("target/Ignored.java", "class Ignored {}");
        write("src/Credentials.java", "class Credentials { String password = \"private-password-fixture\"; }");
        write("src/Broken.java", "class Broken { this is broken }");
        var index = new JavaSourceIndexer().index(root.toRealPath());
        assertThat(index.files()).extracting(SourceModels.FileEntry::path).containsExactly("src/Good.java");
        assertThat(index.skippedFiles()).isEqualTo(1); assertThat(index.parseFailures()).isEqualTo(1);
        assertThat(index.toString()).doesNotContain("private-password-fixture");
    }
    @Test void refusesLinksEscapingTheSelectedProject() throws Exception {
        Path external = Files.createTempFile(root.getParent(), "external-source-", ".java");
        try {
            Files.writeString(external, "class Outside { }");
            boolean linked = true;
            try { Files.createSymbolicLink(root.resolve("Linked.java"), external); }
            catch (Exception error) { linked = false; }
            assumeTrue(linked, "Host does not allow symbolic links");
            assertThatThrownBy(() -> SourceFiles.read(root.toRealPath(), "Linked.java")).isInstanceOf(java.io.IOException.class);
            assertThat(new JavaSourceIndexer().index(root.toRealPath()).files()).isEmpty();
        } finally { Files.deleteIfExists(external); }
    }
}
