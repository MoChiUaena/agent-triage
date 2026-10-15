"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor() { this.children = []; this.text = ""; this.value = ""; this.events = {}; this.open = false; }
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
const nodes = new Map(), document = { querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); }, createElement: () => new Element() };
const window = {}, calls = []; let pendingPull;
const context = vm.createContext({ document, window, URL, Date, console, fetch: async (url, options) => {
  calls.push({url, options});
  if (url.endsWith("/pulls/7")) return new Promise(resolve => { pendingPull = resolve; });
  return { ok: true, json: async () => [] };
}});
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/pr.js"), "utf8"), context);
const fixture = { id: "report", repository: "acme/service", pull: { number: 7, title: "<script>alert(1)</script>", url: "https://github.com/acme/service/pull/7", headSha: "b".repeat(40), baseSha: "a".repeat(40), changedFiles: 2, additions: 2, deletions: 1 }, mergeBaseSha: "c".repeat(40), createdAt: "2026-10-14T00:00:00Z", notice: "规则分析，不构成合入批准。",
  files: [{ file: "Security.java", status: "modified", additions: 1, deletions: 1, coverage: "COMPLETE" }], findings: [{ id: "PR-CHANGE-1", title: "访问控制规则变更", file: "Security.java", line: 11, side: "RIGHT", excerpt: "http.permitAll(); <script>", reason: "需核对路径", check: "验证匿名访问", url: "https://github.com/acme/service/blob/b/Security.java#L11" }],
  ci: { state: "INCOMPLETE", checks: [{ name: "Verify", status: "completed", conclusion: "success", url: "https://github.com/acme/service/pull/7/checks" }], gaps: ["Checks 未完整读取"] }, issues: [{ number: 2, title: "<img src=x>", state: "open", url: "https://github.com/acme/service/issues/2" }], gaps: ["未读取部分补丁"] };
window.prPanel.render(fixture);
assert.match(document.querySelector("#pr-report-title").textContent, /<script>/);
assert.match(document.querySelector("#pr-report-versions").textContent, /c{40}/);
assert.match(document.querySelector("#pr-report-findings").textContent, /Security.java:11/);
assert.match(document.querySelector("#pr-report-findings").textContent, /<script>/);
assert.match(document.querySelector("#pr-report-ci-state").textContent, /未完整读取/);
assert.match(document.querySelector("#pr-report-gaps").textContent, /未读取部分补丁/);
assert.match(document.querySelector("#pr-report-issues").textContent, /#2/);
fixture.findings = []; fixture.pull.url = "javascript:alert(1)";
window.prPanel.render(fixture);
assert.equal(document.querySelector("#pr-report-link").children[0].href, undefined);
assert.match(document.querySelector("#pr-report-findings").textContent, /不代表/);
(async () => {
  window.prPanel.selectRepository("first");
  const request = window.prPanel.openPull("first", 7);
  window.prPanel.selectRepository("second");
  pendingPull({ ok: true, json: async () => fixture.pull });
  await request;
  assert.equal(document.querySelector("#pr-review-dialog").open, false);
  assert.equal(document.querySelector("#pr-review-button").disabled, true);
  assert.equal(document.querySelector("#pr-report-result").hidden, true);
  console.log("PR UI passed: frozen versions, scope gaps, safe text and stale repository responses");
})().catch(error => { console.error(error); process.exitCode = 1; });
