"use strict";
const $ = selector => document.querySelector(selector);
let repositories = [], selectionVersion = 0, listVersion = 0, busy = false;
const failures = ["failure", "timed_out", "action_required", "startup_failure"];
function element(tag, text, className) { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
function notice(text, error = false) { $("#development-notice").textContent = text; $("#development-notice").hidden = !text; $("#development-notice").classList.toggle("error", error); }
async function request(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  if (!response.ok) { const problem = await response.json().catch(() => ({})); throw new Error(problem.detail || "请求失败 (" + response.status + ")"); }
  return response.status === 204 ? null : response.json();
}
const writeHeaders = { "Content-Type": "application/json", "X-Triage-Development": "1" };
function time(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—"; }
function link(text, url) {
  const anchor = element("a", text);
  try { const target = new URL(url); if (target.protocol === "https:" && target.hostname === "github.com") anchor.href = target.href; } catch {}
  anchor.target = "_blank"; anchor.rel = "noopener noreferrer"; return anchor;
}
function setBusy(value) {
  busy = value; $("#register-button").disabled = value; $("#repository-select").disabled = value;
  for (const id of ["#refresh-repository", "#remove-repository"]) $(id).disabled = value || !$("#repository-select").value;
}
function renderList(selector, section, render) {
  const target = $(selector); target.replaceChildren();
  if (!section.available) { target.append(element("p", section.message || "读取失败，请更新状态后重试。", "repository-error")); return; }
  if (!section.items.length) { target.append(element("p", "本次读取没有条目。", "repository-list-empty")); return; }
  target.append(...section.items.map(render));
}
function statistic(title, count, caption) { const card = element("div", null, "repository-statistic"); card.append(element("p", title), element("strong", count), element("small", caption)); return card; }
function scope(section, text) { return section.available ? text + (section.truncated ? " · 还有更多条目" : "") : "读取失败"; }
const SECTION_NAMES = { ISSUES: "开放 Issue 抽样", PULL_REQUESTS: "开放 PR 抽样", WORKFLOWS: "默认分支近期工作流" };
let healthReport = null;
function setHealthBusy(value) { $("#health-generate").disabled = value || !$("#repository-select").value; }
function renderHealthReport(report) {
  healthReport = report;
  const target = $("#health-summary-result"); target.hidden = false; target.replaceChildren();
  const heading = element("div", null, "repository-heading");
  heading.append(element("h3", "健康摘要 · " + time(report.createdAt)),
    element("span", report.stale ? "快照过期或上次读取失败" : "快照有效", "repository-label"));
  target.append(heading);
  const list = element("ul");
  for (const section of report.sections) {
    const item = element("li");
    if (!section.available) item.append(element("span", SECTION_NAMES[section.kind] + "：未读取", "repository-error"));
    else {
      item.append(element("span", SECTION_NAMES[section.kind] + "：列出 " + section.listed + " 条"
        + (section.truncated ? "（截断，覆盖不完整）" : "（抽样，最多 20 条）")
        + (section.kind === "WORKFLOWS" ? " · 失败 " + section.failing + " 次" : "")));
      const runs = element("ul");
      for (const run of section.failingRuns || []) { const row = element("li"); row.append(link(run.name + " · " + run.conclusion, run.url)); runs.append(row); }
      if ((section.failingRuns || []).length) item.append(runs);
    }
    list.append(item);
  }
  for (const record of report.localRecords) {
    const item = element("li");
    item.append(element("span", (record.kind === "CI" ? "CI 排障" : record.kind === "PR" ? "PR 审查" : "Issue 分诊")
      + " 本机记录 " + record.saved + " 条" + (record.latestAt ? " · 最近 " + time(record.latestAt) + " · " + record.latestTitle : " · 暂无")));
    list.append(item);
  }
  target.append(list);
  const notes = element("ul");
  for (const note of report.notes) notes.append(element("li", note, "management-caption"));
  target.append(notes);
}
async function loadHealthSummaries(id, current) {
  try {
    const values = await request("/api/development/repositories/" + encodeURIComponent(id) + "/health-summaries");
    if (current !== selectionVersion) return;
    const target = $("#health-summary-history"); target.replaceChildren();
    $("#health-summary-history-box").hidden = !values.length;
    for (const report of values) {
      const row = element("button", time(report.createdAt) + (report.stale ? " · 快照过期" : ""), "ci-history-item");
      row.type = "button"; row.addEventListener("click", () => renderHealthReport(report));
      target.append(row);
    }
  } catch (error) { if (current === selectionVersion) notice("健康摘要历史读取失败：" + error.message, true); }
}
async function generateHealthSummary() {
  if (busy) return; const id = $("#repository-select").value; if (!id) return;
  const current = selectionVersion; setHealthBusy(true); notice("正在按规则生成健康摘要…");
  try {
    const report = await request("/api/development/repositories/" + encodeURIComponent(id) + "/health-summaries",
      { method: "POST", headers: writeHeaders });
    if (current !== selectionVersion) return;
    renderHealthReport(report); await loadHealthSummaries(id, current);
    notice("健康摘要已生成：基于快照与本机记录，未访问 GitHub 或调用模型。");
  } catch (error) { if (current === selectionVersion) notice(error.message, true); }
  finally { if (current === selectionVersion) setHealthBusy(false); }
}
$("#health-generate").addEventListener("click", generateHealthSummary);
$("#repository-select").addEventListener("change", () => { setHealthBusy(!$("#repository-select").value); setActivityBusy(!$("#repository-select").value); setDraftBusy(!$("#repository-select").value); });
const ACTIVITY_KINDS = { COMMIT: "提交", ISSUE: "Issue", PULL_REQUEST: "PR", WORKFLOW: "工作流",
  CI_REPORT: "CI 排障报告", PR_REPORT: "PR 审查报告", ISSUE_REPORT: "Issue 分诊报告" };
const ACTION_KINDS = { FAILING_WORKFLOW: "失败工作流", OPEN_ISSUE: "开放 Issue", OPEN_PULL_REQUEST: "开放 PR" };
let activityReport = null;
function setActivityBusy(value) {
  for (const id of ["#activity-generate", "#activity-today", "#activity-week"]) $(id).disabled = value || !$("#repository-select").value;
}
function localIso(date) {
  const pad = value => String(value).padStart(2, "0");
  return date.getFullYear() + "-" + pad(date.getMonth() + 1) + "-" + pad(date.getDate());
}
function activityRow(item) {
  const row = element("li");
  row.append(element("span", (ACTIVITY_KINDS[item.kind] || item.kind) + " · ", "repository-label"), link(item.title, item.url));
  if (item.detail) row.append(element("small", " " + item.detail));
  if (item.reportId) {
    const open = element("button", "打开本机报告", "secondary ci-open-button"); open.type = "button";
    open.addEventListener("click", () => {
      window.location.href = "/development.html?repository=" + encodeURIComponent(activityReport.repositoryId)
        + "&reportType=" + item.reportType + "&report=" + encodeURIComponent(item.reportId);
    });
    row.append(open);
  }
  return row;
}
function renderActivityReport(report) {
  activityReport = report;
  const target = $("#activity-result"); target.hidden = false; target.replaceChildren();
  const heading = element("div", null, "repository-heading");
  heading.append(element("h3", "活动报告 · " + report.fromDate + " 至 " + report.toDate),
    element("span", report.stale ? "快照过期或上次读取失败" : "快照有效", "repository-label"));
  target.append(heading);
  target.append(element("p", "生成于 " + time(report.createdAt) + " · 快照读取 " + time(report.snapshotReadAt), "management-caption"));
  if (!report.days.length) target.append(element("p", "所选日期范围内没有匹配的活动记录。"));
  for (const day of report.days) {
    target.append(element("h4", day.date));
    const list = element("ul");
    for (const item of day.items) list.append(activityRow(item));
    target.append(list);
  }
  target.append(element("h4", "待处理事项"));
  if (!report.actionItems.length) target.append(element("p", "当前快照抽样内没有待处理事项。"));
  else {
    const actions = element("ul");
    for (const item of report.actionItems) {
      const row = element("li");
      row.append(element("span", (ACTION_KINDS[item.kind] || item.kind) + " · ", "repository-label"), link(item.title, item.url));
      if (item.detail) row.append(element("small", " " + item.detail));
      actions.append(row);
    }
    target.append(actions);
  }
  target.append(element("p", report.localRecords.map(record =>
    (record.kind === "CI" ? "CI 排障" : record.kind === "PR" ? "PR 审查" : "Issue 分诊")
    + " 范围内 " + record.inRange + " 条（窗口 " + record.saved + " 条，最多 20 条）").join(" · "), "management-caption"));
  if (report.gaps.length) {
    target.append(element("h4", "读取缺口"));
    const gaps = element("ul");
    for (const gap of report.gaps) gaps.append(element("li", gap, "repository-error"));
    target.append(gaps);
  }
  target.append(element("h4", "说明"));
  const notes = element("ul");
  for (const note of report.notes) notes.append(element("li", note, "management-caption"));
  target.append(notes);
  const exportButton = element("button", "导出 Markdown", "secondary"); exportButton.type = "button";
  exportButton.addEventListener("click", exportActivityMarkdown);
  target.append(exportButton);
}
async function loadActivityReports(id, current) {
  try {
    const values = await request("/api/development/repositories/" + encodeURIComponent(id) + "/activity-reports");
    if (current !== selectionVersion) return;
    const target = $("#activity-history"); target.replaceChildren();
    $("#activity-history-box").hidden = !values.length;
    for (const report of values) {
      const row = element("button", report.fromDate + " 至 " + report.toDate + " · " + time(report.createdAt)
        + (report.stale ? " · 快照过期" : ""), "ci-history-item");
      row.type = "button"; row.addEventListener("click", () => renderActivityReport(report));
      target.append(row);
    }
  } catch (error) { if (current === selectionVersion) notice("活动报告历史读取失败：" + error.message, true); }
}
async function generateActivityReport(event) {
  event.preventDefault();
  if (busy) return; const id = $("#repository-select").value; if (!id) return;
  const from = $("#activity-from").value, to = $("#activity-to").value;
  if (!from || !to) { notice("请选择开始与结束日期。", true); return; }
  const current = selectionVersion; setActivityBusy(true); notice("正在按规则生成活动报告…");
  try {
    const report = await request("/api/development/repositories/" + encodeURIComponent(id) + "/activity-reports",
      { method: "POST", headers: writeHeaders, body: JSON.stringify({ fromDate: from, toDate: to }) });
    if (current !== selectionVersion) return;
    renderActivityReport(report); await loadActivityReports(id, current);
    notice("活动报告已生成：基于快照与本机记录，未访问 GitHub 或调用模型。");
  } catch (error) { if (current === selectionVersion) notice(error.message, true); }
  finally { if (current === selectionVersion) setActivityBusy(false); }
}
async function exportActivityMarkdown() {
  if (!activityReport) return;
  try {
    const response = await fetch("/api/development/activity-reports/" + encodeURIComponent(activityReport.id) + "/markdown",
      { headers: { "X-Triage-Development": "1" } });
    if (!response.ok) throw new Error("请求失败 (" + response.status + ")");
    const text = await response.text();
    if (typeof Blob === "function" && typeof URL.createObjectURL === "function") {
      const url = URL.createObjectURL(new Blob([text], { type: "text/markdown;charset=UTF-8" }));
      const anchor = element("a"); anchor.href = url; anchor.download = "activity-report-" + activityReport.id + ".md";
      if (document.body?.append) { document.body.append(anchor); anchor.click?.(); URL.revokeObjectURL(url); }
      notice("Markdown 已导出（仅本机下载，未发送任何内容）。");
    } else notice("Markdown 已生成 " + text.length + " 字符（当前环境不支持文件下载）。");
  } catch (error) { notice("Markdown 导出失败：" + error.message, true); }
}
$("#activity-form").addEventListener("submit", generateActivityReport);
$("#activity-today").addEventListener("click", () => { const today = localIso(new Date()); $("#activity-from").value = today; $("#activity-to").value = today; });
$("#activity-week").addEventListener("click", () => {
  const now = new Date(); $("#activity-to").value = localIso(now); $("#activity-from").value = localIso(new Date(now.getTime() - 6 * 86400000));
});
const DRAFT_KINDS = { COMMENT: "评论草稿", LABEL: "标签草稿" };
let draftBasis = [];
function setDraftBusy(value) {
  $("#draft-create").disabled = value || !$("#repository-select").value || !$("#draft-basis").value;
}
function selectedBasis() { return draftBasis.find(basis => $("#draft-basis").value === basis.type + ":" + basis.id) || null; }
function syncDraftKind() {
  const basis = selectedBasis();
  if (basis && basis.type === "PR_REPORT" && $("#draft-kind").value === "LABEL") $("#draft-kind").value = "COMMENT";
  $("#draft-kind-note").textContent = basis && basis.type === "PR_REPORT" ? "标签草稿仅支持 Issue 分诊报告作为依据（提交时服务端同样校验）。" : "";
}
async function loadDraftBasisOptions(id, current) {
  const target = $("#draft-basis");
  try {
    const [issues, pulls] = await Promise.all([
      request("/api/development/repositories/" + encodeURIComponent(id) + "/issue/reports"),
      request("/api/development/repositories/" + encodeURIComponent(id) + "/pr/reports")]);
    if (current !== selectionVersion) return;
    draftBasis = [
      ...issues.map(report => ({ type: "ISSUE_REPORT", id: report.id, label: "Issue 分诊 #" + report.issue.number + " " + report.issue.title })),
      ...pulls.map(report => ({ type: "PR_REPORT", id: report.id, label: "PR 审查 #" + report.pull.number + " " + report.pull.title }))];
    target.replaceChildren();
    const empty = element("option", draftBasis.length ? "选择依据报告" : "本仓库暂无已保存的 Issue / PR 报告");
    empty.value = ""; target.append(empty);
    for (const basis of draftBasis) {
      const option = element("option", basis.label);
      option.value = basis.type + ":" + basis.id;
      target.append(option);
    }
  } catch (error) {
    if (current === selectionVersion) { draftBasis = []; target.replaceChildren(element("option", "依据报告读取失败")); }
  } finally { if (current === selectionVersion) { syncDraftKind(); setDraftBusy(false); } }
}
function draftCard(draft) {
  const card = element("article", null, "management-card");
  const heading = element("div", null, "repository-heading");
  heading.append(element("h4", (DRAFT_KINDS[draft.kind] || draft.kind) + " · " + (draft.target.objectType === "ISSUE" ? "Issue" : "PR") + " #" + draft.target.number),
    element("span", "草稿 · 未执行", "repository-label"));
  card.append(heading);
  const targetRow = element("p");
  targetRow.append(element("span", "目标：", "repository-label"), link(draft.target.title, draft.target.url));
  card.append(targetRow);
  const basisRow = element("p", null, "management-caption");
  basisRow.append(element("span", "依据：" + draft.basis.title + "（" + time(draft.basis.createdAt) + " 保存）"));
  const basisOpen = element("button", "查看依据报告", "secondary ci-open-button"); basisOpen.type = "button";
  basisOpen.addEventListener("click", () => {
    window.location.href = "/development.html?repository=" + encodeURIComponent(draft.repositoryId)
      + "&reportType=" + (draft.basis.type === "ISSUE_REPORT" ? "issue" : "pr") + "&report=" + encodeURIComponent(draft.basis.reportId);
  });
  basisRow.append(basisOpen);
  card.append(basisRow);
  card.append(element("p", "目标版本：" + (draft.target.headSha ? "head " + draft.target.headSha.substring(0, 7) : "更新于 " + time(draft.target.updatedAt))
    + " · 修订 " + draft.revision + " · 更新 " + time(draft.updatedAt), "management-caption"));
  const bodyLabel = element("label", draft.kind === "LABEL" ? "标签说明（可选）" : "评论正文", "management-caption");
  const bodyInput = element("textarea");
  bodyInput.value = draft.body; bodyInput.rows = draft.kind === "LABEL" ? 2 : 6;
  bodyLabel.append(bodyInput); card.append(bodyLabel);
  let addInput = null, removeInput = null;
  if (draft.kind === "LABEL") {
    const addRow = element("label", "添加标签（逗号分隔，最多 10 个）", "management-caption");
    addInput = element("input"); addInput.value = draft.addLabels.join(", ");
    addRow.append(addInput); card.append(addRow);
    const removeRow = element("label", "移除标签（逗号分隔，最多 10 个）", "management-caption");
    removeInput = element("input"); removeInput.value = draft.removeLabels.join(", ");
    removeRow.append(removeInput); card.append(removeRow);
  }
  const controls = element("div", null, "workflow-controls");
  const save = element("button", "保存修改", "primary"); save.type = "button";
  const discardButton = element("button", "丢弃草稿", "secondary"); discardButton.type = "button";
  const note = element("span", "", "management-caption");
  controls.append(save, discardButton, note); card.append(controls);
  const executionBox = element("div", null, "draft-execution-box");
  card.append(executionBox);
  if (draftWriteEnabled) {
    const previewButton = element("button", "预览执行", "secondary"); previewButton.type = "button";
    previewButton.addEventListener("click", () => loadExecutionPreview(draft, executionBox, record => {
      if (record.state === "SUCCESS") {
        previewButton.hidden = true; save.hidden = true; discardButton.hidden = true; bodyInput.disabled = true;
        if (addInput) { addInput.disabled = true; removeInput.disabled = true; }
        heading.children[1].textContent = "已执行";
      }
      const current = $("#repository-select").value;
      if (current) loadDraftExecutions(current, selectionVersion);
    }));
    controls.append(previewButton);
  }
  const labelValues = value => value.split(",").map(item => item.trim()).filter(Boolean);
  save.addEventListener("click", async () => {
    save.disabled = true;
    try {
      const updated = await request("/api/development/drafts/" + encodeURIComponent(draft.id), {
        method: "PUT", headers: writeHeaders,
        body: JSON.stringify(draft.kind === "COMMENT"
          ? { revision: draft.revision, body: bodyInput.value }
          : { revision: draft.revision, body: bodyInput.value, addLabels: labelValues(addInput.value), removeLabels: labelValues(removeInput.value) }) });
      draft = updated; bodyInput.value = updated.body;
      if (updated.kind === "LABEL") { addInput.value = updated.addLabels.join(", "); removeInput.value = updated.removeLabels.join(", "); }
      note.textContent = "已保存（修订 " + updated.revision + "）；草稿仍未执行。";
      refreshDraftsQuietly();
    } catch (error) { note.textContent = error.message; }
    finally { save.disabled = false; }
  });
  discardButton.addEventListener("click", async () => {
    discardButton.disabled = true;
    try {
      await request("/api/development/drafts/" + encodeURIComponent(draft.id) + "/discard", { method: "POST", headers: writeHeaders });
      notice("草稿已丢弃；未对 GitHub 执行任何操作。");
      await refreshDraftsQuietly();
    } catch (error) { notice(error.message, true); discardButton.disabled = false; }
  });
  return card;
}
async function loadDrafts(id, current) {
  try {
    const values = await request("/api/development/repositories/" + encodeURIComponent(id) + "/drafts");
    if (current !== selectionVersion) return;
    const target = $("#draft-list"); target.replaceChildren();
    if (!values.length) { target.append(element("p", "暂无未丢弃的草稿。可从一份已保存的分诊或审查报告生成第一条草稿。")); return; }
    for (const draft of values) target.append(draftCard(draft));
  } catch (error) { if (current === selectionVersion) notice("草稿列表读取失败：" + error.message, true); }
}
async function refreshDraftsQuietly() {
  const id = $("#repository-select").value; if (!id) return;
  await loadDrafts(id, selectionVersion);
}
async function createDraft(event) {
  event.preventDefault();
  if (busy) return;
  const id = $("#repository-select").value; if (!id) return;
  const basis = selectedBasis();
  if (!basis) { notice("请选择依据报告。", true); return; }
  const current = selectionVersion; setDraftBusy(true); notice("正在从本机报告生成草稿…");
  try {
    await request("/api/development/repositories/" + encodeURIComponent(id) + "/drafts",
      { method: "POST", headers: writeHeaders, body: JSON.stringify({ kind: $("#draft-kind").value, basisReportId: basis.id }) });
    if (current !== selectionVersion) return;
    notice("草稿已生成（未执行）：保存在本机，不会发布到 GitHub。");
    await loadDrafts(id, current);
  } catch (error) { if (current === selectionVersion) notice(error.message, true); }
  finally { if (current === selectionVersion) setDraftBusy(false); }
}
$("#draft-form").addEventListener("submit", createDraft);
$("#draft-basis").addEventListener("change", () => { syncDraftKind(); setDraftBusy(false); });
$("#draft-kind").addEventListener("change", syncDraftKind);
let draftWriteEnabled = false;
const EXECUTION_STATES = { SUCCESS: "执行成功", FAILED: "执行失败", UNCERTAIN: "结果不确定" };
async function loadDraftWriteOption() {
  try {
    const option = await request("/api/development/drafts/write-option");
    draftWriteEnabled = !!option.enabled;
    $("#draft-write-option").textContent = option.message;
  } catch (error) { draftWriteEnabled = false; $("#draft-write-option").textContent = "写入配置读取失败：" + error.message; }
}
async function loadDraftExecutions(id, current) {
  try {
    const values = await request("/api/development/repositories/" + encodeURIComponent(id) + "/draft-executions");
    if (current !== selectionVersion) return;
    const target = $("#draft-executions"); target.replaceChildren();
    $("#draft-executions-box").hidden = !values.length;
    for (const record of values) target.append(executionRow(record));
  } catch (error) { if (current === selectionVersion) $("#draft-executions-box").hidden = true; }
}
function executionRow(record) {
  const row = element("li");
  row.append(element("span", (EXECUTION_STATES[record.state] || record.state) + " · " + time(record.executedAt)
    + " · " + (record.kind === "LABEL" ? "标签" : "评论") + " #" + record.target.number, "repository-label"));
  if (record.resultUrl) row.append(link("查看结果", record.resultUrl));
  if (record.resultSummary) row.append(element("span", " " + record.resultSummary, "management-caption"));
  if (record.message) row.append(element("span", " " + record.message, "repository-error"));
  return row;
}
async function loadExecutionPreview(draft, box, onExecuted) {
  box.replaceChildren(element("p", "正在核对目标、权限与版本…", "management-caption"));
  try {
    const preview = await request("/api/development/drafts/" + encodeURIComponent(draft.id) + "/execution-previews",
      { method: "POST", headers: writeHeaders });
    renderExecutionPreview(draft, preview, box, onExecuted);
  } catch (error) { box.replaceChildren(element("p", "执行预览失败：" + error.message, "repository-error")); }
}
function renderExecutionPreview(draft, preview, box, onExecuted) {
  box.replaceChildren();
  box.append(element("h4", "执行预览 · " + preview.operation));
  const meta = element("ul");
  const targetLine = element("li"); targetLine.append(element("span", "目标：", "repository-label"),
    link((preview.target.objectType === "ISSUE" ? "Issue #" : "PR #") + preview.target.number + " " + preview.target.title, preview.target.url));
  meta.append(targetLine);
  meta.append(element("li", "权限：" + (preview.permitted ? preview.permissionMessage : "写入凭据没有推送权限。")));
  if (preview.kind === "LABEL") {
    const labelTexts = values => (values || []).join("、") || "（无）";
    meta.append(element("li", "当前标签：" + labelTexts(preview.currentLabels)));
    meta.append(element("li", "执行后标签：" + labelTexts(preview.resultingLabels)));
  } else meta.append(element("li", "将发布以上草稿正文为一条评论。"));
  meta.append(element("li", "读取时间：" + time(preview.readAt) + " · 十分钟内有效且只能使用一次；执行前会再次核对目标状态。"));
  box.append(meta);
  const consent = element("label", null, "management-caption");
  const checkbox = element("input"); checkbox.type = "checkbox";
  consent.append(checkbox, element("span", "我已核对以上目标、内容与影响，确认对该 GitHub 对象执行此操作。"));
  const executeButton = element("button", "确认执行", "primary"); executeButton.type = "button"; executeButton.disabled = true;
  checkbox.addEventListener("change", () => { executeButton.disabled = !checkbox.checked; });
  executeButton.addEventListener("click", async () => {
    executeButton.disabled = true;
    box.append(element("p", "正在执行并等待 GitHub 响应…", "management-caption"));
    try {
      const record = await request("/api/development/drafts/" + encodeURIComponent(draft.id) + "/executions",
        { method: "POST", headers: writeHeaders, body: JSON.stringify({ previewId: preview.id, confirmed: true }) });
      renderExecutionResult(record, box, draft);
      if (onExecuted) onExecuted(record);
    } catch (error) {
      box.append(element("p", "执行失败：" + error.message, "repository-error"));
      executeButton.disabled = false;
    }
  });
  box.append(consent, executeButton);
  const notes = element("ul");
  for (const note of preview.notes || []) notes.append(element("li", note, "management-caption"));
  box.append(notes);
}
function renderExecutionResult(record, box, draft) {
  box.replaceChildren();
  const heading = element("h4", (EXECUTION_STATES[record.state] || record.state) + " · " + time(record.executedAt));
  box.append(heading);
  if (record.resultUrl) { const row = element("p"); row.append(link("在 GitHub 查看结果", record.resultUrl)); box.append(row); }
  if (record.resultSummary) box.append(element("p", record.resultSummary, "management-caption"));
  if (record.message) box.append(element("p", record.message, record.state === "SUCCESS" ? "management-caption" : "repository-error"));
  box.append(element("p", "已写入本机执行审计（不保存凭据）；本次确认时间 " + time(record.confirmedAt) + "。", "management-caption"));
}
let workflowCatalog = null;
let templateCheckboxes = {};
function setTemplateBusy(value) {
  $("#workflow-template-create").disabled = value || !$("#repository-select").value || selectedTemplateTasks().length < 2;
}
function selectedTemplateTasks() {
  if (!Array.isArray(workflowCatalog)) return [];
  return workflowCatalog.filter(entry => entry.id === "SUMMARY" || templateCheckboxes[entry.id]?.checked).map(entry => entry.id);
}
async function loadWorkflowCatalog() {
  if (Array.isArray(workflowCatalog)) return;
  try {
    const values = await request("/api/development/workflow-task-catalog");
    if (!Array.isArray(values) || !values.length) throw new Error("目录响应无效");
    workflowCatalog = values;
    templateCheckboxes = {};
    const box = $("#workflow-template-tasks"); box.replaceChildren();
    for (const entry of values) {
      const label = element("label", null, "management-caption");
      const checkbox = element("input"); checkbox.type = "checkbox"; checkbox.id = "template-task-" + entry.id;
      checkbox.checked = entry.id === "SUMMARY" || ["REPOSITORY", "PR", "CHECK"].includes(entry.id);
      checkbox.disabled = entry.id === "SUMMARY";
      checkbox.addEventListener("change", setTemplateBusy);
      templateCheckboxes[entry.id] = checkbox;
      label.append(checkbox, element("span", entry.title + "（" + entry.id + (entry.requires.length ? " · 前置 " + entry.requires.join("/") : "") + "）" + entry.description));
      box.append(label);
    }
    setTemplateBusy(false);
  } catch (error) { notice("任务目录读取失败：" + error.message, true); }
}
async function createWorkflowTemplate(event) {
  event.preventDefault();
  if (busy) return;
  const id = $("#repository-select").value; if (!id) return;
  const name = $("#workflow-template-name").value.trim();
  if (!name) { notice("请填写模板名称。", true); return; }
  const current = selectionVersion; setTemplateBusy(true); notice("正在保存模板…");
  try {
    await request("/api/development/repositories/" + encodeURIComponent(id) + "/workflow-templates",
      { method: "POST", headers: writeHeaders, body: JSON.stringify({ name, tasks: selectedTemplateTasks().map(task => ({ id: task })) }) });
    if (current !== selectionVersion) return;
    $("#workflow-template-name").value = "";
    notice("模板已保存；可在下方按 PR 编号生成计划并执行。");
    await loadWorkflowTemplates(id, current);
  } catch (error) { if (current === selectionVersion) notice(error.message, true); }
  finally { if (current === selectionVersion) setTemplateBusy(false); }
}
async function runWorkflowTemplate(template) {
  const id = $("#repository-select").value; if (!id) return;
  const input = $("#template-run-" + template.id);
  const prNumber = input && input.value ? Number(input.value) : 0;
  if (!prNumber || prNumber < 1) { notice("请填写模板执行使用的 PR 编号。", true); return; }
  notice("正在按模板创建计划…");
  try {
    const plan = await request("/api/development/repositories/" + encodeURIComponent(id) + "/workflow-templates/" + encodeURIComponent(template.id) + "/plans",
      { method: "POST", headers: writeHeaders, body: JSON.stringify({ prNumber }) });
    const run = await request("/api/development/repositories/" + encodeURIComponent(id) + "/workflows",
      { method: "POST", headers: writeHeaders, body: JSON.stringify({ planId: plan.id }) });
    notice("模板工作流已启动，任务快照已冻结；可在下方记录中查看进度。");
    window.workflowPanel?.openRun(run.id);
  } catch (error) { notice(error.message, true); }
}
async function loadWorkflowTemplates(id, current) {
  try {
    const values = await request("/api/development/repositories/" + encodeURIComponent(id) + "/workflow-templates");
    if (current !== selectionVersion) return;
    const target = $("#workflow-template-list"); target.replaceChildren();
    if (!values.length) { target.append(element("p", "此仓库暂无模板，可在上方保存第一个任务组合。")); return; }
    for (const template of values) {
      const card = element("article", null, "management-card");
      const heading = element("div", null, "repository-heading");
      heading.append(element("h4", template.name), element("span", "修订 " + template.revision + " · " + time(template.updatedAt), "repository-label"));
      card.append(heading);
      card.append(element("p", template.tasks.map(task => task.id).join(" → ") + "（创建计划时冻结）", "management-caption"));
      const run = element("label", null, "management-caption");
      const runInput = element("input"); runInput.type = "number"; runInput.min = 1; runInput.placeholder = "PR 编号"; runInput.id = "template-run-" + template.id;
      const runButton = element("button", "生成计划并执行", "primary"); runButton.type = "button";
      runButton.addEventListener("click", () => runWorkflowTemplate(template));
      const deleteButton = element("button", "删除", "secondary"); deleteButton.type = "button";
      deleteButton.addEventListener("click", () => {
        if (deleteButton.armed !== true) {
          deleteButton.armed = true; deleteButton.textContent = "确认删除";
          setTimeout(() => { deleteButton.armed = false; deleteButton.textContent = "删除"; }, 3000);
          return;
        }
        (async () => {
          try {
            await request("/api/development/workflow-templates/" + encodeURIComponent(template.id), { method: "DELETE", headers: writeHeaders });
            notice("模板已删除；已保存的工作流记录不受影响。");
            await loadWorkflowTemplates($("#repository-select").value, selectionVersion);
          } catch (error) { notice(error.message, true); }
        })();
      });
      run.append(runInput, runButton, deleteButton);
      card.append(run);
      target.append(card);
    }
  } catch (error) { if (current === selectionVersion) notice("模板列表读取失败：" + error.message, true); }
}
$("#workflow-template-form").addEventListener("submit", createWorkflowTemplate);
function workflowState(run) {
  if (run.status !== "completed") return [run.status === "in_progress" ? "执行中" : run.status === "queued" ? "排队中" : "等待执行", "running"];
  if (run.conclusion === "success") return ["通过", "success"];
  if (failures.includes(run.conclusion)) return [run.conclusion === "timed_out" ? "超时" : "失败", "failed"];
  return [{ cancelled: "已取消", skipped: "已跳过", neutral: "中立" }[run.conclusion] || "未提供结论", ""];
}
function renderHealth(snapshot) {
  const health = snapshot.health; $("#repository-empty").hidden = true; $("#repository-health").hidden = false;
  window.workflowPanel?.selectRepository(snapshot.registration.id);
  $("#repository-name").textContent = health.repository.fullName;
  $("#repository-description").textContent = health.repository.description || "仓库未填写描述。";
  $("#repository-link").href = snapshot.registration.url;
  $("#repository-freshness").textContent = "上次读取 GitHub：" + time(health.checkedAt) + " · 默认分支 " + health.repository.defaultBranch;
  const warning = snapshot.error || (snapshot.stale ? "这里是上次读取的结果。点击“更新 GitHub 状态”获取当前数据。" : health.repository.archived ? "此仓库已归档。" : "");
  $("#repository-warning").textContent = warning; $("#repository-warning").hidden = !warning;
  $("#repository-statistics").replaceChildren(
    statistic("开放 Issue 抽样", health.issues.available ? health.issues.items.length : "—", "最多 20 条；完整列表见 GitHub"),
    statistic("开放 PR 抽样", health.pullRequests.available ? health.pullRequests.items.length : "—", "最多 20 条；包括草稿 PR"),
    statistic("近期 CI 失败", health.workflows.available ? health.workflows.items.filter(run => run.status === "completed" && failures.includes(run.conclusion)).length : "—", "默认分支最近 20 次工作流"));
  $("#issue-scope").textContent = scope(health.issues, "开放事项中的 Issue"); $("#pull-scope").textContent = scope(health.pullRequests, "最近更新");
  $("#workflow-scope").textContent = scope(health.workflows, health.repository.defaultBranch + " · 最近 20 次");
  renderList("#repository-issues", health.issues, issue => {
    const row = element("div", null, "repository-item"); row.append(link("#" + issue.number + " " + issue.title, issue.url));
    const meta = element("div", null, "repository-item-meta"); meta.append(element("span", time(issue.updatedAt)), ...issue.labels.map(label => element("span", label, "repository-label"))); row.append(meta); return row;
  });
  if (window.issuePanel) {
    window.issuePanel.selectRepository(snapshot.registration.id);
    if (health.issues.available) health.issues.items.forEach((issue, index) => {
      const button = element("button", "分诊", "secondary ci-open-button"); button.type = "button";
      button.addEventListener("click", () => window.issuePanel.openIssue(snapshot.registration.id, issue.number));
      $("#repository-issues").children[index].append(button);
    });
  }
  renderList("#repository-pulls", health.pullRequests, pr => {
    const row = element("div", null, "repository-item"); row.append(link("#" + pr.number + " " + pr.title, pr.url));
    const meta = element("div", null, "repository-item-meta"); meta.append(element("span", time(pr.updatedAt))); if (pr.draft) meta.append(element("span", "草稿", "repository-label")); row.append(meta); return row;
  });
  if (window.prPanel) {
    window.prPanel.selectRepository(snapshot.registration.id);
    if (health.pullRequests.available) health.pullRequests.items.forEach((pr, index) => {
      const button = element("button", "审查变更", "secondary ci-open-button"); button.type = "button";
      button.addEventListener("click", () => window.prPanel.openPull(snapshot.registration.id, pr.number));
      $("#repository-pulls").children[index].append(button);
    });
  }
  renderList("#repository-workflows", health.workflows, run => {
    const row = element("div", null, "workflow-row"), [label, style] = workflowState(run);
    row.append(link(run.name, run.url), element("span", label, "workflow-state " + style), element("small", run.branch + " · " + run.headSha.substring(0, 7)), element("small", time(run.updatedAt))); return row;
  });
  if (window.ciPanel) {
    window.ciPanel.selectRepository(snapshot.registration.id);
    const rows = $("#repository-workflows").children;
    if (health.workflows.available) health.workflows.items.forEach((run, index) => {
      if (run.status === "completed" && failures.includes(run.conclusion)) {
        const button = element("button", "排查 CI", "secondary ci-open-button"); button.type = "button";
        button.addEventListener("click", () => window.ciPanel.openRun(snapshot.registration.id, run.id)); rows[index].append(button);
      }
    });
  }
}
async function loadHealth() {
  const id = $("#repository-select").value, version = ++selectionVersion;
  loadRepositoryKnowledge(id, version);
  window.workflowPanel?.selectRepository(id);
  window.issuePanel?.selectRepository(id);
  window.prPanel?.selectRepository(id);
  setHealthBusy(!!id); $("#health-summary-result").hidden = true; $("#health-summary-history").replaceChildren(); $("#health-summary-history-box").hidden = true;
  activityReport = null; setActivityBusy(!!id); $("#activity-result").hidden = true; $("#activity-history").replaceChildren(); $("#activity-history-box").hidden = true;
  draftBasis = []; $("#draft-basis").replaceChildren(element("option", id ? "正在加载依据报告…" : "选择仓库后加载")); $("#draft-basis").value = ""; $("#draft-list").replaceChildren(); setDraftBusy(true);
  $("#draft-executions").replaceChildren(); $("#draft-executions-box").hidden = true; loadDraftWriteOption();
  $("#workflow-template-list").replaceChildren(); loadWorkflowCatalog();
  if (id) { loadHealthSummaries(id, version); loadActivityReports(id, version); loadDrafts(id, version); loadDraftBasisOptions(id, version); loadDraftExecutions(id, version); loadWorkflowTemplates(id, version); }
  if (!id) { $("#repository-health").hidden = true; $("#repository-empty").hidden = false; window.ciPanel?.selectRepository(""); setBusy(false); return; }
  setBusy(true);
  try { const snapshot = await request("/api/development/repositories/" + id + "/health"); if (version === selectionVersion) renderHealth(snapshot); }
  catch (error) { if (version === selectionVersion) { $("#repository-health").hidden = true; notice(error.message, true); } }
  finally { if (version === selectionVersion) setBusy(false); }
}
async function loadRepositoryKnowledge(id, version) {
  const target = $("#repository-knowledge"), manage = $("#repository-knowledge-manage");
  target.replaceChildren(); manage.href = id ? "/sources.html?scopeKind=REPOSITORY&scopeId=" + encodeURIComponent(id) + "#knowledge" : "/sources.html#knowledge";
  if (!id) { target.append(element("p", "选择仓库后查看已导入资料。")); return; }
  try {
    const values = await request("/api/knowledge/sources?scopeKind=REPOSITORY&scopeId=" + encodeURIComponent(id));
    if (version !== selectionVersion || id !== $("#repository-select").value) return;
    if (!values.length) target.append(element("p", "此仓库尚无导入知识，可在项目源码页选择固定 SHA 后预览导入。"));
    for (const source of values) target.append(element("p", source.state + " · v" + source.revision + " · " + (source.commitSha || "无提交") + " · " + source.documents + " 个当前文档 · " + time(source.importedAt)));
  } catch (error) { if (version === selectionVersion) target.append(element("p", error.message)); }
}
async function loadRepositories(selected = $("#repository-select").value) {
  const version = ++listVersion;
  const values = await request("/api/development/repositories");
  if (version !== listVersion) return;
  repositories = values;
  const options = repositories.map(repo => { const option = element("option", repo.owner + "/" + repo.name); option.value = repo.id; return option; });
  if (!options.length) { const option = element("option", "尚未登记仓库"); option.value = ""; options.push(option); }
  $("#repository-select").replaceChildren(...options); $("#repository-select").value = repositories.some(repo => repo.id === selected) ? selected : repositories[0]?.id || "";
  await loadHealth();
}
$("#register-repository").addEventListener("submit", async event => {
  event.preventDefault(); if (busy) return; listVersion++; selectionVersion++; setBusy(true); notice("正在从 GitHub 读取仓库…");
  try { const result = await request("/api/development/repositories", { method: "POST", headers: writeHeaders, body: JSON.stringify({ repository: $("#repository-input").value.trim() }) }); $("#repository-input").value = ""; await loadRepositories(result.registration.id); notice("仓库已接入。"); }
  catch (error) { notice(error.message, true); } finally { setBusy(false); }
});
$("#repository-select").addEventListener("change", () => { notice(""); loadHealth(); });
$("#refresh-repository").addEventListener("click", async () => {
  if (busy) return; const id = $("#repository-select").value; setBusy(true); notice("正在更新 GitHub 状态…");
  try { renderHealth(await request("/api/development/repositories/" + id + "/refresh", { method: "POST", headers: writeHeaders })); notice("GitHub 状态已更新。"); }
  catch (error) { await loadHealth(); notice(error.message, true); } finally { setBusy(false); }
});
$("#remove-repository").addEventListener("click", () => { const repo = repositories.find(repo => repo.id === $("#repository-select").value); if (!repo || busy) return; $("#remove-detail").textContent = "移除 " + repo.owner + "/" + repo.name + " 的本机登记？"; $("#remove-repository-dialog").showModal(); });
$("#cancel-remove").addEventListener("click", () => $("#remove-repository-dialog").close());
$("#remove-repository-form").addEventListener("submit", async event => {
  event.preventDefault(); if (busy) return; const id = $("#repository-select").value; setBusy(true);
  try { await request("/api/development/repositories/" + id + "?confirmId=" + id, { method: "DELETE", headers: writeHeaders }); $("#remove-repository-dialog").close(); await loadRepositories(""); notice("仓库登记已移除。"); }
  catch (error) { notice(error.message, true); } finally { setBusy(false); }
});
async function openSavedReport(parameters) {
  const kind = parameters.get("reportType")?.toLowerCase(), id = parameters.get("report"), repository = parameters.get("repository");
  if (!id || !["pr", "issue", "ci", "workflow"].includes(kind) || repository !== $("#repository-select").value) return;
  const version = selectionVersion;
  if (kind === "workflow") { await window.workflowPanel?.openRun(id); return; }
  const report = await request("/api/development/" + kind + "/reports/" + encodeURIComponent(id));
  if (version !== selectionVersion || repository !== $("#repository-select").value) return;
  if (report.repositoryId !== repository) throw new Error("保存报告不属于当前仓库。");
  ({ pr: window.prPanel, issue: window.issuePanel, ci: window.ciPanel })[kind]?.render(report);
}
const reportParameters = new URL(window.location?.href || "http://localhost/development.html").searchParams;
Promise.all([loadRepositories(reportParameters.get("repository") || undefined).then(() => openSavedReport(reportParameters)), request("/api/development/settings").then(settings => $("#github-access").textContent = settings.authenticated ? "已配置 GitHub 凭据" : "公开仓库访问")]).catch(error => notice(error.message, true));
