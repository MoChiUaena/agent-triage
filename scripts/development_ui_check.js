"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor(tag = "div") { this.tagName = tag; this.children = []; this.value = ""; this.text = ""; this.disabled = false; this.hidden = false; this.type = ""; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(""); }
  set innerHTML(value) { throw new Error("Untrusted HTML must not be rendered"); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this.text = ""; }
  addEventListener(name, callback) { this.events = this.events || {}; this.events[name] = callback; }
  classList = { toggle() {} };
}
const nodes = new Map(), calls = [], document = {
  querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); },
  createElement: tag => new Element(tag),
};
const healthReport = { id: "h1", repositoryId: "repo", repository: "acme/service", createdAt: "2026-10-24T00:00:00Z", stale: true,
  sections: [{ kind: "ISSUES", name: "开放 Issue 抽样", available: true, listed: 1, truncated: false, failing: 0, failingRuns: [], readAt: "2026-10-24T00:00:00Z" },
    { kind: "WORKFLOWS", name: "默认分支近期工作流", available: true, listed: 2, truncated: false, failing: 1,
      failingRuns: [{ name: "Verify", conclusion: "failure", url: "https://github.com/acme/service/actions/runs/9" }], readAt: "2026-10-24T00:00:00Z" }],
  localRecords: [{ kind: "CI", saved: 1, latestAt: "2026-10-24T00:00:00Z", latestTitle: "Verify / Build" }, { kind: "PR", saved: 0, latestAt: null, latestTitle: "" }],
  notes: ["汇总基于快照；生成时未重新访问 GitHub。", "Issue 与 PR 为抽样列表（最多 20 条），不代表全量统计；完整列表以 GitHub 为准。",
    "快照已过期或上次读取失败；请先点击“更新 GitHub 状态”再生成。", "本机记录仅统计已保存的分析报告（每类最多 20 条窗口），不代表仓库全部状态；既有报告结论保持原版本，本摘要只引用数量与时间，不改写任何结论。"] };
const history = [{ ...healthReport, id: "h0" }];
const context = vm.createContext({ document, window: {}, URL, Date, console, fetch: () => new Promise(() => {}), setTimeout, clearTimeout });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/development.js"), "utf8"), context);
const section = items => ({ available: true, items, truncated: false, message: "" });
const fixture = { registration: { url: "https://github.com/acme/service" }, stale: false, error: null, health: {
  repository: { fullName: "acme/service", defaultBranch: "main", description: "Example" }, checkedAt: "2026-10-13T00:00:00Z",
  issues: section([{ number: 7, title: "<img src=x onerror=alert(1)>", url: "https://github.com/acme/service/issues/7", labels: [], updatedAt: "2026-10-13T00:00:00Z" }]),
  pullRequests: section([{ number: 8, title: "Fix", url: "https://github.com/acme/service/pull/8", draft: true }]),
  workflows: section([{ name: "Verify", url: "https://github.com/acme/service/actions/runs/9", status: "completed", conclusion: "failure", branch: "main", headSha: "a".repeat(40) },
    { name: "Queued", status: "queued", conclusion: "", branch: "main", headSha: "b".repeat(40) }]),
} };
context.fixture = fixture; vm.runInContext("renderHealth(fixture)", context);
assert.match(document.querySelector("#repository-issues").textContent, /<img src=x onerror=alert\(1\)>/);
assert.match(document.querySelector("#repository-pulls").textContent, /草稿/);
assert.match(document.querySelector("#repository-workflows").textContent, /失败.*排队中/);
assert.equal(document.querySelector("#repository-statistics").children[2].children[1].textContent, "1");
assert.equal(vm.runInContext("link('unsafe', 'javascript:alert(1)').href", context), undefined);
fixture.health.workflows = { available: false, items: [], truncated: false, message: "没有 Actions 读取权限" };
fixture.stale = true; fixture.error = "GitHub 暂时不可用";
vm.runInContext("renderHealth(fixture)", context);
assert.equal(document.querySelector("#repository-statistics").children[2].children[1].textContent, "—");
assert.match(document.querySelector("#repository-workflows").textContent, /没有 Actions 读取权限/);
assert.match(document.querySelector("#repository-warning").textContent, /暂时不可用/);
console.log("Development UI passed: real state labels, unavailable sections, drafts and text rendering");
let completeReport,rendered=null;
context.fetch=async()=>new Promise(resolve=>completeReport=resolve);
context.window.prPanel={render:report=>rendered=report, selectRepository(){}};
context.window.issuePanel={selectRepository(){}};
context.window.ciPanel={selectRepository(){}};
context.window.workflowPanel={selectRepository(){}};
context.window.knowledgePanel={selectRepository(){}, loadRepositoryKnowledge(){}};
document.querySelector("#repository-select").value="repo";
context.requestedParameters=new URL("http://localhost/development.html?repository=repo&reportType=PR&report=saved").searchParams;
(async()=>{
  const pending=vm.runInContext("openSavedReport(requestedParameters)",context);
  completeReport({ok:true,json:async()=>({id:"saved",repositoryId:"repo"})});await pending;
  assert.equal(rendered.id,"saved");rendered=null;
  const stale=vm.runInContext("openSavedReport(requestedParameters)",context);
  vm.runInContext("selectionVersion++",context);
  completeReport({ok:true,json:async()=>({id:"saved",repositoryId:"repo"})});await stale;
  assert.equal(rendered,null);
  // R0：仓库健康摘要——生成、来源与缺口标注、stale 提示、历史重开与安全链接。
  context.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("/health-summaries") && options.method === "POST") return { ok: true, json: async () => healthReport };
    if (url.endsWith("/health-summaries")) return { ok: true, json: async () => history };
    return { ok: true, json: async () => ({}) };
  };
  document.querySelector("#repository-select").events = document.querySelector("#repository-select").events || {};
  document.querySelector("#repository-select").events.change();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(document.querySelector("#health-summary-history-box").hidden, false);
  document.querySelector("#health-generate").disabled = false;
  const generating = vm.runInContext("generateHealthSummary()", context);
  await generating; await new Promise(resolve => setImmediate(resolve));
  const generateCall = calls.filter(call => call.url.endsWith("/health-summaries") && call.options?.method === "POST");
  assert.equal(generateCall.length, 1, "Health summary generation must not repeat");
  const result = document.querySelector("#health-summary-result");
  assert.match(result.textContent, /快照过期或上次读取失败/);
  assert.match(result.textContent, /请先点击“更新 GitHub 状态”/);
  assert.match(result.textContent, /默认分支近期工作流：列出 2 条（抽样，最多 20 条） · 失败 1 次/);
  assert.match(result.textContent, /Verify · failure/);
  assert.match(result.textContent, /CI 排障 本机记录 1 条/);
  assert.match(result.textContent, /PR 审查 本机记录 0 条 · 暂无/);
  assert.match(result.textContent, /不代表全量统计/);
  const unsafe = vm.runInContext("link('run', 'javascript:alert(1)').href", context);
  assert.equal(unsafe, undefined, "Failing run links must reject unsafe URLs");
  console.log("Development original-report links passed: saved report opening, stale scope guard and rule-based health summary");
  // R1：研发活动报告——日期范围生成、来源跳转、本机报告重开、缺口渲染、历史重开与 Markdown 导出。
  const activityFixture = { id: "a1", repositoryId: "repo", repository: "acme/service", createdAt: "2026-10-24T00:00:00Z",
    fromDate: "2026-10-13", toDate: "2026-10-13", stale: false, snapshotReadAt: "2026-10-13T12:00:00Z",
    days: [{ date: "2026-10-13", items: [
      { kind: "COMMIT", title: "Fix login timeout", detail: "dev · aaaaaaa", url: "https://github.com/acme/service/commit/" + "a".repeat(40), reportType: null, reportId: null, at: "2026-10-13T12:00:00Z" },
      { kind: "CI_REPORT", title: "Verify / Build", detail: "本机 CI 排障报告", url: "https://github.com/acme/service/actions/runs/9901/job/777", reportType: "ci", reportId: "local-1", at: "2026-10-13T06:00:00Z" },
      { kind: "ISSUE", title: "<img src=x onerror=alert(1)>", detail: "", url: "javascript:alert(1)", reportType: null, reportId: null, at: "2026-10-13T05:00:00Z" }] }],
    actionItems: [{ kind: "FAILING_WORKFLOW", title: "Verify", detail: "结论 failure · main", url: "https://github.com/acme/service/actions/runs/9" },
      { kind: "OPEN_ISSUE", title: "#7 Slow request", detail: "bug", url: "https://github.com/acme/service/issues/7" }],
    localRecords: [{ kind: "CI", inRange: 1, saved: 1, latestInRangeAt: "2026-10-13T06:00:00Z" }],
    gaps: ["提交区段未读取：GitHub 未允许读取这部分数据，请检查访问权限。本报告不含该区段数据，不代表该时段没有相关活动。"],
    notes: ["提交、Issue、PR 与工作流来自快照抽样（各最多 20 条；Issue 与 PR 仅开放状态），仅覆盖已读取样本，完整列表以 GitHub 为准。"] };
  const activityHistory = [{ ...activityFixture, id: "a0" }];
  calls.length = 0;
  context.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("/activity-reports") && options.method === "POST") return { ok: true, json: async () => activityFixture };
    if (url.endsWith("/activity-reports")) return { ok: true, json: async () => activityHistory };
    if (url.includes("/markdown")) return { ok: true, text: async () => "# 研发活动报告 · acme/service\n" };
    if (url.endsWith("/health-summaries")) return { ok: true, json: async () => history };
    return { ok: true, json: async () => ({}) };
  };
  document.querySelector("#repository-select").events.change();
  await new Promise(resolve => setImmediate(resolve));
  document.querySelector("#activity-from").value = "2026-10-13";
  document.querySelector("#activity-to").value = "2026-10-13";
  document.querySelector("#activity-generate").disabled = false;
  await vm.runInContext("generateActivityReport({ preventDefault() {} })", context);
  await new Promise(resolve => setImmediate(resolve));
  const activityCalls = calls.filter(call => call.url.endsWith("/activity-reports") && call.options?.method === "POST");
  assert.equal(activityCalls.length, 1, "Activity report generation must not repeat");
  assert.equal(activityCalls[0].options.body, JSON.stringify({ fromDate: "2026-10-13", toDate: "2026-10-13" }));
  assert.equal(activityCalls[0].options.headers["X-Triage-Development"], "1");
  const activityResult = document.querySelector("#activity-result");
  assert.equal(activityResult.hidden, false);
  assert.match(activityResult.textContent, /Fix login timeout/);
  assert.match(activityResult.textContent, /Verify \/ Build/);
  assert.match(activityResult.textContent, /打开本机报告/);
  assert.match(activityResult.textContent, /待处理事项/);
  assert.match(activityResult.textContent, /#7 Slow request/);
  assert.match(activityResult.textContent, /CI 排障 范围内 1 条（窗口 1 条，最多 20 条）/);
  assert.match(activityResult.textContent, /读取缺口/);
  assert.match(activityResult.textContent, /提交区段未读取/);
  assert.match(activityResult.textContent, /完整列表以 GitHub 为准/);
  assert.match(activityResult.textContent, /<img src=x onerror=alert\(1\)>/);
  assert.doesNotMatch(activityResult.textContent, /href="javascript/);
  assert.equal(document.querySelector("#activity-history-box").hidden, false);
  assert.match(document.querySelector("#activity-history").children[0].textContent, /2026-10-13 至 2026-10-13/);
  document.querySelector("#activity-history").children[0].events.click();
  assert.match(activityResult.textContent, /Fix login timeout/, "History reopen must re-render the saved report");
  const exportPending = vm.runInContext("exportActivityMarkdown()", context);
  await exportPending; await new Promise(resolve => setImmediate(resolve));
  const markdownCall = calls.find(call => call.url.includes("/markdown"));
  assert.ok(markdownCall, "Markdown export must call the export endpoint");
  assert.ok(markdownCall.url.endsWith("/api/development/activity-reports/a0/markdown"), "Export follows the reopened report");
  assert.match(document.querySelector("#development-notice").textContent, /Markdown 已生成/);
  context.emptyActivity = { ...activityFixture, days: [], gaps: [] };
  vm.runInContext("renderActivityReport(emptyActivity)", context);
  assert.match(activityResult.textContent, /所选日期范围内没有匹配的活动记录/);
  assert.doesNotMatch(activityResult.textContent, /读取缺口/);
  console.log("Development activity report passed: dated generation, source links, local report reopen, gaps, history and Markdown export");
  // W1：操作草稿——依据加载、生成、未执行标识、修订保存与 409、丢弃、PR 依据的标签限制与安全文本。
  const draftFixture = { id: "d1", repositoryId: "repo", repository: "acme/service", kind: "COMMENT",
    target: { objectType: "ISSUE", number: 7, title: "Slow request", url: "https://github.com/acme/service/issues/7", headSha: null, updatedAt: "2026-10-13T12:00:00Z" },
    basis: { type: "ISSUE_REPORT", reportId: "r1", title: "Issue 分诊 #7 <img src=x onerror=alert(1)>", createdAt: "2026-10-13T06:00:00Z" },
    body: "草稿正文 <img src=x onerror=alert(1)>", addLabels: [], removeLabels: [], state: "DRAFT", revision: 1,
    createdAt: "2026-10-24T00:00:00Z", updatedAt: "2026-10-24T00:00:00Z" };
  const labelDraftFixture = { ...draftFixture, id: "d2", kind: "LABEL", body: "标签说明", addLabels: ["BUG"], removeLabels: [] };
  calls.length = 0;
  context.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (url.includes("/issue/reports")) return { ok: true, json: async () => [{ id: "basis-1", issue: { number: 7, title: "Slow request" } }] };
    if (url.includes("/pr/reports")) return { ok: true, json: async () => [{ id: "basis-2", pull: { number: 8, title: "Fix" } }] };
    if (url.endsWith("/drafts") && options.method === "POST") return { ok: true, json: async () => draftFixture };
    if (url.endsWith("/drafts")) return { ok: true, json: async () => [draftFixture, labelDraftFixture] };
    if (/\/drafts\/d1$/.test(url) && options.method === "PUT") return { ok: true, json: async () => ({ ...draftFixture, revision: 2, body: "更新后的草稿正文" }) };
    if (/\/drafts\/d2$/.test(url) && options.method === "PUT") return { ok: false, status: 409, json: async () => ({ detail: "草稿已被其他修改更新，请刷新后重试。" }) };
    if (url.endsWith("/discard")) return { ok: true, json: async () => ({ ...draftFixture, state: "DISCARDED" }) };
    return { ok: true, json: async () => ({}) };
  };
  document.querySelector("#repository-select").events.change();
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  const basisSelect = document.querySelector("#draft-basis");
  assert.match(basisSelect.children.map(child => child.textContent).join("|"), /Issue 分诊 #7 Slow request\|PR 审查 #8 Fix/);
  const draftList = document.querySelector("#draft-list");
  assert.equal(draftList.children.length, 2);
  assert.match(draftList.textContent, /评论草稿 · Issue #7/);
  assert.match(draftList.textContent, /标签草稿 · Issue #7/);
  assert.match(draftList.textContent, /草稿 · 未执行/);
  assert.match(draftList.textContent, /依据：Issue 分诊 #7 <img src=x onerror=alert\(1\)>/);
  assert.match(draftList.children[0].children[4].children[0].value, /草稿正文 <img src=x onerror=alert\(1\)>/, "Body stays a plain value, never innerHTML");
  assert.match(draftList.textContent, /目标版本：更新于/);
  assert.match(draftList.textContent, /修订 1/);
  basisSelect.value = "ISSUE_REPORT:basis-1";
  document.querySelector("#draft-kind").value = "COMMENT";
  document.querySelector("#draft-create").disabled = false;
  await vm.runInContext("createDraft({ preventDefault() {} })", context);
  await new Promise(resolve => setImmediate(resolve));
  const draftPost = calls.filter(call => call.url.endsWith("/drafts") && call.options?.method === "POST");
  assert.equal(draftPost.length, 1, "Draft creation must not repeat");
  assert.equal(draftPost[0].options.body, JSON.stringify({ kind: "COMMENT", basisReportId: "basis-1" }));
  assert.equal(draftPost[0].options.headers["X-Triage-Development"], "1");
  assert.match(document.querySelector("#development-notice").textContent, /不会发布到 GitHub/);
  const commentCard = draftList.children[0];
  const commentControls = commentCard.children[5];
  await commentControls.children[0].events.click();
  await new Promise(resolve => setImmediate(resolve));
  const draftPut = calls.find(call => /\/drafts\/d1$/.test(call.url) && call.options?.method === "PUT");
  assert.ok(draftPut, "Draft save must call the update endpoint");
  assert.equal(draftPut.options.body, JSON.stringify({ revision: 1, body: draftFixture.body }));
  assert.match(commentControls.children[2].textContent, /已保存（修订 2）；草稿仍未执行。/);
  const labelCard = draftList.children[1];
  const labelControls = labelCard.children[7];
  await labelControls.children[0].events.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(labelControls.children[2].textContent, /刷新后重试/);
  await commentControls.children[1].events.click();
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  const discardCall = calls.find(call => call.url.endsWith("/drafts/d1/discard"));
  assert.ok(discardCall, "Discard must call the discard endpoint");
  assert.match(document.querySelector("#development-notice").textContent, /未对 GitHub 执行任何操作/);
  vm.runInContext("draftBasis.push({ type: 'PR_REPORT', id: 'p1', label: 'PR' }); $('#draft-basis').value = 'PR_REPORT:p1'; $('#draft-kind').value = 'LABEL'; syncDraftKind()", context);
  assert.equal(vm.runInContext("$('#draft-kind').value", context), "COMMENT", "PR basis must not offer label drafts");
  assert.match(document.querySelector("#draft-kind-note").textContent, /标签草稿仅支持 Issue 分诊报告/);
  console.log("Development drafts passed: basis loading, dated creation, revision save with 409 handling, discard and safe text");
  // W2：受限执行——写入配置、执行预览、确认门控、执行结果与审计刷新、不确定结果可重新预览。
  const executionPreviewFixture = { id: "p1", draftId: "d1", draftRevision: 1, kind: "COMMENT",
    target: { objectType: "ISSUE", number: 7, title: "Slow request", url: "https://github.com/acme/service/issues/7", headSha: null, updatedAt: "2026-10-13T12:00:00Z" },
    operation: "发布评论", body: draftFixture.body, currentLabels: [], resultingLabels: [],
    permitted: true, permissionMessage: "写入凭据具备该仓库推送权限。", readAt: "2026-10-25T00:00:00Z", expiresAt: "2026-10-25T00:10:00Z",
    notes: ["执行前会再次核对目标状态与内容；预览十分钟内有效且只能使用一次。"] };
  const executionRecordFixture = { id: "e1", draftId: "d1", repositoryId: "repo", repository: "acme/service", kind: "COMMENT",
    target: executionPreviewFixture.target, basis: draftFixture.basis, body: draftFixture.body, addLabels: [], removeLabels: [], resultingLabels: [],
    state: "SUCCESS", confirmedAt: "2026-10-25T00:05:00Z", executedAt: "2026-10-25T00:05:01Z",
    resultUrl: "https://github.com/acme/service/issues/7#issuecomment-501", resultSummary: "已发布评论（评论 501）。", message: "", confirmedVia: "LOCAL_PAGE" };
  let executionOutcome = executionRecordFixture;
  calls.length = 0;
  context.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("/drafts/write-option")) return { ok: true, json: async () => ({ enabled: true, message: "已配置独立写入凭据；每次执行仍需预览核对与显式确认。" }) };
    if (url.endsWith("/execution-previews")) return { ok: true, json: async () => executionPreviewFixture };
    if (url.endsWith("/executions")) return { ok: true, json: async () => executionOutcome };
    if (url.endsWith("/draft-executions")) return { ok: true, json: async () => [executionRecordFixture] };
    if (url.endsWith("/discard")) return { ok: true, json: async () => ({ ...draftFixture, state: "DISCARDED" }) };
    if (url.endsWith("/drafts") && options.method === "POST") return { ok: true, json: async () => draftFixture };
    if (url.endsWith("/drafts")) return { ok: true, json: async () => [draftFixture] };
    if (url.includes("/issue/reports")) return { ok: true, json: async () => [{ id: "basis-1", issue: { number: 7, title: "Slow request" } }] };
    if (url.includes("/pr/reports")) return { ok: true, json: async () => [] };
    return { ok: true, json: async () => ({}) };
  };
  document.querySelector("#repository-select").events.change();
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  assert.match(document.querySelector("#draft-write-option").textContent, /已配置独立写入凭据/);
  assert.equal(document.querySelector("#draft-executions-box").hidden, false);
  assert.match(document.querySelector("#draft-executions").textContent, /执行成功 · .+ · 评论 #7/);
  assert.match(document.querySelector("#draft-executions").textContent, /查看结果/);
  const card = document.querySelector("#draft-list").children[0];
  const cardControls = card.children[5];
  const cardExecutionBox = card.children[6];
  assert.equal(cardControls.children.length, 4, "Executed-capable drafts must offer preview execution");
  await cardControls.children[3].events.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(cardExecutionBox.textContent, /执行预览 · 发布评论/);
  assert.match(cardExecutionBox.textContent, /写入凭据具备该仓库推送权限/);
  assert.match(cardExecutionBox.textContent, /十分钟内有效且只能使用一次/);
  const executeButton = cardExecutionBox.children[3];
  assert.equal(executeButton.disabled, true, "Execution must stay gated until explicitly confirmed");
  const consentCheckbox = cardExecutionBox.children[2].children[0];
  consentCheckbox.checked = true; consentCheckbox.events.change();
  assert.equal(executeButton.disabled, false);
  await executeButton.events.click();
  await new Promise(resolve => setImmediate(resolve));
  const executionPosts = calls.filter(call => call.url.endsWith("/executions"));
  assert.equal(executionPosts.length, 1, "Execution must fire exactly once");
  assert.equal(executionPosts[0].options.body, JSON.stringify({ previewId: "p1", confirmed: true }));
  assert.equal(executionPosts[0].options.headers["X-Triage-Development"], "1");
  assert.match(cardExecutionBox.textContent, /执行成功/);
  assert.match(cardExecutionBox.textContent, /在 GitHub 查看结果/);
  assert.match(cardExecutionBox.textContent, /已写入本机执行审计/);
  assert.equal(card.children[0].children[1].textContent, "已执行");
  assert.equal(cardControls.children[0].hidden, true, "Executed drafts must not stay editable");
  assert.equal(cardControls.children[3].hidden, true, "Executed drafts must not offer re-execution");
  executionOutcome = { ...executionRecordFixture, state: "UNCERTAIN", resultUrl: "", resultSummary: "", message: "写入超时且远端未找到该评论；请到 GitHub 核对。" };
  await vm.runInContext("loadDrafts('repo', selectionVersion)", context);
  await new Promise(resolve => setImmediate(resolve));
  const retryCard = document.querySelector("#draft-list").children[0];
  const retryControls = retryCard.children[5];
  await retryControls.children[3].events.click();
  await new Promise(resolve => setImmediate(resolve));
  const retryConsent = retryCard.children[6].children[2].children[0];
  retryConsent.checked = true; retryConsent.events.change();
  await retryCard.children[6].children[3].events.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.match(retryCard.children[6].textContent, /结果不确定/);
  assert.match(retryCard.children[6].textContent, /写入超时且远端未找到该评论/);
  assert.equal(retryControls.children[3].hidden, false, "Uncertain drafts must allow re-previewing");
  assert.equal(retryControls.children[0].hidden, false, "Uncertain drafts must stay editable");
  console.log("Development draft execution passed: write option, preview gate, one-shot confirm, audit refresh and uncertain recovery");
  // F1：工作流模板——目录勾选、保存、按 PR 编号执行、两步删除与选择门控。
  const catalogFixture = [
    { id: "REPOSITORY", title: "读取仓库概览", requires: [], description: "读取抽样并标注缺口。" },
    { id: "PR", title: "审查固定 PR 版本", requires: ["REPOSITORY"], description: "只读规则审查。" },
    { id: "ISSUE", title: "Issue 分诊", requires: ["REPOSITORY"], description: "本机规则分诊。" },
    { id: "CI", title: "排查同一提交的 CI", requires: ["PR"], description: "同一提交失败作业。" },
    { id: "CHECK", title: "核对证据与版本", requires: [], description: "核对版本与来源。" },
    { id: "RECHECK", title: "一次有界补查", requires: [], description: "最多一次补查。" },
    { id: "SUMMARY", title: "生成综合报告", requires: [], description: "汇总结果与缺口。" }];
  const templateFixture = { id: "t1", repositoryId: "repo", repository: "acme/service", name: "只读 PR 检查",
    tasks: [
      { id: "REPOSITORY", title: "读取仓库概览", requires: [], after: [] },
      { id: "PR", title: "审查固定 PR 版本", requires: ["REPOSITORY"], after: [] },
      { id: "CHECK", title: "核对证据与版本", requires: [], after: ["REPOSITORY", "PR"] },
      { id: "SUMMARY", title: "生成综合报告", requires: [], after: ["REPOSITORY", "PR", "CHECK"] }],
    revision: 1, createdAt: "2026-10-25T00:00:00Z", updatedAt: "2026-10-25T00:00:00Z" };
  calls.length = 0;
  let openedRun = null;
  context.window.workflowPanel.openRun = id => { openedRun = id; };
  context.fetch = async (url, options = {}) => {
    calls.push({ url, options });
    if (url.endsWith("/workflow-task-catalog")) return { ok: true, json: async () => catalogFixture };
    if (url.endsWith("/workflow-templates") && options.method === "POST") return { ok: true, json: async () => templateFixture };
    if (url.endsWith("/workflow-templates") && options.method === "DELETE") return { ok: true, json: async () => ({}) };
    if (url.endsWith("/workflow-templates")) return { ok: true, json: async () => [templateFixture] };
    if (url.endsWith("/plans") && options.method === "POST") return { ok: true, json: async () => ({ id: "plan-1", tasks: templateFixture.tasks }) };
    if (url.endsWith("/workflows") && options.method === "POST") return { ok: true, json: async () => ({ id: "run-9" }) };
    return { ok: true, json: async () => ({}) };
  };
  document.querySelector("#repository-select").events.change();
  await new Promise(resolve => setImmediate(resolve));
  await new Promise(resolve => setImmediate(resolve));
  const taskBox = document.querySelector("#workflow-template-tasks");
  assert.equal(taskBox.children.length, 7, "Catalog must render all seven tasks");
  assert.equal(taskBox.children[6].children[0].disabled, true, "SUMMARY is mandatory and cannot be unchecked");
  assert.equal(taskBox.children[6].children[0].checked, true);
  assert.equal(taskBox.children[3].children[0].checked, false);
  assert.equal(taskBox.children[1].children[0].checked, true);
  const templateList = document.querySelector("#workflow-template-list");
  assert.match(templateList.textContent, /只读 PR 检查/);
  assert.match(templateList.textContent, /REPOSITORY → PR → CHECK → SUMMARY/);
  assert.match(templateList.textContent, /创建计划时冻结/);
  document.querySelector("#workflow-template-name").value = "CI 排障组合";
  taskBox.children[3].children[0].checked = true;
  document.querySelector("#workflow-template-create").disabled = false;
  await vm.runInContext("createWorkflowTemplate({ preventDefault() {} })", context);
  await new Promise(resolve => setImmediate(resolve));
  const templatePost = calls.filter(call => call.url.endsWith("/workflow-templates") && call.options?.method === "POST");
  assert.equal(templatePost.length, 1);
  assert.equal(templatePost[0].options.body, JSON.stringify({ name: "CI 排障组合",
    tasks: [{ id: "REPOSITORY" }, { id: "PR" }, { id: "CI" }, { id: "CHECK" }, { id: "SUMMARY" }] }));
  assert.match(document.querySelector("#development-notice").textContent, /模板已保存/);
  document.querySelector("#template-task-t1").value = "7";
  document.querySelector("#template-run-t1").value = "7";
  const runRow = templateList.children[0].children[2];
  await runRow.children[1].events.click();
  await new Promise(resolve => setImmediate(resolve));
  const planPost = calls.find(call => call.url.endsWith("/plans") && call.options?.method === "POST");
  assert.ok(planPost, "Template run must create a plan first");
  assert.equal(planPost.options.body, JSON.stringify({ prNumber: 7 }));
  const workflowPost = calls.find(call => call.url.endsWith("/workflows") && call.options?.method === "POST");
  assert.ok(workflowPost, "Template run must start the workflow");
  assert.equal(workflowPost.options.body, JSON.stringify({ planId: "plan-1" }));
  assert.equal(openedRun, "run-9", "The started run must open in the workflow panel");
  const deleteButton = runRow.children[2];
  await deleteButton.events.click();
  assert.equal(deleteButton.textContent, "确认删除", "Delete needs a second confirming click");
  assert.equal(calls.filter(call => call.options?.method === "DELETE").length, 0);
  await deleteButton.events.click();
  await new Promise(resolve => setImmediate(resolve));
  assert.ok(calls.some(call => call.url.endsWith("/workflow-templates/t1") && call.options?.method === "DELETE"));
  taskBox.children[0].children[0].checked = false;
  taskBox.children[1].children[0].checked = false;
  taskBox.children[3].children[0].checked = false;
  taskBox.children[4].children[0].checked = false;
  taskBox.children[0].children[0].events.change();
  assert.equal(document.querySelector("#workflow-template-create").disabled, true, "Saving requires at least one task besides SUMMARY");
  console.log("Development workflow templates passed: catalog selection, save, frozen plan run and two-step delete");
})().catch(error=>{console.error(error);process.exitCode=1;});

