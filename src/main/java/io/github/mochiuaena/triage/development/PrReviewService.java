package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.PrModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.CONFLICT;

@Service
public class PrReviewService {
    private final GithubRepositoryStore repositories;
    private final GithubPrClient github;
    private final PrDiffAnalyzer analyzer;
    private final PrReportStore reports;
    public PrReviewService(GithubRepositoryStore repositories, GithubPrClient github, PrDiffAnalyzer analyzer, PrReportStore reports) {
        this.repositories = repositories; this.github = github; this.analyzer = analyzer; this.reports = reports;
    }
    public Context inspect(UUID repository, long number) { return github.pull(key(repository), number); }
    public List<Report> list(UUID repository) { repositories.require(repository); return reports.list(repository); }
    public Report get(UUID id) { return reports.require(id); }
    public synchronized Report create(UUID repository, long number, String base, String head) {
        Key key = key(repository);
        Context pull = github.pull(key, number); same(pull, base, head);
        Diff diff = github.diff(key, pull);
        Analysis analysis = analyzer.analyze(key, pull, diff);
        CiSnapshot ci = github.ci(key, pull);
        IssueSnapshot issues = github.issues(key, pull);
        same(github.pull(key, number), base, head);
        var gaps = new ArrayList<String>(analysis.gaps()); gaps.addAll(ci.gaps()); gaps.addAll(issues.gaps());
        Report report = new Report(UUID.randomUUID(), repository, key.fullName(), pull, diff.mergeBaseSha(), Instant.now(), "GITHUB_RULES",
            analysis.files(), analysis.findings(), ci, issues.issues(), List.copyOf(gaps),
            "本机差异规则检查，不调用模型。提醒需要人工核对；没有命中不代表没有风险，也不构成合入批准。CI 仅覆盖 head 提交上已读取的 Checks 和 Status，未核对分支保护或合并提交 CI。Issue 仅来自 PR 正文的同仓库明确引用。");
        try { reports.insert(report); }
        catch (org.springframework.dao.DataIntegrityViolationException error) { throw new ResponseStatusException(CONFLICT, "仓库登记已被移除，请重新选择仓库。"); }
        return report;
    }
    private static void same(Context pull, String base, String head) {
        if (!pull.baseSha().equals(base) || !pull.headSha().equals(head)) throw new ResponseStatusException(CONFLICT, "PR 提交版本已变化，请重新读取 PR 后审查。");
    }
    private Key key(UUID id) { var repo = repositories.require(id).registration(); return new Key(repo.owner(), repo.name()); }
}
