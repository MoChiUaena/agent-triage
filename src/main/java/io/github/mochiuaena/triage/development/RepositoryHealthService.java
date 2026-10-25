package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Health;
import io.github.mochiuaena.triage.development.GithubModels.Section;
import io.github.mochiuaena.triage.development.GithubModels.Snapshot;
import io.github.mochiuaena.triage.development.RepositoryHealthModels.FailingRun;
import io.github.mochiuaena.triage.development.RepositoryHealthModels.HealthReport;
import io.github.mochiuaena.triage.development.RepositoryHealthModels.LocalRecord;
import io.github.mochiuaena.triage.development.RepositoryHealthModels.SectionSummary;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.*;

/**
 * Rule-based health summary over the saved repository overview snapshot plus locally stored
 * analysis records. Reads no GitHub data and calls no model; sample lists are never presented as
 * full statistics, and existing report conclusions are referenced, never rewritten.
 */
@Service
public class RepositoryHealthService {
    private static final Set<String> FAILURES = Set.of("failure", "timed_out", "startup_failure", "action_required");
    private static final int MAX_FAILING_RUNS = 10;
    private final GithubRepositoryService repositories;
    private final CiReportStore ciReports;
    private final PrReportStore prReports;
    private final IssueReportStore issueReports;
    private final RepositoryHealthStore store;

    public RepositoryHealthService(GithubRepositoryService repositories, CiReportStore ciReports,
                                   PrReportStore prReports, IssueReportStore issueReports, RepositoryHealthStore store) {
        this.repositories = repositories;
        this.ciReports = ciReports;
        this.prReports = prReports;
        this.issueReports = issueReports;
        this.store = store;
    }

    public HealthReport generate(UUID repositoryId) {
        Snapshot snapshot = repositories.snapshot(repositoryId);
        Health health = snapshot.health();
        var notes = new ArrayList<String>();
        notes.add("汇总基于 " + health.checkedAt() + " 读取的仓库概览快照与本机已保存分析记录；生成时未重新访问 GitHub，也不调用模型。");
        if (snapshot.stale())
            notes.add("快照已过期或上次读取失败；请先点击“更新 GitHub 状态”再生成，本摘要仍保留当前快照的读取时间与范围。");
        notes.add("Issue 与 PR 为抽样列表（最多 20 条），不代表全量统计；完整列表以 GitHub 为准。");
        var sections = List.of(
            section("ISSUES", "开放 Issue 抽样", health.issues(), health.checkedAt()),
            section("PULL_REQUESTS", "开放 PR 抽样", health.pullRequests(), health.checkedAt()),
            section("WORKFLOWS", "默认分支近期工作流（最多 20 次）", health.workflows(), health.checkedAt()));
        for (SectionSummary item : sections)
            if (!item.available()) notes.add(item.kind() + " 区段未读取：" + (item.message() == null || item.message().isBlank()
                ? "上次快照读取时不可用。" : item.message()) + " 健康摘要不含该区段数据，不代表其没有失败项。");
        var localRecords = List.of(
            localRecord("CI", ciReports.list(repositoryId), io.github.mochiuaena.triage.development.CiModels.Report::createdAt,
                report -> report.workflowName() + " / " + report.job().name()),
            localRecord("PR", prReports.list(repositoryId), io.github.mochiuaena.triage.development.PrModels.Report::createdAt,
                report -> "#" + report.pull().number() + " " + report.pull().title()),
            localRecord("ISSUE", issueReports.list(repositoryId), io.github.mochiuaena.triage.development.IssueModels.Report::createdAt,
                report -> "#" + report.issue().number() + " " + report.issue().title()));
        notes.add("本机记录仅统计已保存的分析报告（每类最多 20 条窗口），不代表仓库全部状态；既有报告结论保持原版本，本摘要只引用数量与时间，不改写任何结论。");
        var report = new HealthReport(UUID.randomUUID(), repositoryId, snapshot.registration().owner() + "/"
            + snapshot.registration().name(), Instant.now(), snapshot.stale(), sections, localRecords, List.copyOf(notes));
        store.insert(report);
        return report;
    }

    private SectionSummary section(String kind, String name, Section<?> data, Instant readAt) {
        if (!data.available())
            return new SectionSummary(kind, name, false, 0, false, 0, List.of(), data.message(), readAt);
        int failing = 0;
        var failingRuns = new ArrayList<FailingRun>();
        if (data.items().size() > 0 && data.items().getFirst() instanceof io.github.mochiuaena.triage.development.GithubModels.Workflow) {
            for (Object value : data.items()) {
                var workflow = (io.github.mochiuaena.triage.development.GithubModels.Workflow) value;
                if (!"completed".equals(workflow.status()) || !FAILURES.contains(workflow.conclusion())) continue;
                failing++;
                if (failingRuns.size() < MAX_FAILING_RUNS)
                    failingRuns.add(new FailingRun(workflow.name(), workflow.conclusion(), workflow.url()));
            }
        }
        return new SectionSummary(kind, name, true, data.items().size(), data.truncated(), failing,
            List.copyOf(failingRuns), data.message(), readAt);
    }

    private <T> LocalRecord localRecord(String kind, List<T> reports, java.util.function.Function<T, Instant> at,
                                        java.util.function.Function<T, String> title) {
        if (reports.isEmpty()) return new LocalRecord(kind, 0, null, "");
        T latest = reports.getFirst();
        return new LocalRecord(kind, reports.size(), at.apply(latest), title.apply(latest));
    }

    public List<HealthReport> list(UUID repositoryId) { repositories.snapshot(repositoryId); return store.list(repositoryId); }

    public HealthReport get(UUID id) { return store.require(id); }
}
