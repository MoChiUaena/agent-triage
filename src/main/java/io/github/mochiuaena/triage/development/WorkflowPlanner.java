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
        var pull = prs.inspect(repository, prNumber);
        Resolved resolved = resolve(repository, pull, issueNumber, issueReport, ciReport);
        Instant now = Instant.now();
        Plan plan = new Plan(UUID.randomUUID(), repository, resolved.registration().owner() + "/" + resolved.registration().name(), pull, resolved.issueNumber(), resolved.relation(), issueReport, ciReport,
            now, now.plusSeconds(300), List.of(
                new Spec("REPOSITORY", "读取仓库概览", List.of(), List.of()),
                new Spec("PR", "审查固定 PR 版本", List.of("REPOSITORY"), List.of()),
                new Spec("ISSUE", "Issue 分诊", resolved.relation().equals("PR_REFERENCE") ? List.of("REPOSITORY", "PR") : List.of("REPOSITORY"), List.of()),
                new Spec("CI", "排查同一提交的 CI", List.of("PR"), List.of()),
                new Spec("CHECK", "核对证据与版本", List.of(), List.of("REPOSITORY", "PR", "ISSUE", "CI")),
                new Spec("RECHECK", "一次有界补查", List.of(), List.of("CHECK")),
                new Spec("SUMMARY", "生成综合报告", List.of(), List.of("RECHECK"))),
            "固定 PR base/head；自动 Issue 只取正文首个同仓库明确引用。新分诊使用本机规则，已有模型报告只作为保存的材料复用，不发起新的模型调用。最多两个并发工作流、三分钟执行与一次补查，不修改 GitHub。");
        return cache(plan);
    }

    /** Custom template plan: the same frozen PR/issue resolution, but the task list comes from the saved template. */
    public Plan createCustom(UUID repository, WorkflowModels.Template template, long prNumber, Long issueNumber, UUID issueReport, UUID ciReport) {
        var has = template.tasks().stream().map(Spec::id).toList();
        if (ciReport != null && !has.contains("CI"))
            throw new ResponseStatusException(BAD_REQUEST, "模板不包含 CI 任务，不能携带 CI 报告材料。");
        if ((issueNumber != null || issueReport != null) && !has.contains("ISSUE"))
            throw new ResponseStatusException(BAD_REQUEST, "模板不包含 ISSUE 任务，不能携带 Issue 材料或编号。");
        var pull = prs.inspect(repository, prNumber);
        Resolved resolved = resolve(repository, pull, issueNumber, issueReport, ciReport);
        Instant now = Instant.now();
        String tasks = String.join(" → ", has);
        Plan plan = new Plan(UUID.randomUUID(), repository, resolved.registration().owner() + "/" + resolved.registration().name(), pull, resolved.issueNumber(), resolved.relation(), issueReport, ciReport,
            now, now.plusSeconds(300), template.tasks(),
            "自定义模板「" + template.name() + "」：" + tasks + "。任务列表在创建计划时冻结，模板后续修改不影响本次执行。沿用固定边界：固定 PR base/head、最多两个并发工作流、三分钟执行与一次补查，只读分析，不修改 GitHub、不调用模型。");
        return cache(plan);
    }

    private Resolved resolve(UUID repository, PrModels.Context pull, Long issueNumber, UUID issueReport, UUID ciReport) {
        var registration = repositories.require(repository).registration();
        String relation = "NONE";
        if (issueReport != null) {
            var report = issues.get(issueReport);
            if (!report.repositoryId().equals(repository) || issueNumber != null && issueNumber != report.issue().number()) throw new ResponseStatusException(BAD_REQUEST, "Issue 报告不属于所选仓库或编号。");
            issueNumber = report.issue().number(); relation = "SAVED_REPORT";
        } else if (issueNumber != null) relation = "SELECTED";
        else if (!pull.issueNumbers().isEmpty()) { issueNumber = pull.issueNumbers().getFirst(); relation = "PR_REFERENCE"; }
        if (ciReport != null && !ci.get(ciReport).repositoryId().equals(repository)) throw new ResponseStatusException(BAD_REQUEST, "CI 报告不属于所选仓库。");
        return new Resolved(registration, issueNumber, relation);
    }

    private Plan cache(Plan plan) {
        Instant now = Instant.now();
        synchronized (plans) {
            plans.values().removeIf(value -> !value.expiresAt().isAfter(now));
            while (plans.size() >= 40) plans.remove(plans.keySet().iterator().next());
            plans.put(plan.id(), plan);
        }
        return plan;
    }

    private record Resolved(GithubModels.Registration registration, Long issueNumber, String relation) {}
    Plan require(UUID repository, UUID id) {
        repositories.require(repository); Plan plan;
        synchronized (plans) { plan = plans.get(id); }
        if (plan == null || !plan.repositoryId().equals(repository) || !plan.expiresAt().isAfter(Instant.now())) throw new ResponseStatusException(CONFLICT, "工作流计划已过期或不属于所选仓库，请重新读取计划。");
        return plan;
    }
}
