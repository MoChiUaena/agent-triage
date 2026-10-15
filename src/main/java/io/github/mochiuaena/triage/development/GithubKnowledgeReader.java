package io.github.mochiuaena.triage.development;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;

import static org.springframework.http.HttpStatus.*;

import com.fasterxml.jackson.databind.JsonNode;

import io.github.mochiuaena.triage.knowledge.*;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.BooleanSupplier;

/**
 * Reads only the selected commit's tree and content-addressed blobs through the existing transport.
 */
@Component
public class GithubKnowledgeReader {
    private final GithubClient github;

    public GithubKnowledgeReader(GithubClient github) {
        this.github = github;
    }

    public Read read(GithubModels.Key key, String commit, BooleanSupplier cancelled) {
        return read(key, commit, cancelled, count -> {});
    }

    public Read read(
            GithubModels.Key key,
            String commit,
            BooleanSupplier cancelled,
            java.util.function.IntConsumer progress) {
        if (commit == null || !commit.matches("[a-fA-F0-9]{40}"))
            throw KnowledgeFiles.bad("请输入完整 40 位提交 SHA。");
        commit = commit.toLowerCase(Locale.ROOT);
        String base = "/repos/" + key.fullName() + "/git/";
        KnowledgeFiles.check(cancelled);
        var commitPayload = github.get(base + "commits/" + commit);
        JsonNode c = commitPayload.data();
        if (commitPayload.truncated()
                || !commit.equals(c.path("sha").asText())
                || !sha(c.path("tree").path("sha").asText())) throw invalid();
        String tree = c.path("tree").path("sha").asText();
        KnowledgeFiles.check(cancelled);
        var payload = github.get(base + "trees/" + tree + "?recursive=1");
        JsonNode data = payload.data();
        if (payload.truncated()
                || !data.path("truncated").isBoolean()
                || data.path("truncated").asBoolean()
                || !tree.equals(data.path("sha").asText())
                || !data.path("tree").isArray())
            throw new ResponseStatusException(BAD_GATEWAY, "GitHub 文档树不完整，未创建导入预览。");
        if (data.path("tree").size() > MAX_ENTRIES)
            throw new ResponseStatusException(BAD_GATEWAY, "GitHub 文档树超过 5000 条目，未创建导入预览。");
        var candidates = new TreeMap<String, JsonNode>();
        var skips = new ArrayList<Exclusion>();
        boolean complete = true;
        int visited = 0;
        for (JsonNode entry : data.path("tree")) {
            KnowledgeFiles.check(cancelled);
            visited++;
            String path = entry.path("path").asText();
            try {
                KnowledgeFiles.safePath(path);
            } catch (ResponseStatusException e) {
                throw invalid();
            }
            if (!Set.of("blob", "tree", "commit").contains(entry.path("type").asText())
                    || !sha(entry.path("sha").asText())) throw invalid();
            if (path.split("/").length > MAX_DEPTH) {
                KnowledgeFiles.skip(skips, path, "DEPTH_LIMIT");
                complete = false;
                continue;
            }
            if (!entry.path("type").asText().equals("blob")) continue;
            boolean include =
                    path.equalsIgnoreCase("README.md")
                            || path.equalsIgnoreCase("README.txt")
                            || path.startsWith("docs/") && KnowledgeFiles.eligible(path);
            if (!include) {
                KnowledgeFiles.skip(skips, path, "OUTSIDE_README_DOCS");
                continue;
            }
            if (KnowledgeFiles.excluded(path)) {
                KnowledgeFiles.skip(skips, path, "DIRECTORY_OR_CREDENTIAL");
                continue;
            }
            if (!Set.of("100644", "100755").contains(entry.path("mode").asText())) {
                KnowledgeFiles.skip(skips, path, "LINK_OR_SPECIAL");
                continue;
            }
            if (!entry.path("size").canConvertToInt() || entry.path("size").asInt() < 0)
                throw invalid();
            if (candidates.put(path, entry) != null) throw invalid();
        }
        var documents = new ArrayList<Document>();
        int bytes = 0, chunks = 0, eligible = 0, readBytes = 0;
        for (var candidate : candidates.entrySet()) {
            KnowledgeFiles.check(cancelled);
            String path = candidate.getKey();
            JsonNode entry = candidate.getValue();
            int size = entry.path("size").asInt();
            if (++eligible > MAX_FILES || size > MAX_FILE_BYTES || readBytes + size > MAX_BYTES) {
                KnowledgeFiles.skip(
                        skips,
                        path,
                        eligible > MAX_FILES
                                ? "FILE_LIMIT"
                                : size > MAX_FILE_BYTES ? "FILE_BYTES" : "TOTAL_BYTES");
                complete = false;
                continue;
            }
            String blob = entry.path("sha").asText();
            var bp = github.get(base + "blobs/" + blob);
            JsonNode b = bp.data();
            if (bp.truncated()
                    || !blob.equals(b.path("sha").asText())
                    || !b.path("size").canConvertToInt()
                    || b.path("size").asInt() != size
                    || !b.path("encoding").asText().equals("base64")
                    || !b.path("content").isTextual()) throw invalid();
            byte[] content;
            try {
                content =
                        Base64.getDecoder()
                                .decode(
                                        b.path("content")
                                                .asText()
                                                .replace("\n", "")
                                                .replace("\r", ""));
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            if (content.length != size || !blob.equals(blobHash(content))) throw invalid();
            readBytes += content.length;
            Document document;
            try {
                document = KnowledgeFiles.document(path, content);
            } catch (KnowledgeFiles.Rejected e) {
                KnowledgeFiles.skip(skips, path, e.getMessage());
                continue;
            }
            int n = KnowledgeFiles.chunkCount(document.text());
            if (chunks + n > MAX_CHUNKS) {
                KnowledgeFiles.skip(skips, path, "CHUNK_LIMIT");
                complete = false;
                continue;
            }
            documents.add(document);
            progress.accept(documents.size());
            bytes += content.length;
            chunks += n;
        }
        return new Read(
                key.fullName(),
                commit,
                List.copyOf(documents),
                List.copyOf(skips),
                visited,
                complete);
    }

    private static boolean sha(String s) {
        return s.matches("[a-f0-9]{40}");
    }

    private static String blobHash(byte[] bytes) {
        try {
            var digest = MessageDigest.getInstance("SHA-1");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest(bytes));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ResponseStatusException invalid() {
        return new ResponseStatusException(BAD_GATEWAY, "GitHub 固定版本文档校验失败，未导入任何内容。");
    }
}
