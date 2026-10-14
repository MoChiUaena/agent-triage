package io.github.mochiuaena.triage.development;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.PrModels.*;
import java.util.*;
import java.util.regex.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static io.github.mochiuaena.triage.development.GithubClient.*;

/** Versioned, read-only PR context. Uses the same bounded HTTP transport as CI. */
@Component
public class GithubPrClient {
    private static final Pattern LOCAL_ISSUE = Pattern.compile("(?<![\\p{L}\\p{N}_/\\-])#([1-9][0-9]{0,8})\\b");
    private final GithubClient github;
    public GithubPrClient(GithubClient github) { this.github = github; }

    public Context pull(Key key, long number) {
        if (number < 1 || number > Integer.MAX_VALUE) throw new IllegalArgumentException("Invalid PR number");
        JsonNode value = github.get("/repos/" + key.fullName() + "/pulls/" + number).data();
        if (integer(value, "number") != number || !key.fullName().equalsIgnoreCase(value.path("base").path("repo").path("full_name").asText())
            || !value.path("draft").isBoolean() || !Set.of("open", "closed").contains(value.path("state").asText())) throw invalid();
        String body = text(value, "body", 20_000);
        var references = new LinkedHashSet<Long>();
        Matcher local = LOCAL_ISSUE.matcher(body);
        while (local.find()) { references.add(Long.parseLong(local.group(1))); if (references.size() == 6) break; }
        boolean clipped = references.size() > 5 || value.path("body").asText().length() > 20_000;
        return new Context(number, clean(text(value, "title", 300)), key.url() + "/pull/" + number, text(value, "state", 20),
            value.path("draft").asBoolean(), sha(value.path("base")), sha(value.path("head")), integer(value, "changed_files"),
            integer(value, "additions"), integer(value, "deletions"), references.stream().limit(5).toList(), clipped);
    }

    Diff diff(Key key, Context pull) {
        // GitHub's first compare page contains the file list (at most 300), regardless of commit pagination.
        JsonNode value = github.get("/repos/" + key.fullName() + "/compare/" + pull.baseSha() + "..." + pull.headSha() + "?per_page=1").data();
        if (!sha(value.path("base_commit")).equals(pull.baseSha()) || !value.path("files").isArray()) throw invalid();
        String mergeBase = sha(value.path("merge_base_commit"));
        var files = new ArrayList<PatchFile>(); var gaps = new ArrayList<String>(); int characters = 0;
        for (var file : value.path("files")) {
            if (files.size() == 100) break;
            String path = file.path("filename").asText(), previous = file.path("previous_filename").asText("");
            if (!validPath(path) || !previous.isEmpty() && !validPath(previous)
                || !Set.of("added", "removed", "modified", "renamed", "copied", "changed", "unchanged").contains(file.path("status").asText())) throw invalid();
            String patch = file.path("patch").isTextual() ? file.path("patch").asText() : null;
            if (patch != null && (patch.length() > 60_000 || characters + patch.length() > 200_000)) {
                patch = null; gaps.add(clean(path) + "：补丁超过本次字符限额，未进行行级检查。");
            }
            if (patch != null) characters += patch.length();
            files.add(new PatchFile(path, previous, text(file, "status", 20), integer(file, "additions"), integer(file, "deletions"), patch));
        }
        if (files.size() != pull.changedFiles()) gaps.add("PR 标记 " + pull.changedFiles() + " 个文件，本次比较读取 " + files.size() + " 个；其余差异未覆盖（本机最多 100 个，GitHub compare 最多 300 个）。");
        return new Diff(mergeBase, List.copyOf(files), List.copyOf(gaps));
    }

    CiSnapshot ci(Key key, Context pull) {
        var checks = new ArrayList<CiCheck>(); var gaps = new ArrayList<String>();
        boolean available = false, statusAvailable = false, truncated = false;
        String root = "/repos/" + key.fullName() + "/commits/" + pull.headSha();
        String url = pull.url() + "/checks";
        try {
            var page = github.get(root + "/check-runs?per_page=50&filter=latest"); JsonNode values = page.data().path("check_runs");
            if (!values.isArray() || values.size() > 50) throw invalid();
            var selected = new ArrayList<CiCheck>();
            for (var check : values) {
                if (!sha(check.path("head_sha").asText()).equals(pull.headSha())) throw invalid();
                selected.add(new CiCheck(clean(text(check, "name", 200)), text(check, "status", 40), text(check, "conclusion", 40), url));
            }
            checks.addAll(selected); available = true;
            truncated |= page.truncated() || integer(page.data(), "total_count") > values.size();
        } catch (ResponseStatusException error) { gaps.add("提交 Checks 未完整读取：" + error.getReason()); }
        try {
            var page = github.get(root + "/status?per_page=50"); JsonNode values = page.data().path("statuses");
            if (!sha(page.data()).equals(pull.headSha()) || !values.isArray() || values.size() > 50) throw invalid();
            var selected = new ArrayList<CiCheck>();
            for (var status : values) {
                String state = text(status, "state", 40);
                selected.add(new CiCheck(clean(text(status, "context", 200)), state.equals("pending") ? "pending" : "completed", state, url));
            }
            checks.addAll(selected); statusAvailable = true;
            truncated |= page.truncated() || integer(page.data(), "total_count") > values.size();
        } catch (ResponseStatusException error) { gaps.add("提交 Status 未完整读取：" + error.getReason()); }
        if (truncated) gaps.add("提交 CI 只读取首批 Checks 和 Status，各最多 50 项。");
        String state = !available || !statusAvailable || truncated ? "INCOMPLETE"
            : checks.isEmpty() ? "NOT_REPORTED"
            : checks.stream().anyMatch(check -> Set.of("failure", "error", "timed_out", "cancelled", "action_required", "startup_failure").contains(check.conclusion())) ? "OBSERVED_FAILURE"
            : checks.stream().anyMatch(check -> !check.status().equals("completed")) ? "PENDING"
            : checks.stream().allMatch(check -> check.conclusion().equals("success")) ? "OBSERVED_SUCCESS" : "NO_SUCCESS_CONCLUSION";
        return new CiSnapshot(state, available, statusAvailable, truncated, List.copyOf(checks), List.copyOf(gaps));
    }

    IssueSnapshot issues(Key key, Context pull) {
        var issues = new ArrayList<IssueReference>(); var gaps = new ArrayList<String>();
        if (pull.issueReferencesTruncated()) gaps.add("PR 正文或 Issue 引用超过读取范围：只识别前 20,000 字符中的前五个同仓库 #编号。");
        for (long number : pull.issueNumbers()) {
            try {
                JsonNode issue = github.get("/repos/" + key.fullName() + "/issues/" + number).data();
                if (integer(issue, "number") != number) throw invalid();
                if (issue.has("pull_request")) { gaps.add("正文引用 #" + number + " 是 PR，未作为 Issue 关联。"); continue; }
                if (!Set.of("open", "closed").contains(issue.path("state").asText())) throw invalid();
                issues.add(new IssueReference(number, clean(text(issue, "title", 300)), text(issue, "state", 20), key.url() + "/issues/" + number));
            } catch (ResponseStatusException error) { gaps.add("正文引用 Issue #" + number + " 未读取：" + error.getReason()); }
        }
        return new IssueSnapshot(List.copyOf(issues), List.copyOf(gaps));
    }
    private static String sha(JsonNode value) { return sha(value.path("sha").asText()); }
    private static String sha(String value) { if (!value.matches("[a-f0-9]{40}")) throw invalid(); return value; }
    private static int integer(JsonNode value, String field) {
        JsonNode number = value.path(field);
        if (!number.isIntegralNumber() || !number.canConvertToInt() || number.asInt() < 0) throw invalid();
        return number.asInt();
    }
    private static boolean validPath(String value) {
        return !value.isEmpty() && value.length() <= 500 && !value.startsWith("/") && !value.contains("\\")
            && value.chars().noneMatch(Character::isISOControl) && Arrays.stream(value.split("/", -1)).noneMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."));
    }
    static String clean(String value) { return PrEvidenceRedactor.clean(value); }
}
