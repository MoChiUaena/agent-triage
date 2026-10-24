package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.IssueModels.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Instant;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static io.github.mochiuaena.triage.development.GithubClient.*;
import static org.springframework.http.HttpStatus.*;

@Component
public class GithubIssueClient {
    private static final Pattern PATH = Pattern.compile("(?<![\\w./\\\\:-])((?:[A-Za-z0-9_$.-]+/)+[A-Za-z0-9_$.-]+\\.java)(?::([1-9][0-9]{0,6}))?");
    private final GithubClient github;
    public GithubIssueClient(GithubClient github) { this.github = github; }
    ReadIssue issue(Key key, long number) {
        if (number < 1 || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid issue");
        JsonNode value = github.get("/repos/" + key.fullName() + "/issues/" + number).data();
        if (value.has("pull_request")) throw new ResponseStatusException(BAD_REQUEST, "这个编号对应 PR，请使用 PR 审查。");
        if (integer(value, "number") != number || !value.path("title").isTextual() || !Set.of("open", "closed").contains(value.path("state").asText())
            || !value.path("labels").isArray() || !value.path("assignees").isArray()) throw invalid();
        String body = value.path("body").isNull() ? "" : value.path("body").asText("");
        var labels = new ArrayList<String>(); var assignees = new ArrayList<String>();
        value.path("labels").forEach(label -> { if (labels.size() < 20) labels.add(IssueEvidenceText.clean(text(label, "name", 100))); });
        value.path("assignees").forEach(user -> { if (assignees.size() < 10) assignees.add(login(user)); });
        Instant updated; try { updated = Instant.parse(value.path("updated_at").asText()); } catch (RuntimeException error) { throw invalid(); }
        String fingerprint = hash(value.toString());
        String clean = IssueEvidenceText.clean(body);
        boolean truncated = clean.length() > 12_000 || clean.lines().count() > 200 || value.path("labels").size() > 20 || value.path("assignees").size() > 10;
        List<String> rows = clean.lines().limit(200).toList();
        clean = String.join("\n", rows); if (clean.length() > 12_000) clean = clean.substring(0, 12_000);
        return new ReadIssue(new Issue(number, IssueEvidenceText.clean(text(value, "title", 300)), key.url() + "/issues/" + number, text(value, "state", 20), updated,
            List.copyOf(labels), List.copyOf(assignees), integer(value, "comments"), truncated), clean, fingerprint);
    }
    /** Bounded read of the five most recent comments; failures degrade to an empty read with a hash. */
    ReadComments comments(Key key, long number) {
        JsonNode page = github.get("/repos/" + key.fullName() + "/issues/" + number + "/comments?per_page=5&sort=created&direction=desc").data();
        if (!page.isArray() || page.size() > 5) throw invalid();
        var items = new ArrayList<Comment>();
        for (JsonNode value : page) {
            if (integer(value, "id") < 0) throw invalid();
            String url = text(value, "html_url", 300);
            if (!url.startsWith(key.url() + "/issues/" + number)) throw invalid();
            Instant createdAt; try { createdAt = Instant.parse(value.path("created_at").asText()); } catch (RuntimeException error) { throw invalid(); }
            String body = IssueEvidenceText.clean(value.path("body").isNull() ? "" : value.path("body").asText(""));
            List<String> rows = body.lines().limit(60).toList();
            body = String.join("\n", rows); if (body.length() > 2_000) body = body.substring(0, 2_000);
            items.add(new Comment(integer(value, "id"), login(value.path("user")), createdAt, url, body));
        }
        return new ReadComments(List.copyOf(items), hash(page.toString()), page.size());
    }

    /** Issue and comment identity in one value; a changed comment invalidates the preview. Unreadable comments fall back to the issue alone. */
    String contextFingerprint(Key key, long number) {
        ReadIssue read = issue(key, number);
        try {
            JsonNode page = github.get("/repos/" + key.fullName() + "/issues/" + number + "/comments?per_page=5&sort=created&direction=desc").data();
            if (!page.isArray() || page.size() > 5) throw invalid();
            return read.fingerprint() + ":" + hash(page.toString());
        } catch (ResponseStatusException error) { return read.fingerprint(); }
    }

    Prepared prepare(UUID repository, Key key, long number, List<String> extra) {
        if (extra.size() > 3 || extra.stream().anyMatch(path -> !validPath(path))) throw new IllegalArgumentException("Invalid source paths");
        ReadIssue read = issue(key, number); Issue issue = read.issue();
        var evidence = new ArrayList<Evidence>(); var gaps = new ArrayList<String>(); var owners = new ArrayList<Owner>();
        evidence.add(new Evidence("ISSUE-1", "ISSUE", "Issue 标题与标签", issue.url(), "", 0, 0, issue.title() + "\n标签：" + String.join(", ", issue.labels())));
        if (!read.body().isBlank()) {
            String[] rows = read.body().split("\n", -1);
            evidence.add(new Evidence("ISSUE-2", "ISSUE", "Issue 正文（报告者描述）", issue.url(), "", 1, rows.length, read.body()));
        } else gaps.add("Issue 未提供正文，无法确认复现过程或影响范围。");
        if (issue.bodyTruncated()) gaps.add("Issue 正文只读取前 12,000 字符、200 行；标签与负责人也有条数限制。");
        var commentTexts = new ArrayList<String>();
        ReadComments comments = null;
        try {
            comments = comments(key, number);
            int commentIndex = 0;
            for (Comment comment : comments.items()) {
                evidence.add(new Evidence("COMMENT-" + (++commentIndex), "COMMENT", "最近评论（不可信用户内容）", comment.url(), "", 0, 0,
                    "@" + comment.login() + " · " + comment.createdAt() + "\n" + comment.text()));
                commentTexts.add(comment.text());
            }
            if (issue.comments() > comments.items().size()) gaps.add("只读取最近 5 条评论，未读取其余 " + (issue.comments() - comments.items().size()) + " 条。");
            gaps.add("评论为不可信用户内容；其中提及的账号与提交不构成因果结论或负责人推荐。");
        } catch (ResponseStatusException error) { gaps.add("评论未读取：" + error.getReason()); }
        String branch = "", head = "";
        try {
            JsonNode info = github.get("/repos/" + key.fullName()).data();
            if (!key.fullName().equalsIgnoreCase(info.path("full_name").asText())) throw invalid();
            branch = text(info, "default_branch", 160); if (branch.isBlank()) throw invalid();
            head = sha(github.get("/repos/" + key.fullName() + "/commits/" + encode(branch)).data().path("sha").asText());
        } catch (ResponseStatusException error) { gaps.add("默认分支版本未读取：" + error.getReason()); }
        if (!head.isEmpty()) {
            try {
                var page = github.get("/repos/" + key.fullName() + "/commits?sha=" + head + "&per_page=5");
                if (!page.data().isArray() || page.data().size() > 5) throw invalid();
                int index = 0;
                for (var commit : page.data()) {
                    String commitSha = sha(commit.path("sha").asText());
                    evidence.add(new Evidence("COMMIT-" + (++index), "COMMIT", "近期提交（未证明与 Issue 有关）", key.url() + "/commit/" + commitSha, "", 0, 0,
                        commitSha + "\n" + IssueEvidenceText.clean(text(commit.path("commit"), "message", 600))));
                }
                if (page.truncated()) gaps.add("近期提交只读取固定分支版本的前五条，没有推断其与 Issue 的因果关系。");
            } catch (ResponseStatusException error) { gaps.add("近期提交未读取：" + error.getReason()); }
        }
        try {
            var page = github.get("/repos/" + key.fullName() + "/contributors?per_page=10");
            if (!page.data().isArray() || page.data().size() > 10) throw invalid();
            for (var user : page.data()) {
                if (user.path("type").asText().equals("Bot") || user.path("login").asText().endsWith("[bot]")) continue;
                String name = login(user), id = "OWNER-" + (owners.size() + 1);
                owners.add(new Owner(name, "https://github.com/" + name, id));
                evidence.add(new Evidence(id, "CONTRIBUTOR", "仓库贡献者（不代表可分配或当前有空）", key.url() + "/graphs/contributors", "", 0, 0, name));
            }
            if (page.truncated()) gaps.add("贡献者只读取前十位；没有核对可分配权限、职责或当前工作量。");
        } catch (ResponseStatusException error) { gaps.add("贡献者未读取：" + error.getReason()); }
        var paths = new LinkedHashMap<String, Integer>();
        extra.forEach(path -> paths.put(path, 1));
        Matcher matcher = PATH.matcher(read.body()); boolean morePaths = false;
        while (matcher.find()) {
            String path = matcher.group(1); if (!validPath(path) || paths.containsKey(path)) continue;
            if (paths.size() == 3) { morePaths = true; break; }
            paths.put(path, matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2)));
        }
        for (String text : commentTexts) {
            matcher = PATH.matcher(text);
            while (matcher.find()) {
                String path = matcher.group(1); if (!validPath(path) || paths.containsKey(path)) continue;
                if (paths.size() == 3) { morePaths = true; break; }
                paths.put(path, matcher.group(2) == null ? 1 : Integer.parseInt(matcher.group(2)));
            }
            if (morePaths) break;
        }
        if (paths.isEmpty()) gaps.add("没有明确的仓库相对 Java 文件路径，未自动搜索整个代码库。可补充最多三个路径后重新读取。");
        if (morePaths) gaps.add("Issue 提到更多文件，本次只读取前三个路径。");
        for (var entry : paths.entrySet()) {
            if (head.isEmpty()) { gaps.add(entry.getKey() + "：没有可固定的提交，未读取源码。"); continue; }
            try { evidence.add(source(key, head, entry.getKey(), entry.getValue(), "CODE-" + (evidence.stream().filter(e -> e.kind().equals("CODE")).count() + 1))); }
            catch (ResponseStatusException error) { gaps.add(entry.getKey() + "：源码未读取，" + error.getReason()); }
        }
        if (!head.isEmpty()) {
            try {
                var rules = codeowners(key, head);
                if (rules == null) gaps.add("CODEOWNERS 未读取：仓库未提供该文件。");
                else if (!rules.isEmpty()) {
                    int claimed = 0;
                    for (var path : paths.keySet()) {
                        for (var rule : rules) {
                            if (!matches(rule.pattern(), path)) continue;
                            for (String name : rule.owners()) {
                                if (owners.stream().anyMatch(owner -> owner.login().equals(name)) || claimed >= 5) continue;
                                String id = "OWNER-" + (owners.size() + 1);
                                owners.add(new Owner(name, "https://github.com/" + name, id));
                                evidence.add(new Evidence(id, "CODEOWNER", "CODEOWNERS 规则（声明范围，不代表已同意处理此 Issue）",
                                    key.url() + "/blob/" + head + "/.github/CODEOWNERS",
                                    path, rule.line(), rule.line(), "规则: " + rule.pattern() + "（第 " + rule.line() + " 行）\n匹配路径: " + path));
                                claimed++;
                            }
                        }
                    }
                    gaps.add("CODEOWNERS 只解析前 50 条规则与 @用户 条目；团队、邮箱条目与完整匹配语义未解析，候选不代表已同意处理此 Issue。");
                }
            } catch (ResponseStatusException error) { gaps.add("CODEOWNERS 未读取：" + error.getReason()); }
        }
        Instant now = Instant.now();
        Preview preview = new Preview(UUID.randomUUID(), repository, key.fullName(), issue, branch, head, now, now.plusSeconds(300), List.copyOf(evidence), List.copyOf(owners), List.copyOf(gaps), null);
        return new Prepared(preview, read.fingerprint() + (comments == null ? "" : ":" + comments.hash()));
    }

    private static boolean matches(String rule, String path) {
        if (rule.endsWith("/")) return path.startsWith(rule);
        if (rule.startsWith("*")) return path.endsWith(rule.substring(1));
        return path.equals(rule) || path.startsWith(rule + "/");
    }

    /** Bounded CODEOWNERS parse: at most 50 rules, @user entries only; null when the file does not exist. */
    private List<CodeownerRule> codeowners(Key key, String head) {
        JsonNode value;
        try { value = github.get("/repos/" + key.fullName() + "/contents/" + encode(".github/CODEOWNERS") + "?ref=" + head).data(); }
        catch (ResponseStatusException error) {
            if (error.getStatusCode().value() != 404) throw error;
            try { value = github.get("/repos/" + key.fullName() + "/contents/" + encode("CODEOWNERS") + "?ref=" + head).data(); }
            catch (ResponseStatusException inner) {
                if (inner.getStatusCode().value() != 404) throw inner;
                return null;
            }
        }
        if (!value.path("type").asText().equals("file") || !value.path("encoding").asText().equals("base64") || integer(value, "size") > 100_000) throw invalid();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value.path("content").asText().replace("\n", "").replace("\r", "")); } catch (IllegalArgumentException error) { throw invalid(); }
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); } catch (java.nio.charset.CharacterCodingException error) { throw invalid(); }
        var rules = new ArrayList<CodeownerRule>();
        int line = 0;
        for (String row : text.replace("\r\n", "\n").split("\n")) {
            line++;
            if (rules.size() == 50) break;
            String clean = row.strip();
            if (clean.isEmpty() || clean.startsWith("#")) continue;
            String[] tokens = clean.split("\\s+");
            if (tokens.length < 2 || !tokens[0].matches("[A-Za-z0-9_.*?/!+-]{1,200}")) continue;
            var owners = new ArrayList<String>();
            for (int i = 1; i < tokens.length && owners.size() < 5; i++) {
                String token = tokens[i];
                if (!token.startsWith("@") || token.contains("/")) continue;
                String name = token.substring(1);
                if (name.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}")) owners.add(name);
            }
            if (!owners.isEmpty()) rules.add(new CodeownerRule(tokens[0], line, List.copyOf(owners)));
        }
        return List.copyOf(rules);
    }
    private Evidence source(Key key, String head, String path, int focus, String id) {
        JsonNode value = github.get("/repos/" + key.fullName() + "/contents/" + Arrays.stream(path.split("/")).map(GithubIssueClient::encode).reduce((a,b) -> a + "/" + b).orElseThrow() + "?ref=" + head).data();
        if (!value.path("type").asText().equals("file") || !value.path("path").asText().equals(path) || !value.path("encoding").asText().equals("base64") || integer(value, "size") > 100_000) throw invalid();
        byte[] bytes;
        try { bytes = Base64.getDecoder().decode(value.path("content").asText().replace("\n", "").replace("\r", "")); } catch (IllegalArgumentException error) { throw invalid(); }
        if (bytes.length > 100_000 || bytes.length != integer(value, "size")) throw invalid();
        String text;
        try { text = StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes)).toString(); } catch (java.nio.charset.CharacterCodingException error) { throw invalid(); }
        List<String> rows = IssueEvidenceText.clean(text).lines().toList();
        if (rows.isEmpty() || focus > rows.size()) throw new ResponseStatusException(BAD_GATEWAY, "文件为空或正文行号不在此版本内。");
        int start = Math.max(1, focus - 5), end = Math.min(rows.size(), start + 39);
        String excerpt = String.join("\n", rows.subList(start - 1, end));
        while (excerpt.length() > 2500 && end > focus) { end--; excerpt = String.join("\n", rows.subList(start - 1, end)); }
        while (excerpt.length() > 2500 && start < focus) { start++; excerpt = String.join("\n", rows.subList(start - 1, end)); }
        if (excerpt.length() > 2500) throw new ResponseStatusException(BAD_GATEWAY, "源码行超过片段限额。");
        return new Evidence(id, "CODE", "固定提交的静态代码（未验证执行路径）", key.url() + "/blob/" + head + "/" + Arrays.stream(path.split("/")).map(GithubIssueClient::encode).reduce((a,b) -> a + "/" + b).orElseThrow() + "#L" + start,
            path, start, end, excerpt);
    }
    private static String login(JsonNode value) { String name = text(value, "login", 50); if (!name.matches("[A-Za-z0-9][A-Za-z0-9-]{0,38}(?:\\[bot\\])?")) throw invalid(); return name; }
    private static int integer(JsonNode node, String name) { if (!node.path(name).isIntegralNumber() || !node.path(name).canConvertToInt() || node.path(name).asInt() < 0) throw invalid(); return node.path(name).asInt(); }
    private static String sha(String value) { if (!value.matches("[a-f0-9]{40}")) throw invalid(); return value; }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    static boolean validPath(String path) { return path != null && path.length() <= 400 && path.endsWith(".java") && path.matches("[A-Za-z0-9_$./-]+") && !path.startsWith("/") && Arrays.stream(path.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals("..") || part.equals(".")); }
    private static String hash(String value) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); } catch (NoSuchAlgorithmException error) { throw new IllegalStateException(error); } }
}
