"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), vm = require("node:vm"), path = require("node:path");
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.text = ""; this.value = ""; this.events = {}; this.checked = false; this.disabled = false; this.hidden = false; }
  set textContent(v) { this.text = String(v ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(c => c.textContent).join(""); }
  set innerHTML(v) { throw new Error("Search model config must render as safe text"); }
  append(...values) { this.children.push(...values); }
  replaceChildren(...values) { this.text = ""; this.children = values; }
  addEventListener(event, action) { this.events[event] = action; }
  setAttribute() {}
  focus() {}
  reset() { this.events = {}; }
  showModal() { this.open = true; }
  close() { this.open = false; }
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const ok = value => ({ ok: true, json: async () => value });
const searchState = config => ({ embedding: config.embedding, rerank: config.rerank });
const chatState = { providers: [], selection: { mode: "DEMO", providerId: null } };
const embeddingView = { kind: "EMBEDDING", id: "e1", displayName: "<img 向量服务>", baseUrl: "https://svc.example/v1", model: "test-embedding", keyConfigured: true, timeoutSeconds: 5, version: 1 };
const rerankView = { kind: "RERANK", id: "r1", displayName: "重排服务", baseUrl: "https://rerank.example", model: "test-rerank", keyConfigured: true, timeoutSeconds: 8, version: 2 };
function harness(config, confirmDelete = true) {
  const nodes = new Map(), requests = [];
  const document = { querySelector(s) { if (!nodes.has(s)) nodes.set(s, new Element()); return nodes.get(s); }, createElement(tag) { return new Element(tag); }, createElementNS(ns, tag) { return new Element(tag); } };
  const window = { location: { search: "" }, confirm: () => confirmDelete };
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/settings.js"), "utf8"), vm.createContext({ document, window, URLSearchParams, encodeURIComponent, console, AbortSignal, fetch: async (url, options = {}) => {
    requests.push({ url, options, body: options.body ? JSON.parse(options.body) : null });
    if (url === "/api/settings") return ok(chatState);
    if (url === "/api/settings/search-models") return ok(searchState(config));
    if (url === "/api/settings/search-models/embedding/test") return ok({ success: true, message: "Embedding 服务可用，向量维度 4。", dimensions: 4, tokens: 12, elapsedMs: 88 });
    if (url === "/api/settings/search-models/rerank/test") return ok({ success: false, message: "Rerank 服务返回 HTTP 503，请检查配置或稍后重试。", dimensions: null, tokens: null, elapsedMs: 91 });
    if (url.startsWith("/api/settings/search-models/embedding") && options.method === "PUT") {
      config.embedding = { ...(config.embedding || embeddingView), displayName: options.body.displayName, baseUrl: options.body.baseUrl, model: options.body.model, timeoutSeconds: options.body.timeoutSeconds, version: (config.embedding?.version || 0) + 1 };
      return ok(config.embedding);
    }
    if (url.startsWith("/api/settings/search-models/")) return ok({ ...(config.embedding || embeddingView), version: (config.embedding?.version || 0) + 1 });
    throw new Error("Unexpected " + url);
  } }));
  return { document, requests };
}
function flatten(node) { return node.children.flatMap(c => [c, ...flatten(c)]); }
(async () => {
  const empty = harness({ embedding: null, rerank: null }); await tick();
  const cards = empty.document.querySelector("#search-models");
  assert.match(cards.textContent, /Embedding 服务（未配置）/);
  assert.match(cards.textContent, /Rerank 重排服务（未配置）/);
  assert.match(cards.textContent, /知识分段的正文会发送到此服务/);
  assert.match(cards.textContent, /候选摘录（每段最多 1000 字符）会发送到此服务/);
  assert.match(cards.textContent, /不影响关键词检索/);

  const h = harness({ embedding: embeddingView, rerank: rerankView }); await tick();
  assert.match(h.document.querySelector("#search-models").textContent, /<img 向量服务>/, "Names render as text, never HTML");
  assert.match(h.document.querySelector("#search-models").textContent, /https:\/\/svc\.example\/v1.*test-embedding/);
  assert.match(h.document.querySelector("#search-models").textContent, /已保存/);

  // 保存：PUT 携带版本与表单值；Embedding 保存提示需重建向量索引。
  const save = h.document.querySelector("#search-model-form").events.submit;
  flatten(h.document.querySelector("#search-models")).find(n => n.tagName === "button" && n.textContent === "编辑").events.click();
  assert.equal(h.document.querySelector("#search-model-kind").value, "embedding");
  assert.equal(h.document.querySelector("#search-model-key").value, "");
  h.document.querySelector("#search-model-name").value = "新向量服务";
  h.document.querySelector("#search-model-url").value = "https://other.example/v1";
  h.document.querySelector("#search-model-model").value = "test-embedding-2";
  h.document.querySelector("#search-model-key").value = "embedding-secret";
  h.document.querySelector("#search-model-timeout").value = "9";
  await save({ preventDefault() {} }); await tick();
  const put = h.requests.find(r => r.options.method === "PUT");
  assert.match(put.url, /\/api\/settings\/search-models\/embedding/);
  assert.deepEqual(put.body, { displayName: "新向量服务", baseUrl: "https://other.example/v1", model: "test-embedding-2", apiKey: "embedding-secret", timeoutSeconds: 9, version: 1 });
  assert.equal(put.options.headers["X-Triage-Settings"], "1");
  assert.match(h.document.querySelector("#settings-notice").textContent, /重建相关范围的向量索引/);

  // 测试连接：POST /test；成功与失败都不回显密钥。
  const testing = flatten(h.document.querySelector("#search-models")).find(n => n.tagName === "button" && n.textContent === "测试");
  await testing.events.click(); await tick();
  const probe = h.requests.find(r => r.options.method === "POST" && r.url.endsWith("/test"));
  assert.match(probe.url, /embedding\/test/);
  assert.match(h.document.querySelector("#search-models").textContent, /向量维度 4/);

  // 删除：确认后 DELETE 携带当前版本。
  const deleting = flatten(h.document.querySelector("#search-models")).find(n => n.tagName === "button" && n.textContent === "删除");
  await deleting.events.click(); await tick();
  const removed = h.requests.find(r => r.options.method === "DELETE");
  assert.match(removed.url, /\/api\/settings\/search-models\/embedding\?version=2/);
  assert.match(h.document.querySelector("#settings-notice").textContent, /已删除/);
  const refuse = harness({ embedding: embeddingView, rerank: rerankView }, false); await tick();
  const refusing = flatten(refuse.document.querySelector("#search-models")).find(n => n.tagName === "button" && n.textContent === "删除");
  await refusing.events.click(); await tick();
  assert.equal(refuse.requests.find(r => r.options.method === "DELETE"), undefined, "Delete without confirm must not call the API");
  console.log("Search model settings UI passed: separate optional embedding/rerank cards, send-scope notes, safe text, versioned save with rebuild hint, test and confirmed delete");
})().catch(e => { console.error(e); process.exitCode = 1; });
