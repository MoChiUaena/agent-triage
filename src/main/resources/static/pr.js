"use strict";
(() => {
  const $ = selector => document.querySelector(selector);
  const node = (tag, text, className) => { const value = document.createElement(tag); if (text != null) value.textContent = text; if (className) value.className = className; return value; };
  const date = value => new Date(value).toLocaleString("zh-CN", { hour12: false });
  const ciState = { INCOMPLETE: "CI 未完整读取", NOT_REPORTED: "未发现提交检查", OBSERVED_FAILURE: "已读取检查有失败或取消", PENDING: "检查尚未结束", OBSERVED_SUCCESS: "已读取检查通过", NO_SUCCESS_CONCLUSION: "检查没有全部成功结论" };
  const coverage = { COMPLETE: "补丁完整", METADATA_ONLY: "仅元数据，无增删行", NO_PATCH: "无补丁", INCOMPLETE: "补丁不完整" };
  const fileState = { added: "新增", removed: "删除", modified: "修改", renamed: "重命名", copied: "复制", changed: "变更", unchanged: "未变更" };
  let repository = "", context = null, busy = false, version = 0;
  let modelPreview = null, modelBusy = false, modelReportId = null;
  async function request(path, options = {}) {
    const response = await fetch(path, { cache: "no-store", ...options });
    if (!response.ok) { const problem = await response.json().catch(() => ({})); throw new Error(problem.detail || "读取 PR 失败 (" + response.status + ")"); }
    return response.json();
  }
  function link(text, url) {
    const value = node("a", text);
    try { const target = new URL(url); if (target.protocol === "https:" && target.hostname === "github.com" && !target.username && !target.password) value.href = target.href; } catch {}
    value.target = "_blank"; value.rel = "noopener noreferrer"; return value;
  }
  function message(text, error = false) { $("#pr-dialog-notice").textContent = text; $("#pr-dialog-notice").hidden = !text; $("#pr-dialog-notice").classList.toggle("error", error); }
  function ready() {
    $("#pr-review-button").disabled = busy || !context;
    $("#pr-close-dialog").disabled = busy; $("#pr-open-number").disabled = busy || !repository;
  }
  function render(report) {
    if (!report) return;
    $("#pr-report-result").hidden = false;
    $("#pr-report-title").textContent = "#" + report.pull.number + " " + report.pull.title;
    $("#pr-report-meta").textContent = report.repository + " · " + date(report.createdAt) + " · 保存时的版本与检查状态";
    $("#pr-report-meta").dataset.reportId = report.id;
    resetModelSection(report.id);
    loadModelOption(report.id);
    loadModelHistory(report.id);
    loadSourceProjects();
    $("#pr-report-notice").textContent = report.notice;
    $("#pr-report-versions").replaceChildren(...[["Base", report.pull.baseSha], ["Head", report.pull.headSha], ["Merge-base（删除行）", report.mergeBaseSha]].map(([name, sha]) => {
      const row = node("div", null, "pr-version"); row.append(node("span", name), node("code", sha)); return row;
    }));
    const followup = node("a", "继续追问"); followup.href = "/workspace.html?type=PR&report=" + encodeURIComponent(report.id) + "#conversations";
    const materialLink = node("a", "材料 / 发送预览"); materialLink.href = "/workspace.html?type=PR&repository=" + encodeURIComponent(report.repositoryId) + "&report=" + encodeURIComponent(report.id) + "#materials";
    $("#pr-report-link").replaceChildren(link("在 GitHub 查看 PR ↗", report.pull.url), node("span", " · "), materialLink);
    $("#pr-report-link").append(node("span", " · "), followup);
    $("#pr-report-counts").textContent = "读取 " + report.files.length + " / " + report.pull.changedFiles + " 个文件 · PR +" + report.pull.additions + " / −" + report.pull.deletions + " 行 · " + report.findings.length + " 条变更提醒";
    $("#pr-report-findings").replaceChildren(...report.findings.map(finding => {
      const card = node("section", null, "pr-finding");
      const heading = node("div", null, "pr-finding-heading"); heading.append(node("h4", finding.title), node("span", "待核对", "repository-label"));
      card.append(heading, link(finding.file + ":" + finding.line + " · " + (finding.side === "LEFT" ? "删除行" : "新增行"), finding.url),
        node("pre", finding.excerpt), node("p", finding.reason), node("p", "建议核查：" + finding.check, "management-caption")); return card;
    }));
    if (!report.findings.length) $("#pr-report-findings").append(node("p", "本次规则未命中变更提醒，不代表没有风险。请结合检查范围和 CI 核对改动。", "repository-list-empty"));
    $("#pr-report-ci-state").textContent = ciState[report.ci.state] || "检查状态未知";
    $("#pr-report-ci").replaceChildren(...report.ci.checks.map(check => {
      const item = node("li"); item.append(link(check.name, check.url), node("span", " · " + check.status + " / " + (check.conclusion || "等待结论"))); return item;
    }));
    if (!report.ci.checks.length) $("#pr-report-ci").append(node("li", "没有已读取的检查条目。"));
    $("#pr-report-issues").replaceChildren(...report.issues.map(issue => { const item = node("li"); item.append(link("#" + issue.number + " " + issue.title, issue.url), node("span", issue.state === "open" ? " · 开放" : " · 已关闭")); return item; }));
    if (!report.issues.length) $("#pr-report-issues").append(node("li", "没有已读取的同仓库 Issue 引用。"));
    $("#pr-report-gaps").replaceChildren(...report.gaps.map(text => node("li", text)));
    if (!report.gaps.length) $("#pr-report-gaps").append(node("li", "本次读取范围内未记录额外缺口；仍受下方检查范围限制。"));
    $("#pr-report-files").replaceChildren(...report.files.map(file => {
      const row = node("tr"); row.append(node("td", file.file + (file.previousFile ? " ← " + file.previousFile : "")), node("td", fileState[file.status] || file.status), node("td", "+" + file.additions + " / −" + file.deletions), node("td", coverage[file.coverage] || file.coverage)); return row;
    }));
    $("#pr-report-result").scrollIntoView({ behavior: "smooth", block: "start" });
  }
  async function loadReports() {
    const selected = repository, current = version;
    $("#pr-report-history").replaceChildren(); if (!selected) return;
    try {
      const reports = await request("/api/development/repositories/" + selected + "/pr/reports");
      if (current !== version) return;
      $("#pr-report-history").replaceChildren(...reports.map(report => {
        const button = node("button", "#" + report.pull.number + " " + report.pull.title + " · " + report.pull.headSha.substring(0, 7) + " · " + date(report.createdAt), "ci-history-item"); button.type = "button";
        button.addEventListener("click", () => { if (current === version) render(report); }); return button;
      }));
      if (!reports.length) $("#pr-report-history").append(node("p", "还没有保存的 PR 审查报告。", "repository-list-empty"));
    } catch (error) { if (current === version) $("#pr-report-history").replaceChildren(node("p", error.message, "repository-error")); }
  }
  async function openPull(selected, number) {
    if (busy || !selected || selected !== repository) return;
    const current = version; context = null; busy = true; message(""); ready();
    $("#pr-pull-context").textContent = "正在读取 PR 的当前版本…"; $("#pr-pull-versions").textContent = "";
    $("#pr-review-dialog").showModal();
    try {
      const result = await request("/api/development/repositories/" + selected + "/pr/pulls/" + number);
      if (current !== version) return; context = result;
      $("#pr-pull-context").textContent = "#" + result.number + " " + result.title + (result.draft ? " · 草稿" : "") + (result.state === "closed" ? " · 已关闭" : "");
      $("#pr-pull-versions").textContent = "Base " + result.baseSha + "\nHead " + result.headSha;
      message("将审查此版本的 " + result.changedFiles + " 个变更文件。过程中提交发生变化，会要求重新读取。");
    } catch (error) { if (current === version) message(error.message, true); }
    finally { if (current === version) { busy = false; ready(); } }
  }
  $("#pr-close-dialog").addEventListener("click", () => { if (!busy) $("#pr-review-dialog").close(); });
  $("#pr-review-dialog").addEventListener("cancel", event => { if (busy) event.preventDefault(); });
  $("#pr-refresh-reports").addEventListener("click", loadReports);
  $("#pr-number-form").addEventListener("submit", event => { event.preventDefault(); const value = Number($("#pr-number-input").value); if (Number.isSafeInteger(value) && value > 0 && value <= 2147483647) openPull(repository, value); });
  $("#pr-review-form").addEventListener("submit", async event => {
    event.preventDefault(); if (busy || !context) return;
    const selected = repository, current = version, input = { number: context.number, baseSha: context.baseSha, headSha: context.headSha };
    busy = true; ready(); message("正在读取固定版本差异、提交检查和 Issue 引用…");
    try {
      const report = await request("/api/development/repositories/" + selected + "/pr/reports", { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Development": "1" }, body: JSON.stringify(input) });
      if (current !== version) return; $("#pr-review-dialog").close(); render(report); await loadReports();
    } catch (error) { if (current === version) message(error.message, true); }
    finally { if (current === version) { busy = false; ready(); } }
  });
  const CONFIDENCE = { HIGH: "把握较高", MEDIUM: "把握中等", LOW: "把握较低（需重点人工核查）" };
  function modelNotice(text, error = false) {
    $("#pr-model-notice").textContent = text; $("#pr-model-notice").hidden = !text;
    $("#pr-model-notice").classList.toggle("error", error);
  }
  function resetModelSection(reportId) {
    modelReportId = reportId; modelPreview = null;
    $("#pr-model-option").textContent = ""; $("#pr-model-preview").replaceChildren(); $("#pr-model-preview").hidden = true;
    $("#pr-model-confirm").checked = false; $("#pr-model-code-confirm").checked = false;
    $("#pr-model-source").value = ""; $("#pr-model-source-note").textContent = "";
    $("#pr-model-consent-row").hidden = true; $("#pr-model-code-row").hidden = true; $("#pr-model-analyze-button").disabled = true;
    $("#pr-model-preview-button").disabled = true; modelNotice("");
    $("#pr-model-result").replaceChildren(); $("#pr-model-history").replaceChildren(); $("#pr-model-history-box").hidden = true;
  }
  function currentReportId() {
    const meta = $("#pr-report-meta").dataset.reportId;
    if (!meta) throw new Error("请先打开一份 PR 审查报告。");
    return meta;
  }
  async function loadModelOption(reportId) {
    const current = version;
    try {
      const option = await request("/api/development/pr/model-option");
      if (current !== version || modelReportId !== reportId) return;
      $("#pr-model-option").textContent = option.available
        ? "模型服务：" + (option.displayName || "启动配置") + " · " + option.modelName
        : "未启用模型：" + (option.message || "请先在模型设置中启用。");
      $("#pr-model-preview-button").disabled = busy || !option.available;
    } catch (error) { if (current === version && modelReportId === reportId) modelNotice(error.message, true); }
  }
  async function previewModel() {
    if (modelBusy) return;
    const current = version, id = currentReportId();
    modelBusy = true; $("#pr-model-preview-button").disabled = true; modelNotice("正在按报告冻结的 SHA 读取差异并准备发送范围…");
    try {
      const input = {};
      const source = $("#pr-model-source").value;
      const selected = sourceProjects.find(project => project.id === source);
      if (selected) { input.sourceProjectId = selected.id; input.sourceRevision = selected.revision; }
      const value = await request("/api/development/pr/reports/" + encodeURIComponent(id) + "/model/previews",
        { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Development": "1" }, body: JSON.stringify(input) });
      if (current !== version || id !== modelReportId) return;
      modelPreview = value;
      const target = $("#pr-model-preview"); target.hidden = false;
      const STATES = { MATCHED: "版本核对通过", VERSION_MISMATCH: "版本不一致，未采用", NOT_INDEXED: "不在本机索引", UNAVAILABLE: "不可用" };
      const children = [node("h3", "将发送到模型服务的范围"),
        node("p", "PR #" + value.number + " " + value.title + " · Base " + value.baseSha + " · Head " + value.headSha),
        node("p", "发送补丁文件：" + value.files.map(file => file.file + (file.clipped ? "（部分 hunk）" : "")).join("、")),
        node("p", "随补丁一并发送：" + value.findings.length + " 条规则提醒 · 检查状态 · " + value.issues.length + " 条 Issue 引用")];
      for (const entry of value.context || []) {
        const card = node("section", null, "pr-finding");
        card.append(node("h4", entry.file + " · " + (STATES[entry.state] || entry.state)), node("p", entry.message, "management-caption"));
        for (const symbol of entry.symbols || []) card.append(node("p", "摘录 " + symbol.method + "（" + symbol.startLine + "–" + symbol.endLine + " 行 · 本机上下文）", "management-caption"));
        for (const caller of entry.callers || []) card.append(node("p", "调用候选：" + caller.className + "." + caller.method + "（" + caller.line + " 行 · " + caller.state + "）", "management-caption"));
        if ((entry.implementations || []).length) card.append(node("p", "实现候选：" + entry.implementations.join("、"), "management-caption"));
        children.push(card);
      }
      children.push(...value.omissions.map(text => node("p", text)),
        node("p", "接收模型：" + (value.model.displayName || "启动配置") + " · " + value.model.modelName + " · 十分钟有效"));
      target.replaceChildren(...children);
      $("#pr-model-consent-row").hidden = false; $("#pr-model-code-row").hidden = false;
      modelNotice("请核对发送范围，并分别确认一般发送与源码补丁外发。");
    } catch (error) { if (current === version && id === modelReportId) modelNotice(error.message, true); }
    finally { if (current === version && id === modelReportId) { modelBusy = false; $("#pr-model-preview-button").disabled = false; } }
  }
  function renderAdvice(advice) {
    const target = $("#pr-model-result"); target.replaceChildren();
    const model = advice.model || {};
    target.append(node("p", "状态：" + model.state + " · 审查 SHA " + advice.headSha + " · " + (advice.createdAt ? date(advice.createdAt) : "")));
    if (model.usage) target.append(node("p", "已知 tokens：" + model.usage.totalTokens + "（输入 " + model.usage.inputTokens + " / 输出 " + model.usage.outputTokens + "）· " + (model.elapsedMs || 0) + " ms"));
    if (model.message) target.append(node("p", model.message));
    const suggestion = advice.suggestion;
    if (!suggestion) { target.append(node("p", "本次没有模型建议；规则报告保持不变，不构成合入批准。", "management-caption")); return; }
    target.append(node("h3", "总体判断"), node("p", suggestion.summary),
      node("p", "置信边界：" + (CONFIDENCE[suggestion.confidence] || suggestion.confidence) + " · 审查 SHA " + suggestion.reviewedSha),
      node("h3", "变更意图（从补丁推断，待核对）"), node("p", suggestion.intent));
    const list = (items, heading) => {
      target.append(node("h3", heading));
      if (!items.length) { target.append(node("p", "无。", "management-caption")); return; }
      for (const item of items) {
        const row = node("p"); row.append(node("strong", item.text));
        row.append(node("span", "（引用 " + item.references.map(ref => ref.file + ":" + ref.line
          + (ref.side === "OLD" ? " 删除行" : ref.side === "CONTEXT" ? " 本机上下文" : " 新增行")).join("、") + "）"));
        target.append(row);
      }
    };
    list(suggestion.risks, "潜在风险（待核对假设）");
    list(suggestion.testGaps, "测试缺口（相对本次改动可见范围）");
    target.append(node("p", "以上均为辅助审阅建议；引用可在规则报告与补丁范围内核对，不构成合入批准，也不会自动修改代码。"));
  }
  async function analyzeModel() {
    if (modelBusy || !modelPreview || !$("#pr-model-confirm").checked || !$("#pr-model-code-confirm").checked) return;
    const current = version, id = currentReportId();
    modelBusy = true; $("#pr-model-analyze-button").disabled = true; modelNotice("正在发送已确认的范围并等待模型建议…");
    try {
      const advice = await request("/api/development/pr/reports/" + encodeURIComponent(id) + "/model/analyses",
        { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Development": "1" },
          body: JSON.stringify({ previewId: modelPreview.id, confirmed: true, codeConfirmed: true }) });
      if (current !== version || id !== modelReportId) return;
      renderAdvice(advice);
      modelNotice(advice.model && advice.model.state === "SUCCEEDED"
        ? "模型建议已生成；规则报告保持不变，意图与风险为待核对假设，不构成合入批准。"
        : "本次未生成模型建议；规则报告保持不变。");
      await loadModelHistory(id);
    } catch (error) { if (current === version && id === modelReportId) modelNotice(error.message, true); }
    finally { if (current === version && id === modelReportId) { modelBusy = false; $("#pr-model-analyze-button").disabled = true; modelPreview = null; $("#pr-model-confirm").checked = false; $("#pr-model-code-confirm").checked = false; } }
  }
  async function loadModelHistory(reportId) {
    const current = version;
    try {
      const values = await request("/api/development/pr/reports/" + encodeURIComponent(reportId) + "/model/analyses");
      if (current !== version || modelReportId !== reportId) return;
      const target = $("#pr-model-history"); target.replaceChildren();
      $("#pr-model-history-box").hidden = !values.length;
      for (const advice of values) {
        const row = node("button", (advice.createdAt ? date(advice.createdAt) : "") + " · " + (advice.model ? advice.model.state : "UNKNOWN")
          + (advice.suggestion ? " · " + advice.suggestion.confidence : ""), "ci-history-item");
        row.type = "button"; row.addEventListener("click", () => renderAdvice(advice));
        target.append(row);
      }
    } catch (error) { if (current === version && modelReportId === reportId) modelNotice(error.message, true); }
  }
  let sourceProjects = [];
  async function loadSourceProjects() {
    const current = version;
    try {
      const values = await request("/api/source-projects");
      if (current !== version) return;
      sourceProjects = (values || []).slice(0, 20);
      const select = $("#pr-model-source");
      const previous = select.value;
      select.replaceChildren(...[node("option", "不附带本机源码上下文")].concat(sourceProjects.map(project => {
        const option = node("option", project.name + " · revision " + project.revision + " · " + project.files + " 文件");
        option.value = project.id; return option;
      })));
      if (sourceProjects.some(project => project.id === previous)) select.value = previous;
      $("#pr-model-source-note").textContent = sourceProjects.length
        ? "上下文随预览一起展示发送范围；源码内容外发同样受下方 codeConfirmed 约束。"
        : "尚未登记本机 Java 项目；可在项目源码页登记并索引后再附带上下文。";
    } catch (error) { if (current === version) $("#pr-model-source-note").textContent = "源码项目列表读取失败：" + error.message; }
  }
  $("#pr-model-source").addEventListener("change", () => {
    const selected = sourceProjects.find(project => project.id === $("#pr-model-source").value);
    $("#pr-model-source-note").textContent = selected
      ? "将按 revision " + selected.revision + " 构建上下文；该索引变化后需重新预览。"
      : "";
  });
  $("#pr-model-preview-button").addEventListener("click", previewModel);
  for (const name of ["confirm", "code-confirm"]) $("#pr-model-" + name).addEventListener("change", () => {
    $("#pr-model-analyze-button").disabled = !modelPreview || !$("#pr-model-confirm").checked || !$("#pr-model-code-confirm").checked;
  });
  $("#pr-model-analyze-button").addEventListener("click", analyzeModel);
  window.prPanel = { selectRepository(id) { if (id === repository) return; repository = id; version++; context = null; busy = false; $("#pr-review-dialog").close(); $("#pr-report-result").hidden = true; ready(); loadReports(); }, openPull, render, previewModel, analyzeModel };
})();
