"use strict";
(() => {
  const $ = selector => document.querySelector(selector);
  const node = (tag, text, className) => { const value = document.createElement(tag); if (text != null) value.textContent = text; if (className) value.className = className; return value; };
  const date = value => new Date(value).toLocaleString("zh-CN", { hour12: false });
  const failed = job => ["failure", "timed_out", "action_required", "startup_failure"].includes(job.conclusion);
  let repository = "", context = null, busy = false, version = 0;
  let modelPreview = null, modelBusy = false, modelReportId = null;
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
    if (!report) return;
    const analysis = report.analysis;
    $("#ci-report-result").hidden = false; $("#ci-report-title").textContent = analysis.title;
    $("#ci-report-state").textContent = analysis.status === "OBSERVED" ? "已识别失败阶段" : "证据不足";
    $("#ci-report-meta").textContent = report.workflowName + " / " + report.job.name + " · 尝试 " + report.attempt + " · " + report.headSha + " · " + date(report.createdAt);
    $("#ci-report-meta").dataset.reportId = report.id;
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
    resetModelSection(report.id);
    loadModelOption(report.id);
    loadModelHistory(report.id);
    const followup = node("a", "继续追问"); followup.href = "/workspace.html?type=CI&report=" + encodeURIComponent(report.id) + "#conversations";
    const materialLink = node("a", "材料 / 发送预览"); materialLink.href = "/workspace.html?type=CI&repository=" + encodeURIComponent(report.repositoryId) + "&report=" + encodeURIComponent(report.id) + "#materials";
    $("#ci-report-meta").append(node("span", " · "), materialLink);
    $("#ci-report-meta").append(node("span", " · "), followup);
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
  function modelNotice(text, error = false) {
    $("#ci-model-notice").textContent = text; $("#ci-model-notice").hidden = !text;
    $("#ci-model-notice").classList.toggle("error", error);
  }
  function resetModelSection(reportId) {
    modelReportId = reportId; modelPreview = null;
    $("#ci-model-option").textContent = ""; $("#ci-model-preview").replaceChildren(); $("#ci-model-preview").hidden = true;
    $("#ci-model-confirm").checked = false; $("#ci-model-consent-row").hidden = true; $("#ci-model-analyze-button").disabled = true;
    $("#ci-model-preview-button").disabled = true; modelNotice("");
    $("#ci-model-result").replaceChildren(); $("#ci-model-history").replaceChildren(); $("#ci-model-history-box").hidden = true;
  }
  const RELATIONS = { MATCHES: "与规则阶段一致", EXTENDS: "补充规则未覆盖方向", MISMATCHES: "与规则阶段不一致（需人工核对）", UNCLEAR: "证据不足" };
  async function loadModelOption(reportId) {
    const current = version;
    try {
      const option = await request("/api/development/ci/model-option");
      if (current !== version || modelReportId !== reportId) return;
      $("#ci-model-option").textContent = option.available
        ? "模型服务：" + (option.displayName || "启动配置") + " · " + option.modelName
        : "未启用模型：" + (option.message || "请先在模型设置中启用。");
      $("#ci-model-preview-button").disabled = busy || !option.available;
    } catch (error) { if (current === version && modelReportId === reportId) modelNotice(error.message, true); }
  }
  async function previewModel() {
    if (modelBusy) return;
    const current = version, id = currentReportId();
    modelBusy = true; $("#ci-model-preview-button").disabled = true; modelNotice("正在准备发送范围预览…");
    try {
      const value = await request("/api/development/ci/reports/" + encodeURIComponent(id) + "/model/previews",
        { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Development": "1" }, body: "{}" });
      if (current !== version || id !== modelReportId) return;
      modelPreview = value;
      const target = $("#ci-model-preview"); target.hidden = false;
      target.replaceChildren(node("h3", "将发送到模型服务的范围"), node("p", value.workflowName + " · 作业 " + value.job.name + " · 尝试 " + value.attempt + " · 提交 " + value.headSha),
        node("p", "规则阶段：" + value.ruleTitle + "（" + value.ruleCategory + " · " + value.ruleStatus + "）"),
        node("p", "脱敏错误块：" + value.evidence.map(block => block.id + "（日志 " + block.startLine + "–" + block.endLine + " 行）").join("、")),
        ...value.omissions.map(text => node("p", text)),
        node("p", "接收模型：" + (value.model.displayName || "启动配置") + " · " + value.model.modelName + " · 十分钟有效"));
      $("#ci-model-consent-row").hidden = false;
      modelNotice("请核对发送范围后确认生成模型建议。");
    } catch (error) { if (current === version && id === modelReportId) modelNotice(error.message, true); }
    finally { if (current === version && id === modelReportId) { modelBusy = false; $("#ci-model-preview-button").disabled = false; } }
  }
  function currentReportId() {
    const meta = $("#ci-report-meta").dataset.reportId;
    if (!meta) throw new Error("请先打开一份 CI 报告。");
    return meta;
  }
  function renderAdvice(advice) {
    const target = $("#ci-model-result"); target.replaceChildren();
    const model = advice.model || {};
    target.append(node("p", "状态：" + model.state + " · " + (advice.createdAt ? date(advice.createdAt) : "")));
    if (model.usage) target.append(node("p", "已知 tokens：" + model.usage.totalTokens + "（输入 " + model.usage.inputTokens + " / 输出 " + model.usage.outputTokens + "）· " + (model.elapsedMs || 0) + " ms"));
    if (model.message) target.append(node("p", model.message));
    const suggestion = advice.suggestion;
    if (!suggestion) { target.append(node("p", "本次没有模型建议；规则报告保持不变。", "management-caption")); return; }
    target.append(node("h3", "总体判断"), node("p", suggestion.summary),
      node("p", "与规则阶段的关系：" + (RELATIONS[suggestion.stageRelation] || suggestion.stageRelation) + " · " + suggestion.knownStage));
    const list = (items, heading) => {
      target.append(node("h3", heading));
      if (!items.length) { target.append(node("p", "无。", "management-caption")); return; }
      for (const item of items) {
        const row = node("p"); row.append(node("strong", item.text)); row.append(node("span", "（引用 " + item.evidenceIds.join("、") + "）"));
        target.append(row);
      }
    };
    list(suggestion.hypotheses, "原因假设（待验证，不是已确认根因）");
    list(suggestion.verifications, "下一步待验证事项");
    target.append(node("p", "整体引用：" + suggestion.evidenceIds.join("、") + "。引用可在上方日志证据区核对原行；建议不构成自动操作。"));
  }
  async function analyzeModel() {
    if (modelBusy || !modelPreview || !$("#ci-model-confirm").checked) return;
    const current = version, id = currentReportId();
    modelBusy = true; $("#ci-model-analyze-button").disabled = true; modelNotice("正在发送已确认的范围并等待模型建议…");
    try {
      const advice = await request("/api/development/ci/reports/" + encodeURIComponent(id) + "/model/analyses",
        { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Development": "1" },
          body: JSON.stringify({ previewId: modelPreview.id, confirmed: true }) });
      if (current !== version || id !== modelReportId) return;
      renderAdvice(advice);
      modelNotice(advice.model && advice.model.state === "SUCCEEDED"
        ? "模型建议已生成；规则报告保持不变，假设与待验证事项需人工确认。"
        : "本次未生成模型建议；规则报告保持不变。");
      await loadModelHistory(id);
    } catch (error) { if (current === version && id === modelReportId) modelNotice(error.message, true); }
    finally { if (current === version && id === modelReportId) { modelBusy = false; $("#ci-model-analyze-button").disabled = true; modelPreview = null; $("#ci-model-confirm").checked = false; } }
  }
  async function loadModelHistory(reportId) {
    const current = version;
    try {
      const values = await request("/api/development/ci/reports/" + encodeURIComponent(reportId) + "/model/analyses");
      if (current !== version || modelReportId !== reportId) return;
      const target = $("#ci-model-history"); target.replaceChildren();
      $("#ci-model-history-box").hidden = !values.length;
      for (const advice of values) {
        const row = node("button", (advice.createdAt ? date(advice.createdAt) : "") + " · " + (advice.model ? advice.model.state : "UNKNOWN")
          + (advice.suggestion ? " · " + advice.suggestion.stageRelation : ""), "ci-history-item");
        row.type = "button"; row.addEventListener("click", () => renderAdvice(advice));
        target.append(row);
      }
    } catch (error) { if (current === version && modelReportId === reportId) modelNotice(error.message, true); }
  }
  $("#ci-model-preview-button").addEventListener("click", previewModel);
  $("#ci-model-confirm").addEventListener("change", () => { $("#ci-model-analyze-button").disabled = !modelPreview || !$("#ci-model-confirm").checked; });
  $("#ci-model-analyze-button").addEventListener("click", analyzeModel);
  window.ciPanel = { selectRepository(id) { if (id === repository) return; repository = id; version++; context = null; $("#ci-report-result").hidden = true; loadReports(); }, openRun, render, previewModel, analyzeModel };
})();
