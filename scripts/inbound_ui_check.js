"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

// Only the DOM operations used by the application renderer are supplied here.
class Element {
  constructor(tag = "div") { this.tagName = tag; this.children = []; this.value = ""; this.dataset = {}; this.attributes = {}; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return (this.text || "") + this.children.map(child => typeof child === "string" ? child : child.textContent).join(""); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.text = ""; this.children = [...children]; }
  setAttribute(name, value) { this.attributes[name] = value; }
  addEventListener() {}
  querySelectorAll() { return []; }
  classList = { add() {}, remove() {}, toggle() {} };
}
const elements = new Map();
const document = {
  querySelector(selector) { if (!elements.has(selector)) elements.set(selector, new Element()); return elements.get(selector); },
  querySelectorAll() { return []; }, createElement: tag => new Element(tag), createElementNS: (ns, tag) => new Element(tag),
  createTextNode: text => String(text),
};
const context = vm.createContext({ document, window: { location: { search: "" } }, URLSearchParams, console,
  fetch: () => new Promise(() => {}), sourceView: {}, setTimeout() {}, clearTimeout() {}, Set, Map, Intl, Date });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/app.js"), "utf8"), context);
const run = {
  service: "entry-service", serviceInfo: { id: "entry-service", name: "入口服务", downstreamId: null, downstreamName: null },
  windowMinutes: 1, evidence: [{ id: "metrics", source: "read_service_metrics", title: "请求", summary: "入站请求", data: {
    observationType: "HTTP_REQUESTS", requestCount: 2, requestP95Ms: 20, baselineRequestP95Ms: null,
    windowStart: "2026-10-02T00:00:00Z", windowEnd: "2026-10-02T00:01:00Z", requestDetails: { endpoints: [] },
  } }],
};
context.fixture = run;
vm.runInContext("renderMetrics(fixture); renderEvidence(fixture);", context);
function presentation(node) {
  if (typeof node === "string") return node;
  if (node.tagName === "pre") return "";
  return (node.text || "") + node.children.map(presentation).join("");
}
for (const selector of ["#metrics", "#evidence-list"]) {
  const text = presentation(document.querySelector(selector));
  assert.match(text, /未采集/);
  assert.doesNotMatch(text, /undefined|NaN|null|下游超时率0%|下游调用 p950 ms/);
}
run.evidence[0].data.requestFailures = { executionFailures: 1, serverErrorResponses: 0, asyncTimeouts: 0, asyncErrors: 0, handledExceptions: 0 };
vm.runInContext("renderMetrics(fixture); renderEvidence(fixture);", context);
assert.match(presentation(document.querySelector("#metrics")), /请求执行异常1/);
assert.match(presentation(document.querySelector("#evidence-list")), /请求失败分类已采集/);
run.serviceInfo.downstreamId = "inventory-service"; run.serviceInfo.downstreamName = "库存服务";
run.evidence[0].data.observationType = undefined;
run.evidence[0].data.downstreamP95Ms = 10; run.evidence[0].data.downstreamTimeoutRate = 0;
vm.runInContext("renderMetrics(fixture); renderEvidence(fixture);", context);
assert.match(document.querySelector("#metrics").textContent, /下游超时率0%/);
assert.match(document.querySelector("#evidence-list").textContent, /下游调用 p9510 ms/);
console.log("Inbound UI passed: missing downstream stays uncollected; legacy zero timeout remains a measured zero");
