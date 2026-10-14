package io.github.mochiuaena.triage.development;

import io.github.mochiuaena.triage.development.GithubModels.Key;
import io.github.mochiuaena.triage.development.WorkflowModels.CiObservation;
import java.util.*;
import org.springframework.stereotype.Component;
import static io.github.mochiuaena.triage.development.GithubClient.*;

@Component
public class WorkflowCiReader {
    private final GithubClient github;
    private final CiTriageService ci;
    public WorkflowCiReader(GithubClient github, CiTriageService ci) { this.github = github; this.ci = ci; }
    CiObservation read(UUID repository, Key key, String head) {
        if (!head.matches("[a-f0-9]{40}")) throw new IllegalArgumentException("Invalid commit");
        var page = github.get("/repos/" + key.fullName() + "/actions/runs?head_sha=" + head + "&per_page=5");
        var values = page.data().path("workflow_runs");
        if (!values.isArray() || values.size() > 5) throw invalid();
        var gaps = new ArrayList<String>(); Long failure = null; int matching = 0; boolean pending = false;
        for (var run : values) {
            String sha = text(run, "head_sha", 64);
            if (!sha.matches("[a-f0-9]{40}")) throw invalid();
            if (!sha.equals(head)) { gaps.add("Actions 返回的其他提交运行已排除。"); continue; }
            matching++; String status = text(run, "status", 40), conclusion = text(run, "conclusion", 40);
            if (!status.equals("completed")) pending = true;
            if (failure == null && status.equals("completed") && Set.of("failure", "timed_out", "action_required", "startup_failure").contains(conclusion)) {
                if (!run.path("id").canConvertToLong() || run.path("id").asLong() < 1) throw invalid(); failure = run.path("id").asLong();
            }
        }
        if (page.truncated()) gaps.add("只读取所选 head 的前五个 Actions 运行；未读取合并提交或第三方 CI 日志。");
        if (failure == null) {
            if (matching == 0) gaps.add("未读取到此 head 的 Actions 运行，可能使用合并提交或其他 CI；不能据此认定通过。");
            return new CiObservation(matching == 0 ? "NO_RUNS" : pending ? "PENDING" : "NO_FAILED_RUNS", null, "GITHUB", matching, page.truncated(), List.copyOf(gaps));
        }
        var context = ci.inspect(repository, failure);
        if (!context.headSha().equals(head)) { gaps.add("运行提交与 PR 不同，已排除其作业。"); return new CiObservation("INCOMPLETE", null, "GITHUB", matching, true, List.copyOf(gaps)); }
        var job = context.jobs().stream().filter(item -> item.status().equals("completed") && Set.of("failure", "timed_out", "action_required", "startup_failure").contains(item.conclusion())).findFirst();
        if (context.truncated()) gaps.add("失败运行只读取前五十个作业。");
        if (job.isEmpty()) { gaps.add("失败运行没有可读取的已结束失败作业，未生成根因判断。"); return new CiObservation("INCOMPLETE", null, "GITHUB", matching, context.truncated(), List.copyOf(gaps)); }
        var report = ci.create(repository, context.runId(), context.attempt(), job.get().id(), head, null);
        gaps.add("只分析一个失败运行中的首个失败作业，其余作业未逐个分析。");
        return new CiObservation("ANALYZED", report, "GITHUB", matching, page.truncated() || context.truncated(), List.copyOf(gaps));
    }
}
