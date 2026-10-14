package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.IssueModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class IssueTriageService {
    private final GithubRepositoryStore repositories;
    private final GithubIssueClient github;
    private final IssueModelAnalyzer models;
    private final IssueReportStore reports;
    private final Map<UUID, Prepared> previews = new LinkedHashMap<>();
    public IssueTriageService(GithubRepositoryStore repositories, GithubIssueClient github, IssueModelAnalyzer models, IssueReportStore reports) {
        this.repositories = repositories; this.github = github; this.models = models; this.reports = reports;
    }
    public Preview preview(UUID repository, long number, List<String> paths) {
        Prepared prepared = github.prepare(repository, key(repository), number, paths == null ? List.of() : paths);
        Preview p = prepared.preview();
        Preview result = new Preview(p.id(), repository, p.repository(), p.issue(), p.branch(), p.headSha(), p.readAt(), p.expiresAt(), p.evidence(), p.owners(), p.gaps(), models.option());
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
        if (!github.issue(key, p.issue().number()).fingerprint().equals(prepared.fingerprint())) throw new ResponseStatusException(CONFLICT, "Issue 内容已变化，请重新读取上下文后生成分诊报告。");
        Proposal proposal = local(p); ModelResult model = new ModelResult("SKIPPED", "", null, 0, null, null, 0, "本次未选择模型建议，没有发送上下文。");
        if (useModel) {
            if (!p.model().available()) throw new ResponseStatusException(CONFLICT, "预览时没有可用模型，请启用模型并重新读取上下文。");
            ModelAnalysis result = models.analyze(p); model = result.model(); if (result.proposal() != null) proposal = result.proposal();
        }
        var gaps = new ArrayList<String>(p.gaps());
        try { if (!github.issue(key, p.issue().number()).fingerprint().equals(prepared.fingerprint())) gaps.add("生成期间 Issue 内容发生变化。这份报告保留预览时的版本，请重新读取后核对。"); }
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
}
