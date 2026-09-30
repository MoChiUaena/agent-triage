package io.github.mochiuaena.triage.source;

import io.github.mochiuaena.triage.domain.TriageModel.*;
import io.github.mochiuaena.triage.execution.RunFailure;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static io.github.mochiuaena.triage.source.SourceModels.*;
import static org.assertj.core.api.Assertions.*;

class SourceFailureLocationsTest {
    @TempDir Path root;
    private Index index(String path, String source) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, source);
        return new JavaSourceIndexer().index(root.toRealPath());
    }
    private FrameMatch match(Index index, FailureFrame frame) {
        return new SourceFailureLocations(index).match(evidence(frame), (id, focus) -> {
            Symbol symbol = index.files().stream().flatMap(file -> file.symbols().stream()).filter(value -> value.id().equals(id)).findFirst().orElseThrow();
            int first = focus == null ? symbol.startLine() : focus;
            return new Excerpt(id, symbol.path(), symbol.fileHash(), symbol.className(), symbol.method(), symbol.route(), symbol.httpMethods(), symbol.calls(), first, first, "fixture");
        }, () -> {}).getFirst().frames().getFirst();
    }
    private List<Evidence> evidence(FailureFrame frame) {
        return List.of(new Evidence("logs-fixture", "query_error_logs", "错误事件", "固定提示", Map.of("failureLocations",
            List.of(new RequestFailure(Instant.parse("2026-09-29T00:00:00Z"), "request-fixture", new FailureLocation("HTTP_CLIENT_FAILURE",
                List.of("java.net.SocketTimeoutException"), List.of(frame), false))))));
    }
    @Test void lineNumbersSelectAnOverloadAndUnknownLinesKeepBothCandidates() throws Exception {
        Index index = index("Gateway.java", """
            package fixture;
            class Gateway {
                String lookup(String id) {
                    return id;
                }
                String lookup(int id) { return ""; }
            }
            """);
        var known = match(index, new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", 4));
        assertThat(known.state()).isEqualTo("LINE_MATCH"); assertThat(known.excerpts()).hasSize(1);
        assertThat(known.excerpts().getFirst().startLine()).isEqualTo(4);
        assertThat(known.message()).contains("运行版本");
        var unknown = match(index, new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", null));
        assertThat(unknown.state()).isEqualTo("AMBIGUOUS"); assertThat(unknown.excerpts()).hasSize(2);
        assertThat(match(index, new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", 2)).state()).isEqualTo("LINE_MISMATCH");
        assertThat(match(index, new FailureFrame("fixture.Gateway", "lookup", "Other.java", 4)).state()).isEqualTo("FILE_MISMATCH");
        assertThat(match(index, new FailureFrame("fixture.Unknown", "lookup", "Gateway.java", 4)).state()).isEqualTo("UNMATCHED");
    }
    @Test void duplicateModuleClassesRemainAmbiguousAndOnlyTwoCandidatesAreRead() throws Exception {
        for (String module : List.of("one", "two", "three")) index(module + "/Gateway.java", "package fixture; class Gateway { void lookup() {} }");
        Index index = new JavaSourceIndexer().index(root.toRealPath());
        var result = match(index, new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", 1));
        assertThat(result.state()).isEqualTo("AMBIGUOUS"); assertThat(result.excerpts()).hasSize(2);
        assertThat(result.excerpts()).extracting(Excerpt::path).containsExactly("one/Gateway.java", "three/Gateway.java");
    }
    @Test void namedNestedClassesAndConstructorsResolveButAnonymousClassesAreNotInvented() throws Exception {
        Index index = index("Owner.java", "package fixture; class Owner { Owner() {} class Inner { void open() {} } }");
        assertThat(match(index, new FailureFrame("fixture.Owner$Inner", "open", "Owner.java", 1)).state()).isEqualTo("LINE_MATCH");
        assertThat(match(index, new FailureFrame("fixture.Owner", "<init>", "Owner.java", 1)).state()).isEqualTo("LINE_MATCH");
        assertThat(match(index, new FailureFrame("fixture.Owner$1", "open", "Owner.java", 1)).state()).isEqualTo("UNMATCHED");
        assertThat(match(index, new FailureFrame("fixture.Owner", "<clinit>", "Owner.java", 1)).state()).isEqualTo("UNMATCHED");
        assertThat(match(index, new FailureFrame("fixture.Owner$Inner", "open", null, null)).state()).isEqualTo("CANDIDATE");
    }
    @Test void staleFilesStayVisibleAsUnmatchedAndCancellationIsNotSwallowed() throws Exception {
        Index index = index("Gateway.java", "package fixture; class Gateway { void lookup() {} }");
        var frame = new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", 1);
        var locator = new SourceFailureLocations(index);
        var result = locator.match(evidence(frame), (id, focus) -> { throw new ResponseStatusException(CONFLICT); }, () -> {});
        assertThat(result.getFirst().frames().getFirst().state()).isEqualTo("STALE");
        assertThat(result.getFirst().frames().getFirst().excerpts()).isEmpty();
        assertThatThrownBy(() -> locator.match(evidence(frame), (id, focus) -> null,
            () -> { throw new RunFailure("RUN_CANCELLED", "fixture"); })).isInstanceOf(RunFailure.class);
    }
    @Test void syntheticLambdaMapsOnlyToAnEnclosingMethodCandidate() throws Exception {
        Index index = index("OwnerController.java", """
            package fixture;
            class OwnerController {
                Object findOwner(int id) {
                    return Optional.of(id)
                        .orElseThrow(() -> new IllegalArgumentException());
                }
                Object showOwner(int id) { return null; }
            }
            """);
        var frame = new FailureFrame("fixture.OwnerController", "lambda$findOwner$0", "OwnerController.java", 5);
        var candidate = match(index, frame);
        assertThat(candidate.state()).isEqualTo("LAMBDA_CANDIDATE");
        assertThat(candidate.excerpts()).extracting(Excerpt::method).containsExactly("findOwner");
        assertThat(candidate.version().state()).isEqualTo("UNKNOWN");
        assertThat(match(index, new FailureFrame("fixture.OwnerController", "lambda$findOwner$0", "OwnerController.java", null)).state()).isEqualTo("UNMATCHED");
        assertThat(match(index, new FailureFrame("fixture.OwnerController", "lambda$findOwner$0", "OwnerController.java", 7)).state()).isEqualTo("UNMATCHED");
        assertThat(match(index, new FailureFrame("fixture.OwnerController", "lambda$unknown$0", "OwnerController.java", 5)).state()).isEqualTo("UNMATCHED");
        assertThat(match(index, new FailureFrame("fixture.OwnerController", "lambda$findOwner$0", "OwnerController.java", 5, "f".repeat(64))).state()).isEqualTo("SOURCE_MISMATCH");
    }
    @Test void buildDigestDistinguishesModuleCandidatesAndRefusesSameLineInDifferentSource() throws Exception {
        Index index = index("one/Gateway.java", "package fixture; class Gateway { void lookup() { } }");
        index = index("two/Gateway.java", "package fixture; class Gateway { void lookup() { int newer = 1; } }");
        String expected = index.files().stream().filter(file -> file.path().equals("two/Gateway.java")).findFirst().orElseThrow().hash();
        var result = match(index, new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", 1, expected));
        assertThat(result.state()).isEqualTo("LINE_MATCH"); assertThat(result.version().state()).isEqualTo("MATCHED");
        assertThat(result.excerpts()).extracting(Excerpt::path).containsExactly("two/Gateway.java");
        var different = match(index, new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", 1, "f".repeat(64)));
        assertThat(different.state()).isEqualTo("SOURCE_MISMATCH"); assertThat(different.version().state()).isEqualTo("DIFFERENT"); assertThat(different.excerpts()).isEmpty();
        assertThat(match(index, new FailureFrame("fixture.Gateway", "lookup", "Gateway.java", 1)).version().state()).isEqualTo("UNKNOWN");
    }
}
