"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const vm = require("node:vm");

class Element {
  constructor(tag = "div") { this.tagName = tag; this.children = []; this.text = ""; this.value = ""; this.dataset = {}; this.disabled = false; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(item => item.textContent ?? "").join(""); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.text = ""; this.children = children; }
  setAttribute() {}
  addEventListener() {}
  classList = { add() {}, remove() {}, toggle() {} };
}
const nodes = new Map();
const document = {
  querySelector(selector) { if (!nodes.has(selector)) nodes.set(selector, new Element()); return nodes.get(selector); },
  querySelectorAll(selector) { return selector === "[data-lab-scenario]" ? document.querySelector(".live-lab-actions").children : []; },
  createElement: tag => new Element(tag), createElementNS: (ns, tag) => new Element(tag), createTextNode: text => String(text),
};
const calls = [];
const checkoutId = "EP-" + "a".repeat(32);
const context = vm.createContext({ document, window: { location: { search: "" } }, URLSearchParams, console,
  fetch: (url, options) => {
    calls.push({ url, options });
    if (url === "/api/config") return new Promise(() => {});
    if (url === "/api/live-lab/traffic") return Promise.resolve({ ok: true, json: async () => ({ scenario: "OBSERVED", generatedRequests: 5, inventoryTimeoutCount: 5 }) });
    if (url.startsWith("/api/services/catalog-service/endpoints?")) return Promise.resolve({ ok: true, json: async () => ({
      endpoints: [{ endpoint: { id: checkoutId, httpMethod: "GET", routeTemplate: "/api/checkouts/{id}" }, requestCount: 5, timeoutCount: 5 }],
    }) });
    throw new Error("Unexpected URL: " + url);
  }, sourceView: {}, setTimeout() {}, clearTimeout() {}, Set, Map, Intl, Date });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/app.js"), "utf8"), context);

const config = { service: "catalog-service", protocol: "OBSERVATIONS_V5", labEnabled: false,
  checkoutDemoEnabled: true, observationAvailable: true, endpointSupported: true };
context.config = config;
vm.runInContext("runtimeConfig = config; configureLab(config);", context);
const actions = document.querySelector(".live-lab-actions").children;
assert.equal(document.querySelector("#live-lab-title").textContent, "Starter 商品结算");
assert.deepEqual(actions.map(button => button.textContent), ["正常结算", "库存超时结算"]);

vm.runInContext("generateLabTraffic('DOWNSTREAM_TIMEOUT')", context).then(() => {
  assert.equal(calls.filter(call => call.url === "/api/live-lab/traffic").length, 1);
  const sent = calls.find(call => call.url === "/api/live-lab/traffic");
  assert.equal(sent.options.headers["X-Triage-Lab"], "1");
  assert.deepEqual(JSON.parse(sent.options.body), { service: "catalog-service", scenario: "DOWNSTREAM_TIMEOUT", count: 5 });
  assert.match(document.querySelector("#live-lab-status").textContent, /5 次商品结算/);
  assert.equal(document.querySelector("#scenario").value, "OBSERVED");
  assert.equal(document.querySelector("#submit-button").disabled, false);
  assert.equal(document.querySelector("#endpoint").value, checkoutId);
  assert.equal(calls.filter(call => call.url.startsWith("/api/services/catalog-service/endpoints?")).length, 1);
  console.log("Checkout demo UI passed: actions, traffic and endpoint refresh");
}).catch(error => { console.error(error); process.exitCode = 1; });
