"use strict";
(() => {
  const $ = selector => document.querySelector(selector);
  const node = (tag, text) => { const value = document.createElement(tag); if (text != null) value.textContent = text; return value; };
  const parameters = new URLSearchParams(window.location.search);
  const types = ["SERVICE", "PR", "ISSUE", "CI", "WORKFLOW"];
  const states = { RUNNING: "生成中", SUCCEEDED: "回答已保存", CANCELLED: "已取消", TIMED_OUT: "等待超时", FAILED: "调用失败", INVALID_OUTPUT: "输出校验未通过", INTERRUPTED: "重启中断", BUSY: "队列已满", REFERENCE_ONLY: "未调用模型" };
  let active = false, generation = 0, formVersion = 0, listVersion = 0, searchVersion = 0, busy = false, polling = null;
  let current = null, prepared = null;
  const selected = new Map();
  $("#conversation-type").value = types.includes(parameters.get("type")) ? parameters.get("type") : "SERVICE";
  $("#conversation-report").value = parameters.get("report") || "";
  async function request(url, options = {}) {
    const response = await fetch(url, { cache: "no-store", ...options });
    if (!response.ok) { const problem = await response.json().catch(() => ({})); throw new Error(problem.detail || "读取追问失败 (" + response.status + ")"); }
    return response.json();
  }
  const post = (path, value) => request("/api/conversations" + path, { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Conversation": "1" }, body: JSON.stringify(value) });
  const message = text => { $("#conversation-notice").textContent = text; };
  function stopPolling() { if (polling != null) clearTimeout(polling); polling = null; }
  function invalidate() {
    formVersion++; prepared = null;
    $("#conversation-preview").hidden = true; $("#conversation-preview-text").textContent = "";
    $("#conversation-confirm").checked = false; $("#conversation-documents-confirm").checked = false;
    ready();
  }
  function ready() {
    const running = Boolean(current?.activeAttempt), available = Boolean(current?.model?.available), useModel = $("#conversation-use-model").checked;
    const lastState = current?.attempts?.at(-1)?.state;
    $("#conversation-preview-button").textContent = ["FAILED", "CANCELLED", "INVALID_OUTPUT", "TIMED_OUT", "INTERRUPTED", "BUSY"].includes(lastState) ? "重新预览并重试" : "预览本次模型发送内容";
    $("#conversation-reference").disabled = busy || running || !current;
    $("#conversation-preview-button").disabled = busy || running || !current || !available || !useModel;
    $("#conversation-use-model").disabled = running || !available;
    $("#conversation-question").disabled = running;
    $("#conversation-search-button").disabled = !current || running || busy || !scope();
    $("#conversation-cancel").hidden = !running; $("#conversation-cancel").disabled = busy;
    $("#conversation-send").disabled = busy || running || !prepared || !available || !useModel || !$("#conversation-confirm").checked || (prepared.context.documentsRequired && !$("#conversation-documents-confirm").checked);
  }
  function safeLink(element, value) {
    element.hidden = !/^\/(?:\?run=|development\.html\?|sources\.html\?)/.test(value || "");
    element.href = element.hidden ? "#" : value;
  }
  function showCitation(citation) {
    $("#conversation-citation").hidden = false;
    if (citation.report) {
      const saved = citation.report, ref = saved.reference;
      $("#conversation-citation-meta").textContent = citation.id + " · " + ref.version + " · SHA256 " + ref.contentSha256;
      $("#conversation-citation-text").textContent = saved.text;
      safeLink($("#conversation-citation-link"), ref.originalLink);
    } else if (citation.chunk) {
      const c = citation.chunk;
      $("#conversation-citation-meta").textContent = citation.id + " · " + c.path + " · 版本 " + c.versionId + " · 行 " + c.startLine + "—" + c.endLine + " · 偏移 " + c.startOffset + "—" + c.endOffset + " · SHA256 " + c.chunkSha256;
      $("#conversation-citation-text").textContent = c.text;
      safeLink($("#conversation-citation-link"), c.originalUrl);
    }
  }
  function render(detail) {
    if (prepared && (prepared.context.revision !== detail.revision || JSON.stringify(prepared.preview.recipient) !== JSON.stringify(detail.model))) invalidate();
    current = detail; $("#conversation-detail").hidden = false;
    const original = detail.original, ref = original.reference, s = ref.scope;
    $("#conversation-title").textContent = "任务追问 · " + ref.type + " · " + detail.id;
    $("#conversation-scope").textContent = "项目 " + (s.projectId || "—") + " · 仓库 " + (s.repositoryId || "—") + " · 服务 " + (s.service || "—");
    $("#conversation-model").textContent = detail.model.available ? "已启用：" + detail.model.displayName + " / " + detail.model.model + " · 配置版本 " + detail.model.version : "未配置可用模型；可保存问题、查阅证据与本机检索。未调用模型。";
    $("#conversation-boundary").textContent = detail.notice;
    $("#conversation-origin-meta").textContent = ref.type + " / " + ref.reportId + " · " + ref.version + " · SHA256 " + ref.contentSha256;
    $("#conversation-origin").textContent = original.text;
    $("#conversation-origin-omissions").replaceChildren(...original.omissions.map(o => node("li", o)));
    safeLink($("#conversation-origin-link"), ref.originalLink);
    $("#conversation-messages").replaceChildren(...detail.messages.map(m => {
      const card = node("section"), heading = node("h3", m.role === "USER" ? "你的问题" : "模型回答");
      card.className = "conversation-message"; card.append(heading, node("pre", m.text));
      for (const citation of m.citations) { const button = node("button", "查看引用 " + citation.id); button.type = "button"; button.className = "secondary"; button.addEventListener("click", () => showCitation(citation)); card.append(button); }
      if (m.documentsDerived) card.append(node("p", "此条消息或生成上下文含项目资料；纳入后续发送时仍须独立授权。"));
      return card;
    }));
    $("#conversation-attempts").replaceChildren(...detail.attempts.map(a => {
      const card = node("p", (states[a.state] || a.state) + " · " + a.message);
      if (a.recipient) card.append(node("small", " · " + a.recipient.model + " / 配置版本 " + a.recipient.version));
      card.append(node("small", a.usage ? " · 已知 tokens：" + a.usage.totalTokens : " · 用量未知或未调用"));
      return card;
    }));
    ready();
  }
  function scope() {
    const s = current?.original?.reference?.scope;
    return s?.projectId ? { scopeKind: "PROJECT", scopeId: s.projectId } : s?.repositoryId ? { scopeKind: "REPOSITORY", scopeId: s.repositoryId } : null;
  }
  async function list() {
    const ticket = ++listVersion;
    try {
      const result = await request("/api/conversations"); if (!active || ticket !== listVersion) return;
      $("#conversation-list").replaceChildren(...result.map(c => { const button = node("button", c.type + " · " + c.title + " · " + new Date(c.updatedAt).toLocaleString("zh-CN")); button.type = "button"; button.className = "secondary conversation-history"; button.addEventListener("click", () => open(c.id)); return button; }));
      if (!result.length) $("#conversation-list").append(node("p", "尚无追问。可从五类报告结果上的“继续追问”进入。"));
    } catch (error) { if (active && ticket === listVersion) message(error.message); }
  }
  function poll(ticket) {
    stopPolling(); if (!active || !current?.activeAttempt) return;
    const id = current.id;
    polling = setTimeout(async () => {
      try { const detail = await request("/api/conversations/" + encodeURIComponent(id)); if (!active || ticket !== generation || current?.id !== id) return; render(detail); if (detail.activeAttempt) poll(ticket); else list(); }
      catch (error) { if (active && ticket === generation) message(error.message + " 可刷新恢复已保存状态。"); }
    }, 750);
  }
  async function open(id) {
    const ticket = ++generation; stopPolling(); busy = false; current = null; selected.clear(); searchVersion++; invalidate();
    $("#conversation-detail").hidden = true; $("#conversation-hits").replaceChildren(); $("#conversation-selected").textContent = "";
    $("#conversation-citation").hidden = true; $("#conversation-question").value = "";
    try {
      const detail = await request("/api/conversations/" + encodeURIComponent(id)); if (!active || ticket !== generation) return;
      render(detail); const query = new URLSearchParams(window.location.search); query.set("conversation", id); window.history.replaceState(null, "", "/workspace.html?" + query + "#conversations");
      message("已恢复保存的原报告、问题、回答与引用。"); poll(ticket);
    } catch (error) { if (active && ticket === generation) message(error.message); }
  }
  async function create(event) {
    event?.preventDefault(); if (!active) return; const ticket = generation;
    $("#conversation-start").disabled = true;
    try { const result = await post("", { origin: { type: $("#conversation-type").value, reportId: $("#conversation-report").value.trim() } }); if (!active || ticket !== generation) return; await open(result.id); list(); }
    catch (error) { if (active && ticket === generation) message(error.message); }
    finally { $("#conversation-start").disabled = false; }
  }
  async function reference() {
    if (!current || busy || current.activeAttempt) return; const ticket = generation, id = current.id;
    busy = true; invalidate();
    try { const result = await post("/" + id + "/references", { revision: current.revision, question: $("#conversation-question").value }); if (!active || ticket !== generation) return; render(result); message("问题已保存，未调用模型。上方可展开原报告证据与规则依据。"); list(); }
    catch (error) { if (active && ticket === generation) message(error.message); }
    finally { if (ticket === generation) { busy = false; ready(); } }
  }
  async function preview() {
    if (!active || !current || busy || current.activeAttempt || !$("#conversation-use-model").checked) return;
    invalidate(); const ticket = generation, version = formVersion, id = current.id; busy = true; ready();
    try {
      const result = await post("/" + id + "/previews", { revision: current.revision, question: $("#conversation-question").value, selectedChunks: [...selected.values()].map(c => ({ versionId: c.versionId, chunkId: c.chunkId })) });
      if (!active || ticket !== generation || version !== formVersion) return;
      prepared = result; const p = result.preview, r = p.recipient; $("#conversation-preview").hidden = false;
      $("#conversation-recipient").textContent = r.displayName + " / " + r.model + " · " + r.baseUrl + " · Provider " + (r.providerId || "启动配置") + " / 版本 " + r.version;
      $("#conversation-preview-hash").textContent = "准确系统指令、问题、历史、报告与资料正文 SHA256 " + p.contentSha256 + " · 有效至 " + new Date(p.expiresAt).toLocaleString("zh-CN");
      $("#conversation-preview-text").textContent = p.text; $("#conversation-preview-omissions").replaceChildren(...p.omissions.map(o => node("li", o)));
      $("#conversation-documents-label").hidden = !result.context.documentsRequired;
      message("已生成准确预览，尚未调用模型。修改问题、资料或模型后需重新预览。");
    } catch (error) { if (active && ticket === generation && version === formVersion) message(error.message); }
    finally { if (ticket === generation) { busy = false; ready(); } }
  }
  async function send() {
    ready(); if ($("#conversation-send").disabled) return;
    const ticket = generation, id = current.id, p = prepared;
    busy = true; ready();
    try {
      const result = await post("/" + id + "/attempts", { previewId: p.preview.id, confirmed: $("#conversation-confirm").checked, documentsConfirmed: $("#conversation-documents-confirm").checked });
      if (!active || ticket !== generation) return; invalidate(); render(result); message("请求已受理；可取消或刷新恢复。已发出的请求可能计费。"); poll(ticket); list();
    } catch (error) { if (active && ticket === generation) { invalidate(); message(error.message + " 请刷新状态并重新预览后再试。"); } }
    finally { if (ticket === generation) { busy = false; ready(); } }
  }
  async function cancel() {
    if (!current?.activeAttempt || busy) return; const ticket = generation, id = current.id, attempt = current.activeAttempt; busy = true; ready();
    try { const result = await post("/" + id + "/attempts/" + attempt + "/cancel", {}); if (!active || ticket !== generation) return; stopPolling(); invalidate(); render(result); message("已取消，迟到回答不会发布。用量未知，可重新预览发起新尝试。"); list(); }
    catch (error) { if (active && ticket === generation) message(error.message); }
    finally { if (ticket === generation) { busy = false; ready(); } }
  }
  async function search() {
    const s = scope(); if (!active || !s || !current) return;
    const ticket = generation, version = ++searchVersion; $("#conversation-hits").replaceChildren();
    try {
      const query = new URLSearchParams({ ...s, query: $("#conversation-keyword").value, limit: "10" });
      const result = await request("/api/knowledge/search?" + query); if (!active || ticket !== generation || version !== searchVersion) return;
      $("#conversation-search-notice").textContent = result.coverage + (result.truncated ? " 已达到扫描上限，可能有遗漏。" : "") + " 此检索未调用模型，最多选择 3 个准确分段。";
      $("#conversation-hits").replaceChildren(...result.results.map(c => {
        const card = node("section"), label = node("label"), check = node("input"); check.type = "checkbox"; check.checked = selected.has(c.chunkId); label.append(check, node("span", c.title + " / " + c.path + " / 行 " + c.startLine + "—" + c.endLine)); card.append(label, node("pre", c.excerpt));
        check.addEventListener("change", () => { if (!current || ticket !== generation) return; if (check.checked && selected.size >= 3) { check.checked = false; message("最多选择 3 个分段。"); return; } if (check.checked) selected.set(c.chunkId, c); else selected.delete(c.chunkId); invalidate(); $("#conversation-selected").textContent = "已选：" + [...selected.values()].map(v => v.path + ":" + v.startLine + "—" + v.endLine).join("、"); });
        return card;
      }));
    } catch (error) { if (active && ticket === generation && version === searchVersion) $("#conversation-search-notice").textContent = error.message; }
  }
  async function setActive(value) {
    active = value; generation++; listVersion++; searchVersion++; stopPolling(); busy = false; invalidate();
    if (!active) return; await list(); const id = new URLSearchParams(window.location.search).get("conversation"); if (id) await open(id);
  }
  $("#conversation-create").addEventListener("submit", create);
  $("#conversation-form").addEventListener("submit", event => { event.preventDefault(); preview(); });
  $("#conversation-reference").addEventListener("click", reference); $("#conversation-send").addEventListener("click", send); $("#conversation-cancel").addEventListener("click", cancel);
  $("#conversation-search").addEventListener("submit", event => { event.preventDefault(); search(); });
  $("#conversation-question").addEventListener("input", invalidate); $("#conversation-use-model").addEventListener("change", invalidate);
  $("#conversation-keyword").addEventListener("input", () => { searchVersion++; });
  $("#conversation-confirm").addEventListener("change", ready); $("#conversation-documents-confirm").addEventListener("change", ready);
  window.addEventListener("focus", async () => { if (!active || !current) return; const ticket = generation, id = current.id; try { const detail = await request("/api/conversations/" + id); if (active && ticket === generation) { render(detail); poll(ticket); } } catch (error) { if (active && ticket === generation) message(error.message); } });
  window.conversationPanel = { setActive, open, preview, search, reference };
  ready();
})();
