"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor() { this.children = []; this.text = ""; this.value = ""; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(""); }
  set innerHTML(value) { throw new Error("Raw logs must be displayed as text"); }
  replaceChildren(...children) { this.children = children; this.text = ""; }
  append(...children) { this.children.push(...children); }
  addEventListener() {}
  scrollIntoView() {}
  classList = { toggle() {} };
}
const nodes = new Map(), document = { querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); }, createElement: () => new Element() };
const window = {}, context = vm.createContext({ document, window, URL, Date, console, fetch: () => new Promise(() => {}) });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/ci.js"), "utf8"), context);
const fixture = { workflowName: "Verify", job: { name: "Build", url: "https://github.com/acme/service/actions/runs/9/job/91" }, attempt: 1, headSha: "a".repeat(40), createdAt: "2026-10-14T00:00:00Z", source: "PASTED",
  notice: "来自粘贴日志，与所选作业的对应关系未验证。", analysis: { title: "编译阶段失败", status: "OBSERVED", summary: "无法解析符号", checks: ["核对版本"], scope: "Compile", truncated: false,
    evidence: [{ id: "CI-LOG-1", startLine: 10, endLine: 11, text: "[ERROR] cannot find symbol\n<script>alert(1)</script>" }], locations: [{ file: "Foo.java", line: 17 }] } };
window.ciPanel.render(fixture);
assert.match(document.querySelector("#ci-report-meta").textContent, /尝试 1/);
assert.match(document.querySelector("#ci-report-source").textContent, /未验证/);
assert.match(document.querySelector("#ci-report-evidence").textContent, /10  \[ERROR\]/);
assert.match(document.querySelector("#ci-report-evidence").textContent, /11  <script>/);
assert.match(document.querySelector("#ci-report-locations").textContent, /Foo.java:17/);
fixture.job.url = "javascript:alert(1)";
fixture.analysis.status = "INSUFFICIENT_EVIDENCE"; fixture.analysis.evidence = []; fixture.analysis.locations = [];
window.ciPanel.render(fixture);
assert.equal(document.querySelector("#ci-report-link").children[0].href, undefined);
assert.equal(document.querySelector("#ci-report-state").textContent, "证据不足");
assert.match(document.querySelector("#ci-report-evidence").textContent, /没有可引用/);
console.log("CI UI passed: source disclosure, frozen SHA, line references and safe log rendering");
