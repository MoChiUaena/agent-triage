package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.WorkflowModels.*;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Component
public class WorkflowPlanner {
    private final GithubRepositoryStore repositories;
    private final PrReviewService prs;
    private final IssueTriageService issues;
    private final CiTriageService ci;
    private final Map<UUID, Plan> plans = new LinkedHashMap<>();
    public WorkflowPlanner(GithubRepositoryStore repositories, PrReviewService prs, IssueTriageService issues, CiTriageService ci) {
        this.repositories = repositories; this.prs = prs; this.issues = issues; this.ci = ci;
    }
    public Plan create(UUID repository, long prNumber, Long issueNumber, UUID issueReport, UUID ciReport) {
        var registration = repositories.require(repository).registration();
        var pull = prs.inspect(repository, prNumber); String relation = "NONE";
        if (issueReport != null) {
            var report = issues.get(issueReport);
            if (!report.repositoryId().equals(repository) || issueNumber != null && issueNumber != report.issue().number()) throw new ResponseStatusException(BAD_REQUEST, "Issue 报告不属于所选仓库或编号。");
            issueNumber = report.issue().number(); relation = "SAVED_REPORT";
        } else if (issueNumber != null) relation = "SELECTED";
        else if (!pull.issueNumbers().isEmpty()) { issueNumber = pull.issueNumbers().getFirst(); relation = "PR_REFERENCE"; }
        if (ciReport != null && !ci.get(ciReport).repositoryId().equals(repository)) throw new ResponseStatusException(BAD_REQUEST, "CI 报告不属于所选仓库。");
        Instant now = Instant.now();
        Plan plan = new Plan(UUID.randomUUID(), repository, registration.owner() + "/" + registration.name(), pull, issueNumber, relation, issueReport, ciReport,
            now, now.plusSeconds(300), List.of(
                new Spec("REPOSITORY", "读取仓库概览", List.of(), List.of()),
                new Spec("PR", "审查固定 PR 版本", List.of("REPOSITORY"), List.of()),
                new Spec("ISSUE", "Issue 分诊", relation.equals("PR_REFERENCE") ? List.of("REPOSITORY", "PR") : List.of("REPOSITORY"), List.of()),
                new Spec("CI", "排查同一提交的 CI", List.of("PR"), List.of()),
                new Spec("CHECK", "核对证据与版本", List.of(), List.of("REPOSITORY", "PR", "ISSUE", "CI")),
                new Spec("RECHECK", "一次有界补查", List.of(), List.of("CHECK")),
                new Spec("SUMMARY", "生成综合报告", List.of(), List.of("RECHECK"))),
            "固定 PR base/head；自动 Issue 只取正文首个同仓库明确引用。新分诊使用本机规则，已有模型报告只作为保存的材料复用，不发起新的模型调用。最多两个并发工作流、三分钟执行与一次补查，不修改 GitHub。");
        synchronized (plans) {
            plans.values().removeIf(value -> !value.expiresAt().isAfter(now));
            while (plans.size() >= 40) plans.remove(plans.keySet().iterator().next());
            plans.put(plan.id(), plan);
        }
        return plan;
    }
    Plan require(UUID repository, UUID id) {
        repositories.require(repository); Plan plan;
        synchronized (plans) { plan = plans.get(id); }
        if (plan == null || !plan.repositoryId().equals(repository) || !plan.expiresAt().isAfter(Instant.now())) throw new ResponseStatusException(CONFLICT, "工作流计划已过期或不属于所选仓库，请重新读取计划。");
        return plan;
    }
}
