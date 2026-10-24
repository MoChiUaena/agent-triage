"use strict";
(() => {
  const $ = selector => document.querySelector(selector);
  const node = (tag, text, className) => { const value = document.createElement(tag); if (text != null) value.textContent = text; if (className) value.className = className; return value; };
  const date = value => new Date(value).toLocaleString("zh-CN", {hour12:false});
  const category = {BUG:"缺陷", FEATURE_REQUEST:"功能请求", QUESTION:"使用咨询", DOCUMENTATION:"文档", UNKNOWN:"待分类"};
  let repository = "", number = 0, preview = null, version = 0, busy = false;
  let sourceProjects = [];
  function link(text, url) {
    const value = node("a", text);
    try { const target = new URL(url); if (target.protocol === "https:" && target.hostname === "github.com" && !target.username && !target.password) value.href = target.href; } catch {}
    value.target = "_blank"; value.rel = "noopener noreferrer"; return value;
  }
  async function request(path, options = {}) {
    const response = await fetch(path, {cache:"no-store", ...options});
    if (!response.ok) { const problem = await response.json().catch(() => ({})); const error = new Error(problem.detail || "读取 Issue 失败 (" + response.status + ")"); error.status = response.status; throw error; }
    return response.json();
  }
  const post = (path, input) => request(path, {method:"POST", headers:{"Content-Type":"application/json", "X-Triage-Development":"1"}, body:JSON.stringify(input)});
  function message(text, error = false) { $("#issue-dialog-notice").textContent = text; $("#issue-dialog-notice").hidden = !text; $("#issue-dialog-notice").classList.toggle("error", error); }
  function ready() {
    $("#issue-generate-button").disabled = busy || !preview;
    $("#issue-close-dialog").disabled = busy; $("#issue-read-context").disabled = busy || !number;
    $("#issue-use-model").disabled = busy || !preview?.model.available; $("#issue-code-paths").disabled = busy;
    $("#issue-open-number").disabled = busy || !repository;
  }
  function evidenceBlocks(evidence, container) {
    const blocks = new Map();
    container.replaceChildren(...evidence.map(item => {
      const detail = node("details", null, "ci-evidence"); detail.open = item.kind === "ISSUE";
      detail.append(node("summary", item.id + " · " + item.title + (item.file ? " · " + item.file + ":" + item.startLine + "–" + item.endLine : "")),
        node("pre", item.startLine ? item.text.split("\n").map((text, index) => (item.startLine + index) + "  " + text).join("\n") : item.text), link("查看来源 ↗", item.url));
      blocks.set(item.id, detail); return detail;
    }));
    return blocks;
  }
  function references(ids, blocks) {
    const value = node("span", null, "issue-reference-set");
    for (const id of ids) { const button = node("button", id, "issue-reference"); button.type = "button"; button.addEventListener("click", () => { const block = blocks.get(id); if (block) { block.open = true; block.scrollIntoView({behavior:"smooth", block:"center"}); } }); value.append(button); }
    return value;
  }
  function render(report) {
    if (!report) return;
    const proposal = report.proposal; $("#issue-report-result").hidden = false;
    $("#issue-report-title").textContent = "#" + report.issue.number + " " + report.issue.title;
    $("#issue-report-mode").textContent = report.source === "MODEL" ? "模型建议 · 待确认" : "基础分诊";
    $("#issue-report-meta").textContent = report.repository + " · 上下文 " + date(report.contextReadAt) + " · 报告 " + date(report.createdAt);
    $("#issue-report-version").textContent = (report.issue.state === "closed" ? "已关闭" : "开放") + " · Issue 更新 " + date(report.issue.updatedAt) + " · " + report.branch + " / " + (report.headSha || "版本未读取");
    const followup = node("a", "继续追问"); followup.href = "/workspace.html?type=ISSUE&report=" + encodeURIComponent(report.id) + "#conversations";
    const materialLink = node("a", "材料 / 发送预览"); materialLink.href = "/workspace.html?type=ISSUE&repository=" + encodeURIComponent(report.repositoryId) + "&report=" + encodeURIComponent(report.id) + "#materials";
    $("#issue-report-link").replaceChildren(link("在 GitHub 查看 Issue ↗", report.issue.url), node("span", " · "), materialLink);
    $("#issue-report-link").append(node("span", " · "), followup);
    $("#issue-report-classification").textContent = (category[proposal.category] || "待分类") + " · 优先级建议：" + (proposal.priority === "UNSPECIFIED" ? "待定" : proposal.priority);
    $("#issue-report-summary").textContent = proposal.summary;
    const blocks = evidenceBlocks(report.evidence, $("#issue-report-evidence"));
    $("#issue-report-reason").replaceChildren(node("span", proposal.reason), references(proposal.evidenceIds, blocks));
    $("#issue-report-questions").replaceChildren(...proposal.questions.map(text => node("li", text)));
    if (!proposal.questions.length) $("#issue-report-questions").append(node("li", "没有额外补充问题，请核对报告者描述与实际环境。"));
    $("#issue-report-actions").replaceChildren(...proposal.actions.map(action => { const item = node("li"); item.append(node("span", action.text), references(action.evidenceIds, blocks)); return item; }));
    $("#issue-report-owners").replaceChildren(...proposal.ownerCandidates.map(candidate => {
      const owner = report.owners.find(owner => owner.login === candidate.login), item = node("li");
      item.append(link(candidate.login, owner?.url || ""), node("span", " · " + candidate.reason), references(candidate.evidenceIds, blocks)); return item;
    }));
    if (!proposal.ownerCandidates.length) $("#issue-report-owners").append(node("li", "没有提出负责人候选。"));
    const model = report.model, usage = model.usage;
    $("#issue-report-model").textContent = model.message + (model.modelName ? " " + model.modelName + " · " + model.elapsedMs + " ms" : "")
      + (usage ? " · 输入 " + usage.inputTokens + " / 输出 " + usage.outputTokens + " / 总计 " + usage.totalTokens + " Token" : model.state === "SKIPPED" ? "" : " · 未取得完整用量");
    $("#issue-report-gaps").replaceChildren(...report.gaps.map(text => node("li", text)));
    if (!report.gaps.length) $("#issue-report-gaps").append(node("li", "读取范围内未记录额外缺口；仍未复现问题或核对完整代码库。"));
    $("#issue-report-result").scrollIntoView({behavior:"smooth", block:"start"});
  }
  async function loadReports() {
    const selected = repository, current = version; $("#issue-report-history").replaceChildren(); if (!selected) return;
    try {
      const reports = await request("/api/development/repositories/" + selected + "/issue/reports"); if (current !== version) return;
      $("#issue-report-history").replaceChildren(...reports.map(report => {
        const button = node("button", "#" + report.issue.number + " " + report.issue.title + " · " + date(report.createdAt) + " · " + (report.source === "MODEL" ? "模型建议" : "基础分诊"), "ci-history-item"); button.type = "button";
        button.addEventListener("click", () => { if (current === version) render(report); }); return button;
      }));
      if (!reports.length) $("#issue-report-history").append(node("p", "还没有保存的 Issue 分诊报告。", "repository-list-empty"));
    } catch (error) { if (current === version) $("#issue-report-history").replaceChildren(node("p", error.message, "repository-error")); }
  }
  async function readContext() {
    if (busy || !repository || !number) return;
    const selected = repository, current = version; preview = null; busy = true; $("#issue-use-model").checked = false; ready(); message("正在读取 Issue、固定版本代码与仓库上下文…");
    $("#issue-preview-evidence").replaceChildren(); $("#issue-preview-gaps").replaceChildren(); $("#issue-model-destination").textContent = "";
    try {
      const paths = $("#issue-code-paths").value.split("\n").map(path => path.trim()).filter(Boolean);
      const input = {number, codePaths:paths};
      const selectedSource = sourceProjects.find(project => project.id === $("#issue-source-select").value);
      if (selectedSource) { input.sourceProjectId = selectedSource.id; input.sourceRevision = selectedSource.revision; }
      const result = await post("/api/development/repositories/" + selected + "/issue/previews", input); if (current !== version) return;
      preview = result; $("#issue-preview-title").textContent = "#" + result.issue.number + " " + result.issue.title + (result.issue.state === "closed" ? " · 已关闭" : " · 开放");
      $("#issue-preview-version").textContent = result.branch + " / " + (result.headSha || "版本未读取");
      evidenceBlocks(result.evidence, $("#issue-preview-evidence"));
      $("#issue-preview-gaps").replaceChildren(...result.gaps.map(text => node("li", text)));
      $("#issue-model-destination").textContent = result.model.available ? result.model.displayName + " · " + result.model.modelName + " · " + result.model.baseUrl : result.model.message;
      message("上下文已读取。预览五分钟内有效；默认只生成基础分诊。");
    } catch (error) { if (current === version) message(error.message, true); }
    finally { if (current === version) { busy = false; ready(); } }
  }
  async function openIssue(selected, value) {
    if (busy || !selected || selected !== repository) return;
    number = value; preview = null; $("#issue-code-paths").value = ""; $("#issue-use-model").checked = false;
    $("#issue-preview-title").textContent = "读取 Issue #" + value; $("#issue-preview-version").textContent = "";
    $("#issue-triage-dialog").showModal(); await readContext();
  }
  $("#issue-close-dialog").addEventListener("click", () => { if (!busy) { $("#issue-use-model").checked = false; $("#issue-triage-dialog").close(); } });
  $("#issue-triage-dialog").addEventListener("cancel", event => { if (busy) event.preventDefault(); else $("#issue-use-model").checked = false; });
  async function loadSourceProjects() {
    const current = version;
    try {
      const values = await request("/api/source-projects");
      if (current !== version) return;
      sourceProjects = (values || []).slice(0, 20);
      const select = $("#issue-source-select");
      const previous = select.value;
      select.replaceChildren(...[node("option", "不附带符号候选")].concat(sourceProjects.map(project => {
        const option = node("option", project.name + " · revision " + project.revision + " · " + project.files + " 文件");
        option.value = project.id; return option;
      })));
      if (sourceProjects.some(project => project.id === previous)) select.value = previous;
      $("#issue-source-note").textContent = sourceProjects.length
        ? "符号候选随预览一起展示并受脱敏约束；索引更新后需重新读取上下文。"
        : "尚未登记本机 Java 项目；可在项目源码页登记并索引后再附带符号候选。";
    } catch (error) { if (current === version) $("#issue-source-note").textContent = "源码项目列表读取失败：" + error.message; }
  }
  $("#issue-source-select").addEventListener("change", () => {
    const selected = sourceProjects.find(project => project.id === $("#issue-source-select").value);
    $("#issue-source-note").textContent = selected ? "将按 revision " + selected.revision + " 检索；该索引变化后需重新读取上下文。" : "";
  });
  $("#issue-read-context").addEventListener("click", readContext);
  loadSourceProjects();
  $("#issue-code-paths").addEventListener("input", () => { preview = null; $("#issue-use-model").checked = false; ready(); message("路径已修改，请重新读取上下文后生成报告。"); });
  $("#issue-refresh-reports").addEventListener("click", loadReports);
  $("#issue-number-form").addEventListener("submit", event => { event.preventDefault(); const value = Number($("#issue-number-input").value); if (Number.isSafeInteger(value) && value > 0 && value <= 2147483647) openIssue(repository, value); });
  $("#issue-generate-form").addEventListener("submit", async event => {
    event.preventDefault(); if (busy || !preview) return;
    const selected = repository, current = version, input = {previewId:preview.id, useModel:$("#issue-use-model").checked};
    busy = true; ready(); message(input.useModel ? "正在向所示模型发送脱敏上下文并生成建议…" : "正在生成基础分诊，不调用模型…");
    try { const report = await post("/api/development/repositories/" + selected + "/issue/reports", input); if (current !== version) return; $("#issue-triage-dialog").close(); $("#issue-use-model").checked = false; render(report); await loadReports(); }
    catch (error) { if (current === version) { message(error.message, true); if (error.status === 409) { preview = null; $("#issue-use-model").checked = false; } } }
    finally { if (current === version) { busy = false; ready(); } }
  });
  window.issuePanel = { selectRepository(id) { if (id === repository) return; repository = id; version++; preview = null; number = 0; busy = false; $("#issue-use-model").checked = false; $("#issue-triage-dialog").close(); $("#issue-report-result").hidden = true; ready(); loadReports(); }, openIssue, render };
})();
