"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.text = ""; this.value = ""; this.events = {}; this.open = false; this.dataset = {}; this.checked = false; this.disabled = false; this.hidden = false; this.type = ""; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(""); }
  set innerHTML(value) { throw new Error("External PR text must be displayed as text"); }
  replaceChildren(...children) { this.children = children; this.text = ""; }
  append(...children) { this.children.push(...children); }
  addEventListener(name, callback) { this.events[name] = callback; }
  scrollIntoView() {}
  showModal() { this.open = true; }
  close() { this.open = false; }
  classList = { toggle() {} };
}
const nodes = new Map(), requests = [];
const document = { querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); }, createElement: tag => new Element() };
const window = {}, calls = []; let pendingPull;
const modelOption = { available: true, displayName: "页面模型", modelName: "fixture-pr", message: "" };
const modelPreview = { id: "mp1", number: 7, title: "Update access", baseSha: "a".repeat(40), headSha: "b".repeat(40),
  files: [{ file: "src/main/java/Security.java", clipped: false }],
  findings: [{ id: "PR-CHANGE-1" }], issues: [{ number: 2 }],
  omissions: ["只发送上方列出的已脱敏补丁与差异元数据；完整文件内容不会发送。", "补丁属于源码，发送前需单独确认 codeConfirmed。"],
  model: { displayName: "页面模型", modelName: "fixture-pr" }, expiresAt: "2026-10-24T00:10:00Z" };
const advice = { id: "a1", headSha: "b".repeat(40), createdAt: "2026-10-24T00:00:00Z",
  suggestion: { summary: "改动将固定认证替换为 permitAll；<script> 需人工核对。", intent: "替换鉴权实现。", confidence: "MEDIUM", reviewedSha: "b".repeat(40),
    risks: [{ text: "权限被完全放开。", references: [{ file: "src/main/java/Security.java", line: 11, side: "NEW" }] }],
    testGaps: [], references: [{ file: "src/main/java/Security.java", line: 11, side: "NEW" }] },
  model: { state: "SUCCEEDED", usage: { inputTokens: 100, outputTokens: 50, totalTokens: 150 }, elapsedMs: 900, message: "已通过校验。" } };
const failedAdvice = { id: "a2", headSha: "b".repeat(40), createdAt: "2026-10-24T00:05:00Z", suggestion: null,
  model: { state: "FAILED", usage: null, elapsedMs: 40, message: "模型调用失败，请核对模型设置。本次保留规则报告；没有保存服务原始错误。" } };
const context = vm.createContext({ document, window, URL, Date, console, URLSearchParams, fetch: async (url, options = {}) => {
  requests.push({ url, options, body: options.body ? JSON.parse(options.body) : null });
  const ok = value => ({ ok: true, json: async () => value });
  if (url === "/api/development/pr/model-option") return ok(modelOption);
  if (url.endsWith("/model/previews")) return ok(modelPreview);
  if (url.endsWith("/model/analyses")) return options.method === "POST" ? ok(advice) : ok([failedAdvice]);
  if (url.endsWith("/pulls/7")) return new Promise(resolve => { pendingPull = resolve; });
  return ok([]);
}});
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/pr.js"), "utf8"), context);
const tick = () => new Promise(resolve => setImmediate(resolve));
const fixture = { id: "report", repository: "acme/service", pull: { number: 7, title: "<script>alert(1)</script>", url: "https://github.com/acme/service/pull/7", headSha: "b".repeat(40), baseSha: "a".repeat(40), changedFiles: 2, additions: 2, deletions: 1 }, mergeBaseSha: "c".repeat(40), createdAt: "2026-10-14T00:00:00Z", notice: "规则分析，不构成合入批准。",
  files: [{ file: "Security.java", status: "modified", additions: 1, deletions: 1, coverage: "COMPLETE" }], findings: [{ id: "PR-CHANGE-1", title: "访问控制规则变更", file: "Security.java", line: 11, side: "RIGHT", excerpt: "http.permitAll(); <script>", reason: "需核对路径", check: "验证匿名访问", url: "https://github.com/acme/service/blob/b/Security.java#L11" }],
  ci: { state: "INCOMPLETE", checks: [{ name: "Verify", status: "completed", conclusion: "success", url: "https://github.com/acme/service/pull/7/checks" }], gaps: ["Checks 未完整读取"] }, issues: [{ number: 2, title: "<img src=x>", state: "open", url: "https://github.com/acme/service/issues/2" }], gaps: ["未读取部分补丁"] };
(async () => {
  window.prPanel.render(fixture); await tick();
  assert.match(document.querySelector("#pr-report-title").textContent, /<script>/);
  assert.match(document.querySelector("#pr-report-versions").textContent, /c{40}/);
  assert.match(document.querySelector("#pr-report-findings").textContent, /Security.java:11/);
  assert.match(document.querySelector("#pr-report-findings").textContent, /<script>/);
  assert.match(document.querySelector("#pr-report-ci-state").textContent, /未完整读取/);
  assert.match(document.querySelector("#pr-report-gaps").textContent, /未读取部分补丁/);
  assert.match(document.querySelector("#pr-report-issues").textContent, /#2/);
  assert.equal(document.querySelector("#pr-report-meta").dataset.reportId, "report");
  assert.match(document.querySelector("#pr-model-option").textContent, /fixture-pr/);
  assert.equal(document.querySelector("#pr-model-preview-button").disabled, false);
  assert.equal(document.querySelector("#pr-model-history-box").hidden, false);
  // 预览发送范围；两个确认都勾选前不能生成。
  await window.prPanel.previewModel();
  const previewRequest = requests.find(r => r.url.endsWith("/model/previews"));
  assert.equal(previewRequest.options.headers["X-Triage-Development"], "1");
  const previewBox = document.querySelector("#pr-model-preview");
  assert.match(previewBox.textContent, /PR #7.*b{40}/);
  assert.match(previewBox.textContent, /src\/main\/java\/Security\.java/);
  assert.match(previewBox.textContent, /codeConfirmed/);
  assert.equal(document.querySelector("#pr-model-consent-row").hidden, false);
  assert.equal(document.querySelector("#pr-model-code-row").hidden, false);
  assert.equal(document.querySelector("#pr-model-analyze-button").disabled, true);
  await window.prPanel.analyzeModel();
  assert.equal(requests.find(r => r.url.endsWith("/model/analyses") && r.options.method === "POST"), undefined, "Unconfirmed flow must not dispatch the model");
  document.querySelector("#pr-model-confirm").checked = true;
  document.querySelector("#pr-model-confirm").events.change();
  assert.equal(document.querySelector("#pr-model-analyze-button").disabled, true, "Code consent is still missing");
  document.querySelector("#pr-model-code-confirm").checked = true;
  document.querySelector("#pr-model-code-confirm").events.change();
  assert.equal(document.querySelector("#pr-model-analyze-button").disabled, false);
  await window.prPanel.analyzeModel();
  const analysisRequest = requests.find(r => r.url.endsWith("/model/analyses") && r.options.method === "POST");
  assert.deepEqual(analysisRequest.body, { previewId: "mp1", confirmed: true, codeConfirmed: true });
  const result = document.querySelector("#pr-model-result");
  assert.match(result.textContent, /状态：SUCCEEDED.*审查 SHA b{40}/);
  assert.match(result.textContent, /150（输入 100 \/ 输出 50）/);
  assert.match(result.textContent, /<script> 需人工核对/);
  assert.match(result.textContent, /把握中等.*审查 SHA b{40}/);
  assert.match(result.textContent, /权限被完全放开。（引用 src\/main\/java\/Security\.java:11 新增行）/);
  assert.match(result.textContent, /不构成合入批准/);
  assert.match(document.querySelector("#pr-model-notice").textContent, /规则报告保持不变/);
  assert.equal(document.querySelector("#pr-model-analyze-button").disabled, true, "A consumed preview cannot be replayed");
  document.querySelector("#pr-model-history").children[0].events.click();
  assert.match(document.querySelector("#pr-model-result").textContent, /状态：FAILED/);
  assert.match(document.querySelector("#pr-model-result").textContent, /规则报告保持不变/);
  fixture.findings = []; fixture.pull.url = "javascript:alert(1)";
  requests.length = 0;
  window.prPanel.render(fixture); await tick();
  assert.equal(document.querySelector("#pr-report-link").children[0].href, undefined);
  assert.match(document.querySelector("#pr-report-findings").textContent, /不代表/);
  assert.match(document.querySelector("#pr-model-option").textContent, /fixture-pr/);
  window.prPanel.selectRepository("first");
  const request = window.prPanel.openPull("first", 7);
  window.prPanel.selectRepository("second");
  pendingPull({ ok: true, json: async () => fixture.pull });
  await request;
  assert.equal(document.querySelector("#pr-review-dialog").open, false);
  assert.equal(document.querySelector("#pr-review-button").disabled, true);
  assert.equal(document.querySelector("#pr-report-result").hidden, true);
  console.log("PR UI passed: frozen versions, scope gaps, safe text, stale responses, explicit model consent with file/line citations and preserved rule report");
})().catch(error => { console.error(error); process.exitCode = 1; });
