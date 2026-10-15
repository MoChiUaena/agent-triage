package io.github.mochiuaena.triage.development;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.*;

import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

class GithubKnowledgeReaderTest {
    HttpServer server;
    ObjectMapper json = new ObjectMapper();
    String sha = "a".repeat(40), tree = "b".repeat(40);
    List<String> paths = new ArrayList<>();
    boolean truncated, wrongHash;
    String blob;

    @BeforeEach
    void start() throws Exception {
        blob =
                HexFormat.of()
                        .formatHex(
                                MessageDigest.getInstance("SHA-1")
                                        .digest(
                                                "blob 12\0# Readme\nOK\n"
                                                        .getBytes(StandardCharsets.UTF_8)));
        // Compute the exact Git object identity, including byte length.
        byte[] content = "# Readme\nOK\n".getBytes(StandardCharsets.UTF_8);
        var digest = MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.UTF_8));
        blob = HexFormat.of().formatHex(digest.digest(content));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(
                "/",
                exchange -> {
                    String path = exchange.getRequestURI().toString();
                    paths.add(path);
                    Object response;
                    if (path.endsWith("/git/commits/" + sha))
                        response = Map.of("sha", sha, "tree", Map.of("sha", tree));
                    else if (path.endsWith("/git/trees/" + tree + "?recursive=1"))
                        response =
                                Map.of(
                                        "sha",
                                        tree,
                                        "truncated",
                                        truncated,
                                        "tree",
                                        List.of(
                                                Map.of(
                                                        "path",
                                                        "README.md",
                                                        "type",
                                                        "blob",
                                                        "mode",
                                                        "100644",
                                                        "size",
                                                        content.length,
                                                        "sha",
                                                        blob),
                                                Map.of(
                                                        "path",
                                                        "docs/guide.txt",
                                                        "type",
                                                        "blob",
                                                        "mode",
                                                        "100644",
                                                        "size",
                                                        content.length,
                                                        "sha",
                                                        blob),
                                                Map.of(
                                                        "path",
                                                        "src/no.md",
                                                        "type",
                                                        "blob",
                                                        "mode",
                                                        "100644",
                                                        "size",
                                                        content.length,
                                                        "sha",
                                                        blob),
                                                Map.of(
                                                        "path",
                                                        "docs/link.md",
                                                        "type",
                                                        "blob",
                                                        "mode",
                                                        "120000",
                                                        "size",
                                                        content.length,
                                                        "sha",
                                                        blob)));
                    else if (path.endsWith("/git/blobs/" + blob))
                        response =
                                Map.of(
                                        "sha",
                                        wrongHash ? "c".repeat(40) : blob,
                                        "size",
                                        content.length,
                                        "encoding",
                                        "base64",
                                        "content",
                                        Base64.getEncoder().encodeToString(content));
                    else {
                        exchange.sendResponseHeaders(404, -1);
                        exchange.close();
                        return;
                    }
                    byte[] bytes = json.writeValueAsBytes(response);
                    exchange.sendResponseHeaders(200, bytes.length);
                    exchange.getResponseBody().write(bytes);
                    exchange.close();
                });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    GithubKnowledgeReader reader() {
        return new GithubKnowledgeReader(
                new GithubClient(json, "http://127.0.0.1:" + server.getAddress().getPort(), ""));
    }

    @Test
    void pinsCommitTreeAndValidatesBlobsWithoutFollowingDocumentUrls() {
        var read = reader().read(new GithubModels.Key("fixture", "repo"), sha, () -> false);
        assertThat(read.documents())
                .extracting(d -> d.path())
                .containsExactly("README.md", "docs/guide.txt");
        assertThat(read.commitSha()).isEqualTo(sha);
        assertThat(paths).allMatch(p -> p.startsWith("/repos/fixture/repo/git/"));
        assertThat(paths).noneMatch(p -> p.contains("main") || p.contains("contents"));
    }

    @Test
    void rejectsIncompleteTreeAndWrongBlobIdentity() {
        truncated = true;
        assertThatThrownBy(
                        () ->
                                reader().read(
                                                new GithubModels.Key("fixture", "repo"),
                                                sha,
                                                () -> false))
                .hasMessageContaining("完整");
        truncated = false;
        wrongHash = true;
        assertThatThrownBy(
                        () ->
                                reader().read(
                                                new GithubModels.Key("fixture", "repo"),
                                                sha,
                                                () -> false))
                .hasMessageContaining("校验");
    }

    @Test
    void rejectsBranchAndTraversalBeforeNetwork() {
        assertThatThrownBy(
                        () ->
                                reader().read(
                                                new GithubModels.Key("fixture", "repo"),
                                                "main",
                                                () -> false))
                .hasMessageContaining("40");
        assertThat(paths).isEmpty();
    }
}
