package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.WorkflowModels.*;
import java.util.*;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class WorkflowTasks {
    private final GithubClient github;
    private final PrReviewService prs;
    private final IssueTriageService issues;
    private final GithubIssueClient issueReader;
    private final CiTriageService ci;
    private final WorkflowCiReader ciReader;
    private final WorkflowPolicy policy;
    public WorkflowTasks(GithubClient github, PrReviewService prs, IssueTriageService issues, GithubIssueClient issueReader,
                         CiTriageService ci, WorkflowCiReader ciReader, WorkflowPolicy policy) {
        this.github = github; this.prs = prs; this.issues = issues; this.issueReader = issueReader; this.ci = ci; this.ciReader = ciReader; this.policy = policy;
    }
    Result execute(String task, Plan plan, Data data, List<Task> previous) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        Key key = Key.parse(plan.repository());
        return switch (task) {
            case "REPOSITORY" -> {
                var health = github.read(key); var gaps = new ArrayList<String>();
                if (!health.issues().available()) gaps.add("Issue 概览：" + health.issues().message());
                if (!health.pullRequests().available()) gaps.add("PR 概览：" + health.pullRequests().message());
                if (!health.workflows().available()) gaps.add("默认分支 CI 概览：" + health.workflows().message());
                var context = new RepositoryContext(key.fullName(), GithubPrClient.clean(health.repository().defaultBranch()), health.checkedAt(),
                    health.issues().available() ? health.issues().items().size() : null, health.pullRequests().available() ? health.pullRequests().items().size() : null,
                    health.workflows().available() ? (int) health.workflows().items().stream().filter(run -> run.status().equals("completed") && Set.of("failure", "timed_out", "action_required", "startup_failure").contains(run.conclusion())).count() : null, List.copyOf(gaps));
                yield result(gaps.isEmpty() ? "SUCCEEDED" : "PARTIAL", "已读取仓库概览，列表仍受抽样范围限制。", data.repository(context));
            }
            case "PR" -> {
                var report = prs.create(plan.repositoryId(), plan.pull().number(), plan.pull().baseSha(), plan.pull().headSha());
                yield new Result("SUCCEEDED", "已保存固定版本的 PR 审查报告。", data.pr(report), List.of(WorkflowPolicy.pr(report)));
            }
            case "ISSUE" -> {
                if (plan.issueNumber() == null) yield result("SKIPPED", "没有所选或明确引用的同仓库 Issue。", data);
                if (plan.issueRelation().equals("PR_REFERENCE") && !data.pr().pull().issueNumbers().contains(plan.issueNumber())) yield result("SKIPPED", "执行时的 PR 正文不再引用该 Issue，未将其自动关联。", data);
                var report = plan.issueReportId() == null ? issues.create(plan.repositoryId(), issues.preview(plan.repositoryId(), plan.issueNumber(), List.of()).id(), false) : issues.get(plan.issueReportId());
                if (!report.repositoryId().equals(plan.repositoryId())) throw new IllegalArgumentException("Foreign Issue report");
                yield new Result(report.gaps().isEmpty() ? "SUCCEEDED" : "PARTIAL", plan.issueReportId() == null ? "已保存基础分诊，没有调用模型。" : "复用已保存的分诊材料，没有重新发送模型数据。", data.issue(report), List.of(WorkflowPolicy.issue(report)));
            }
            case "CI" -> {
                CiObservation observation;
                if (plan.ciReportId() != null) {
                    var report = ci.get(plan.ciReportId()); if (!report.repositoryId().equals(plan.repositoryId())) throw new IllegalArgumentException("Foreign CI report");
                    observation = new CiObservation("REUSED", report, "SAVED_REPORT", 0, false, List.of());
                } else observation = ciReader.read(plan.repositoryId(), key, plan.pull().headSha());
                yield new Result(Set.of("ANALYZED", "NO_FAILED_RUNS", "REUSED").contains(observation.state()) ? "SUCCEEDED" : "PARTIAL", ciMessage(observation), data.ci(observation), observation.report() == null ? List.of() : List.of(WorkflowPolicy.ci(observation.report())));
            }
            case "CHECK" -> {
                PrModels.Context current = null; IssueModels.Issue currentIssue = null; var extra = new ArrayList<Check>();
                if (data.pr() != null) try { current = prs.inspect(plan.repositoryId(), plan.pull().number()); } catch (ResponseStatusException error) { extra.add(new Check("CHECK_READ_FAILED", "GAP", error.getReason(), List.of("PR"))); }
                if (data.issue() != null) try { currentIssue = issueReader.issue(key, data.issue().issue().number()).issue(); } catch (ResponseStatusException error) { extra.add(new Check("ISSUE_CHECK_UNAVAILABLE", "GAP", "Issue 版本未核对：" + error.getReason(), List.of("ISSUE"))); }
                var checks = new ArrayList<>(policy.check(plan, data, current, currentIssue)); checks.addAll(extra);
                CiObservation observation = data.ci();
                if (observation != null && observation.report() != null && observation.report().headSha().equals(plan.pull().headSha())) {
                    var report = observation.report();
                    try {
                        var live = ci.inspect(plan.repositoryId(), report.runId());
                        if (live.attempt() != report.attempt()) checks.add(new Check("CI_ATTEMPT_CHANGED", "CONFLICT", "CI 已有新的运行尝试，旧日志不能代表最新状态。", List.of("CI")));
                        else if (!live.headSha().equals(report.headSha()) || live.jobs().stream().noneMatch(job -> job.id() == report.job().id() && job.runId() == report.runId() && job.attempt() == report.attempt() && job.headSha().equals(report.headSha()) && job.status().equals("completed") && Set.of("failure", "timed_out", "action_required", "startup_failure").contains(job.conclusion())))
                            checks.add(new Check("CI_JOB_UNVERIFIED", "GAP", "当前作业范围未核验到这份失败报告，不能作为适用来源。", List.of("CI")));
                        else if (report.source().equals("GITHUB") && checks.stream().noneMatch(check -> check.code().startsWith("CI_") && Set.of("CONFLICT", "GAP").contains(check.state())))
                            observation = new CiObservation("ANALYZED", report, observation.source().equals("SAVED_REPORT") ? "SAVED_REPORT_VALIDATED" : observation.source(), observation.runsRead(), observation.truncated() || live.truncated(), observation.gaps().stream().filter(text -> !text.contains("尚需核对提交")).toList());
                    }
                    catch (ResponseStatusException error) { checks.add(new Check("CI_ATTEMPT_UNVERIFIED", "GAP", "CI 最新尝试未核对：" + error.getReason(), List.of("CI"))); }
                }
                yield result(checks.stream().anyMatch(check -> Set.of("GAP", "CONFLICT").contains(check.state())) ? "PARTIAL" : "SUCCEEDED", "已核对 PR 与 Issue 元数据、CI 提交、来源和尝试；未核对完整 Issue 正文或评论。", data.ci(observation).checks(List.copyOf(checks)));
            }
            case "RECHECK" -> recheck(plan, key, data);
            case "SUMMARY" -> result("SUCCEEDED", "综合报告已生成，保留任务失败、缺口和证据来源。", data.summary(policy.summarize(data, previous)));
            default -> throw new IllegalArgumentException("Unknown workflow task");
        };
    }
    private Result recheck(Plan plan, Key key, Data data) {
        Set<String> codes = new HashSet<>(data.checks().stream().filter(check -> Set.of("GAP", "CONFLICT").contains(check.state())).map(Check::code).toList());
        if (data.pr() == null || Collections.disjoint(codes, Set.of("PR_VERSION_CHANGED", "CHECK_READ_FAILED", "CI_HEAD_MISMATCH", "CI_PASTED_ORIGIN", "CI_STATUS_DISAGREEMENT", "CI_ATTEMPT_CHANGED", "CI_ATTEMPT_UNVERIFIED", "CI_JOB_UNVERIFIED", "ISSUE_SNAPSHOT_STALE"))) return result("SKIPPED", "没有可进行有界补查的证据冲突。", data);
        var current = prs.inspect(plan.repositoryId(), plan.pull().number());
        if (!current.headSha().equals(plan.pull().headSha()) || !current.baseSha().equals(plan.pull().baseSha())) {
            var checks = new ArrayList<>(data.checks());
            if (checks.stream().noneMatch(check -> check.code().equals("PR_VERSION_CHANGED"))) checks.add(new Check("PR_VERSION_CHANGED", "CONFLICT", "补查确认 PR base/head 已更新，请重新生成计划；旧版本材料不用于当前判断。", List.of("PR")));
            return result("PARTIAL", "补查确认 PR 已更新，需要重新生成计划；没有分析新提交或混用旧材料。", data.checks(List.copyOf(checks)));
        }
        var pr = data.pr(); var observation = data.ci(); var checks = new ArrayList<Check>();
        boolean refreshCi = codes.stream().anyMatch(code -> code.startsWith("CI_"));
        if (refreshCi) { pr = prs.create(plan.repositoryId(), plan.pull().number(), current.baseSha(), current.headSha()); observation = ciReader.read(plan.repositoryId(), key, current.headSha()); }
        boolean ciReplacementVerified = refreshCi && observation != null && Set.of("ANALYZED", "NO_FAILED_RUNS").contains(observation.state())
            && Set.of("OBSERVED_SUCCESS", "OBSERVED_FAILURE").contains(pr.ci().state());
        for (Check check : data.checks()) {
            boolean resolved = check.code().equals("CHECK_READ_FAILED") || check.code().equals("CI_HEAD_MISMATCH") && observation != null && (observation.report() == null || observation.report().headSha().equals(current.headSha()))
                || check.code().equals("CI_PASTED_ORIGIN") && observation != null && observation.source().equals("GITHUB")
                || check.code().equals("CI_ATTEMPT_CHANGED") && refreshCi && observation != null && Set.of("ANALYZED", "NO_FAILED_RUNS").contains(observation.state())
                || Set.of("CI_ATTEMPT_UNVERIFIED", "CI_JOB_UNVERIFIED").contains(check.code()) && ciReplacementVerified
                || check.code().equals("CI_STATUS_DISAGREEMENT") && (pr.ci().state().equals("OBSERVED_FAILURE") || pr.ci().state().equals("OBSERVED_SUCCESS") && observation != null && observation.state().equals("NO_FAILED_RUNS") && observation.report() == null);
            checks.add(resolved ? new Check(check.code(), "RESOLVED", check.message() + " 已补查同一 PR 提交，旧来源不再用于综合结论。", check.sources()) : check);
        }
        if (observation != null && observation.report() != null && pr.ci().state().equals("OBSERVED_SUCCESS") && checks.stream().noneMatch(check -> check.code().equals("CI_STATUS_DISAGREEMENT") && check.state().equals("CONFLICT")))
            checks.add(new Check("CI_STATUS_DISAGREEMENT", "CONFLICT", "补查后检查成功与失败日志仍不一致，需要在 GitHub 核对，不再循环查询。", List.of("PR", "CI")));
        if (codes.contains("ISSUE_SNAPSHOT_STALE")) { issueReader.issue(key, data.issue().issue().number()); checks.add(new Check("ISSUE_NEW_PREVIEW_REQUIRED", "GAP", "补查确认 Issue 可读取，请创建新的分诊预览；没有自动重发模型上下文。", List.of("ISSUE"))); }
        Data updated = data.rechecked(pr, observation, List.copyOf(checks));
        return result(checks.stream().anyMatch(check -> Set.of("GAP", "CONFLICT").contains(check.state())) ? "PARTIAL" : "SUCCEEDED", "已完成一次补查；保留原始报告与补查后的适用来源，不再重试。", updated);
    }
    private static String ciMessage(CiObservation value) { return switch(value.state()) { case "ANALYZED" -> "已分析同一提交的一个失败作业。"; case "NO_FAILED_RUNS" -> "读取范围没有已结束的失败运行，不代表所有 CI 通过。"; case "PENDING" -> "还有运行未结束，暂不能给出完整 CI 判断。"; case "REUSED" -> "已载入旧 CI 报告，等待证据核验。"; default -> "没有足够的同提交 CI 作业材料，请核对读取范围。"; }; }
    private static Result result(String state, String message, Data data) { return new Result(state, message, data, List.of()); }
}
