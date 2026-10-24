"use strict";
(() => {
  const $ = s => document.querySelector(s);
  const node = (tag, text) => { const n = document.createElement(tag); if (text != null) n.textContent = text; return n; };
  const params = new URLSearchParams(window.location.search);
  let startupIntent = ["PROJECT", "REPOSITORY"].includes(params.get("scopeKind")) && params.get("scopeId")
    ? { scope: { kind: params.get("scopeKind"), id: params.get("scopeId") }, version: params.get("version"), chunk: params.get("chunk") } : null;
  let scopes = [], knownSources = [], generation = 0, formGeneration = 0, scopeGeneration = 0, detailGeneration = 0, searchGeneration = 0, previewValue = null, selectedSource = null, timer = null;
  let searchConfig = null, embedPreviewValue = null, embedTimer = null;
  const query = scope => "?" + new URLSearchParams({ scopeKind: scope.kind, scopeId: scope.id });
  const selected = () => { const [kind, id] = $("#knowledge-scope").value.split(":"); return id ? { kind, id } : null; };
  const message = text => { $("#knowledge-notice").textContent = text; };
  async function request(url, options = {}) {
    const response = await fetch(url, { cache: "no-store", ...options });
    const value = await response.json(); if (!response.ok) throw new Error(value.detail || "知识读取失败，请重试。"); return value;
  }
  const write = (url, body = {}) => request(url, { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Knowledge": "1" }, body: JSON.stringify(body) });
  function invalidate() {
    formGeneration++; previewValue = null; $("#knowledge-preview").replaceChildren(); $("#knowledge-preview").hidden = true;
    $("#knowledge-confirm").checked = false; $("#knowledge-import").disabled = true; $("#knowledge-preview-button").disabled = !selected();
  }
  const button = (label, action) => { const b = node("button", label); b.type = "button"; b.className = "secondary"; b.addEventListener("click", action); return b; };
  const stamp = value => value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—";
  function clearSearch() {
    searchGeneration++; detailGeneration++;
    $("#knowledge-results").replaceChildren(); $("#knowledge-original").replaceChildren();
  }
  function sourceLink(value) {
    if (typeof value !== "string" || !/^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/blob\/[a-f0-9]{40}\//.test(value)) return null;
    const a = node("a", "查看固定提交来源"); a.href = value; a.target = "_blank"; a.rel = "noopener noreferrer"; return a;
  }
  async function openChunk(versionId, chunkId, scope = selected()) {
    if (!scope) return; const current = generation, detail = ++detailGeneration;
    try {
      const c = await request("/api/knowledge/versions/" + encodeURIComponent(versionId) + "/chunks/" + encodeURIComponent(chunkId) + query(scope));
      if (current !== generation || detail !== detailGeneration) return;
      const target = $("#knowledge-original");
      target.replaceChildren(node("h3", c.title), node("p", c.path + " · 原文行 " + c.startLine + "–" + c.endLine + " · " + c.recallMethod),
        node("p", "保存范围 " + c.scope.kind + " · " + c.scope.id + " · 来源 " + c.sourceId),
        node("p", "不可变版本 " + c.versionId + " · 分段 " + c.chunkId), node("p", "原文 SHA256 " + c.sha256 + " · 分段 SHA256 " + c.chunkSha256),
        node("p", "导入 " + stamp(c.importedAt) + (c.commitSha ? " · 提交 " + c.commitSha : "")), node("pre", (c.text || "").slice(0, 2000)),
        button("查看此版本全文", () => { if (current === generation && detail === detailGeneration) return openVersion(versionId, scope); }));
      const source = sourceLink(c.sourceUrl); if (source) target.append(source);
      target.append(node("p", "此处为保存的准确分段，更新或解绑不会改写；知识内容不是当前运行观测，也不授予资料或源码外发权限。"));
    } catch (e) { if (current === generation && detail === detailGeneration) message(e.message); }
  }
  async function search() {
    clearSearch(); const scope = selected(); if (!scope) { message("请选择已登记项目或仓库。"); return; }
    const current = generation, searching = searchGeneration;
    if ($("#knowledge-mode").value === "HYBRID") return hybridSearch(scope, current, searching);
    const values = new URLSearchParams({ scopeKind: scope.kind, scopeId: scope.id, query: $("#knowledge-query").value.trim(), docType: $("#knowledge-doc-type").value || "ALL", limit: "10" });
    const service = $("#knowledge-service").value; if (service) values.set("service", service);
    message("正在本机检索当前知识，不调用模型…");
    try {
      const value = await request("/api/knowledge/search?" + values); if (current !== generation || searching !== searchGeneration) return;
      const target = $("#knowledge-results"), results = value.results.slice(0, 20);
      target.replaceChildren(node("p", "KEYWORD · 扫描 " + value.scannedChunks + "/" + value.availableChunks + " 当前分段 · " + (value.truncated ? "扫描达上限，结果可能遗漏。" : "已覆盖本次类型的当前导入分段。")), node("p", value.coverage));
      if (!results.length) target.append(node("p", "本次扫描范围内没有匹配结果。可调整关键词或先检查导入范围。"));
      for (const c of results) {
        const card = node("article"); card.className = "source-project";
        card.append(node("h3", c.title), node("pre", (c.excerpt || "").slice(0, 360)), node("p", c.path + " · 行 " + c.startLine + "–" + c.endLine + " · " + c.recallMethod + " · 分数 " + c.score),
          node("p", "版本 " + c.versionId + " · 原文 SHA256 " + c.sha256 + " · 分段 SHA256 " + c.chunkSha256), node("p", "导入 " + stamp(c.importedAt)),
          button("查看分段原文", () => { if (current === generation && searching === searchGeneration) return openChunk(c.versionId, c.chunkId, scope); }));
        const source = sourceLink(c.sourceUrl); if (source) card.append(source); target.append(card);
      }
      message("本机关键词检索完成；资料经验不替代运行证据。来源更新后请重新搜索。");
    } catch (e) { if (current === generation && searching === searchGeneration) message(e.message); }
  }
  async function hybridSearch(scope, current, searching) {
    const embedding = searchConfig?.embedding, rerank = searchConfig?.rerank;
    if (!embedding) { message("尚未配置 Embedding 服务，混合检索不可用；可使用关键词检索，或在模型设置中添加。"); return; }
    if (!$("#knowledge-embed-confirm").checked) { message("混合检索会把检索词发送至 Embedding 服务（" + embedding.displayName + " · " + embedding.model + "）；请先勾选发送确认。"); return; }
    const rerankOn = rerank && $("#knowledge-rerank").checked;
    if (rerankOn && !$("#knowledge-rerank-confirm").checked) { message("启用重排会把融合候选摘录发送至 Rerank 服务（" + rerank.displayName + " · " + rerank.model + "）；请先勾选对应确认。"); return; }
    const body = { scopeKind: scope.kind, scopeId: scope.id, query: $("#knowledge-query").value.trim(), docType: $("#knowledge-doc-type").value || "ALL", limit: 10, confirmed: true, rerank: rerankOn, documentsConfirmed: rerankOn };
    const service = $("#knowledge-service").value; if (service) body.service = service;
    message("正在混合检索：检索词将发送至 Embedding 服务" + (rerankOn ? "，候选摘录将发送至 Rerank 服务" : "") + "…");
    try {
      const value = await write("/api/knowledge/hybrid-search", body);
      if (current !== generation || searching !== searchGeneration) return;
      const target = $("#knowledge-results"), results = (value.results || []).slice(0, 20);
      const vector = value.vector || {}, rerankState = value.rerank || {};
      target.replaceChildren(node("p", "HYBRID · 关键词扫描 " + value.scannedChunks + "/" + value.availableChunks + " 当前分段" + (value.truncated ? " · 存在扫描上限" : "")),
        node("p", "向量：" + vector.status + (vector.reason ? " · " + vector.reason : "") + (vector.queryTokens != null ? " · 查询已知 tokens " + vector.queryTokens : "")),
        node("p", "重排：" + rerankState.status + (rerankState.reason ? " · " + rerankState.reason : "")),
        node("p", value.coverage));
      if (!results.length) target.append(node("p", "本次扫描范围内没有匹配结果。可调整关键词，或先检查导入范围与向量索引。"));
      for (const h of results) {
        const c = h.citation || {}; const card = node("article"); card.className = "source-project";
        card.append(node("h3", c.title), node("pre", (c.excerpt || "").slice(0, 360)),
          node("p", c.path + " · 行 " + c.startLine + "–" + c.endLine + " · " + (h.matchedBy || []).join("+") + " · 融合 " + h.fusedScore + (h.rerankScore != null ? " · 重排 " + h.rerankScore : "")),
          node("p", "版本 " + c.versionId + " · 原文 SHA256 " + c.sha256 + " · 分段 SHA256 " + c.chunkSha256), node("p", "导入 " + stamp(c.importedAt)),
          button("查看分段原文", () => { if (current === generation && searching === searchGeneration) return openChunk(c.versionId, c.chunkId, scope); }));
        const source = sourceLink(c.sourceUrl); if (source) card.append(source); target.append(card);
      }
      message("混合检索完成；向量与重排状态见结果区。资料经验不替代运行证据。来源更新或索引重建后请重新搜索。");
    } catch (e) { if (current === generation && searching === searchGeneration) message(e.message); }
  }
  function updateSearchModeUi() {
    const embedding = searchConfig?.embedding, rerank = searchConfig?.rerank;
    $("#knowledge-embed-preview-button").disabled = !embedding || !selected();
    $("#knowledge-hybrid-consent").hidden = $("#knowledge-mode").value !== "HYBRID";
    let note = embedding
      ? "混合检索会把检索词发送至 Embedding 服务：" + embedding.displayName + "（模型 " + embedding.model + " · " + embedding.baseUrl + "）。"
      : "尚未配置 Embedding 服务：混合检索不可用，可先使用关键词检索，或在模型设置中添加 Embedding 服务。";
    if (rerank) note += " 启用重排时，最多 20 段融合候选摘录（每段最多 1000 字符）会发送至 Rerank 服务：" + rerank.displayName + "（模型 " + rerank.model + "）。";
    $("#knowledge-send-note").textContent = note;
    $("#knowledge-rerank-row").hidden = !rerank;
    if (!rerank) { $("#knowledge-rerank").checked = false; $("#knowledge-rerank-confirm").checked = false; }
    $("#knowledge-rerank-consent-row").hidden = !rerank || !$("#knowledge-rerank").checked;
  }
  async function loadSearchConfig() {
    const current = generation;
    try { searchConfig = await request("/api/settings/search-models"); }
    catch (e) { searchConfig = null; }
    if (current === generation) updateSearchModeUi();
  }
  async function embedPreview() {
    const scope = selected(); if (!scope) return;
    const current = generation;
    $("#knowledge-embed-preview-button").disabled = true; message("正在读取向量索引发送范围…");
    try {
      const p = await write("/api/knowledge/embeddings/preview", { scopeKind: scope.kind, scopeId: scope.id });
      if (current !== generation) return;
      embedPreviewValue = p;
      const target = $("#knowledge-embed-preview"); target.hidden = false;
      target.replaceChildren(node("h3", "将发送到 Embedding 服务的范围"),
        node("p", "模型 " + p.model + " · " + p.baseUrl),
        node("p", "当前有效分段 " + p.chunks + " 个 · 来源 " + p.sources + " 个" + (p.truncated ? " · 超出单次上限，仅索引排序在前的 2000 段" : "")),
        node("p", p.note), node("p", "预览十分钟有效，至 " + stamp(p.expiresAt)));
      $("#knowledge-embed-consent-row").hidden = false;
      message("请核对发送范围后确认建立 / 重建索引。");
    } catch (e) { if (current === generation) message(e.message); }
    finally { if (current === generation) $("#knowledge-embed-preview-button").disabled = !searchConfig?.embedding; }
  }
  async function embedStart() {
    if (!embedPreviewValue || !$("#knowledge-embed-index-confirm").checked) return;
    const scope = selected(), current = generation;
    try {
      await write("/api/knowledge/embeddings/jobs", { scopeKind: scope.kind, scopeId: scope.id, previewId: embedPreviewValue.id, documentsConfirmed: true });
      if (current !== generation) return;
      embedPreviewValue = null;
      $("#knowledge-embed-preview").replaceChildren(); $("#knowledge-embed-preview").hidden = true;
      $("#knowledge-embed-index-confirm").checked = false; $("#knowledge-embed-consent-row").hidden = true; $("#knowledge-embed-start").disabled = true;
      message("已提交向量索引任务；分段正文将发送至 Embedding 服务，可在下方查看进度或取消。");
      await refresh(current);
    } catch (e) { if (current === generation) message(e.message); }
  }
  async function embeddingJobs(current) {
    const scope = selected(); if (!scope) return;
    const values = await request("/api/knowledge/embeddings/jobs" + query(scope)); if (current !== generation) return;
    const target = $("#knowledge-embedding-jobs"); target.replaceChildren(node("h3", "最近向量索引任务（20 条）"));
    let pending = false;
    for (const job of values) {
      const active = ["QUEUED", "RUNNING"].includes(job.state); pending ||= active;
      const row = node("div");
      row.append(node("p", job.state + " · " + job.progress + "/" + job.total + " · " + job.model + " · Embedding 调用 " + job.usageCalls + " 次 · 已知 tokens " + (job.usageTokens > 0 ? job.usageTokens : "未知") + " · " + job.message));
      if (active) row.append(button("取消索引", async () => { if (current !== generation) return; try { await write("/api/knowledge/embeddings/jobs/" + job.id + "/cancel"); if (current === generation) await refresh(current); } catch (e) { if (current === generation) message(e.message); } }));
      target.append(row);
    }
    clearTimeout(embedTimer);
    if (pending) embedTimer = setTimeout(async () => { if (current !== generation) return; try { await refresh(current); } catch (e) { if (current === generation) message(e.message); } }, 1000);
  }
  function renderPreview(p) {
    const target = $("#knowledge-preview"); target.hidden = false;
    target.append(node("h3", "将导入的准确范围"), node("p", p.origin + " · " + p.root + (p.commitSha ? " · 固定 SHA " + p.commitSha : "")),
      node("p", p.files.length + " 个文档 · " + p.bytes + " 字节 · " + p.chunks + " 段 · 遍历 " + p.visited + " 条目"),
      node("p", p.complete ? "符合读取规则的范围已读取；排除项见下方。" : "范围未完整覆盖：存在读取上限或缺口。仅导入下方文件，不代表全部资料。"),
      node("p", "清单 SHA256 " + p.manifestHash), node("p", "读取 " + stamp(p.readAt) + " · 十分钟有效，至 " + stamp(p.expiresAt)));
    const files = node("ul"); for (const f of p.files) { const row = node("li"); row.append(node("strong", f.path + " · " + f.bytes + " 字节"), node("p", f.sha256), node("pre", f.excerpt)); files.append(row); } target.append(files);
    const exclusions = node("details"); exclusions.append(node("summary", "排除与缺口（最多列 100 条，额外项合并提示）"));
    const list = node("ul"); p.exclusions.forEach(e => list.append(node("li", e.path + " · " + e.reason))); exclusions.append(list); target.append(exclusions);
  }
  async function preview() {
    invalidate(); const scope = selected(); if (!scope) return;
    const current = generation, form = formGeneration;
    const body = { scopeKind: scope.kind, scopeId: scope.id };
    if (scope.kind === "PROJECT") body.directory = $("#knowledge-directory").value.trim(); else body.commitSha = $("#knowledge-commit").value.trim();
    if (selectedSource) { body.sourceId = selectedSource.id; body.revision = selectedSource.revision; }
    $("#knowledge-preview-button").disabled = true; message("正在只读预览文档范围…");
    try { const p = await write("/api/knowledge/previews", body); if (current !== generation || form !== formGeneration) return; previewValue = p; renderPreview(p); message("请核对准确目录 / SHA、文件及排除项，再确认导入。"); }
    catch (e) { if (current === generation && form === formGeneration) message(e.message); }
    finally { if (current === generation && form === formGeneration) $("#knowledge-preview-button").disabled = false; }
  }
  async function openVersion(id, scope = selected()) {
    if (!scope) return; const current = generation, detail = ++detailGeneration;
    try { const v = await request("/api/knowledge/versions/" + encodeURIComponent(id) + query(scope)); if (current !== generation || detail !== detailGeneration) return;
      $("#knowledge-original").replaceChildren(node("h3", v.title), node("p", v.path + " · " + v.origin + " · " + (v.commitSha || v.sha256)), node("p", "来源 " + v.root + " · 导入 " + stamp(v.importedAt) + " · 不可变版本 " + v.id), node("pre", v.text));
      if (knownSources.some(s => s.id === v.sourceId && s.state === "ACTIVE" && s.scope.kind === scope.kind && s.scope.id === scope.id)) {
        const material = node("a", "在材料页本机预览此版本"); material.href = "/workspace.html?" + new URLSearchParams({ type: "KNOWLEDGE", report: v.id, [scope.kind === "PROJECT" ? "project" : "repository"]: scope.id }) + "#materials"; $("#knowledge-original").append(material);
      } else $("#knowledge-original").append(node("p", "此处保留历史原文；只有仍登记且有效的来源可生成新的发送材料。"));
    } catch (e) { if (current === generation && detail === detailGeneration) message(e.message); }
  }
  async function versions(source) {
    const current = generation, detail = ++detailGeneration, scope = selected(); if (!scope) return;
    try { const values = await request("/api/knowledge/sources/" + encodeURIComponent(source.id) + "/versions" + query(scope)); if (current !== generation || detail !== detailGeneration) return;
      const target = $("#knowledge-versions"); target.replaceChildren(node("h3", "来源版本（最近 500 条；历史引用可直接按版本 ID 打开）"));
      for (const v of values) { const row = node("article"); row.append(node("p", v.path + " · " + (v.active ? "当前" : "历史") + " · " + stamp(v.importedAt)), node("p", v.sha256 + (v.commitSha ? " · " + v.commitSha : "")), button("查看原文", () => { if (current === generation) return openVersion(v.id, scope); })); target.append(row); }
    } catch (e) { if (current === generation && detail === detailGeneration) message(e.message); }
  }
  async function action(source, actionName) {
    const current = generation, scope = selected(); if (!scope || !window.confirm((actionName === "DELETE" ? "删除" : "解绑") + "这份知识来源？之后不再进入新检索与材料发送；历史版本与引用保留。重新导入需要新预览。")) return;
    clearSearch(); $("#knowledge-versions").replaceChildren();
    try { await write("/api/knowledge/sources/" + source.id, { scopeKind: scope.kind, scopeId: scope.id, revision: source.revision, action: actionName, confirmed: true }); if (current !== generation) return; invalidate(); selectedSource = null; await refresh(current); message("来源已停用，历史版本仍可打开。"); }
    catch (e) { if (current === generation) message(e.message); }
  }
  function renderSources(values, current) {
    const fingerprint = items => JSON.stringify(items.map(s => [s.id, s.revision, s.state, s.documents]));
    if (knownSources.length && fingerprint(knownSources) !== fingerprint(values)) { clearSearch(); $("#knowledge-versions").replaceChildren(); }
    knownSources = values;
    const target = $("#knowledge-sources"); target.replaceChildren();
    if (!values.length) target.append(node("p", "此范围尚无导入来源。"));
    for (const source of values) {
      const card = node("article"); card.className = "source-project";
      card.append(node("h3", source.origin + " · " + source.state + " · v" + source.revision), node("p", source.root), node("p", (source.commitSha || source.manifestHash) + " · " + source.documents + " 当前文档 · " + stamp(source.importedAt)),
        button("更新 / 重新绑定", () => { if (current !== generation) return; clearSearch(); $("#knowledge-versions").replaceChildren(); selectedSource = source; if (source.origin === "LOCAL") $("#knowledge-directory").value = source.root; else $("#knowledge-commit").value = source.commitSha || ""; invalidate(); message("已选来源 v" + source.revision + "，修改目录将视为新来源；请重新预览。"); }),
        button("版本 / 原文", () => { if (current === generation) return versions(source); }), button("解绑", () => { if (current === generation) return action(source, "UNBIND"); }), button("删除", () => { if (current === generation) return action(source, "DELETE"); }));
      target.append(card);
    }
  }
  async function jobs(current) {
    const scope = selected(); if (!scope) return;
    const values = await request("/api/knowledge/jobs" + query(scope)); if (current !== generation) return;
    const target = $("#knowledge-jobs"); target.replaceChildren(node("h3", "最近导入任务（20 条）"));
    let pending = false;
    for (const job of values) { const active = ["QUEUED", "RUNNING"].includes(job.state); pending ||= active; const row = node("div"); row.append(node("p", job.state + " · " + job.progress + "/" + job.total + " · " + job.message));
      if (active) row.append(button("取消导入", async () => { if (current !== generation) return; try { await write("/api/knowledge/jobs/" + job.id + "/cancel"); if (current === generation) await jobs(current); } catch (e) { if (current === generation) message(e.message); } })); target.append(row); }
    clearTimeout(timer); if (pending) timer = setTimeout(async () => { if (current !== generation) return; try { await refresh(current); } catch (e) { if (current === generation) message(e.message); } }, 1000);
  }
  async function refresh(current = generation) {
    const scope = selected(); if (!scope) return;
    const [values] = await Promise.all([request("/api/knowledge/sources" + query(scope)), jobs(current), embeddingJobs(current).catch(() => {})]);
    if (current !== generation) return; renderSources(values, current);
  }
  async function select(automatic = false) {
    if (automatic !== true) startupIntent = null;
    const current = ++generation; clearTimeout(timer); clearTimeout(embedTimer); selectedSource = null; knownSources = []; clearSearch(); invalidate(); message("");
    for (const name of ["sources", "jobs", "versions", "original"]) $("#knowledge-" + name).replaceChildren();
    embedPreviewValue = null; $("#knowledge-embed-preview").replaceChildren(); $("#knowledge-embed-preview").hidden = true;
    $("#knowledge-embed-consent-row").hidden = true; $("#knowledge-embed-index-confirm").checked = false; $("#knowledge-embed-start").disabled = true;
    const scope = selected(); $("#knowledge-local").hidden = scope?.kind !== "PROJECT"; $("#knowledge-github").hidden = scope?.kind !== "REPOSITORY";
    const service = scopes.find(s => s.kind === scope?.kind && s.id === scope?.id)?.service;
    const all = node("option", "此范围全部服务资料"); all.value = "";
    $("#knowledge-service").replaceChildren(all); $("#knowledge-service").value = "";
    if (service && scope.kind === "PROJECT") { const bound = node("option", "绑定服务 · " + service); bound.value = service; $("#knowledge-service").append(bound); }
    $("#knowledge-service").disabled = !service || scope?.kind !== "PROJECT";
    $("#knowledge-directory").value = ""; $("#knowledge-commit").value = "";
    updateSearchModeUi();
    try { await Promise.all([refresh(current), loadSearchConfig()]); } catch (e) { if (current === generation) message(e.message); }
  }
  $("#knowledge-form").addEventListener("submit", e => { e.preventDefault(); preview(); });
  $("#knowledge-search-form").addEventListener("submit", e => { e.preventDefault(); search(); });
  for (const name of ["query", "doc-type", "service"]) $("#knowledge-" + name).addEventListener(name === "query" ? "input" : "change", clearSearch);
  $("#knowledge-mode").addEventListener("change", () => { clearSearch(); updateSearchModeUi(); });
  $("#knowledge-rerank").addEventListener("change", () => { $("#knowledge-rerank-consent-row").hidden = !$("#knowledge-rerank").checked; if (!$("#knowledge-rerank").checked) $("#knowledge-rerank-confirm").checked = false; });
  $("#knowledge-embed-preview-button").addEventListener("click", embedPreview);
  $("#knowledge-embed-index-confirm").addEventListener("change", () => { $("#knowledge-embed-start").disabled = !embedPreviewValue || !$("#knowledge-embed-index-confirm").checked; });
  $("#knowledge-embed-start").addEventListener("click", embedStart);
  $("#knowledge-scope").addEventListener("change", () => select());
  for (const name of ["directory", "commit"]) $("#knowledge-" + name).addEventListener("input", () => { if (name === "directory") selectedSource = null; invalidate(); });
  $("#knowledge-confirm").addEventListener("change", () => { $("#knowledge-import").disabled = !previewValue || !$("#knowledge-confirm").checked; });
  $("#knowledge-refresh").addEventListener("click", async () => { const current = generation; try { await loadScopes(); await refresh(current); } catch (e) { if (current === generation) message(e.message); } });
  $("#knowledge-import").addEventListener("click", async () => {
    if (!previewValue || !$("#knowledge-confirm").checked) return; const p = previewValue, current = generation; selectedSource = null; clearSearch(); $("#knowledge-versions").replaceChildren(); invalidate();
    try { await write("/api/knowledge/imports", { previewId: p.id }); if (current !== generation) return; message("已提交有界导入任务，可查看进度或取消。没有资料发送给模型。"); await refresh(current); }
    catch (e) { if (current === generation) message(e.message); }
  });
  async function loadScopes() {
    const current = ++scopeGeneration;
    const values = await request("/api/materials/scopes"); if (current !== scopeGeneration) return;
    const previous = $("#knowledge-scope").value;
    scopes = values.filter(s => ["PROJECT", "REPOSITORY"].includes(s.kind)); const empty = node("option", "请选择已登记项目或仓库"); empty.value = "";
    $("#knowledge-scope").replaceChildren(empty, ...scopes.map(s => { const n = node("option", (s.kind === "PROJECT" ? "本机项目 · " : "GitHub 仓库 · ") + s.name); n.value = s.kind + ":" + s.id; return n; }));
    const requested = startupIntent ? startupIntent.scope.kind + ":" + startupIntent.scope.id : null;
    if (scopes.some(s => s.kind + ":" + s.id === previous)) $("#knowledge-scope").value = previous;
    else if (!previous && scopes.some(s => s.kind + ":" + s.id === requested)) { $("#knowledge-scope").value = requested; await select(true); }
    else if (previous) { $("#knowledge-scope").value = ""; await select(true); }
    if (current !== scopeGeneration || !startupIntent) return;
    const intent = startupIntent; startupIntent = null;
    if (intent.version && intent.chunk) await openChunk(intent.version, intent.chunk, intent.scope);
    else if (intent.version) await openVersion(intent.version, intent.scope);
  }
  window.knowledgePanel = { select, preview, search, openChunk, openVersion, hybridSearch, embedPreview, embedStart, embeddingJobs, updateSearchModeUi, refreshScopes: () => loadScopes().catch(e => message(e.message)) };
  loadSearchConfig().catch(() => {});
  loadScopes().catch(e => message(e.message));
})();
