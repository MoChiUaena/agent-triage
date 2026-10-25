package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.DraftExecutionModels.ExecutionPreview;
import io.github.mochiuaena.triage.development.DraftExecutionModels.ExecutionRecord;
import io.github.mochiuaena.triage.development.DraftExecutionModels.WriteOption;
import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.GithubModels.Snapshot;
import io.github.mochiuaena.triage.development.GithubWriteClient.WriteFailure;
import io.github.mochiuaena.triage.development.OperationDraftModels.Draft;
import io.github.mochiuaena.triage.development.OperationDraftModels.Target;

import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.BAD_GATEWAY;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;

/**
 * Confirmed execution of operation drafts. Every execution needs a fresh preview (target, content,
 * impact, permission and version check), a second target re-check right before the write, and a
 * claimed EXECUTING state that prevents duplicate submissions. Timeouts are verified against the
 * remote state instead of being retried; every attempt lands in a local audit record without
 * credentials. No arbitrary requests, Shell access or PR merging exists here.
 */
@Service
public class DraftExecutionService {
    private static final Duration PREVIEW_TTL = Duration.ofMinutes(10);
    private static final int MAX_PREVIEWS = 20;
    private final GithubRepositoryService repositories;
    private final OperationDraftStore drafts;
    private final GithubWriteClient github;
    private final DraftExecutionStore store;
    private final Map<UUID, ExecutionPreview> previews = new LinkedHashMap<>();

    public DraftExecutionService(GithubRepositoryService repositories, OperationDraftStore drafts,
                                 GithubWriteClient github, DraftExecutionStore store) {
        this.repositories = repositories;
        this.drafts = drafts;
        this.github = github;
        this.store = store;
    }

    public WriteOption writeOption() {
        return new WriteOption(github.authenticated(), github.authenticated()
            ? "已配置独立写入凭据；每次执行仍需预览核对与显式确认。"
            : "未配置 GitHub 写入凭据（triage.github.write-token），草稿只能保存在本机，不能执行。");
    }

    public ExecutionPreview preview(UUID draftId) {
        requireWriteCredential();
        Draft draft = drafts.require(draftId);
        requireActiveDraft(draft);
        Key key = repositoryKey(draft.repositoryId());
        var access = read(() -> github.repositoryAccess(key));
        if (!access.push())
            throw new ResponseStatusException(FORBIDDEN, "写入凭据没有该仓库的推送权限，请检查 triage.github.write-token 的授权范围。");
        Target target = draft.target();
        List<String> currentLabels = List.of();
        List<String> resultingLabels = List.of();
        if (target.objectType().equals("ISSUE")) {
            var issue = read(() -> github.issue(key, target.number()));
            requireOpen(issue.state(), "Issue");
            requireSameVersion("Issue 更新时间", target.updatedAt(), issue.updatedAt());
            if (draft.kind().equals("LABEL")) {
                currentLabels = read(() -> github.labels(key, target.number()));
                resultingLabels = mergeLabels(currentLabels, draft.addLabels(), draft.removeLabels());
            }
        } else {
            var pull = read(() -> github.pull(key, target.number()));
            requireOpen(pull.state(), "PR");
            requireSameVersion("PR head 提交", target.headSha(), pull.headSha());
        }
        var preview = new ExecutionPreview(UUID.randomUUID(), draft.id(), draft.revision(), draft.kind(), target,
            draft.kind().equals("COMMENT") ? "发布评论" : "更新标签", draft.body(), currentLabels, resultingLabels,
            true, "写入凭据具备该仓库推送权限。", Instant.now(), Instant.now().plus(PREVIEW_TTL),
            List.of("执行前会再次核对目标状态与内容；预览十分钟内有效且只能使用一次。",
                "执行结果写入本机审计记录（不保存凭据）；超时不自动重试，先核对远端状态。",
                "仅开放评论与标签两类操作，不提供任意请求、Shell 或自动合并。"));
        previews.put(draftId, preview);
        while (previews.size() > MAX_PREVIEWS) {
            UUID eldest = previews.keySet().iterator().next();
            previews.remove(eldest);
        }
        return preview;
    }

    public synchronized ExecutionRecord execute(UUID draftId, UUID previewId, Boolean confirmed) {
        requireWriteCredential();
        if (!Boolean.TRUE.equals(confirmed)) throw new ResponseStatusException(BAD_REQUEST, "必须显式确认后才能执行。");
        Draft draft = drafts.require(draftId);
        requireActiveDraft(draft);
        ExecutionPreview preview = previews.get(draftId);
        if (preview == null || !preview.id().equals(previewId))
            throw new ResponseStatusException(CONFLICT, "执行预览不存在、已过期或已使用，请重新预览确认。");
        previews.remove(draftId);
        if (preview.draftRevision() != draft.revision())
            throw new ResponseStatusException(CONFLICT, "草稿在预览后已被修改，请重新预览确认。");
        if (preview.expiresAt().isBefore(Instant.now()))
            throw new ResponseStatusException(CONFLICT, "执行预览已过期，请重新预览确认。");
        Key key = repositoryKey(draft.repositoryId());
        Target target = draft.target();
        Outcome outcome;
        if (target.objectType().equals("ISSUE")) {
            var issue = read(() -> github.issue(key, target.number()));
            requireOpen(issue.state(), "Issue");
            requireDrifted("Issue 更新时间", target.updatedAt(), issue.updatedAt());
            if (draft.kind().equals("LABEL")) {
                List<String> current = read(() -> github.labels(key, target.number()));
                if (!current.equals(preview.currentLabels()))
                    throw new ResponseStatusException(CONFLICT, "预览后目标已变化（标签被修改），请重新预览确认。");
            }
        } else {
            var pull = read(() -> github.pull(key, target.number()));
            requireOpen(pull.state(), "PR");
            requireDrifted("PR head 提交", target.headSha(), pull.headSha());
        }
        if (!drafts.claim(draftId, Instant.now()))
            throw new ResponseStatusException(CONFLICT, "草稿正在执行或已执行。");
        Instant confirmedAt = Instant.now();
        try {
            outcome = perform(draft, preview, key);
        } catch (RuntimeException error) {
            drafts.revert(draftId, Instant.now());
            throw error;
        }
        List<String> resultingLabels = draft.kind().equals("LABEL") ? preview.resultingLabels() : List.of();
        var record = new ExecutionRecord(UUID.randomUUID(), draft.id(), draft.repositoryId(), draft.repository(), draft.kind(),
            target, draft.basis(), draft.body(), draft.addLabels(), draft.removeLabels(), resultingLabels,
            outcome.state(), confirmedAt, Instant.now(), outcome.url(), outcome.summary(), outcome.message(), "LOCAL_PAGE");
        store.insert(record);
        if (outcome.state().equals("SUCCESS"))
            drafts.complete(draftId, withState(draft, "EXECUTED", record.executedAt()));
        else drafts.revert(draftId, Instant.now());
        return record;
    }

    private Outcome perform(Draft draft, ExecutionPreview preview, Key key) {
        Target target = draft.target();
        if (draft.kind().equals("COMMENT")) {
            try {
                var comment = github.addComment(key, target.number(), draft.body());
                return new Outcome("SUCCESS", comment.url(), "已发布评论（评论 " + comment.id() + "）。", "");
            } catch (WriteFailure failure) {
                if (!failure.uncertain) return new Outcome("FAILED", "", "", failure.getMessage());
                var recent = readQuietly(() -> github.recentComments(key, target.number()));
                var landed = recent == null ? java.util.Optional.<GithubWriteClient.CommentResult>empty()
                    : recent.stream().filter(comment -> draft.body().equals(comment.body())).findFirst();
                return landed.map(comment -> new Outcome("SUCCESS", comment.url(),
                        "写入超时，已核对远端存在该评论（评论 " + comment.id() + "），不重复发送。", ""))
                    .orElseGet(() -> new Outcome("UNCERTAIN", "", "",
                        "写入超时且远端未找到该评论；未自动重试，请到 GitHub 核对后再决定是否重新执行。"));
            }
        }
        try {
            List<String> applied = github.setLabels(key, target.number(), preview.resultingLabels());
            if (applied.equals(preview.resultingLabels()))
                return new Outcome("SUCCESS", key.url() + "/issues/" + target.number() + "/labels",
                    "标签已更新为：" + String.join("、", applied) + "。", "");
            return new Outcome("UNCERTAIN", key.url() + "/issues/" + target.number() + "/labels", "",
                "远端返回的标签与预期不一致（实际：" + String.join("、", applied) + "）；请到 GitHub 核对。");
        } catch (WriteFailure failure) {
            if (!failure.uncertain) return new Outcome("FAILED", "", "", failure.getMessage());
            List<String> current = readQuietly(() -> github.labels(key, target.number()));
            if (current != null && current.equals(preview.resultingLabels()))
                return new Outcome("SUCCESS", key.url() + "/issues/" + target.number() + "/labels",
                    "写入超时，已核对远端标签与预期一致。", "");
            return new Outcome("UNCERTAIN", "", "",
                "写入超时且无法确认远端标签与预期一致；未自动重试，请到 GitHub 核对后再决定是否重新执行。");
        }
    }

    private static List<String> mergeLabels(List<String> current, List<String> add, List<String> remove) {
        var result = new ArrayList<>(current);
        result.removeAll(remove);
        for (String label : add) if (!result.contains(label)) result.add(label);
        return List.copyOf(result);
    }

    private static Draft withState(Draft draft, String state, Instant at) {
        return new Draft(draft.id(), draft.repositoryId(), draft.repository(), draft.kind(), draft.target(), draft.basis(),
            draft.body(), draft.addLabels(), draft.removeLabels(), state, draft.revision(), draft.createdAt(), at);
    }

    private void requireWriteCredential() {
        if (!github.authenticated())
            throw new ResponseStatusException(FORBIDDEN, "未配置 GitHub 写入凭据，无法执行；请在启动配置中单独设置写入 Token。");
    }

    private void requireActiveDraft(Draft draft) {
        switch (draft.state()) {
            case "EXECUTED" -> throw new ResponseStatusException(CONFLICT, "草稿已执行，不能重复执行。");
            case "EXECUTING" -> throw new ResponseStatusException(CONFLICT, "草稿正在执行或上次执行被中断，请先核对 GitHub 实际状态。");
            case "DISCARDED" -> throw new ResponseStatusException(CONFLICT, "草稿已丢弃，不能执行。");
            default -> { }
        }
    }

    private void requireOpen(String state, String what) {
        if (!state.equals("open"))
            throw new ResponseStatusException(CONFLICT, "目标已变化：" + what + "已关闭，请核对后重新生成草稿。");
    }

    private void requireSameVersion(String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new ResponseStatusException(CONFLICT, "目标已变化：" + what + "已从 " + expected + " 变为 " + actual + "，请核对后重新生成草稿。");
    }

    private void requireDrifted(String what, Object expected, Object actual) {
        if (!Objects.equals(expected, actual))
            throw new ResponseStatusException(CONFLICT, "预览后目标已变化：" + what + "已从 " + expected + " 变为 " + actual + "，请重新预览确认。");
    }

    private Key repositoryKey(UUID repositoryId) {
        Snapshot snapshot = repositories.snapshot(repositoryId);
        return new Key(snapshot.registration().owner(), snapshot.registration().name());
    }

    private <T> T read(java.util.function.Supplier<T> reader) {
        try { return reader.get(); }
        catch (WriteFailure failure) { throw new ResponseStatusException(BAD_GATEWAY, failure.getMessage()); }
    }

    private <T> T readQuietly(java.util.function.Supplier<T> reader) {
        try { return reader.get(); }
        catch (RuntimeException error) { return null; }
    }

    public List<ExecutionRecord> list(UUID repositoryId) { repositories.snapshot(repositoryId); return store.list(repositoryId); }

    public ExecutionRecord get(UUID id) { return store.require(id); }

    private record Outcome(String state, String url, String summary, String message) {}
}
