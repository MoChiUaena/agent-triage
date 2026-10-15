"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor() { this.children = []; this.text = ""; this.value = ""; this.checked = false; this.events = {}; this.open = false; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(""); }
  set innerHTML(value) { throw new Error("Issue and model output must be rendered as text"); }
  replaceChildren(...children) { this.children = children; this.text = ""; }
  append(...children) { this.children.push(...children); }
  addEventListener(name, callback) { this.events[name] = callback; }
  scrollIntoView() {}
  showModal() { this.open = true; }
  close() { this.open = false; }
  classList = { toggle() {} };
}
const nodes = new Map(), document = { querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); }, createElement: () => new Element() };
const window = {}, calls = []; let resolvePreview;
const fixture = { id: "report", repository: "acme/service", issue: { number: 2, title: "<script>Issue</script>", url: "https://github.com/acme/service/issues/2", updatedAt: "2026-10-14T00:00:00Z", labels: ["bug"] }, branch: "main", headSha: "a".repeat(40), createdAt: "2026-10-14T00:00:00Z", contextReadAt: "2026-10-14T00:00:00Z", source: "MODEL",
  proposal: { summary: "Reported failure", category: "BUG", priority: "P2", reason: "Reporter description", evidenceIds: ["ISSUE-2"], questions: ["Which version?"], actions: [{ text: "Reproduce", evidenceIds: ["ISSUE-2"] }], ownerCandidates: [{ login: "alice", reason: "Candidate only", evidenceIds: ["OWNER-1"] }] },
  model: { state: "SUCCEEDED", modelName: "test-model", message: "模型建议仍需确认。", elapsedMs: 80, usage: {inputTokens: 100, outputTokens: 50, totalTokens: 150} },
  evidence: [{ id: "ISSUE-2", kind: "ISSUE", title: "Issue 正文", url: "https://github.com/acme/service/issues/2", file: "", startLine: 1, endLine: 2, text: "First line\n<script>untrusted</script>" }, { id: "OWNER-1", kind: "CONTRIBUTOR", title: "Contributor", url: "https://github.com/alice", file: "", startLine: 0, endLine: 0, text: "alice" }], owners: [{login: "alice", url: "https://github.com/alice", evidenceId: "OWNER-1"}], gaps: ["评论未读取"] };
const preview = { id: "preview", issue: fixture.issue, branch: "main", headSha: fixture.headSha, evidence: fixture.evidence, owners: fixture.owners, gaps: fixture.gaps, model: { available: true, displayName: "测试模型", modelName: "test-model", baseUrl: "http://127.0.0.1/model" } };
const context = vm.createContext({ document, window, URL, Date, console, fetch: async (url, options) => {
  calls.push({url, options});
  if (url.endsWith("/previews")) return new Promise(resolve => { resolvePreview = resolve; });
  return { ok: true, json: async () => options?.method === "POST" ? fixture : [] };
}});
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/issue.js"), "utf8"), context);
window.issuePanel.render(fixture);
assert.match(document.querySelector("#issue-report-title").textContent, /<script>/);
assert.match(document.querySelector("#issue-report-mode").textContent, /模型建议/);
assert.match(document.querySelector("#issue-report-evidence").textContent, /2  <script>/);
assert.match(document.querySelector("#issue-report-model").textContent, /150/);
assert.match(document.querySelector("#issue-report-owners").textContent, /alice/);
fixture.issue.url = "javascript:alert(1)"; window.issuePanel.render(fixture);
assert.equal(document.querySelector("#issue-report-link").children[0].href, undefined);
(async () => {
  window.issuePanel.selectRepository("first");
  const old = window.issuePanel.openIssue("first", 2);
  window.issuePanel.selectRepository("second"); resolvePreview({ok:true, json:async () => preview}); await old;
  assert.equal(document.querySelector("#issue-triage-dialog").open, false);
  assert.equal(document.querySelector("#issue-generate-button").disabled, true);
  const fresh = window.issuePanel.openIssue("second", 2); resolvePreview({ok:true, json:async () => preview}); await fresh;
  assert.equal(document.querySelector("#issue-use-model").checked, false);
  assert.match(document.querySelector("#issue-model-destination").textContent, /127\.0\.0\.1/);
  await document.querySelector("#issue-generate-form").events.submit({preventDefault(){}});
  const write = calls.filter(call => call.url.endsWith("/reports") && call.options?.method === "POST").at(-1);
  assert.equal(JSON.parse(write.options.body).useModel, false);
  assert.equal(JSON.parse(write.options.body).previewId, "preview");
  const editable = window.issuePanel.openIssue("second", 2); resolvePreview({ok:true, json:async () => preview}); await editable;
  document.querySelector("#issue-use-model").checked = true;
  document.querySelector("#issue-code-paths").value = "src/New.java";
  document.querySelector("#issue-code-paths").events.input?.();
  assert.equal(document.querySelector("#issue-generate-button").disabled, true);
  assert.equal(document.querySelector("#issue-use-model").checked, false);
  console.log("Issue UI passed: explicit model consent, frozen preview, evidence, safe text and stale responses");
})().catch(error => { console.error(error); process.exitCode = 1; });
