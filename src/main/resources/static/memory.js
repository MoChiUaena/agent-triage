(() => {
  "use strict";
  const $ = selector => document.querySelector(selector);
  if (!$("#memory-panel")) return;
  const node = (tag, text) => { const e = document.createElement(tag); if (text != null) e.textContent = text; return e; };
  let scopes = [], current = null, selected = null, generation = 0, draftVersion = 0, detailVersion = 0, listVersion = 0, scopeVersion = 0;
  let dirty = false, referencesDirty = false, historical = false, busy = false;
  let selectedTarget = null, loadingDetail = false, mutationVersion = 0;
  const notice = text => { $("#memory-notice").textContent = text; };
  const query = () => new URLSearchParams({ scopeKind: selected.kind, scopeId: selected.id }).toString();
  async function request(path, options = {}) {
    const response = await fetch("/api/memories" + path, { cache: "no-store", ...options });
    if (!response.ok) { const p = await response.json().catch(() => ({})); throw new Error(p.detail || "读取项目记忆失败 (" + response.status + ")"); }
    return response.json();
  }
  const post = (path, body) => request(path, { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Memory": "1" }, body: JSON.stringify(body) });
  function ready() {
    const registration = selected && scopes.find(s => s.kind === selected.kind && s.id === selected.id);
    const live = Boolean(registration);
    const writable = live && !loadingDetail && !historical && current?.status !== "DELETED";
    const bindingMatches = !current || (registration?.service || null) === (current.scope.service || null);
    const editable = writable && bindingMatches;
    $("#memory-save").disabled = busy || !editable;
    $("#memory-confirm").disabled = busy || !editable || !current || dirty || !$("#memory-reviewed").checked;
    for (const action of ["disable", "delete"]) $("#memory-" + action).disabled = busy || !writable || !current || dirty;
    $("#memory-reviewed").disabled = !editable || !current || dirty;
    for (const name of ["topic", "content", "note"]) $("#memory-" + name).disabled = !editable;
    for (let i = 0; i < 3; i++) for (const field of ["type", "report", "version", "chunk"]) $("#memory-ref-" + i + "-" + field).disabled = !editable;
    $("#memory-scope-note").textContent = selected ? (live ? "当前登记范围 · " : "登记已移除，仅可阅读历史 · ") + selected.kind + " / " + selected.id + (!bindingMatches && live ? " · 服务绑定已变化；旧经验仅可阅读/撤回，不可编辑或确认。" : "") : "请选择准确的项目或仓库；不按服务名推断。";
  }
  function invalidateDraft() { draftVersion++; dirty = true; $("#memory-reviewed").checked = false; ready(); }
  function clear() {
    current = null; historical = false; dirty = false; referencesDirty = false; busy = false;
    selectedTarget = null; loadingDetail = false; mutationVersion++;
    draftVersion++; detailVersion++;
    for (const name of ["topic", "content", "note"]) $("#memory-" + name).value = "";
    for (let i = 0; i < 3; i++) for (const field of ["type", "report", "version", "chunk"]) $("#memory-ref-" + i + "-" + field).value = "";
    $("#memory-reviewed").checked = false;
    for (const name of ["evidence", "history", "detail-meta", "review-label"]) $("#memory-" + name).replaceChildren();
    ready();
  }
  function safeLink(parent, value, label) {
    if (!/^\/(?:\?run=|development\.html\?|sources\.html\?)/.test(value || "") && !/^https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+\/blob\/[a-f0-9]{40}\//.test(value || "")) return;
    const a = node("a", label); a.href = value; a.rel = "noopener noreferrer"; parent.append(a);
  }
  function render(value, oldVersion = false) {
    current = value; historical = oldVersion; dirty = false; referencesDirty = false;
    $("#memory-topic").value = value.body.topic; $("#memory-content").value = value.body.content; $("#memory-note").value = value.body.basis.note || "";
    $("#memory-reviewed").checked = false;
    for (let i = 0; i < 3; i++) {
      const ref = value.body.basis.references?.[i]?.reference;
      for (const [field, property] of [["type", "type"], ["report", "reportId"], ["version", "versionId"], ["chunk", "chunkId"]]) $("#memory-ref-" + i + "-" + field).value = ref?.[property] || "";
    }
    $("#memory-detail-meta").textContent = (historical ? "历史只读 · " : "当前版本 · ") + value.status + " · v" + value.revision + " · " + value.id + " · SHA256 " + value.bodySha256 + (value.confirmedAt ? " · 用户确认 " + value.confirmedAt : " · 尚未确认");
    $("#memory-review-label").textContent = "我已审阅范围 " + value.scope.kind + " / " + value.scope.id + "、保存服务 " + (value.scope.service || "—") + "、版本 " + value.revision + "、正文及完整历史依据，SHA256 " + value.bodySha256 + "。用户确认不保证事实正确或仍有效，也不授权资料外发。";
    const evidence = $("#memory-evidence"); evidence.replaceChildren(node("p", "用户陈述和历史证据，未经独立验证；不代表当前运行观察。"), node("pre", JSON.stringify(value.body.basis, null, 2)));
    for (const snapshot of value.body.basis.references || []) {
      const saved = snapshot.saved || {}; safeLink(evidence, saved.reference?.originalLink || saved.originalUrl, "打开原始来源（可能已移除）"); safeLink(evidence, saved.sourceUrl, "查看固定提交来源");
    }
    safeLink(evidence, value.body.basis.conversationOriginal?.reference?.originalLink, "打开对话原始报告"); ready();
  }
  async function refresh() {
    if (!selected) return;
    const ticket = ++listVersion, gen = generation;
    try {
      const values = await request("?" + query() + "&status=" + encodeURIComponent($("#memory-status").value || "CONFIRMED") + "&limit=20");
      if (ticket !== listVersion || gen !== generation) return;
      const list = $("#memory-list"); list.replaceChildren(...values.slice(0, 20).map(v => {
        const button = node("button", v.topic + " · " + v.status + " · v" + v.revision + " · 保存服务 " + (v.scope.service || "—")); button.type = "button"; button.className = "secondary"; button.addEventListener("click", () => open(v.id)); return button;
      }));
      if (!values.length) list.append(node("p", "此筛选下没有记忆。待确认候选请切换“候选”。"));
    } catch (e) { if (gen === generation && ticket === listVersion) notice(e.message); }
  }
  async function open(id, revision) {
    if (!selected) return;
    const target = id + ":" + (revision || "current");
    if (target !== selectedTarget) { clear(); selectedTarget = target; }
    const gen = generation, draft = draftVersion, ticket = ++detailVersion, params = query();
    current = null; loadingDetail = true;
    $("#memory-detail-meta").textContent = "正在读取所选记忆 " + id + "；读取完成前不能保存或更改状态。";
    $("#memory-reviewed").checked = false; ready();
    try {
      const value = await request("/" + encodeURIComponent(id) + (revision ? "/versions/" + revision : "") + "?" + params);
      if (gen !== generation || draft !== draftVersion || ticket !== detailVersion) return;
      loadingDetail = false;
      render(value, Boolean(revision));
      const history = await request("/" + encodeURIComponent(id) + "/history?" + params);
      if (gen !== generation || draft !== draftVersion || ticket !== detailVersion) return;
      $("#memory-history").replaceChildren(...history.slice(0, 22).map(v => {
        const b = node("button", "查看历史 v" + v.revision + " · " + v.status); b.type = "button"; b.className = "secondary"; b.addEventListener("click", () => open(id, v.revision)); return b;
      }));
    } catch (e) { if (gen === generation && draft === draftVersion && ticket === detailVersion) notice(e.message); }
  }
  async function select() {
    generation++; listVersion++; selected = null; clear(); $("#memory-list").replaceChildren();
    const [kind, id] = $("#memory-scope").value.split(":");
    if (kind && id) selected = { kind, id };
    ready(); if (selected) await refresh();
  }
  function newDraft() { clear(); notice("新建本机候选。用户陈述未经独立验证；保存后仍需打开精确版本确认。"); }
  function references() {
    const refs = [];
    for (let i = 0; i < 3; i++) {
      const type = $("#memory-ref-" + i + "-type").value;
      if (!type) continue;
      const ref = { type }; for (const [field, property] of [["report", "reportId"], ["version", "versionId"], ["chunk", "chunkId"]]) { const v = $("#memory-ref-" + i + "-" + field).value.trim(); if (v) ref[property] = v; }
      refs.push(ref);
    }
    return refs;
  }
  async function save() {
    if ($("#memory-save").disabled || !selected) return;
    const gen = generation, draft = draftVersion, id = current?.id, target = selectedTarget, operation = ++mutationVersion;
    const input = { scopeKind: selected.kind, scopeId: selected.id, revision: current?.revision || 0, topic: $("#memory-topic").value, content: $("#memory-content").value, note: $("#memory-note").value, references: current && !referencesDirty ? null : references() };
    busy = true; $("#memory-reviewed").checked = false; ready();
    try {
      const result = await post(id ? "/" + id + "/edit" : "", input);
      if (gen !== generation || target !== selectedTarget || operation !== mutationVersion) return;
      if (current?.id === result.id && current.revision > result.revision) return;
      if (draft === draftVersion) { detailVersion++; loadingDetail = false; selectedTarget = result.id + ":current"; render(result); }
      notice("已保存为未确认候选；请审阅准确版本后确认。" + (draft === draftVersion ? "" : " 当前新草稿已保留，请从列表打开保存版本。")); refresh();
    } catch (e) { if (gen === generation && target === selectedTarget && operation === mutationVersion) notice(e.message); }
    finally { if (gen === generation && operation === mutationVersion) { busy = false; ready(); } }
  }
  async function action(name) {
    if (!current || $("#memory-" + name).disabled) return;
    if (name === "delete" && !window.confirm("软删除此记忆？历史版本保留；删除后不能编辑或重新确认。")) return;
    const gen = generation, draft = draftVersion, id = current.id, target = selectedTarget, operation = ++mutationVersion;
    const input = { scopeKind: selected.kind, scopeId: selected.id, revision: current.revision, confirmed: name === "confirm" && $("#memory-reviewed").checked, bodySha256: current.bodySha256 };
    busy = true; $("#memory-reviewed").checked = false; ready();
    try {
      const result = await post("/" + id + "/" + name, input);
      if (gen !== generation || draft !== draftVersion || target !== selectedTarget || operation !== mutationVersion) return;
      if (current?.id === result.id && current.revision > result.revision) return;
      detailVersion++; loadingDetail = false;
      render(result); notice("已保存状态版本。确认仅代表用户审阅历史经验，未加入模型上下文。" ); refresh();
    } catch (e) { if (gen === generation && draft === draftVersion && target === selectedTarget && operation === mutationVersion) notice(e.message); }
    finally { if (gen === generation && operation === mutationVersion) { busy = false; ready(); } }
  }
  async function refreshScopes(initial = false) {
    const ticket = ++scopeVersion, gen = generation, draft = draftVersion;
    try {
      const values = await request("/scopes"); if (ticket !== scopeVersion) return;
      scopes = values.filter(s => ["PROJECT", "REPOSITORY"].includes(s.kind));
      const params = new URLSearchParams(window.location.search), deepKind = params.get("scopeKind"), deepId = params.get("scopeId");
      const keep = selected || (initial && deepKind && deepId ? { kind: deepKind, id: deepId } : null);
      const options = [node("option", "选择项目 / 仓库")]; options[0].value = "";
      for (const s of scopes) { const o = node("option", s.name + " · " + s.kind + " · " + s.id); o.value = s.kind + ":" + s.id; options.push(o); }
      if (keep && !scopes.some(s => s.kind === keep.kind && s.id === keep.id)) { const o = node("option", "历史登记已移除 · " + keep.id); o.value = keep.kind + ":" + keep.id; options.push(o); }
      $("#memory-scope").replaceChildren(...options);
      if (keep) $("#memory-scope").value = keep.kind + ":" + keep.id;
      if (initial && gen === generation && draft === draftVersion && keep) {
        selected = keep; if (params.get("memory")) { $("#memory-status").value = "ALL"; await open(params.get("memory"), params.get("memoryRevision")); }
        else refresh();
      }
      ready();
    } catch (e) { if (ticket === scopeVersion) notice(e.message); }
  }
  $("#memory-scope").addEventListener("change", select); $("#memory-status").addEventListener("change", refresh);
  $("#memory-new").addEventListener("click", newDraft); $("#memory-refresh").addEventListener("click", () => { refreshScopes(); refresh(); });
  $("#memory-save").addEventListener("click", save); $("#memory-reviewed").addEventListener("change", ready);
  for (const name of ["confirm", "disable", "delete"]) $("#memory-" + name).addEventListener("click", () => action(name));
  for (const name of ["topic", "content", "note"]) $("#memory-" + name).addEventListener("input", invalidateDraft);
  for (let i = 0; i < 3; i++) for (const field of ["type", "report", "version", "chunk"]) $("#memory-ref-" + i + "-" + field).addEventListener(field === "type" ? "change" : "input", () => { referencesDirty = true; invalidateDraft(); });
  window.memoryPanel = { select, refresh, refreshScopes, open, save, action, newDraft };
  clear(); refreshScopes(true);
})();
