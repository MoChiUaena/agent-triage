package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Snapshot;
import io.github.mochiuaena.triage.development.OperationDraftModels.Basis;
import io.github.mochiuaena.triage.development.OperationDraftModels.Draft;
import io.github.mochiuaena.triage.development.OperationDraftModels.Target;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Local GitHub operation drafts derived from saved analysis reports. Creating or editing a draft reads
 * local stores only — no GitHub request at all, and no write client exists yet (that is W2). Comment and
 * label drafts ship first; creating or closing Issues from drafts is a later extension.
 */
@Service
public class OperationDraftService {
    private static final int MAX_BODY = 20000;
    private static final int MAX_LABELS = 10;
    private static final int MAX_LABEL_LENGTH = 64;
    private static final String DRAFT_BOUNDARY = "\n本内容由 Agent Triage 依据本机%s生成草稿（未执行任何 GitHub 操作）；发布前请人工核对。";
    private final GithubRepositoryService repositories;
    private final IssueReportStore issueReports;
    private final PrReportStore prReports;
    private final OperationDraftStore store;

    public OperationDraftService(GithubRepositoryService repositories, IssueReportStore issueReports,
                                 PrReportStore prReports, OperationDraftStore store) {
        this.repositories = repositories;
        this.issueReports = issueReports;
        this.prReports = prReports;
        this.store = store;
    }

    public Draft create(UUID repositoryId, String kind, UUID basisReportId) {
        Snapshot snapshot = repositories.snapshot(repositoryId);
        if (kind == null || !(kind.equals("COMMENT") || kind.equals("LABEL")))
            throw new ResponseStatusException(BAD_REQUEST, "草稿类型仅支持评论（COMMENT）或标签（LABEL）；创建/关闭 Issue 草稿为后续扩展。");
        if (basisReportId == null) throw new ResponseStatusException(BAD_REQUEST, "缺少依据报告。");
        var issueReport = lookup(() -> issueReports.require(basisReportId));
        var prReport = issueReport.isEmpty() ? lookup(() -> prReports.require(basisReportId)) : Optional.<io.github.mochiuaena.triage.development.PrModels.Report>empty();
        if (issueReport.isEmpty() && prReport.isEmpty())
            throw new ResponseStatusException(NOT_FOUND, "依据报告不存在。");
        Resolved resolved;
        if (issueReport.isPresent()) {
            var report = issueReport.get();
            requireSameRepository(report.repositoryId(), repositoryId);
            var issue = report.issue();
            var target = new Target("ISSUE", issue.number(), issue.title(), issue.url(), null, issue.updatedAt());
            var basis = new Basis("ISSUE_REPORT", report.id(), "Issue 分诊 #" + issue.number() + " " + issue.title(), report.createdAt());
            resolved = kind.equals("COMMENT")
                ? new Resolved(basis, target, issueBody(report), List.of())
                : new Resolved(basis, target, labelBody(report), issueLabels(report));
        } else {
            var report = prReport.get();
            requireSameRepository(report.repositoryId(), repositoryId);
            var pull = report.pull();
            if (kind.equals("LABEL")) throw new ResponseStatusException(BAD_REQUEST, "标签草稿仅支持从 Issue 分诊报告生成。");
            var target = new Target("PULL_REQUEST", pull.number(), pull.title(), pull.url(), pull.headSha(), null);
            resolved = new Resolved(new Basis("PR_REPORT", report.id(), "PR 审查 #" + pull.number() + " " + pull.title(), report.createdAt()),
                target, prBody(report), List.of());
        }
        Instant now = Instant.now();
        var draft = new Draft(UUID.randomUUID(), repositoryId, snapshot.registration().owner() + "/"
            + snapshot.registration().name(), kind, resolved.target(), resolved.basis(), resolved.body(),
            resolved.addLabels(), List.of(), "DRAFT", 1, now, now);
        store.insert(draft);
        return draft;
    }

    public Draft update(UUID id, int expectedRevision, String body, List<String> addLabels, List<String> removeLabels) {
        Draft current = store.require(id);
        if (!current.state().equals("DRAFT")) throw new ResponseStatusException(CONFLICT, "草稿已丢弃，不能继续编辑。");
        if (current.revision() != expectedRevision)
            throw new ResponseStatusException(CONFLICT, "草稿已被其他修改更新，请刷新后重试。");
        String nextBody;
        List<String> nextAdd;
        List<String> nextRemove;
        if (current.kind().equals("COMMENT")) {
            if ((addLabels != null && !addLabels.isEmpty()) || (removeLabels != null && !removeLabels.isEmpty()))
                throw new ResponseStatusException(BAD_REQUEST, "评论草稿不包含标签变更。");
            if (body == null || body.isBlank()) throw new ResponseStatusException(BAD_REQUEST, "评论正文不能为空。");
            if (body.length() > MAX_BODY) throw new ResponseStatusException(BAD_REQUEST, "评论正文最长 " + MAX_BODY + " 字符。");
            nextBody = body;
            nextAdd = List.of();
            nextRemove = List.of();
        } else {
            nextAdd = normalize(addLabels);
            nextRemove = normalize(removeLabels);
            if (nextAdd.isEmpty() && nextRemove.isEmpty())
                throw new ResponseStatusException(BAD_REQUEST, "标签草稿至少需要一条添加或移除。");
            var overlap = new ArrayList<>(nextAdd);
            overlap.retainAll(nextRemove);
            if (!overlap.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "同一标签不能同时出现在添加与移除列表。");
            if (body != null && body.length() > MAX_BODY)
                throw new ResponseStatusException(BAD_REQUEST, "标签说明最长 " + MAX_BODY + " 字符。");
            nextBody = body == null ? "" : body;
        }
        var next = new Draft(current.id(), current.repositoryId(), current.repository(), current.kind(),
            current.target(), current.basis(), nextBody, nextAdd, nextRemove, current.state(),
            current.revision() + 1, current.createdAt(), Instant.now());
        if (!store.update(id, expectedRevision, next))
            throw new ResponseStatusException(CONFLICT, "草稿已被其他修改更新，请刷新后重试。");
        return next;
    }

    public Draft discard(UUID id) {
        Draft current = store.require(id);
        var discarded = new Draft(current.id(), current.repositoryId(), current.repository(), current.kind(),
            current.target(), current.basis(), current.body(), current.addLabels(), current.removeLabels(),
            "DISCARDED", current.revision(), current.createdAt(), Instant.now());
        if (!store.discard(id, discarded)) throw new ResponseStatusException(CONFLICT, "草稿不存在或已丢弃。");
        return discarded;
    }

    public List<Draft> list(UUID repositoryId) { repositories.snapshot(repositoryId); return store.list(repositoryId); }

    public Draft get(UUID id) { return store.require(id); }

    private void requireSameRepository(UUID basisRepository, UUID repositoryId) {
        if (!Objects.equals(basisRepository, repositoryId))
            throw new ResponseStatusException(BAD_REQUEST, "依据报告不属于当前仓库。");
    }

    private List<String> issueLabels(IssueModels.Report report) {
        var proposal = report.proposal();
        if (proposal == null || proposal.category() == null || proposal.category().isBlank())
            throw new ResponseStatusException(BAD_REQUEST, "分诊报告未给出标签建议，无法生成标签草稿。");
        return List.of(proposal.category());
    }

    private String issueBody(IssueModels.Report report) {
        var proposal = report.proposal();
        var builder = new StringBuilder();
        builder.append("Agent Triage 分诊摘要（").append(report.repository()).append(" #")
            .append(report.issue().number()).append("）：\n");
        if (proposal != null) {
            builder.append("- 结论：").append(text(proposal.category())).append("，优先级 ").append(text(proposal.priority())).append('\n');
            if (!text(proposal.summary()).isBlank()) builder.append("- ").append(proposal.summary()).append('\n');
            if (proposal.actions() != null && !proposal.actions().isEmpty()) {
                builder.append("\n建议行动：\n");
                for (int i = 0; i < proposal.actions().size() && i < 8; i++)
                    builder.append(i + 1).append(". ").append(proposal.actions().get(i).text()).append('\n');
            }
        }
        return truncate(builder.append(DRAFT_BOUNDARY.formatted("分诊报告")).toString());
    }

    private String prBody(PrModels.Report report) {
        var pull = report.pull();
        var builder = new StringBuilder();
        builder.append("Agent Triage PR 审查要点（").append(report.repository()).append(" #")
            .append(pull.number()).append(" ").append(pull.title()).append("）：\n");
        if (report.findings() == null || report.findings().isEmpty())
            builder.append("- 本次规则检查未发现需要提醒的变更。\n");
        else
            for (int i = 0; i < report.findings().size() && i < 8; i++) {
                var finding = report.findings().get(i);
                builder.append("- ").append(finding.title()).append("（").append(finding.category())
                    .append("）：").append(finding.reason()).append('\n');
            }
        return truncate(builder.append(DRAFT_BOUNDARY.formatted("审查报告")).toString());
    }

    private String labelBody(IssueModels.Report report) {
        var proposal = report.proposal();
        return truncate("依据本机分诊报告（" + text(proposal == null ? null : proposal.category()) + " / "
            + text(proposal == null ? null : proposal.priority()) + "）建议维护标签。"
            + DRAFT_BOUNDARY.formatted("分诊报告"));
    }

    private List<String> normalize(List<String> values) {
        if (values == null) return List.of();
        var result = new ArrayList<String>();
        for (String value : values) {
            String label = value == null ? "" : value.strip();
            if (label.isEmpty()) throw new ResponseStatusException(BAD_REQUEST, "标签不能为空白。");
            if (label.length() > MAX_LABEL_LENGTH)
                throw new ResponseStatusException(BAD_REQUEST, "单个标签最长 " + MAX_LABEL_LENGTH + " 字符。");
            if (result.contains(label)) throw new ResponseStatusException(BAD_REQUEST, "标签重复：" + label);
            result.add(label);
            if (result.size() > MAX_LABELS)
                throw new ResponseStatusException(BAD_REQUEST, "每次最多 " + MAX_LABELS + " 个标签。");
        }
        return List.copyOf(result);
    }

    private static <T> Optional<T> lookup(Supplier<T> reader) {
        try { return Optional.of(reader.get()); }
        catch (ResponseStatusException error) { return Optional.empty(); }
    }

    private static String text(String value) { return value == null ? "" : value; }

    private static String truncate(String value) { return value.length() <= MAX_BODY ? value : value.substring(0, MAX_BODY); }

    private record Resolved(Basis basis, Target target, String body, List<String> addLabels) {}
}
