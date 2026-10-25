package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.ActivityModels.ActionItem;
import io.github.mochiuaena.triage.development.ActivityModels.ActivityReport;
import io.github.mochiuaena.triage.development.ActivityModels.DayActivity;
import io.github.mochiuaena.triage.development.ActivityModels.Item;
import io.github.mochiuaena.triage.development.ActivityModels.LocalRecord;
import io.github.mochiuaena.triage.development.GithubModels.Commit;
import io.github.mochiuaena.triage.development.GithubModels.Health;
import io.github.mochiuaena.triage.development.GithubModels.Issue;
import io.github.mochiuaena.triage.development.GithubModels.PullRequest;
import io.github.mochiuaena.triage.development.GithubModels.Section;
import io.github.mochiuaena.triage.development.GithubModels.Snapshot;
import io.github.mochiuaena.triage.development.GithubModels.Workflow;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;

import static org.springframework.http.HttpStatus.BAD_REQUEST;

/**
 * Rule-based activity report over the saved repository overview snapshot plus locally stored analysis
 * records, grouped by repository and date. Reads no GitHub data and calls no model; every activity
 * keeps a source link or a saved-report reference, sample lists are never presented as complete
 * statistics, and nothing is emailed or posted anywhere.
 */
@Service
public class RepositoryActivityService {
    private static final java.util.Set<String> FAILURES = java.util.Set.of("failure", "timed_out", "startup_failure", "action_required");
    private static final int MAX_PER_KIND = 10;
    private static final int MAX_RANGE_DAYS = 31;
    private static final Map<String, String> KIND_LABELS = Map.of(
        "COMMIT", "提交", "ISSUE", "Issue", "PULL_REQUEST", "PR", "WORKFLOW", "工作流",
        "CI_REPORT", "CI 排障报告", "PR_REPORT", "PR 审查报告", "ISSUE_REPORT", "Issue 分诊报告");
    private final GithubRepositoryService repositories;
    private final CiReportStore ciReports;
    private final PrReportStore prReports;
    private final IssueReportStore issueReports;
    private final RepositoryActivityStore store;

    public RepositoryActivityService(GithubRepositoryService repositories, CiReportStore ciReports,
                                     PrReportStore prReports, IssueReportStore issueReports, RepositoryActivityStore store) {
        this.repositories = repositories;
        this.ciReports = ciReports;
        this.prReports = prReports;
        this.issueReports = issueReports;
        this.store = store;
    }

    public ActivityReport generate(UUID repositoryId, LocalDate from, LocalDate to) {
        if (from.isAfter(to)) throw new ResponseStatusException(BAD_REQUEST, "开始日期不能晚于结束日期。");
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS)
            throw new ResponseStatusException(BAD_REQUEST, "日期范围最多 " + MAX_RANGE_DAYS + " 天（含首尾）。");
        if (to.isAfter(LocalDate.now())) throw new ResponseStatusException(BAD_REQUEST, "结束日期不能晚于今天。");
        Snapshot snapshot = repositories.snapshot(repositoryId);
        Health health = snapshot.health();
        ZoneId zone = ZoneId.systemDefault();
        var gaps = new ArrayList<String>();
        var notes = new ArrayList<String>();
        notes.add("活动明细基于 " + health.checkedAt() + " 读取的仓库概览快照与本机已保存分析记录；生成时未重新访问 GitHub，也不调用模型。");
        if (snapshot.stale())
            notes.add("快照已过期或上次读取失败；请先点击“更新 GitHub 状态”再生成，本报告仍保留当前快照的读取时间与范围。");
        notes.add("提交、Issue、PR 与工作流来自快照抽样（各最多 20 条；Issue 与 PR 仅开放状态），仅覆盖已读取样本，完整列表以 GitHub 为准。");
        var items = new ArrayList<Item>();
        collect(gaps, "提交", health.commits(), items, from, to, zone, commit -> new Item("COMMIT",
            firstLine(commit.message()), (commit.author().isBlank() ? "" : commit.author() + " · ") + commit.sha().substring(0, 7),
            commit.url(), null, null, commit.committedAt()));
        collect(gaps, "Issue", health.issues(), items, from, to, zone, issue -> new Item("ISSUE",
            "#" + issue.number() + " " + issue.title(), issue.labels().isEmpty() ? "开放中" : String.join("、", issue.labels()),
            issue.url(), null, null, issue.updatedAt()));
        collect(gaps, "PR", health.pullRequests(), items, from, to, zone, pull -> new Item("PULL_REQUEST",
            "#" + pull.number() + " " + pull.title(), pull.draft() ? "草稿" : "开放", pull.url(), null, null, pull.updatedAt()));
        collect(gaps, "工作流", health.workflows(), items, from, to, zone, run -> new Item("WORKFLOW",
            run.name(), (run.conclusion() == null || run.conclusion().isBlank() ? run.status() : run.conclusion()) + " · " + run.branch(),
            run.url(), null, null, run.updatedAt()));
        var ciWindow = ciReports.list(repositoryId);
        var ciItems = windowItems(ciWindow, from, to, zone, report -> new Item("CI_REPORT",
            report.workflowName() + " / " + report.job().name(), "本机 CI 排障报告", report.job().url(), "ci", report.id(), report.createdAt()));
        var prWindow = prReports.list(repositoryId);
        var prItems = windowItems(prWindow, from, to, zone, report -> new Item("PR_REPORT",
            "#" + report.pull().number() + " " + report.pull().title(), "本机 PR 审查报告", report.pull().url(), "pr", report.id(), report.createdAt()));
        var issueWindow = issueReports.list(repositoryId);
        var issueItems = windowItems(issueWindow, from, to, zone, report -> new Item("ISSUE_REPORT",
            "#" + report.issue().number() + " " + report.issue().title(), "本机 Issue 分诊报告", report.issue().url(), "issue", report.id(), report.createdAt()));
        items.addAll(ciItems);
        items.addAll(prItems);
        items.addAll(issueItems);
        notes.add("本机分析记录仅统计已保存报告（每类最多 20 条窗口），更早或未保存的分析不在本报告中。");
        var localRecords = List.of(localRecord("CI", ciWindow.size(), ciItems), localRecord("PR", prWindow.size(), prItems),
            localRecord("ISSUE", issueWindow.size(), issueItems));
        var byDay = new TreeMap<LocalDate, List<Item>>(Comparator.reverseOrder());
        for (Item item : items) byDay.computeIfAbsent(item.at().atZone(zone).toLocalDate(), date -> new ArrayList<>()).add(item);
        for (List<Item> dayItems : byDay.values()) dayItems.sort(Comparator.comparing(Item::at).reversed());
        var days = byDay.entrySet().stream().map(entry -> new DayActivity(entry.getKey(), List.copyOf(entry.getValue()))).toList();
        if (days.isEmpty())
            notes.add("所选日期范围内没有匹配的活动记录；这不代表该时段没有其他活动（受快照抽样与本机报告窗口限制）。");
        var actionItems = actionItems(health);
        if (actionItems.isEmpty())
            notes.add("当前快照抽样内没有待处理事项（开放 Issue、开放 PR 或失败工作流）。");
        notes.add("本报告为规则汇总，未调用模型；可选模型摘要为后续增强。");
        notes.add("回写知识库选项未启用：后续版本将按 K1 版本机制显式导入为项目知识，默认关闭，不覆盖已有知识版本。");
        notes.add("报告仅在本机生成与保存，不自动发送邮件、评论或任何 GitHub 写操作；GitHub 实时状态以仓库页实时读取为准。");
        var report = new ActivityReport(UUID.randomUUID(), repositoryId, snapshot.registration().owner() + "/"
            + snapshot.registration().name(), Instant.now(), from, to, snapshot.stale(), health.checkedAt(),
            days, actionItems, localRecords, List.copyOf(gaps), List.copyOf(notes));
        store.insert(report);
        return report;
    }

    private <T> void collect(List<String> gaps, String name, Section<T> section, List<Item> items,
                             LocalDate from, LocalDate to, ZoneId zone, Function<T, Item> convert) {
        if (section == null) {
            gaps.add(name + "区段：当前为旧版本快照，不包含该区段数据；请先“更新 GitHub 状态”后重新生成。");
            return;
        }
        if (!section.available()) {
            gaps.add(name + "区段未读取：" + (section.message() == null || section.message().isBlank()
                ? "上次快照读取时不可用。" : section.message()) + "本报告不含该区段数据，不代表该时段没有相关活动。");
            return;
        }
        if (section.truncated()) gaps.add(name + "区段达到抽样上限被截断（最多 20 条）；本报告只覆盖已读取部分。");
        for (T value : section.items()) {
            Item item = convert.apply(value);
            if (inRange(item.at(), from, to, zone)) items.add(item);
        }
    }

    private <T> List<Item> windowItems(List<T> window, LocalDate from, LocalDate to, ZoneId zone, Function<T, Item> convert) {
        var values = new ArrayList<Item>();
        for (T report : window) {
            Item item = convert.apply(report);
            if (inRange(item.at(), from, to, zone)) values.add(item);
        }
        return values;
    }

    private static LocalRecord localRecord(String kind, int saved, List<Item> inRangeItems) {
        Instant latest = inRangeItems.stream().map(Item::at).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new LocalRecord(kind, inRangeItems.size(), saved, latest);
    }

    private static List<ActionItem> actionItems(Health health) {
        var values = new ArrayList<ActionItem>();
        if (available(health.workflows()))
            for (Workflow run : health.workflows().items()) {
                if (!"completed".equals(run.status()) || !FAILURES.contains(run.conclusion())) continue;
                if (values.size() >= MAX_PER_KIND) break;
                values.add(new ActionItem("FAILING_WORKFLOW", run.name(), "结论 " + run.conclusion() + " · " + run.branch(), run.url()));
            }
        int openedIssues = 0;
        if (available(health.issues()))
            for (Issue issue : health.issues().items()) {
                if (openedIssues >= MAX_PER_KIND) break;
                values.add(new ActionItem("OPEN_ISSUE", "#" + issue.number() + " " + issue.title(),
                    issue.labels().isEmpty() ? "开放中" : String.join("、", issue.labels()), issue.url()));
                openedIssues++;
            }
        int openedPulls = 0;
        if (available(health.pullRequests()))
            for (PullRequest pull : health.pullRequests().items()) {
                if (openedPulls >= MAX_PER_KIND) break;
                values.add(new ActionItem("OPEN_PULL_REQUEST", "#" + pull.number() + " " + pull.title(),
                    pull.draft() ? "草稿" : "等待审查", pull.url()));
                openedPulls++;
            }
        return List.copyOf(values);
    }

    private static boolean available(Section<?> section) { return section != null && section.available(); }

    private static boolean inRange(Instant at, LocalDate from, LocalDate to, ZoneId zone) {
        if (at == null) return false;
        LocalDate date = at.atZone(zone).toLocalDate();
        return !date.isBefore(from) && !date.isAfter(to);
    }

    private static String firstLine(String value) {
        String line = value.split("\\R", 2)[0].strip();
        return line.length() <= 160 ? line : line.substring(0, 160);
    }

    public List<ActivityReport> list(UUID repositoryId) { repositories.snapshot(repositoryId); return store.list(repositoryId); }

    public ActivityReport get(UUID id) { return store.require(id); }

    /** Deterministic Markdown rendering of a saved report; used for local export only. */
    public String markdown(UUID id) {
        ActivityReport report = store.require(id);
        var builder = new StringBuilder();
        builder.append("# 研发活动报告 · ").append(markdownText(report.repository())).append("\n\n");
        builder.append("- 日期范围：").append(report.fromDate()).append(" 至 ").append(report.toDate()).append('\n');
        builder.append("- 生成时间：").append(report.createdAt()).append('\n');
        builder.append("- 快照读取时间：").append(report.snapshotReadAt()).append('\n');
        builder.append("- 数据来源：仓库概览快照（提交 / Issue / PR / 工作流抽样，各最多 20 条）与本机已保存分析报告（每类最多 20 条窗口）；生成时未访问 GitHub、未调用模型。\n\n");
        builder.append("## 活动明细\n\n");
        if (report.days().isEmpty()) builder.append("所选日期范围内没有匹配的活动记录。\n\n");
        for (DayActivity day : report.days()) {
            builder.append("### ").append(day.date()).append("\n\n");
            for (Item item : day.items()) {
                builder.append("- ").append(label(item.kind())).append(" · ");
                if (item.url() != null && item.url().startsWith("https://github.com/"))
                    builder.append('[').append(markdownText(item.title())).append("](").append(item.url()).append(')');
                else builder.append(markdownText(item.title()));
                if (item.detail() != null && !item.detail().isBlank()) builder.append(" — ").append(markdownText(item.detail()));
                if (item.reportId() != null)
                    builder.append("（本机").append(localLabel(item.reportType())).append("报告 ").append(item.reportId()).append('）');
                builder.append('\n');
            }
            builder.append('\n');
        }
        builder.append("## 待处理事项\n\n");
        if (report.actionItems().isEmpty()) builder.append("当前快照抽样内没有待处理事项。\n\n");
        for (ActionItem item : report.actionItems()) {
            builder.append("- ").append(actionLabel(item.kind())).append(" · ");
            if (item.url() != null && item.url().startsWith("https://github.com/"))
                builder.append('[').append(markdownText(item.title())).append("](").append(item.url()).append(')');
            else builder.append(markdownText(item.title()));
            if (item.detail() != null && !item.detail().isBlank()) builder.append(" — ").append(markdownText(item.detail()));
            builder.append('\n');
        }
        builder.append("\n## 本机分析记录\n\n");
        for (LocalRecord record : report.localRecords())
            builder.append("- ").append(localLabel(record.kind().toLowerCase())).append("：范围内 ")
                .append(record.inRange()).append(" 条（窗口 ").append(record.saved()).append(" 条，最多 20 条）\n");
        builder.append("\n## 读取缺口\n\n");
        if (report.gaps().isEmpty()) builder.append("本次生成没有已知读取缺口。\n");
        for (String gap : report.gaps()) builder.append("- ").append(gap).append('\n');
        builder.append("\n## 说明\n\n");
        for (String note : report.notes()) builder.append("- ").append(note).append('\n');
        builder.append("\n---\n本报告由规则汇总生成；不自动发送邮件或评论，GitHub 实时状态以仓库页为准。\n");
        return builder.toString();
    }

    private static String label(String kind) { return KIND_LABELS.getOrDefault(kind, kind); }

    private static String actionLabel(String kind) {
        return switch (kind) {
            case "FAILING_WORKFLOW" -> "失败工作流";
            case "OPEN_ISSUE" -> "开放 Issue";
            case "OPEN_PULL_REQUEST" -> "开放 PR";
            default -> kind;
        };
    }

    private static String localLabel(String reportType) {
        return switch (reportType) {
            case "ci" -> "CI 排障";
            case "pr" -> "PR 审查";
            case "issue" -> "Issue 分诊";
            default -> reportType;
        };
    }

    private static String markdownText(String value) {
        if (value == null) return "";
        String text = value.replace("[", "（").replace("]", "）").replace("\n", " ").replace("\r", " ");
        return text.length() <= 200 ? text : text.substring(0, 200);
    }
}
