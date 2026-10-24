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
function harness(search = "", registered = true, config = null) {
  const nodes = new Map(), searches = [], chunks = [], requests = [], embeds = [];
  let embedJobs = [];
  const document = { querySelector(s) { if (!nodes.has(s)) nodes.set(s, new Element()); return nodes.get(s); }, createElement(tag) { return new Element(tag); } };
  const window = { location: { search }, confirm: () => true };
  let sources = [{ id: "source", scope: hit.scope, origin: "LOCAL", state: "ACTIVE", revision: 1, root: "docs", documents: 1 }];
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/knowledge.js"), "utf8"), vm.createContext({ document, window, URLSearchParams, URL, Date, encodeURIComponent, console,
    clearTimeout() {}, setTimeout: () => 1, fetch: async (url, options = {}) => {
      requests.push({ url, options });
      if (url === "/api/materials/scopes") return ok(registered ? [{ kind: "PROJECT", id: "one", name: "One", service: "svc" }, { kind: "PROJECT", id: "two", name: "Two" }] : []);
      if (url === "/api/settings/search-models") return ok(config);
      if (url.startsWith("/api/knowledge/sources?")) return ok(sources);
      if (url.startsWith("/api/knowledge/embeddings/jobs?")) return ok(embedJobs);
      if (url.startsWith("/api/knowledge/jobs?")) return ok([]);
      if (url.startsWith("/api/knowledge/search?")) return new Promise(resolve => searches.push({ url, resolve }));
      if (options.method === "POST" && url.startsWith("/api/knowledge/hybrid-search")) { const body = JSON.parse(options.body); embeds.push({ url, body, options }); return new Promise(resolve => searches.push({ url, resolve, body })); }
      if (options.method === "POST" && url.startsWith("/api/knowledge/embeddings/")) { embeds.push({ url, body: JSON.parse(options.body), options }); return new Promise(resolve => searches.push({ url, resolve })); }
      if (url.startsWith("/api/knowledge/versions/version/chunks/chunk?")) return new Promise(resolve => chunks.push({ url, resolve }));
      if (url === "/api/knowledge/sources/source") { sources = [{ ...sources[0], state: "UNBOUND", revision: 2 }]; return ok(sources[0]); }
      if (url.startsWith("/api/knowledge/versions/version?")) return ok({ ...hit, id: "version", root: "docs", origin: "LOCAL", text: "Full immutable document" });
      throw new Error("Unexpected " + url);
    } }));
  return { document, window, searches, chunks, requests, embeds, setSources(value) { sources = value; }, setEmbedJobs(value) { embedJobs = value; } };
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

  // 混合检索与向量索引（K3）：未配置时保持关闭并说明；配置后需要显式发送确认。
  const hybridCitation = { ...hit, recallMethod: "FUSED" };
  const vectorOnly = { ...hit, path: "vector-only.md", recallMethod: "VECTOR" };
  const hybridResult = { scope: hit.scope, query: "needle", docType: "ALL", service: null, recallMethod: "HYBRID",
    results: [{ citation: hybridCitation, matchedBy: ["KEYWORD", "VECTOR"], fusedScore: 0.032, rerankScore: null },
      { citation: vectorOnly, matchedBy: ["VECTOR"], fusedScore: 0.016, rerankScore: 0.87 }],
    scannedChunks: 2, availableChunks: 3, truncated: false,
    coverage: "混合检索在本机完成融合（RRF，k=60）…引用仍指向原始知识分段。",
    vector: { status: "USED", reason: "已使用 3 段当前索引", indexedChunks: 3, activeChunks: 3, scannedEmbeddings: 3, dimension: 4, queryTokens: 12 },
    rerank: { status: "OFF", reason: "本次未启用重排。", candidates: 0 } };
  const embedPreviewValue = { id: "embed-preview", scope: hit.scope, chunks: 2, sources: 1, model: "test-embedding",
    baseUrl: "https://svc.example/v1", fingerprint: "fp", truncated: false, note: "建立向量索引会把 2 个当前有效知识分段的正文发送至 https://svc.example/v1（模型 test-embedding）。",
    createdAt: "2026-10-24T00:00:00Z", expiresAt: "2026-10-24T00:10:00Z" };
  const embedJob = { id: "ejob", scope: hit.scope, state: "RUNNING", progress: 1, total: 2, cancelRequested: false,
    message: "正在发送分段正文到 Embedding 服务", fingerprint: "fp", model: "test-embedding", usageCalls: 1, usageTokens: 42,
    createdAt: "2026-10-24T00:00:00Z", updatedAt: "2026-10-24T00:00:00Z" };
  const withoutConfig = harness(); await tick(); await select(withoutConfig);
  withoutConfig.document.querySelector("#knowledge-mode").value = "HYBRID";
  withoutConfig.document.querySelector("#knowledge-mode").events.change();
  assert.match(withoutConfig.document.querySelector("#knowledge-send-note").textContent, /尚未配置 Embedding/);
  assert.equal(withoutConfig.document.querySelector("#knowledge-embed-preview-button").disabled, true);
  withoutConfig.document.querySelector("#knowledge-query").value = "needle";
  await withoutConfig.window.knowledgePanel.search();
  assert.equal(withoutConfig.embeds.length, 0, "Hybrid search must not send without a configured embedding service");
  assert.match(withoutConfig.document.querySelector("#knowledge-notice").textContent, /尚未配置 Embedding/);

  const configured = harness("", true, { embedding: { id: "e1", displayName: "向量服务", baseUrl: "https://svc.example/v1", model: "test-embedding", keyConfigured: true, timeoutSeconds: 5, version: 1 },
    rerank: { id: "r1", displayName: "重排服务", baseUrl: "https://rerank.example", model: "test-rerank", keyConfigured: true, timeoutSeconds: 5, version: 1 } });
  await tick(); await select(configured);
  assert.match(configured.document.querySelector("#knowledge-send-note").textContent, /test-embedding.*svc\.example/);
  assert.match(configured.document.querySelector("#knowledge-send-note").textContent, /test-rerank/);
  assert.equal(configured.document.querySelector("#knowledge-embed-preview-button").disabled, false);
  configured.document.querySelector("#knowledge-mode").value = "HYBRID";
  configured.document.querySelector("#knowledge-mode").events.change();
  assert.equal(configured.document.querySelector("#knowledge-hybrid-consent").hidden, false);
  configured.document.querySelector("#knowledge-query").value = "needle";
  await configured.window.knowledgePanel.search();
  assert.equal(configured.embeds.length, 0, "Hybrid search must require the explicit embed confirmation");
  configured.document.querySelector("#knowledge-embed-confirm").checked = true;
  configured.document.querySelector("#knowledge-rerank").checked = true;
  configured.document.querySelector("#knowledge-rerank").events.change();
  await configured.window.knowledgePanel.search();
  assert.equal(configured.embeds.length, 0, "Rerank needs its own material confirmation before excerpts leave");
  assert.match(configured.document.querySelector("#knowledge-notice").textContent, /Rerank/);
  configured.document.querySelector("#knowledge-rerank-confirm").checked = true;
  const hybridRun = configured.window.knowledgePanel.search();
  configured.searches.at(-1).resolve(ok(hybridResult)); await hybridRun;
  const hybridPost = configured.embeds.at(-1);
  assert.match(hybridPost.url, /hybrid-search/);
  assert.deepEqual(hybridPost.body, { scopeKind: "PROJECT", scopeId: "one", query: "needle", docType: "ALL", limit: 10, confirmed: true, rerank: true, documentsConfirmed: true });
  assert.equal(hybridPost.options.headers["X-Triage-Knowledge"], "1");
  const hybridResults = configured.document.querySelector("#knowledge-results");
  assert.match(hybridResults.textContent, /<img title>/);
  assert.match(hybridResults.textContent, /KEYWORD\+VECTOR.*融合 0\.032/);
  assert.match(hybridResults.textContent, /vector-only\.md.*VECTOR.*融合 0\.016.*重排 0\.87/);
  assert.match(hybridResults.textContent, /向量：USED.*查询已知 tokens 12/);
  assert.match(hybridResults.textContent, /RRF/);
  assert.equal(findAll(hybridResults).some(n => n.href?.startsWith("javascript:")), false);
  const vectorChunk = find(hybridResults, "查看分段原文"); const hybridView = vectorChunk.events.click();
  assert.match(configured.chunks.at(-1).url, /versions\/version\/chunks\/chunk\?scopeKind=PROJECT&scopeId=one/);
  configured.chunks.at(-1).resolve(ok({ ...hit, recallMethod: "HYBRID" })); await hybridView;
  assert.match(configured.document.querySelector("#knowledge-original").textContent, /HYBRID/);

  // 向量索引：预览 → 明确确认正文外发 → 提交任务 → 任务与已知用量可见，可取消。
  const withEmbed = harness("", true, { embedding: { id: "e1", displayName: "向量服务", baseUrl: "https://svc.example/v1", model: "test-embedding", keyConfigured: true, timeoutSeconds: 5, version: 1 }, rerank: null });
  withEmbed.setEmbedJobs([embedJob]);
  await tick(); await select(withEmbed);
  const previewRun = withEmbed.window.knowledgePanel.embedPreview();
  withEmbed.searches.at(-1).resolve(ok(embedPreviewValue)); await previewRun;
  const previewPost = withEmbed.embeds.at(-1);
  assert.match(previewPost.url, /embeddings\/preview/);
  assert.deepEqual(previewPost.body, { scopeKind: "PROJECT", scopeId: "one" });
  const embedBox = withEmbed.document.querySelector("#knowledge-embed-preview");
  assert.match(embedBox.textContent, /test-embedding.*svc\.example/);
  assert.match(embedBox.textContent, /当前有效分段 2 个/);
  assert.equal(withEmbed.document.querySelector("#knowledge-embed-consent-row").hidden, false);
  assert.equal(withEmbed.document.querySelector("#knowledge-embed-start").disabled, true);
  withEmbed.document.querySelector("#knowledge-embed-index-confirm").checked = true;
  withEmbed.document.querySelector("#knowledge-embed-index-confirm").events.change();
  assert.equal(withEmbed.document.querySelector("#knowledge-embed-start").disabled, false);
  const startRun = withEmbed.window.knowledgePanel.embedStart();
  withEmbed.searches.at(-1).resolve(ok({ ...embedJob, state: "QUEUED", progress: 0 }));
  await startRun;
  const jobPost = withEmbed.embeds.at(-1);
  assert.match(jobPost.url, /embeddings\/jobs/);
  assert.deepEqual(jobPost.body, { scopeKind: "PROJECT", scopeId: "one", previewId: "embed-preview", documentsConfirmed: true });
  const jobsBox = withEmbed.document.querySelector("#knowledge-embedding-jobs");
  assert.match(jobsBox.textContent, /RUNNING · 1\/2.*test-embedding.*调用 1 次.*tokens 42/);
  const cancelRun = find(jobsBox, "取消索引").events.click();
  withEmbed.searches.at(-1).resolve(ok({ ...embedJob, state: "CANCELLED" })); await cancelRun;
  assert.match(withEmbed.embeds.at(-1).url, /embeddings\/jobs\/ejob\/cancel/);
  console.log("Knowledge search UI passed: scoped filters, bounded safe text, persisted chunk/original, deep link, stale search/chunk/update/unbind guards, hybrid consent gates with fused origins and vector index jobs");
})().catch(e => { console.error(e); process.exitCode = 1; });
