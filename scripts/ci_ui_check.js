"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.text = ""; this.value = ""; this.events = {}; this.dataset = {}; this.checked = false; this.disabled = false; this.hidden = false; this.type = ""; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(""); }
  set innerHTML(value) { throw new Error("Raw logs must be displayed as text"); }
  replaceChildren(...children) { this.children = children; this.text = ""; }
  append(...children) { this.children.push(...children); }
  addEventListener(event, action) { this.events[event] = action; }
  scrollIntoView() {}
  classList = { toggle() {} };
}
const nodes = new Map(), requests = [];
const document = { querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); }, createElement: tag => new Element() };
const window = {};
const modelOption = { available: true, displayName: "页面模型", modelName: "fixture-ci", message: "" };
const modelPreview = { id: "mp1", workflowName: "Verify", job: { name: "Build" }, attempt: 3, headSha: "b".repeat(40),
  ruleTitle: "编译阶段失败", ruleCategory: "COMPILATION_FAILURE", ruleStatus: "OBSERVED",
  evidence: [{ id: "CI-LOG-1", startLine: 10, endLine: 12 }], omissions: ["只发送上方列出的已脱敏错误块；完整日志其余行不会发送。", "未发送仓库源码与项目文档。"],
  model: { displayName: "页面模型", modelName: "fixture-ci" }, expiresAt: "2026-10-24T00:10:00Z" };
const advice = { id: "a1", createdAt: "2026-10-24T00:00:00Z",
  suggestion: { summary: "编译失败集中于 <script> 符号解析；日志不足以确认根因。", knownStage: "与规则报告的编译阶段失败一致。", stageRelation: "MATCHES",
    hypotheses: [{ text: "Inventory 类可能尚未生成。", evidenceIds: ["CI-LOG-1"] }], verifications: [], evidenceIds: ["CI-LOG-1"] },
  model: { state: "SUCCEEDED", usage: { inputTokens: 100, outputTokens: 50, totalTokens: 150 }, elapsedMs: 800, message: "模型建议已通过校验。" } };
const failedAdvice = { id: "a2", createdAt: "2026-10-24T00:05:00Z", suggestion: null,
  model: { state: "FAILED", usage: null, elapsedMs: 40, message: "模型调用失败，请核对模型设置。本次保留规则报告；没有保存服务原始错误。" } };
const context = vm.createContext({ document, window, URL, Date, console, URLSearchParams, fetch: async (url, options = {}) => {
  requests.push({ url, options, body: options.body ? JSON.parse(options.body) : null });
  const ok = value => ({ ok: true, json: async () => value });
  if (url === "/api/development/ci/model-option") return ok(modelOption);
  if (url.endsWith("/model/previews")) return ok(modelPreview);
  if (url.endsWith("/model/analyses")) return options.method === "POST" ? ok(advice) : ok([failedAdvice]);
  if (url.startsWith("/api/development/repositories/")) return ok([]);
  throw new Error("Unexpected " + url);
} });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/ci.js"), "utf8"), context);
const tick = () => new Promise(resolve => setImmediate(resolve));
const fixture = { workflowName: "Verify", id: "report-1", job: { name: "Build", url: "https://github.com/acme/service/actions/runs/9/job/91" }, attempt: 1, headSha: "a".repeat(40), createdAt: "2026-10-14T00:00:00Z", source: "PASTED",
  notice: "来自粘贴日志，与所选作业的对应关系未验证。", analysis: { title: "编译阶段失败", status: "OBSERVED", summary: "无法解析符号", checks: ["核对版本"], scope: "Compile", truncated: false,
    evidence: [{ id: "CI-LOG-1", startLine: 10, endLine: 11, text: "[ERROR] cannot find symbol\n<script>alert(1)</script>" }], locations: [{ file: "Foo.java", line: 17 }] } };
(async () => {
  window.ciPanel.render(fixture); await tick();
  assert.match(document.querySelector("#ci-report-meta").textContent, /尝试 1/);
  assert.match(document.querySelector("#ci-report-source").textContent, /未验证/);
  assert.match(document.querySelector("#ci-report-evidence").textContent, /10  \[ERROR\]/);
  assert.match(document.querySelector("#ci-report-evidence").textContent, /11  <script>/);
  assert.match(document.querySelector("#ci-report-locations").textContent, /Foo.java:17/);
  assert.equal(document.querySelector("#ci-report-meta").dataset.reportId, "report-1");
  assert.match(document.querySelector("#ci-model-option").textContent, /fixture-ci/);
  assert.equal(document.querySelector("#ci-model-preview-button").disabled, false);
  assert.equal(document.querySelector("#ci-model-history-box").hidden, false);
  // 预览发送范围：POST 带 marker；渲染作业身份、脱敏块与裁剪说明，确认前不可生成。
  const previewing = window.ciPanel.previewModel(); await previewing;
  const previewRequest = requests.find(r => r.url.endsWith("/model/previews"));
  assert.equal(previewRequest.options.headers["X-Triage-Development"], "1");
  const previewBox = document.querySelector("#ci-model-preview");
  assert.match(previewBox.textContent, /尝试 3.*b{40}/);
  assert.match(previewBox.textContent, /编译阶段失败.*COMPILATION_FAILURE/);
  assert.match(previewBox.textContent, /CI-LOG-1（日志 10–12 行）/);
  assert.match(previewBox.textContent, /完整日志其余行不会发送/);
  assert.equal(document.querySelector("#ci-model-consent-row").hidden, false);
  assert.equal(document.querySelector("#ci-model-analyze-button").disabled, true);
  // 未确认不发送；确认后生成建议并渲染引用与状态。
  await window.ciPanel.analyzeModel();
  assert.equal(requests.find(r => r.url.endsWith("/model/analyses") && r.options.method === "POST"), undefined, "Unconfirmed flow must not dispatch the model");
  document.querySelector("#ci-model-confirm").checked = true;
  document.querySelector("#ci-model-confirm").events.change();
  assert.equal(document.querySelector("#ci-model-analyze-button").disabled, false);
  await window.ciPanel.analyzeModel();
  const analysisRequest = requests.find(r => r.url.endsWith("/model/analyses") && r.options.method === "POST");
  assert.deepEqual(analysisRequest.body, { previewId: "mp1", confirmed: true });
  const result = document.querySelector("#ci-model-result");
  assert.match(result.textContent, /状态：SUCCEEDED/);
  assert.match(result.textContent, /150（输入 100 \/ 输出 50）/);
  assert.match(result.textContent, /<script> 符号解析/);
  assert.match(result.textContent, /与规则阶段一致 · 与规则报告的编译阶段失败一致/);
  assert.match(result.textContent, /Inventory 类可能尚未生成。（引用 CI-LOG-1）/);
  assert.match(result.textContent, /建议不构成自动操作/);
  assert.match(document.querySelector("#ci-model-notice").textContent, /规则报告保持不变/);
  assert.equal(document.querySelector("#ci-model-analyze-button").disabled, true, "A consumed preview cannot be replayed");
  // 历史中的失败记录渲染为保留规则报告的说明。
  document.querySelector("#ci-model-history").children[0].events.click();
  assert.match(document.querySelector("#ci-model-result").textContent, /状态：FAILED/);
  assert.match(document.querySelector("#ci-model-result").textContent, /规则报告保持不变/);
  // 失败作业 URL 与空证据回归保留。
  fixture.job.url = "javascript:alert(1)";
  fixture.analysis.status = "INSUFFICIENT_EVIDENCE"; fixture.analysis.evidence = []; fixture.analysis.locations = [];
  requests.length = 0;
  window.ciPanel.render(fixture); await tick();
  assert.equal(document.querySelector("#ci-report-link").children[0].href, undefined);
  assert.equal(document.querySelector("#ci-report-state").textContent, "证据不足");
  assert.match(document.querySelector("#ci-report-evidence").textContent, /没有可引用/);
  assert.match(document.querySelector("#ci-model-option").textContent, /fixture-ci/);
  console.log("CI UI passed: source disclosure, frozen SHA, safe log rendering, explicit model consent flow with citations and preserved rule report");
})().catch(e => { console.error(e); process.exitCode = 1; });
