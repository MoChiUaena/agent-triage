package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.WorkflowModels.*;
import java.util.*;
import org.springframework.stereotype.Component;

@Component
public class WorkflowPolicy {
    static Reference pr(PrModels.Report report) { return new Reference("PR", report.id(), "PR #" + report.pull().number(), report.pull().headSha()); }
    static Reference issue(IssueModels.Report report) { return new Reference("ISSUE", report.id(), "Issue #" + report.issue().number(), report.headSha()); }
    static Reference ci(CiModels.Report report) { return new Reference("CI", report.id(), report.workflowName() + " / " + report.job().name(), report.headSha()); }
    List<Check> check(Plan plan, Data data, PrModels.Context current, IssueModels.Issue currentIssue) {
        var checks = new ArrayList<Check>();
        if (data.pr() == null) checks.add(new Check("PR_UNAVAILABLE", "GAP", "没有固定版本的 PR 报告，无法据此检查 PR 风险。", List.of("PR")));
        else {
            if (current == null) checks.add(new Check("CHECK_READ_FAILED", "GAP", "未能再次读取 PR 版本，不能确认材料仍适用于当前 PR。", List.of("PR")));
            else if (!current.baseSha().equals(plan.pull().baseSha()) || !current.headSha().equals(plan.pull().headSha())) checks.add(new Check("PR_VERSION_CHANGED", "CONFLICT", "PR base/head 已变化，当前材料只能用于旧版本。请重新生成计划。", List.of("PR")));
            if (data.ci() != null && data.ci().report() != null) {
                var report = data.ci().report();
                if (!report.headSha().equals(plan.pull().headSha())) checks.add(new Check("CI_HEAD_MISMATCH", "CONFLICT", "CI 报告来自其他提交，不能用于所选 PR。", List.of("PR", "CI")));
                if (report.source().equals("PASTED")) checks.add(new Check("CI_PASTED_ORIGIN", "GAP", "粘贴日志与 GitHub 作业关系未验证，不能作为自动核验通过或失败的依据。", List.of("CI")));
                if (data.pr().ci().state().equals("OBSERVED_SUCCESS") && report.headSha().equals(plan.pull().headSha())) checks.add(new Check("CI_STATUS_DISAGREEMENT", "CONFLICT", "PR 已读取检查为成功，但存在同提交的失败日志；需核对尝试次数和读取时点。", List.of("PR", "CI")));
            }
            if (data.ci() == null) checks.add(new Check("CI_UNAVAILABLE", "GAP", "CI 排障未完成，不能把缺失材料当作没有失败。", List.of("CI")));
        }
        if (data.issue() != null) {
            if (currentIssue != null && !currentIssue.equals(data.issue().issue())) checks.add(new Check("ISSUE_SNAPSHOT_STALE", "CONFLICT", "Issue 元数据已更新，旧分诊报告需要重新核对。", List.of("ISSUE")));
            if (!plan.pull().issueNumbers().contains(data.issue().issue().number())) checks.add(new Check("ISSUE_RELATION_UNVERIFIED", "NOTE", "所选 Issue 是补充材料，PR 正文未明确引用它。", List.of("PR", "ISSUE")));
            if (!data.issue().headSha().isEmpty() && !data.issue().headSha().equals(plan.pull().headSha())) checks.add(new Check("ISSUE_CODE_VERSION_DIFFERENT", "NOTE", "Issue 上下文与 PR 使用不同提交，未把 Issue 的静态代码当作 PR 修改后的证据。", List.of("PR", "ISSUE")));
        }
        return List.copyOf(checks);
    }
    Summary summarize(Data data, List<Task> tasks) {
        var gaps = new LinkedHashSet<String>(); var actions = new LinkedHashSet<String>(); var references = new ArrayList<Reference>();
        if (data.repository() != null) gaps.addAll(data.repository().gaps());
        boolean prApplicable = data.pr() != null && data.checks().stream().noneMatch(check -> check.code().equals("PR_VERSION_CHANGED") || check.code().equals("CHECK_READ_FAILED") && !check.state().equals("RESOLVED"));
        boolean ciApplicable = prApplicable && data.ci() != null && data.ci().state().equals("ANALYZED") && data.ci().report() != null && data.ci().report().headSha().equals(data.pr().pull().headSha()) && data.ci().report().source().equals("GITHUB")
            && data.checks().stream().noneMatch(check -> check.code().startsWith("CI_") && Set.of("GAP", "CONFLICT").contains(check.state()));
        if (data.pr() != null) { gaps.addAll(data.pr().gaps()); if (prApplicable) { references.add(pr(data.pr())); data.pr().findings().forEach(finding -> actions.add(finding.file() + ":" + finding.line() + " · " + finding.check())); } }
        if (data.issue() != null) { gaps.addAll(data.issue().gaps()); if (data.checks().stream().noneMatch(check -> check.code().startsWith("ISSUE_") && Set.of("GAP", "CONFLICT").contains(check.state()))) { references.add(issue(data.issue())); data.issue().proposal().actions().forEach(action -> actions.add((data.issue().source().equals("MODEL") ? "Issue 历史模型建议（待确认）：" : "Issue 基础分诊：") + action.text())); } }
        if (data.ci() != null) {
            gaps.addAll(data.ci().gaps());
            if (ciApplicable) {
                references.add(ci(data.ci().report())); actions.addAll(data.ci().report().analysis().checks());
            }
        }
        tasks.stream().filter(task -> Set.of("FAILED", "PARTIAL").contains(task.state())).forEach(task -> gaps.add(task.title() + "：" + task.message()));
        data.checks().stream().filter(check -> Set.of("GAP", "CONFLICT").contains(check.state())).forEach(check -> gaps.add(check.message()));
        boolean replan = data.checks().stream().anyMatch(check -> check.code().equals("PR_VERSION_CHANGED")) || tasks.stream().anyMatch(task -> task.id().equals("PR") && task.errorCode().equals("UPSTREAM_409"));
        boolean incomplete = data.pr() == null || data.ci() == null || !Set.of("ANALYZED", "NO_FAILED_RUNS").contains(data.ci().state())
            || data.pr() != null && Set.of("INCOMPLETE", "PENDING", "NOT_REPORTED", "NO_SUCCESS_CONCLUSION").contains(data.pr().ci().state())
            || data.checks().stream().anyMatch(check -> Set.of("GAP", "CONFLICT").contains(check.state())) || tasks.stream().anyMatch(task -> task.state().equals("FAILED"));
        boolean attention = data.pr() != null && (!data.pr().findings().isEmpty() || data.pr().ci().state().equals("OBSERVED_FAILURE"))
            || ciApplicable;
        String verdict = replan ? "REPLAN_REQUIRED" : incomplete ? "INCOMPLETE" : attention ? "ATTENTION_REQUIRED" : "REVIEW_REMAINING";
        String text = switch (verdict) { case "REPLAN_REQUIRED" -> "PR 版本发生变化，请重新生成计划；本次报告保留旧版本材料。"; case "INCOMPLETE" -> "部分任务或证据核验未完成，现有材料不足以给出完整判断。"; case "ATTENTION_REQUIRED" -> "已读取材料包含失败检查或变更提醒，请先核对相关证据与行动项。"; default -> "已完成所选范围的材料整理，仍需人工核对代码、测试覆盖与合入条件。"; };
        if (actions.isEmpty()) actions.add("核对读取范围、当前 PR 版本与 GitHub 上的完整检查。");
        return new Summary(verdict, text, actions.stream().limit(12).toList(), gaps.stream().limit(30).toList(), List.copyOf(references),
            "按固定模板组合现有只读分析，本次未新调用模型。综合报告保留证据核验状态，使用补查后的适用来源；任务明细保留原始结果，仍需人工核对合入条件。");
    }
}
