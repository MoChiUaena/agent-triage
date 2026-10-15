"use strict";
(() => {
  const $ = s => document.querySelector(s);
  const node = (tag, text) => { const n = document.createElement(tag); if (text != null) n.textContent = text; return n; };
  const params = new URLSearchParams(window.location.search);
  let scopes = [], knownSources = [], generation = 0, formGeneration = 0, scopeGeneration = 0, detailGeneration = 0, previewValue = null, selectedSource = null, timer = null;
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
    try { await write("/api/knowledge/sources/" + source.id, { scopeKind: scope.kind, scopeId: scope.id, revision: source.revision, action: actionName, confirmed: true }); if (current !== generation) return; invalidate(); selectedSource = null; await refresh(current); message("来源已停用，历史版本仍可打开。"); }
    catch (e) { if (current === generation) message(e.message); }
  }
  function renderSources(values, current) {
    knownSources = values;
    const target = $("#knowledge-sources"); target.replaceChildren();
    if (!values.length) target.append(node("p", "此范围尚无导入来源。"));
    for (const source of values) {
      const card = node("article"); card.className = "source-project";
      card.append(node("h3", source.origin + " · " + source.state + " · v" + source.revision), node("p", source.root), node("p", (source.commitSha || source.manifestHash) + " · " + source.documents + " 当前文档 · " + stamp(source.importedAt)),
        button("更新 / 重新绑定", () => { if (current !== generation) return; selectedSource = source; if (source.origin === "LOCAL") $("#knowledge-directory").value = source.root; else $("#knowledge-commit").value = source.commitSha || ""; invalidate(); message("已选来源 v" + source.revision + "，修改目录将视为新来源；请重新预览。"); }),
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
    const scope = selected(); if (!scope) return; const [values] = await Promise.all([request("/api/knowledge/sources" + query(scope)), jobs(current)]);
    if (current !== generation) return; renderSources(values, current);
  }
  async function select() {
    const current = ++generation; clearTimeout(timer); selectedSource = null; knownSources = []; invalidate(); message("");
    for (const name of ["sources", "jobs", "versions", "original"]) $("#knowledge-" + name).replaceChildren();
    const scope = selected(); $("#knowledge-local").hidden = scope?.kind !== "PROJECT"; $("#knowledge-github").hidden = scope?.kind !== "REPOSITORY";
    $("#knowledge-directory").value = ""; $("#knowledge-commit").value = "";
    try { await refresh(current); } catch (e) { if (current === generation) message(e.message); }
  }
  $("#knowledge-form").addEventListener("submit", e => { e.preventDefault(); preview(); });
  $("#knowledge-scope").addEventListener("change", select);
  for (const name of ["directory", "commit"]) $("#knowledge-" + name).addEventListener("input", () => { if (name === "directory") selectedSource = null; invalidate(); });
  $("#knowledge-confirm").addEventListener("change", () => { $("#knowledge-import").disabled = !previewValue || !$("#knowledge-confirm").checked; });
  $("#knowledge-refresh").addEventListener("click", async () => { const current = generation; try { await loadScopes(); await refresh(current); } catch (e) { if (current === generation) message(e.message); } });
  $("#knowledge-import").addEventListener("click", async () => {
    if (!previewValue || !$("#knowledge-confirm").checked) return; const p = previewValue, current = generation; selectedSource = null; invalidate();
    try { await write("/api/knowledge/imports", { previewId: p.id }); if (current !== generation) return; message("已提交有界导入任务，可查看进度或取消。没有资料发送给模型。"); await refresh(current); }
    catch (e) { if (current === generation) message(e.message); }
  });
  async function loadScopes() {
    const current = ++scopeGeneration;
    const values = await request("/api/materials/scopes"); if (current !== scopeGeneration) return;
    const previous = $("#knowledge-scope").value;
    scopes = values.filter(s => ["PROJECT", "REPOSITORY"].includes(s.kind)); const empty = node("option", "请选择已登记项目或仓库"); empty.value = "";
    $("#knowledge-scope").replaceChildren(empty, ...scopes.map(s => { const n = node("option", (s.kind === "PROJECT" ? "本机项目 · " : "GitHub 仓库 · ") + s.name); n.value = s.kind + ":" + s.id; return n; }));
    const requested = params.get("scopeKind") + ":" + params.get("scopeId");
    if (scopes.some(s => s.kind + ":" + s.id === previous)) $("#knowledge-scope").value = previous;
    else if (!previous && scopes.some(s => s.kind + ":" + s.id === requested)) { $("#knowledge-scope").value = requested; await select(); }
    else if (previous) { $("#knowledge-scope").value = ""; await select(); }
  }
  window.knowledgePanel = { select, preview, openVersion, refreshScopes: () => loadScopes().catch(e => message(e.message)) };
  loadScopes().then(async () => {
    if (params.get("version") && ["PROJECT", "REPOSITORY"].includes(params.get("scopeKind")) && params.get("scopeId")) await openVersion(params.get("version"), { kind: params.get("scopeKind"), id: params.get("scopeId") });
  }).catch(e => message(e.message));
})();
