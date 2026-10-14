package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.CiModels.*;
import io.github.mochiuaena.triage.development.GithubModels.Key;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.http.HttpStatus.*;

@Service
public class CiTriageService {
    private final GithubRepositoryStore repositories;
    private final GithubClient github;
    private final CiLogAnalyzer analyzer;
    private final CiReportStore reports;
    public CiTriageService(GithubRepositoryStore repositories, GithubClient github, CiLogAnalyzer analyzer, CiReportStore reports) {
        this.repositories = repositories; this.github = github; this.analyzer = analyzer; this.reports = reports;
    }
    public Context inspect(UUID repository, long run) { return github.ciContext(key(repository), run, null, true); }
    public List<Report> list(UUID repository) { repositories.require(repository); return reports.list(repository); }
    public Report get(UUID id) { return reports.require(id); }
    public synchronized Report create(UUID repository, long run, int attempt, long jobId, String sha, String pasted) {
        Key key = key(repository);
        Context context = github.ciContext(key, run, attempt, false);
        Job job = github.ciJob(key, jobId);
        if (!context.headSha().equals(sha) || job.id() != jobId || job.runId() != run || job.attempt() != attempt || !job.headSha().equals(sha))
            throw new ResponseStatusException(CONFLICT, "CI 版本或作业与选择不一致，请重新读取失败作业。");
        if (!job.status().equals("completed")) throw new ResponseStatusException(CONFLICT, "作业尚未结束，请稍后读取日志。");
        if (!Set.of("failure", "timed_out", "action_required", "startup_failure").contains(job.conclusion()))
            throw new ResponseStatusException(BAD_REQUEST, "所选作业没有失败状态，请选择失败作业。");
        boolean manual = pasted != null;
        if (manual && pasted.isBlank()) throw new IllegalArgumentException("Empty CI log");
        String log;
        try { log = manual ? pasted : github.ciLog(key, jobId); }
        catch (ResponseStatusException error) {
            if (error.getStatusCode().value() == 403) throw new ResponseStatusException(FORBIDDEN,
                "GitHub 未允许下载作业日志。可粘贴失败日志，或配置具有 Actions 只读权限的 Token。");
            throw error;
        }
        Report report = new Report(UUID.randomUUID(), repository, key.fullName(), run, attempt, sha, context.name(), job,
            manual ? "PASTED" : "GITHUB", Instant.now(), manual ? "来自本机粘贴日志，与所选 GitHub 作业的对应关系未验证。" : "来自所选 GitHub 作业日志。",
            analyzer.analyze(log, job));
        try { reports.insert(report); }
        catch (org.springframework.dao.DataIntegrityViolationException error) { throw new ResponseStatusException(CONFLICT, "仓库登记已被移除，请重新选择仓库。"); }
        return report;
    }
    private Key key(UUID repository) { var registration = repositories.require(repository).registration(); return new Key(registration.owner(), registration.name()); }
}
