"use strict";
const assert = require("node:assert/strict"), fs = require("node:fs"), vm = require("node:vm"), path = require("node:path");
class Element {
  constructor() { this.children = []; this.value = ""; this.text = ""; this.events = {}; this.dataset = {}; this.checked = false; }
  set textContent(value) { this.text = String(value ?? ""); this.children = []; }
  get textContent() { return this.text + this.children.map(c => c.textContent).join(""); }
  set innerHTML(value) { throw new Error("Knowledge must be rendered as safe text"); }
  append(...children) { this.children.push(...children); }
  replaceChildren(...children) { this.children = children; this.text = ""; }
  addEventListener(event, fn) { this.events[event] = fn; }
  querySelectorAll() { return []; }
  classList = { toggle() {} };
}
const nodes = new Map(), document = { querySelector(s) { if (!nodes.has(s)) nodes.set(s, new Element()); return nodes.get(s); }, createElement() { return new Element(); } };
let requests = [], pendingScopes, pendingPreview, pendingSources;
let jobFixture = [{ id: "job", state: "RUNNING", progress: 1, total: 2, message: "processing <script>" }];
const scope = { kind: "PROJECT", id: "project", name: "<img project>" }, repo = { kind: "REPOSITORY", id: "repo", name: "fixture/repo" };
const source = { id: "source", scope: { kind: "PROJECT", id: "project" }, origin: "LOCAL", root: "C:/docs", revision: 3, state: "ACTIVE", documents: 1, manifestHash: "h", importedAt: "2026-10-15T00:00:00Z" };
const preview = { id: "preview", sourceId: "source", revision: 3, scope: source.scope, origin: "LOCAL", root: "C:/docs", commitSha: null, complete: false, visited: 3, bytes: 10, chunks: 1,
  manifestHash: "hash", readAt: "2026-10-15T00:00:00Z", expiresAt: "2026-10-15T00:10:00Z", files: [{ path: "<script>.md", sha256: "hash", bytes: 10, title: "<img title>", excerpt: "<script>text</script>" }], exclusions: [{ path: "target", reason: "DIRECTORY" }] };
const window = { location: { search: "" }, confirm: () => true };
const context = vm.createContext({ window, document, URLSearchParams, Date, encodeURIComponent, console, setTimeout: () => 1, clearTimeout() {}, fetch: async (url, options = {}) => {
  requests.push({ url, options });
  if (url === "/api/materials/scopes") return new Promise(resolve => pendingScopes = resolve);
  if (url === "/api/knowledge/previews") return new Promise(resolve => pendingPreview = resolve);
  if (url.startsWith("/api/knowledge/sources?")) return new Promise(resolve => pendingSources = resolve);
  if (url.startsWith("/api/knowledge/jobs?")) return { ok: true, json: async () => jobFixture };
  if (url === "/api/knowledge/sources/source") return { ok: true, json: async () => ({ ...source, state: "UNBOUND" }) };
  if (url.endsWith("/cancel")) { jobFixture = [{ ...jobFixture[0], state: "CANCELLED" }]; return { ok: true, json: async () => jobFixture[0] }; }
  throw new Error("unexpected URL " + url);
} });
const ok = value => ({ ok: true, json: async () => value });
vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/knowledge.js"), "utf8"), context);
const tick = () => new Promise(resolve => setImmediate(resolve));
(async () => {
  document.querySelector("#knowledge-directory").value = "typed during scope load";
  document.querySelector("#knowledge-directory").events.input();
  pendingScopes(ok([scope, repo])); await tick();
  assert.equal(document.querySelector("#knowledge-scope").children.length, 3);
  document.querySelector("#knowledge-scope").value = "PROJECT:project";
  const selected = window.knowledgePanel.select(); await tick(); pendingSources(ok([source])); await selected;
  assert.match(document.querySelector("#knowledge-sources").textContent, /C:\/docs/);
  assert.match(document.querySelector("#knowledge-jobs").textContent, /1\/2.*processing <script>/);
  await document.querySelector("#knowledge-jobs").children[1].children[1].events.click();
  assert.equal(requests.find(r => r.url.endsWith("/cancel")).options.headers["X-Triage-Knowledge"], "1");
  assert.match(document.querySelector("#knowledge-jobs").textContent, /CANCELLED/);
  const first = window.knowledgePanel.preview();
  const oldCard = document.querySelector("#knowledge-sources").children[0];
  document.querySelector("#knowledge-scope").value = "REPOSITORY:repo";
  const next = window.knowledgePanel.select(); await tick(); pendingSources(ok([])); await next;
  pendingPreview(ok(preview)); await first;
  oldCard.children.find(n => n.textContent === "更新 / 重新绑定").events.click();
  assert.equal(document.querySelector("#knowledge-directory").value, "", "Old project actions must not change the repository form");
  assert.equal(document.querySelector("#knowledge-preview").hidden, true);
  assert.equal(document.querySelector("#knowledge-import").disabled, true);
  document.querySelector("#knowledge-scope").value = "PROJECT:project";
  const restored = window.knowledgePanel.select(); await tick(); pendingSources(ok([source])); await restored;
  const fresh = window.knowledgePanel.preview();
  assert.equal(requests.at(-1).options.headers["X-Triage-Knowledge"], "1");
  pendingPreview(ok(preview)); await fresh;
  assert.match(document.querySelector("#knowledge-preview").textContent, /<script>/);
  assert.match(document.querySelector("#knowledge-preview").textContent, /DIRECTORY/);
  assert.equal(document.querySelector("#knowledge-import").disabled, true);
  document.querySelector("#knowledge-confirm").checked = true; document.querySelector("#knowledge-confirm").events.change();
  assert.equal(document.querySelector("#knowledge-import").disabled, false);
  const card = document.querySelector("#knowledge-sources").children[0], update = card.children.find(n => n.textContent === "更新 / 重新绑定");
  update.events.click(); assert.equal(document.querySelector("#knowledge-directory").value, source.root);
  const updating = window.knowledgePanel.preview(); assert.equal(JSON.parse(requests.at(-1).options.body).revision, 3); pendingPreview(ok(preview)); await updating;
  const unbind = card.children.find(n => n.textContent === "解绑"); const action = unbind.events.click(); await tick(); pendingSources(ok([{ ...source, state: "UNBOUND" }])); await action;
  assert.equal(JSON.parse(requests.find(r => r.url === "/api/knowledge/sources/source").options.body).confirmed, true);
  document.querySelector("#knowledge-directory").events.input(); assert.equal(document.querySelector("#knowledge-preview").hidden, true);
  const olderScopes = window.knowledgePanel.refreshScopes(), olderResolve = pendingScopes;
  const newerScopes = window.knowledgePanel.refreshScopes(); pendingScopes(ok([{ ...scope, name: "fresh scope" }, repo])); await newerScopes;
  olderResolve(ok([{ ...scope, name: "stale scope" }])); await olderScopes;
  assert.match(document.querySelector("#knowledge-scope").textContent, /fresh scope/);
  assert.doesNotMatch(document.querySelector("#knowledge-scope").textContent, /stale scope/);
  const sourceButton = new Element(), knowledgeButton = new Element(); sourceButton.closest = () => null; knowledgeButton.closest = () => ({}); knowledgeButton.disabled = false;
  const sourceDocument = { ...document, querySelectorAll() { return [sourceButton, knowledgeButton]; } };
  const sourceContext = vm.createContext({ document: sourceDocument, window: {}, console, fetch: () => new Promise(() => {}) });
  vm.runInContext(fs.readFileSync(path.join(__dirname, "../src/main/resources/static/sources.js"), "utf8"), sourceContext);
  assert.equal(sourceButton.disabled, true); assert.equal(knowledgeButton.disabled, false, "Source indexing must not lock knowledge buttons");
  knowledgeButton.disabled = true; vm.runInContext("lock(false)", sourceContext);
  assert.equal(knowledgeButton.disabled, true, "Source unlocking must not remove knowledge consent lock");
  console.log("Knowledge UI passed: safe text, explicit scope/preview/consent, update revision, unbind and stale selection guards");
})().catch(e => { console.error(e); process.exitCode = 1; });
