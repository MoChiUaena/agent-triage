"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

class Element {
  constructor(tag = "div") { this.tagName = tag; this.children = []; this.text = ""; this.value = ""; this.dataset = {}; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(item => typeof item === "string" ? item : item.textContent).join(""); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.text = ""; this.children = [...children]; }
  setAttribute() {}
  addEventListener() {}
  querySelectorAll() { return []; }
  classList = { add() {}, remove() {}, toggle() {} };
}
const nodes = new Map();
const document = {
  querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); },
  querySelectorAll() { return []; }, createElement: tag => new Element(tag),
  createElementNS: (ns, tag) => new Element(tag), createTextNode: text => String(text),
};
const context = vm.createContext({ document, window: { location: { search: "" } }, URLSearchParams, console,
  fetch: () => new Promise(() => {}), sourceView: {}, setTimeout() {}, clearTimeout() {}, Set, Map, Intl, Date });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/app.js"), "utf8"), context);

const rows = [
  { id: "inventory-service", name: "库存服务", requestCount: 2, timeoutCount: 1, downstreamP95Ms: 300, downstreamTimeoutRate: 0.5,
    responseStatuses: { informational: 0, successful: 1, redirection: 0, clientError: 0, serverError: 0, unknown: 1 } },
  { id: "payment-service", name: "支付服务", requestCount: 3, timeoutCount: 0, downstreamP95Ms: 20, downstreamTimeoutRate: 0,
    responseStatuses: { informational: 0, successful: 2, redirection: 0, clientError: 0, serverError: 1, unknown: 0 } },
];
const run = {
  service: "checkout-service", windowMinutes: 5,
  serviceInfo: { id: "checkout-service", name: "结算服务", downstreamId: null, downstreamName: null,
    downstreams: rows.map(({ id, name }) => ({ id, name })) },
  evidence: [{ id: "metrics", source: "read_service_metrics", title: "指标", summary: "分项指标", data: {
    observationType: "HTTP_DOWNSTREAMS", requestCount: 5, timeoutCount: 1, requestP95Ms: 310,
    downstreamP95Ms: 300, downstreamTimeoutRate: 0.2, downstreams: rows,
    windowStart: "2026-10-12T00:00:00Z", windowEnd: "2026-10-12T00:05:00Z",
  } }],
};
context.fixture = run;
vm.runInContext("renderMetrics(fixture); renderEvidence(fixture);", context);
for (const selector of ["#metrics", "#evidence-list"]) {
  const text = document.querySelector(selector).textContent;
  assert.match(text, /库存服务/);
  assert.match(text, /支付服务/);
  assert.match(text, /1 次超时/);
  assert.match(text, /0 次超时/);
  assert.match(text, /2xx 2/);
  assert.match(text, /5xx 1/);
  assert.match(text, /状态未知 1/);
  assert.doesNotMatch(text, /undefined|NaN|null/);
}
rows[0].responseStatuses = undefined;
rows[1].responseStatuses = undefined;
vm.runInContext("renderMetrics(fixture); renderEvidence(fixture);", context);
assert.match(document.querySelector("#metrics").textContent, /响应分类未采集/);
assert.doesNotMatch(document.querySelector("#evidence-list").textContent, /undefined|NaN|null/);
run.serviceInfo = null;
run.service = "order-service";
run.evidence[0].data.observationType = undefined;
run.evidence[0].data.downstreams = undefined;
vm.runInContext("renderMetrics(fixture); renderEvidence(fixture);", context);
assert.match(document.querySelector("#metrics").textContent, /下游超时率/);
assert.doesNotMatch(document.querySelector("#metrics").textContent, /undefined|NaN|null/);
console.log("Multi-downstream UI passed: separate rows and legacy history");
