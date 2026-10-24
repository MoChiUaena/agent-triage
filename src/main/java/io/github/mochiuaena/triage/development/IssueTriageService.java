package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.IssueModels.*;
import io.github.mochiuaena.triage.source.SourceModels.Stored;
import io.github.mochiuaena.triage.source.SourceProjectService;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class IssueTriageService {
    private final GithubRepositoryStore repositories;
    private final GithubIssueClient github;
    private final IssueModelAnalyzer models;
    private final IssueReportStore reports;
    private final SourceProjectService sources;
    private final Map<UUID, Prepared> previews = new LinkedHashMap<>();
    public record SourceSelection(UUID sourceProjectId, Long sourceRevision) {}
    public IssueTriageService(GithubRepositoryStore repositories, GithubIssueClient github, IssueModelAnalyzer models, IssueReportStore reports, SourceProjectService sources) {
        this.repositories = repositories; this.github = github; this.models = models; this.reports = reports; this.sources = sources;
    }
    public Preview preview(UUID repository, long number, List<String> paths, SourceSelection selection) {
        Prepared prepared = github.prepare(repository, key(repository), number, paths == null ? List.of() : paths);
        Preview p = prepared.preview();
        var evidence = new ArrayList<>(p.evidence());
        var gaps = new ArrayList<>(p.gaps());
        if (selection != null && selection.sourceProjectId() != null) {
            evidence.addAll(symbolCandidates(repository, selection, p, gaps));
        }
        Preview result = new Preview(p.id(), repository, p.repository(), p.issue(), p.branch(), p.headSha(), p.readAt(), p.expiresAt(), List.copyOf(evidence), p.owners(), List.copyOf(gaps), models.option());
        synchronized (previews) {
            previews.values().removeIf(value -> value.preview().expiresAt().isBefore(Instant.now()));
            while (previews.size() >= 40) previews.remove(previews.keySet().iterator().next());
            previews.put(result.id(), new Prepared(result, prepared.fingerprint()));
        }
        return result;
    }
    public Report create(UUID repository, UUID previewId, boolean useModel) {
        Key key = key(repository); Prepared prepared;
        synchronized (previews) { prepared = previews.get(previewId); }
        if (prepared == null || !prepared.preview().repositoryId().equals(repository) || !prepared.preview().expiresAt().isAfter(Instant.now())) throw stale();
        Preview p = prepared.preview();
        if (!github.contextFingerprint(key, p.issue().number()).equals(prepared.fingerprint())) throw new ResponseStatusException(CONFLICT, "Issue 或评论内容已变化，请重新读取上下文后生成分诊报告。");
        Proposal proposal = local(p); ModelResult model = new ModelResult("SKIPPED", "", null, 0, null, null, 0, "本次未选择模型建议，没有发送上下文。");
        if (useModel) {
            if (!p.model().available()) throw new ResponseStatusException(CONFLICT, "预览时没有可用模型，请启用模型并重新读取上下文。");
            ModelAnalysis result = models.analyze(p); model = result.model(); if (result.proposal() != null) proposal = result.proposal();
        }
        var gaps = new ArrayList<String>(p.gaps());
        try { if (!github.contextFingerprint(key, p.issue().number()).equals(prepared.fingerprint())) gaps.add("生成期间 Issue 或评论内容发生变化。这份报告保留预览时的版本，请重新读取后核对。"); }
        catch (ResponseStatusException error) { gaps.add("生成后的 Issue 版本未能再次核对：" + error.getReason()); }
        Report report = new Report(UUID.randomUUID(), repository, p.repository(), p.issue(), p.branch(), p.headSha(), p.readAt(), Instant.now(), model.state().equals("SUCCEEDED") ? "MODEL" : "LOCAL_RULES", proposal, model, p.evidence(), p.owners(), List.copyOf(gaps));
        try { reports.insert(report); } catch (org.springframework.dao.DataIntegrityViolationException error) { throw new ResponseStatusException(CONFLICT, "仓库登记已被移除，请重新选择仓库。"); }
        return report;
    }
    public List<Report> list(UUID repository) { repositories.require(repository); return reports.list(repository); }
    public Report get(UUID id) { return reports.require(id); }
    private Proposal local(Preview p) {
        Set<String> labels = new HashSet<>(p.issue().labels().stream().map(value -> value.toLowerCase(Locale.ROOT)).toList());
        var categories = new LinkedHashSet<String>();
        if (labels.contains("bug") || labels.contains("type: bug")) categories.add("BUG");
        if (labels.contains("enhancement") || labels.contains("type: enhancement")) categories.add("FEATURE_REQUEST");
        if (labels.contains("question")) categories.add("QUESTION"); if (labels.contains("documentation")) categories.add("DOCUMENTATION");
        String category = categories.size() == 1 ? categories.iterator().next() : "UNKNOWN";
        String priority = "UNSPECIFIED"; for (String value : List.of("P1", "P2", "P3")) if (labels.contains(value.toLowerCase(Locale.ROOT))) { priority = value; break; }
        return new Proposal("已整理 Issue 描述与所选仓库上下文，尚未验证复现过程或根因。", category, priority,
            "分类只参考已读取的 GitHub 标签；没有明确优先级标签时保持待定。", List.of("ISSUE-1"),
            List.of("确认受影响版本、运行环境与实际影响范围。", "补充最小复现、预期行为和实际行为；核对后续评论是否已有结论。"),
            List.of(new Action("核对报告者描述并复现问题，记录预期与实际结果。", List.of(p.evidence().stream().anyMatch(e -> e.id().equals("ISSUE-2")) ? "ISSUE-2" : "ISSUE-1")),
                new Action("结合已读取代码与提交核查候选路径，确认后再决定修复或调整需求。", List.of("ISSUE-1"))), List.of());
    }
    private Key key(UUID id) { var saved = repositories.require(id).registration(); return new Key(saved.owner(), saved.name()); }
    private static ResponseStatusException stale() { return new ResponseStatusException(CONFLICT, "Issue 预览已过期或不属于当前仓库，请重新读取；预览在重启后失效。"); }

    /** Bounded symbol search over the user-selected local index; static candidates only. */
    private List<Evidence> symbolCandidates(UUID repository, SourceSelection selection, Preview p, List<String> gaps) {
        if (selection.sourceRevision() == null)
            throw new ResponseStatusException(CONFLICT, "请提供所选源码项目的索引 revision；索引已变化时须重新选择。");
        Stored project = sources.stored(selection.sourceProjectId());
        if (project == null) throw new ResponseStatusException(NOT_FOUND, "已登记源码项目不存在。");
        if (project.revision() != selection.sourceRevision())
            throw new ResponseStatusException(CONFLICT, "源码索引已更新（revision " + project.revision() + "），请刷新后使用当前版本重试；旧版本上下文不再采用。");
        Set<String> seen = new HashSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_$]{2,30}").matcher(p.issue().title());
        while (matcher.find() && seen.size() < 6) {
            String word = matcher.group();
            if (word.chars().anyMatch(c -> Character.isUpperCase(c)) || word.contains("_")) seen.add(word);
        }
        for (Evidence item : p.evidence()) {
            if (!item.kind().equals("ISSUE") && !item.kind().equals("COMMENT")) continue;
            matcher = java.util.regex.Pattern.compile("[A-Za-z][A-Za-z0-9_$]{2,30}").matcher(item.text());
            while (matcher.find() && seen.size() < 6) {
                String word = matcher.group();
                if (word.chars().anyMatch(c -> Character.isUpperCase(c)) || word.contains("_")) seen.add(word);
            }
            if (seen.size() >= 6) break;
        }
        var evidence = new ArrayList<Evidence>();
        int index = 0;
        Set<String> visited = new HashSet<>();
        for (String token : List.copyOf(seen)) {
            try {
                for (io.github.mochiuaena.triage.source.SourceModels.Excerpt excerpt : sources.search(project, token)) {
                    if (evidence.size() >= 6) return evidence;
                    if (!visited.add(excerpt.id())) continue;
                    evidence.add(new Evidence("SYMBOL-" + (++index), "SYMBOL", "本机索引静态候选（版本未与提交核对，不证明执行路径）",
                        "", excerpt.path(), excerpt.startLine(), excerpt.endLine(), excerpt.content()));
                }
            } catch (ResponseStatusException error) { gaps.add("符号候选未读取（" + token + "）：" + error.getReason()); }
        }
        gaps.add("符号候选来自所选本机索引（revision " + project.revision() + "），静态参考；索引版本未与任何提交核对，不证明执行路径。");
        return evidence;
    }
}
