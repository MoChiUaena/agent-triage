"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), vm = require("node:vm"), path = require("node:path");
class Element {
  constructor(tag) { this.tagName = tag; this.children = []; this.text = ""; this.value = ""; this.events = {}; }
  set textContent(v) { this.text = String(v ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(c => c.textContent).join(""); }
  set innerHTML(v) { throw new Error("Search must render as safe text"); }
  append(...values) { this.children.push(...values); }
  replaceChildren(...values) { this.text = ""; this.children = values; }
  addEventListener(event, action) { this.events[event] = action; }
}
const tick = () => new Promise(resolve => setImmediate(resolve));
const ok = value => ({ ok: true, json: async () => value });
const hit = { chunkId: "chunk", versionId: "version", sourceId: "source", scope: { kind: "PROJECT", id: "one" }, title: "<img title>", excerpt: "late <script>needle</script>", path: "guide.md", startLine: 4, endLine: 6,
  sha256: "document-hash", chunkSha256: "chunk-hash", text: "Exact old original <script>", importedAt: "2026-10-23T00:00:00Z", recallMethod: "KEYWORD", score: 2, sourceUrl: "javascript:alert(1)" };
const result = { results: [hit], scannedChunks: 1, availableChunks: 2, truncated: true, coverage: "bounded scan" };
function harness(search = "", registered = true) {
  const nodes = new Map(), searches = [], chunks = [], requests = [];
  const document = { querySelector(s) { if (!nodes.has(s)) nodes.set(s, new Element()); return nodes.get(s); }, createElement(tag) { return new Element(tag); } };
  const window = { location: { search }, confirm: () => true };
  let sources = [{ id: "source", scope: hit.scope, origin: "LOCAL", state: "ACTIVE", revision: 1, root: "docs", documents: 1 }];
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/knowledge.js"), "utf8"), vm.createContext({ document, window, URLSearchParams, URL, Date, encodeURIComponent, console,
    clearTimeout() {}, setTimeout: () => 1, fetch: async (url, options = {}) => {
      requests.push({ url, options });
      if (url === "/api/materials/scopes") return ok(registered ? [{ kind: "PROJECT", id: "one", name: "One", service: "svc" }, { kind: "PROJECT", id: "two", name: "Two" }] : []);
      if (url.startsWith("/api/knowledge/sources?")) return ok(sources);
      if (url.startsWith("/api/knowledge/jobs?")) return ok([]);
      if (url.startsWith("/api/knowledge/search?")) return new Promise(resolve => searches.push({ url, resolve }));
      if (url.startsWith("/api/knowledge/versions/version/chunks/chunk?")) return new Promise(resolve => chunks.push({ url, resolve }));
      if (url === "/api/knowledge/sources/source") { sources = [{ ...sources[0], state: "UNBOUND", revision: 2 }]; return ok(sources[0]); }
      if (url.startsWith("/api/knowledge/versions/version?")) return ok({ ...hit, id: "version", root: "docs", origin: "LOCAL", text: "Full immutable document" });
      throw new Error("Unexpected " + url);
    } }));
  return { document, window, searches, chunks, requests, setSources(value) { sources = value; } };
}
function find(root, text) { return root.children.flatMap(c => [c, ...findAll(c)]).find(c => c.textContent === text); }
function findAll(root) { return root.children.flatMap(c => [c, ...findAll(c)]); }
async function select(h, value = "PROJECT:one") { h.document.querySelector("#knowledge-scope").value = value; await h.window.knowledgePanel.select(); }
async function render(h, value = result) {
  h.document.querySelector("#knowledge-query").value = "needle";
  h.document.querySelector("#knowledge-doc-type").value = "MARKDOWN";
  const p = h.window.knowledgePanel.search(); h.searches.at(-1).resolve(ok(value)); await p;
}
(async () => {
  const h = harness(); await tick(); await select(h);
  await render(h);
  assert.match(h.searches[0].url, /scopeKind=PROJECT&scopeId=one/);
  assert.match(h.searches[0].url, /docType=MARKDOWN/);
  const results = h.document.querySelector("#knowledge-results");
  assert.match(results.textContent, /<img title>.*late <script>needle<\/script>/);
  assert.match(results.textContent, /覆盖|截断|上限/);
  assert.match(results.textContent, /document-hash.*chunk-hash/);
  assert.equal(findAll(results).some(n => n.href?.startsWith("javascript:")), false);
  const open = find(results, "查看分段原文"); const viewing = open.events.click();
  assert.match(h.chunks[0].url, /versions\/version\/chunks\/chunk\?scopeKind=PROJECT&scopeId=one/);
  h.chunks[0].resolve(ok(hit)); await viewing;
  assert.match(h.document.querySelector("#knowledge-original").textContent, /4–6.*Exact old original <script>/);
  const full = find(h.document.querySelector("#knowledge-original"), "查看此版本全文"); await full.events.click();
  assert.match(h.document.querySelector("#knowledge-original").textContent, /Full immutable document/);
  const stale = h.window.knowledgePanel.search(); await select(h, "PROJECT:two"); h.searches.at(-1).resolve(ok(result)); await stale;
  assert.equal(results.textContent, "", "Old search response cannot cross scope selection");
  assert.equal(h.document.querySelector("#knowledge-original").textContent, "");
  await select(h); await render(h);
  const staleChunk = find(results, "查看分段原文").events.click(); await select(h, "PROJECT:two"); h.chunks.at(-1).resolve(ok(hit)); await staleChunk;
  assert.equal(h.document.querySelector("#knowledge-original").textContent, "", "Old chunk cannot cross scope selection");
  await select(h); await render(h);
  const beforeUpdate = h.window.knowledgePanel.search();
  find(h.document.querySelector("#knowledge-sources"), "更新 / 重新绑定").events.click();
  h.searches.at(-1).resolve(ok(result)); await beforeUpdate;
  assert.equal(results.textContent, "", "Updating source clears old citations and pending results");
  await render(h);
  await find(h.document.querySelector("#knowledge-sources"), "解绑").events.click();
  assert.equal(results.textContent, "", "Unbind clears citations");
  await render(h, { ...result, truncated: false, results: [] });
  assert.match(results.textContent, /没有匹配/);
  await render(h, { ...result, results: Array.from({ length: 40 }, () => hit) });
  assert.equal(findAll(results).filter(n => n.tagName === "article").length, 20, "DOM rendering bounded even for oversized response");
  await render(h, { ...result, results: [{ ...hit, sourceUrl: "https://github.com/fixture/repo/blob/" + "a".repeat(40) + "/docs/guide.md#L4-L6" }] });
  const fixedSource = find(results, "查看固定提交来源");
  assert.match(fixedSource.href, /^https:\/\/github\.com\/fixture\/repo\/blob\//);
  assert.equal(fixedSource.rel, "noopener noreferrer");

  const deep = harness("?scopeKind=PROJECT&scopeId=one&version=version&chunk=chunk"); await tick();
  assert.equal(deep.chunks.length, 1, "Chunk deep link loads scoped persisted chunk");
  deep.chunks[0].resolve(ok(hit)); await tick();
  assert.match(deep.document.querySelector("#knowledge-original").textContent, /Exact old original/);
  const historical = harness("?scopeKind=PROJECT&scopeId=one&version=version&chunk=chunk", false); await tick();
  assert.equal(historical.chunks.length, 1, "Historical chunk still opens with removed registration");
  historical.chunks[0].resolve(ok(hit)); await tick();
  assert.match(historical.document.querySelector("#knowledge-original").textContent, /Exact old original/);
  const obsolete = harness("?scopeKind=PROJECT&scopeId=one&version=version&chunk=chunk"); await tick();
  await select(obsolete, "PROJECT:two"); obsolete.chunks[0].resolve(ok(hit)); await tick();
  assert.equal(obsolete.document.querySelector("#knowledge-original").textContent, "");
  console.log("Knowledge search UI passed: scoped filters, bounded safe text, persisted chunk/original, deep link and stale search/chunk/update/unbind guards");
})().catch(e => { console.error(e); process.exitCode = 1; });
