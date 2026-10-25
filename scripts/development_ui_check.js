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
const context = vm.createContext({ document, window: {}, URL, Date, console, fetch: () => new Promise(() => {}) });
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
})().catch(error=>{console.error(error);process.exitCode=1;});

