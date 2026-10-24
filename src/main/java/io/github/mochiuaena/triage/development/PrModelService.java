package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.PrModels.Diff;
import io.github.mochiuaena.triage.development.PrModels.PatchFile;
import io.github.mochiuaena.triage.development.PrModels.Report;
import io.github.mochiuaena.triage.development.PrModelModels.*;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Prepares the exact diff scope for a PR model review and stores its outcome. Patches are re-read
 * at the report's frozen SHAs and never persisted; a PR update invalidates previews and analyses.
 */
@Service
public class PrModelService {
    static final int MAX_FILES = 20, PER_FILE_CHARS = 6_000, TOTAL_CHARS = 40_000;
    private static final Pattern HUNK = Pattern.compile("@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*");
    private final GithubRepositoryStore repositories;
    private final GithubPrClient github;
    private final PrReportStore reports;
    private final PrModelStore store;
    private final PrModelAnalyzer analyzer;
    private final Map<UUID, Preview> previews = new LinkedHashMap<>();

    public PrModelService(GithubRepositoryStore repositories, GithubPrClient github, PrReportStore reports,
                          PrModelStore store, PrModelAnalyzer analyzer) {
        this.repositories = repositories;
        this.github = github;
        this.reports = reports;
        this.store = store;
        this.analyzer = analyzer;
    }

    public ModelOption option() { return analyzer.option(); }

    /** Shows the exact redacted patches, rule reminders, CI and issues before anything is sent. */
    public Preview preview(UUID reportId) {
        Report report = reports.require(reportId);
        Key key = key(report.repositoryId());
        var pull = github.pull(key, report.pull().number());
        same(pull, report.pull().baseSha(), report.pull().headSha());
        Diff diff = github.diff(key, pull);
        same(github.pull(key, report.pull().number()), report.pull().baseSha(), report.pull().headSha());
        var omissions = new ArrayList<String>(List.of(
            "只发送上方列出的已脱敏补丁与差异元数据；完整文件内容、仓库其他源码与项目文档不会发送。",
            "补丁属于源码，发送前需单独确认 codeConfirmed；模型输出只是建议，不构成合入批准。",
            "规则提醒、检查状态与 Issue 引用取自报告生成时的冻结版本；模型建议需与当前状态分开核对。"));
        int budget = TOTAL_CHARS;
        var files = new ArrayList<SendFile>();
        var omitted = new ArrayList<String>();
        for (PatchFile file : diff.files()) {
            if (files.size() >= MAX_FILES || budget <= 0) { omitted.add(file.file()); continue; }
            if (file.patch() == null || file.patch().isBlank()) {
                omissions.add("未发送文件 " + file.file() + "：GitHub 未提供补丁内容。");
                continue;
            }
            Built built = build(file, Math.min(PER_FILE_CHARS, budget));
            if (built.clipped()) omissions.add("文件 " + file.file() + " 的补丁超出单文件上限，仅发送前 " + built.chars() + " 字符内的完整 hunk。");
            budget -= built.chars();
            files.add(new SendFile(file.file(), file.status(), file.additions(), file.deletions(), built.patch(),
                built.clipped(), built.newLines(), built.oldLines()));
        }
        if (!omitted.isEmpty()) {
            String names = String.join("、", omitted.subList(0, Math.min(20, omitted.size())));
            omissions.add("未发送 " + omitted.size() + " 个文件的补丁（超出发送预算或数量上限）：" + names + (omitted.size() > 20 ? " 等。" : "。"));
        }
        omissions.add("差异读取自固定 base/head SHA，与报告一致；PR 更新后本预览失效。");
        var preview = new Preview(UUID.randomUUID(), report.id(), report.repositoryId(), report.repository(),
            report.pull().number(), report.pull().title(), report.pull().baseSha(), report.pull().headSha(),
            report.mergeBaseSha(), report.pull().changedFiles(), List.copyOf(files), report.findings(), report.ci(),
            report.issues(), List.copyOf(omissions), Instant.now(), Instant.now().plus(Duration.ofMinutes(10)),
            analyzer.option());
        synchronized (previews) {
            previews.values().removeIf(item -> item.expiresAt().isBefore(Instant.now()));
            if (previews.size() >= 20) throw capacity();
            previews.put(preview.id(), preview);
        }
        return preview;
    }

    public Advice analyze(UUID reportId, UUID previewId, boolean confirmed, boolean codeConfirmed) {
        if (!confirmed || !codeConfirmed)
            throw bad("请先核对发送范围；补丁属于源码，需同时确认 confirmed 与 codeConfirmed 后再请求模型建议。");
        Report report = reports.require(reportId);
        Key key = key(report.repositoryId());
        // A moved PR invalidates every cached preview for the old report.
        same(github.pull(key, report.pull().number()), report.pull().baseSha(), report.pull().headSha());
        Preview preview;
        synchronized (previews) {
            previews.values().removeIf(item -> item.expiresAt().isBefore(Instant.now()));
            preview = previews.get(previewId);
        }
        if (preview == null || preview.expiresAt().isBefore(Instant.now()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR 模型预览已过期或不存在，请重新预览。");
        if (!preview.reportId().equals(reportId))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "模型预览属于另一份 PR 报告；不同提交不能混用，请重新预览。");
        if (!preview.headSha().equals(report.pull().headSha()) || !preview.baseSha().equals(report.pull().baseSha())
            || preview.number() != report.pull().number())
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR 报告与预览不一致，请重新预览。");
        if (!preview.model().available()) throw new ResponseStatusException(HttpStatus.CONFLICT, "预览时没有可用模型，请启用模型并重新预览。");
        ModelAnalysis result = analyzer.analyze(preview);
        var advice = new Advice(UUID.randomUUID(), report.id(), report.repositoryId(), report.repository(),
            report.pull().number(), report.pull().baseSha(), report.pull().headSha(), report.mergeBaseSha(),
            Instant.now(), result.suggestion(), result.model());
        store.insert(advice);
        synchronized (previews) { previews.remove(previewId); }
        return advice;
    }

    public List<Advice> list(UUID reportId) { reports.require(reportId); return store.list(reportId); }

    public Advice get(UUID id) { return store.require(id); }

    private record Built(String patch, boolean clipped, int chars, List<LineRange> newLines, List<LineRange> oldLines) {}

    /** Sends whole hunks only, so a cited line is always present in the sent text. */
    private static Built build(PatchFile file, int budget) {
        String[] rows = file.patch().replace("\r\n", "\n").split("\n", -1);
        var sent = new StringBuilder();
        var newLines = new ArrayList<LineRange>();
        var oldLines = new ArrayList<LineRange>();
        var buffer = new StringBuilder();
        int newStart = 0, newCount = 0, oldStart = 0, oldCount = 0;
        boolean clipped = false, privateKey = false;
        for (String row : rows) {
            Matcher header = HUNK.matcher(row);
            if (header.matches()) {
                if (buffer.length() > 0 && sent.length() + buffer.length() > budget) { clipped = true; break; }
                sent.append(buffer);
                if (newCount > 0) newLines.add(new LineRange(newStart, newCount));
                if (oldCount > 0) oldLines.add(new LineRange(oldStart, oldCount));
                buffer = new StringBuilder(row).append('\n');
                newStart = Integer.parseInt(header.group(3));
                newCount = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
                oldStart = Integer.parseInt(header.group(1));
                oldCount = header.group(2) == null ? 1 : Integer.parseInt(header.group(2));
                continue;
            }
            if (newCount == 0 && oldCount == 0) continue;
            if (row.equals("\\ No newline at end of file")) { buffer.append(row).append('\n'); continue; }
            String raw = row.length() > 0 ? row.substring(1) : "";
            if (raw.contains("-----BEGIN ") && raw.contains("PRIVATE KEY-----")) privateKey = true;
            String clean = privateKey ? "[REDACTED PRIVATE KEY]" : PrEvidenceRedactor.clean(raw);
            if (raw.contains("-----END ") && raw.contains("PRIVATE KEY-----")) privateKey = false;
            buffer.append(row.charAt(0)).append(clean).append('\n');
        }
        if (!clipped) {
            if (sent.length() + buffer.length() > budget) clipped = true;
            else {
                sent.append(buffer);
                if (newCount > 0) newLines.add(new LineRange(newStart, newCount));
                if (oldCount > 0) oldLines.add(new LineRange(oldStart, oldCount));
            }
        }
        return new Built(sent.toString().stripTrailing(), clipped, sent.length(), List.copyOf(newLines), List.copyOf(oldLines));
    }

    private Key key(UUID repositoryId) {
        var registration = repositories.require(repositoryId).registration();
        return new Key(registration.owner(), registration.name());
    }

    private static void same(io.github.mochiuaena.triage.development.PrModels.Context pull, String base, String head) {
        if (!pull.baseSha().equals(base) || !pull.headSha().equals(head))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "PR 已更新（base/head 与报告不一致），旧报告的模型预览失效；请基于当前 PR 重新生成规则报告与预览。");
    }

    private static ResponseStatusException bad(String text) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, text);
    }

    private static ResponseStatusException capacity() {
        return new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "PR 模型预览已达容量上限，请稍后重试（最多 20 份）。");
    }
}
