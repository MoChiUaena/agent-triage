package io.github.mochiuaena.triage.knowledge;

import static io.github.mochiuaena.triage.knowledge.KnowledgeModels.*;
import static org.springframework.http.HttpStatus.*;

import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Local keyword retrieval only. No model configuration, runtime evidence or sending permissions. */
@Service
public class KnowledgeSearchService {
    public static final int MAX_SCAN_CHUNKS = 2000;
    private static final Pattern TOKEN = Pattern.compile("[\\p{L}\\p{N}_]+");
    private final KnowledgeRepository store;
    private final KnowledgeService knowledge;

    public KnowledgeSearchService(KnowledgeRepository store, KnowledgeService knowledge) {
        this.store = store;
        this.knowledge = knowledge;
    }

    public record Citation(UUID chunkId, UUID versionId, UUID sourceId, Scope scope, String title,
        String excerpt, String text, String path, String sourceUrl, String originalUrl, String origin,
        int ordinal, int startLine, int endLine, int startOffset, int endOffset, String sha256,
        String chunkSha256, String commitSha, Instant importedAt, String recallMethod, int score) {}

    public record SearchResult(Scope scope, String query, String docType, String service,
        String recallMethod, List<Citation> results, int scannedChunks, long availableChunks,
        boolean truncated, String coverage) {}

    /** Explicit registered UUID scope; optional service must be this project's current binding. */
    public SearchResult search(Scope selected, String query, String docType, String service, int limit) {
        Scope scope = checkedScope(selected);
        if (query == null || query.isBlank() || query.length() > 200)
            throw KnowledgeFiles.bad("检索词须为 1–200 字符。");
        var tokens = TOKEN.matcher(query.toLowerCase(Locale.ROOT)).results()
            .map(m -> m.group()).distinct().toList();
        if (tokens.isEmpty()) throw KnowledgeFiles.bad("请输入文字或数字关键词。");
        if (limit < 1 || limit > 20) throw KnowledgeFiles.bad("结果上限须为 1–20。");
        String type = docType == null || docType.isBlank() ? "ALL" : docType;
        if (!Set.of("ALL", "MARKDOWN", "TEXT").contains(type))
            throw KnowledgeFiles.bad("文档类型须为 ALL、MARKDOWN 或 TEXT。");
        String boundService = service == null || service.isBlank() ? null : service;
        if (boundService != null && boundService.length() > 100) throw KnowledgeFiles.bad("服务参数过长。");
        try {
            knowledge.validateSearchScope(scope, boundService);
            long available = store.searchCount(scope, type);
            var candidates = store.searchChunks(scope, type, MAX_SCAN_CHUNKS);
            var hits = new ArrayList<Citation>();
            for (var entry : candidates) {
                int score = score(entry.version(), entry.chunk(), tokens);
                if (score > 0) hits.add(citation(entry.version(), entry.chunk(), tokens, score, false));
            }
            hits.sort(Comparator.comparingInt(Citation::score).reversed().thenComparing(Citation::path)
                .thenComparing(Citation::sourceId).thenComparing(Citation::versionId)
                .thenComparingInt(Citation::ordinal).thenComparing(Citation::chunkId));
            // Revalidate registration/binding after storage reads rather than guessing from a name.
            knowledge.validateSearchScope(scope, boundService);
            return new SearchResult(scope, query, type, boundService, "KEYWORD",
                List.copyOf(hits.subList(0, Math.min(limit, hits.size()))), candidates.size(), available,
                available > candidates.size(), "仅检索已导入且当前有效的文档；导入排除与缺口见来源预览。扫描按来源/路径/版本/段序固定排序，达上限可能遗漏后续高分内容。");
        } catch (DataAccessException e) { throw unavailable(); }
    }

    /** Exact persisted chunk, never caller-supplied text. Historical lookup needs no live registration. */
    public Citation chunk(Scope selected, UUID versionId, UUID chunkId) {
        Scope scope = checkedScope(selected);
        if (versionId == null || chunkId == null) throw KnowledgeFiles.bad("请选择准确版本和分段。");
        try {
            Version version = knowledge.version(versionId, scope);
            Chunk chunk = store.chunk(versionId, chunkId);
            return citation(version, chunk, List.of(), 0, true);
        } catch (DataAccessException e) { throw unavailable(); }
    }

    private Scope checkedScope(Scope selected) {
        if (selected == null) throw KnowledgeFiles.bad("请选择已登记项目或仓库。");
        return knowledge.scope(selected.kind(), selected.id());
    }

    private static int score(Version v, Chunk c, List<String> tokens) {
        String title = v.title().toLowerCase(Locale.ROOT), path = v.path().toLowerCase(Locale.ROOT),
            text = c.text().toLowerCase(Locale.ROOT);
        int score = 0;
        for (String token : tokens) {
            int value = (title.contains(token) ? 8 : 0) + (path.contains(token) ? 4 : 0);
            int count = 0, from = 0, at;
            while (count < 3 && (at = text.indexOf(token, from)) >= 0) { count++; from = at + token.length(); }
            value += count * 2;
            if (value == 0) return 0;
            score += value;
        }
        return score;
    }

    private static Citation citation(Version v, Chunk c, List<String> tokens, int score, boolean full) {
        String text = c.text(), folded = text.toLowerCase(Locale.ROOT);
        int foldedAt = tokens.stream().mapToInt(folded::indexOf)
            .filter(i -> i >= 0).min().orElse(0);
        int at = originalOffset(text, foldedAt);
        int start = Math.max(0, at - 100), end = Math.min(text.length(), start + 360);
        if (start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        if (end > start && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        String sourceUrl = null;
        if ("GITHUB".equals(v.origin()) && v.root().matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+")
            && v.commitSha() != null && v.commitSha().matches("[a-f0-9]{40}")) {
            sourceUrl = "https://github.com/" + v.root() + "/blob/" + v.commitSha() + "/"
                + Arrays.stream(v.path().split("/", -1)).map(p -> URLEncoder.encode(p, StandardCharsets.UTF_8).replace("+", "%20"))
                    .collect(java.util.stream.Collectors.joining("/"))
                + "#L" + c.startLine() + "-L" + c.endLine();
        }
        String original = "/sources.html?scopeKind=" + v.scope().kind() + "&scopeId=" + v.scope().id()
            + "&version=" + v.id() + "&chunk=" + c.id() + "#knowledge";
        return new Citation(c.id(), v.id(), v.sourceId(), v.scope(), v.title(), text.substring(start, end),
            full ? text : null, v.path(), sourceUrl, original, v.origin(), c.ordinal(), c.startLine(), c.endLine(),
            c.startOffset(), c.endOffset(), v.sha256(), c.sha256(), v.commitSha(), v.importedAt(), "KEYWORD", score);
    }

    private static int originalOffset(String text, int foldedOffset) {
        // Locale.ROOT lowercasing can expand a code point (e.g. İ -> i + combining dot).
        // Match in folded text, but excerpts and all persisted positions use original UTF-16 coordinates.
        int folded = 0;
        for (int offset = 0; offset < text.length();) {
            int width = Character.charCount(text.codePointAt(offset));
            int foldedWidth = text.substring(offset, offset + width).toLowerCase(Locale.ROOT).length();
            if (folded + foldedWidth > foldedOffset) return offset;
            folded += foldedWidth;
            offset += width;
        }
        return text.length();
    }

    private static ResponseStatusException unavailable() {
        return new ResponseStatusException(SERVICE_UNAVAILABLE, "知识存储暂不可用，请稍后重试；这不表示没有匹配结果。");
    }
}
