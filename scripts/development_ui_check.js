"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), path = require("node:path"), vm = require("node:vm");
class Element {
  constructor(tag = "div") { this.tagName = tag; this.children = []; this.value = ""; this.text = ""; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(child => child.textContent).join(""); }
  set innerHTML(value) { throw new Error("Untrusted HTML must not be rendered"); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this.text = ""; }
  addEventListener() {}
  classList = { toggle() {} };
}
const nodes = new Map(), document = {
  querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); },
  createElement: tag => new Element(tag),
};
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
