package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.CiModelModels.*;
import io.github.mochiuaena.triage.development.CiModels.Report;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Prepares the exact send scope for a CI model suggestion and stores its outcome. The rule report
 * stays frozen; advice records are kept separately so a failed attempt never rewrites history.
 */
@Service
public class CiModelService {
    private final CiReportStore reports;
    private final CiModelStore store;
    private final CiModelAnalyzer analyzer;
    private final Map<UUID, Preview> previews = new LinkedHashMap<>();

    public CiModelService(CiReportStore reports, CiModelStore store, CiModelAnalyzer analyzer) {
        this.reports = reports;
        this.store = store;
        this.analyzer = analyzer;
    }

    public ModelOption option() { return analyzer.option(); }

    /** Shows the exact redacted blocks, job identity and omissions before anything is sent. */
    public Preview preview(UUID reportId) {
        Report report = reports.require(reportId);
        ModelOption model = analyzer.option();
        var omissions = new ArrayList<String>(List.of(
            "只发送上方列出的已脱敏错误块；完整日志其余行、其他作业与其他尝试不会发送。",
            "未发送仓库源码与项目文档；知识资料接入为后续增强。",
            "模型输出只是建议，不会重跑 CI 或修改代码；失败时保留规则报告。"));
        if (report.analysis().truncated()) omissions.add("规则报告生成时日志被截断，模型只能看到已保留的块。");
        var preview = new Preview(UUID.randomUUID(), report.id(), report.repositoryId(), report.repository(),
            report.workflowName(), report.runId(), report.attempt(), report.headSha(), report.job(),
            report.analysis().category(), report.analysis().status(), report.analysis().title(),
            report.analysis().summary(), report.analysis().scope(), report.analysis().evidence(),
            report.analysis().locations(), List.copyOf(omissions), Instant.now(),
            Instant.now().plus(Duration.ofMinutes(10)), model);
        synchronized (previews) {
            previews.values().removeIf(item -> item.expiresAt().isBefore(Instant.now()));
            if (previews.size() >= 20) throw capacity();
            previews.put(preview.id(), preview);
        }
        return preview;
    }

    public Advice analyze(UUID reportId, UUID previewId, boolean confirmed) {
        if (!confirmed) throw bad("请先核对发送范围，并确认 confirmed 后再请求模型建议。");
        Report report = reports.require(reportId);
        Preview preview;
        synchronized (previews) {
            previews.values().removeIf(item -> item.expiresAt().isBefore(Instant.now()));
            preview = previews.get(previewId);
        }
        if (preview == null || preview.expiresAt().isBefore(Instant.now()))
            throw stale("CI 模型预览已过期或不存在，请重新预览。");
        if (!preview.reportId().equals(reportId))
            throw stale("模型预览属于另一份 CI 报告；不同提交或尝试不能混用，请重新预览。");
        // The report payload is immutable; identity fields must still match the previewed snapshot.
        if (!preview.headSha().equals(report.headSha()) || preview.runId() != report.runId()
            || preview.attempt() != report.attempt() || preview.job().id() != report.job().id()
            || preview.evidence().size() != report.analysis().evidence().size())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "CI 报告与预览不一致，请重新预览。");
        if (!preview.model().available()) throw stale("预览时没有可用模型，请启用模型并重新预览。");
        ModelAnalysis result = analyzer.analyze(preview);
        var advice = new Advice(UUID.randomUUID(), report.id(), report.repositoryId(), report.repository(),
            report.runId(), report.attempt(), report.headSha(), Instant.now(), result.suggestion(), result.model());
        store.insert(advice);
        synchronized (previews) { previews.remove(previewId); }
        return advice;
    }

    public List<Advice> list(UUID reportId) { reports.require(reportId); return store.list(reportId); }

    public Advice get(UUID id) { return store.require(id); }

    private static ResponseStatusException bad(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, text);
    }

    private static ResponseStatusException stale(String text) {
        return new ResponseStatusException(HttpStatus.CONFLICT, text);
    }

    private static ResponseStatusException capacity() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "CI 模型预览已达容量上限，请稍后重试（最多 20 份）。");
    }
}
