"use strict";
(() => {
  const $ = selector => document.querySelector(selector);
  const node = (tag, text, className) => { const value = document.createElement(tag); if (text != null) value.textContent = text; if (className) value.className = className; return value; };
  const date = value => new Date(value).toLocaleString("zh-CN", { hour12: false });
  const failed = job => ["failure", "timed_out", "action_required", "startup_failure"].includes(job.conclusion);
  let repository = "", context = null, busy = false, version = 0;
  async function request(path, options = {}) {
    const response = await fetch(path, { cache: "no-store", ...options });
    if (!response.ok) { const problem = await response.json().catch(() => ({})); throw new Error(problem.detail || "读取 CI 失败 (" + response.status + ")"); }
    return response.json();
  }
  function message(text, error = false) { $("#ci-dialog-notice").textContent = text; $("#ci-dialog-notice").hidden = !text; $("#ci-dialog-notice").classList.toggle("error", error); }
  function ready() {
    $("#ci-analyze-button").disabled = busy || !context || !$("#ci-job-select").value;
    $("#ci-close-dialog").disabled = busy; $("#ci-job-select").disabled = busy; $("#ci-pasted-log").disabled = busy;
    $("#ci-analyze-button").textContent = $("#ci-pasted-log").value.trim() ? "分析粘贴日志" : "读取日志并分析";
  }
  function jobSteps() {
    const job = context?.jobs.find(job => String(job.id) === $("#ci-job-select").value);
    $("#ci-job-steps").textContent = job ? "失败步骤：" + (job.steps.filter(step => failed(step)).map(step => step.number + ". " + step.name).join("；") || "GitHub 未提供具体失败步骤") : "";
    ready();
  }
  function render(report) {
    const analysis = report.analysis;
    $("#ci-report-result").hidden = false; $("#ci-report-title").textContent = analysis.title;
    $("#ci-report-state").textContent = analysis.status === "OBSERVED" ? "已识别失败阶段" : "证据不足";
    $("#ci-report-meta").textContent = report.workflowName + " / " + report.job.name + " · 尝试 " + report.attempt + " · " + report.headSha + " · " + date(report.createdAt);
    $("#ci-report-source").textContent = report.notice;
    $("#ci-report-summary").textContent = analysis.summary;
    const origin = node("a", "在 GitHub 查看该作业 ↗");
    try { const target = new URL(report.job.url); if (target.protocol === "https:" && target.hostname === "github.com" && !target.username && !target.password) origin.href = target.href; } catch {}
    origin.target = "_blank"; origin.rel = "noopener noreferrer";
    $("#ci-report-link").replaceChildren(origin);
    $("#ci-report-checks").replaceChildren(...analysis.checks.map(text => node("li", text)));
    $("#ci-report-scope").textContent = analysis.scope + (analysis.truncated ? " 报告只保留部分内容。" : "");
    $("#ci-report-evidence").replaceChildren(...analysis.evidence.map((block, index) => {
      const detail = node("details", null, "ci-evidence"); detail.open = index === 0;
      detail.append(node("summary", block.id + " · 日志 " + block.startLine + "–" + block.endLine + " 行"),
        node("pre", block.text.split("\n").map((line, offset) => (block.startLine + offset) + "  " + line).join("\n"))); return detail;
    }));
    if (!analysis.evidence.length) $("#ci-report-evidence").append(node("p", "没有可引用的错误片段。", "management-caption"));
    $("#ci-location-heading").hidden = !analysis.locations.length;
    $("#ci-report-locations").replaceChildren(...analysis.locations.map(location => node("li", location.file + ":" + location.line)));
    const materialLink = node("a", "材料 / 发送预览"); materialLink.href = "/workspace.html?type=CI&repository=" + encodeURIComponent(report.repositoryId) + "&report=" + encodeURIComponent(report.id) + "#materials";
    $("#ci-report-meta").append(node("span", " · "), materialLink);
    $("#ci-report-result").scrollIntoView({ behavior: "smooth", block: "start" });
  }
  async function loadReports() {
    const selected = repository, current = version;
    $("#ci-report-history").replaceChildren(); if (!selected) return;
    try {
      const reports = await request("/api/development/repositories/" + selected + "/ci/reports");
      if (current !== version) return;
      $("#ci-report-history").replaceChildren(...reports.map(report => {
        const button = node("button", report.workflowName + " / " + report.job.name + " · " + date(report.createdAt)
          + " · " + (report.source === "PASTED" ? "粘贴日志" : "GitHub 日志"), "ci-history-item"); button.type = "button";
        button.addEventListener("click", () => render(report)); return button;
      }));
      if (!reports.length) $("#ci-report-history").append(node("p", "还没有保存的 CI 报告。", "repository-list-empty"));
    } catch (error) { if (current === version) $("#ci-report-history").replaceChildren(node("p", error.message, "repository-error")); }
  }
  async function openRun(selected, run) {
    if (busy || selected !== repository) return;
    const current = version; context = null; busy = true; $("#ci-pasted-log").value = "";
    $("#ci-paste-details").open = false; $("#ci-job-select").replaceChildren(); $("#ci-job-steps").textContent = "";
    $("#ci-run-context").textContent = "正在读取工作流和作业…"; message(""); ready(); $("#ci-job-dialog").showModal();
    try {
      const result = await request("/api/development/repositories/" + selected + "/ci/runs/" + run);
      if (current !== version) return; context = result;
      $("#ci-run-context").textContent = result.name + " · 尝试 " + result.attempt + " · " + result.headSha;
      const jobs = result.jobs.filter(job => failed(job) && job.status === "completed");
      $("#ci-job-select").replaceChildren(...jobs.map(job => { const option = node("option", job.name); option.value = String(job.id); return option; }));
      if (jobs.length) $("#ci-job-select").value = String(jobs[0].id);
      if (!jobs.length) message("当前读取范围没有已结束的失败作业。请在 GitHub 核对运行状态。", true);
      else if (result.truncated) message("只读取了前 50 个作业，其余作业请在 GitHub 核对。");
      jobSteps();
    } catch (error) { if (current === version) message(error.message, true); }
    finally { busy = false; ready(); }
  }
  $("#ci-job-select").addEventListener("change", jobSteps);
  $("#ci-pasted-log").addEventListener("input", ready);
  $("#ci-close-dialog").addEventListener("click", () => { if (!busy) { $("#ci-pasted-log").value = ""; $("#ci-job-dialog").close(); } });
  $("#ci-job-dialog").addEventListener("cancel", event => { if (busy) event.preventDefault(); else $("#ci-pasted-log").value = ""; });
  $("#ci-refresh-reports").addEventListener("click", loadReports);
  $("#ci-analyze-form").addEventListener("submit", async event => {
    event.preventDefault(); if (busy || !context || !$("#ci-job-select").value) return;
    const selected = repository, current = version;
    const input = { runId: context.runId, attempt: context.attempt, headSha: context.headSha, jobId: Number($("#ci-job-select").value) };
    const pasted = $("#ci-pasted-log").value; if (pasted.trim()) input.logText = pasted;
    busy = true; ready(); message("正在读取、脱敏并分析日志…");
    try {
      const report = await request("/api/development/repositories/" + selected + "/ci/reports", { method: "POST",
        headers: { "Content-Type": "application/json", "X-Triage-Development": "1" }, body: JSON.stringify(input) });
      if (current !== version) return; $("#ci-pasted-log").value = ""; $("#ci-job-dialog").close(); render(report); await loadReports();
    } catch (error) { if (current === version) { message(error.message, true); $("#ci-paste-details").open = true; } }
    finally { busy = false; ready(); }
  });
  window.ciPanel = { selectRepository(id) { if (id === repository) return; repository = id; version++; context = null; $("#ci-report-result").hidden = true; loadReports(); }, openRun, render };
})();
