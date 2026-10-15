"use strict";
(() => {
  const $ = selector => document.querySelector(selector);
  const node = (tag, text, className) => { const value = document.createElement(tag); if (text != null) value.textContent = text; if (className) value.className = className; return value; };
  const date = value => new Date(value).toLocaleString("zh-CN", { hour12: false });
  const ciState = { INCOMPLETE: "CI 未完整读取", NOT_REPORTED: "未发现提交检查", OBSERVED_FAILURE: "已读取检查有失败或取消", PENDING: "检查尚未结束", OBSERVED_SUCCESS: "已读取检查通过", NO_SUCCESS_CONCLUSION: "检查没有全部成功结论" };
  const coverage = { COMPLETE: "补丁完整", METADATA_ONLY: "仅元数据，无增删行", NO_PATCH: "无补丁", INCOMPLETE: "补丁不完整" };
  const fileState = { added: "新增", removed: "删除", modified: "修改", renamed: "重命名", copied: "复制", changed: "变更", unchanged: "未变更" };
  let repository = "", context = null, busy = false, version = 0;
  async function request(path, options = {}) {
    const response = await fetch(path, { cache: "no-store", ...options });
    if (!response.ok) { const problem = await response.json().catch(() => ({})); throw new Error(problem.detail || "读取 PR 失败 (" + response.status + ")"); }
    return response.json();
  }
  function link(text, url) {
    const value = node("a", text);
    try { const target = new URL(url); if (target.protocol === "https:" && target.hostname === "github.com" && !target.username && !target.password) value.href = target.href; } catch {}
    value.target = "_blank"; value.rel = "noopener noreferrer"; return value;
  }
  function message(text, error = false) { $("#pr-dialog-notice").textContent = text; $("#pr-dialog-notice").hidden = !text; $("#pr-dialog-notice").classList.toggle("error", error); }
  function ready() {
    $("#pr-review-button").disabled = busy || !context;
    $("#pr-close-dialog").disabled = busy; $("#pr-open-number").disabled = busy || !repository;
  }
  function render(report) {
    $("#pr-report-result").hidden = false;
    $("#pr-report-title").textContent = "#" + report.pull.number + " " + report.pull.title;
    $("#pr-report-meta").textContent = report.repository + " · " + date(report.createdAt) + " · 保存时的版本与检查状态";
    $("#pr-report-notice").textContent = report.notice;
    $("#pr-report-versions").replaceChildren(...[["Base", report.pull.baseSha], ["Head", report.pull.headSha], ["Merge-base（删除行）", report.mergeBaseSha]].map(([name, sha]) => {
      const row = node("div", null, "pr-version"); row.append(node("span", name), node("code", sha)); return row;
    }));
    $("#pr-report-link").replaceChildren(link("在 GitHub 查看 PR ↗", report.pull.url));
    $("#pr-report-counts").textContent = "读取 " + report.files.length + " / " + report.pull.changedFiles + " 个文件 · PR +" + report.pull.additions + " / −" + report.pull.deletions + " 行 · " + report.findings.length + " 条变更提醒";
    $("#pr-report-findings").replaceChildren(...report.findings.map(finding => {
      const card = node("section", null, "pr-finding");
      const heading = node("div", null, "pr-finding-heading"); heading.append(node("h4", finding.title), node("span", "待核对", "repository-label"));
      card.append(heading, link(finding.file + ":" + finding.line + " · " + (finding.side === "LEFT" ? "删除行" : "新增行"), finding.url),
        node("pre", finding.excerpt), node("p", finding.reason), node("p", "建议核查：" + finding.check, "management-caption")); return card;
    }));
    if (!report.findings.length) $("#pr-report-findings").append(node("p", "本次规则未命中变更提醒，不代表没有风险。请结合检查范围和 CI 核对改动。", "repository-list-empty"));
    $("#pr-report-ci-state").textContent = ciState[report.ci.state] || "检查状态未知";
    $("#pr-report-ci").replaceChildren(...report.ci.checks.map(check => {
      const item = node("li"); item.append(link(check.name, check.url), node("span", " · " + check.status + " / " + (check.conclusion || "等待结论"))); return item;
    }));
    if (!report.ci.checks.length) $("#pr-report-ci").append(node("li", "没有已读取的检查条目。"));
    $("#pr-report-issues").replaceChildren(...report.issues.map(issue => { const item = node("li"); item.append(link("#" + issue.number + " " + issue.title, issue.url), node("span", issue.state === "open" ? " · 开放" : " · 已关闭")); return item; }));
    if (!report.issues.length) $("#pr-report-issues").append(node("li", "没有已读取的同仓库 Issue 引用。"));
    $("#pr-report-gaps").replaceChildren(...report.gaps.map(text => node("li", text)));
    if (!report.gaps.length) $("#pr-report-gaps").append(node("li", "本次读取范围内未记录额外缺口；仍受下方检查范围限制。"));
    $("#pr-report-files").replaceChildren(...report.files.map(file => {
      const row = node("tr"); row.append(node("td", file.file + (file.previousFile ? " ← " + file.previousFile : "")), node("td", fileState[file.status] || file.status), node("td", "+" + file.additions + " / −" + file.deletions), node("td", coverage[file.coverage] || file.coverage)); return row;
    }));
    $("#pr-report-result").scrollIntoView({ behavior: "smooth", block: "start" });
  }
  async function loadReports() {
    const selected = repository, current = version;
    $("#pr-report-history").replaceChildren(); if (!selected) return;
    try {
      const reports = await request("/api/development/repositories/" + selected + "/pr/reports");
      if (current !== version) return;
      $("#pr-report-history").replaceChildren(...reports.map(report => {
        const button = node("button", "#" + report.pull.number + " " + report.pull.title + " · " + report.pull.headSha.substring(0, 7) + " · " + date(report.createdAt), "ci-history-item"); button.type = "button";
        button.addEventListener("click", () => { if (current === version) render(report); }); return button;
      }));
      if (!reports.length) $("#pr-report-history").append(node("p", "还没有保存的 PR 审查报告。", "repository-list-empty"));
    } catch (error) { if (current === version) $("#pr-report-history").replaceChildren(node("p", error.message, "repository-error")); }
  }
  async function openPull(selected, number) {
    if (busy || !selected || selected !== repository) return;
    const current = version; context = null; busy = true; message(""); ready();
    $("#pr-pull-context").textContent = "正在读取 PR 的当前版本…"; $("#pr-pull-versions").textContent = "";
    $("#pr-review-dialog").showModal();
    try {
      const result = await request("/api/development/repositories/" + selected + "/pr/pulls/" + number);
      if (current !== version) return; context = result;
      $("#pr-pull-context").textContent = "#" + result.number + " " + result.title + (result.draft ? " · 草稿" : "") + (result.state === "closed" ? " · 已关闭" : "");
      $("#pr-pull-versions").textContent = "Base " + result.baseSha + "\nHead " + result.headSha;
      message("将审查此版本的 " + result.changedFiles + " 个变更文件。过程中提交发生变化，会要求重新读取。");
    } catch (error) { if (current === version) message(error.message, true); }
    finally { if (current === version) { busy = false; ready(); } }
  }
  $("#pr-close-dialog").addEventListener("click", () => { if (!busy) $("#pr-review-dialog").close(); });
  $("#pr-review-dialog").addEventListener("cancel", event => { if (busy) event.preventDefault(); });
  $("#pr-refresh-reports").addEventListener("click", loadReports);
  $("#pr-number-form").addEventListener("submit", event => { event.preventDefault(); const value = Number($("#pr-number-input").value); if (Number.isSafeInteger(value) && value > 0 && value <= 2147483647) openPull(repository, value); });
  $("#pr-review-form").addEventListener("submit", async event => {
    event.preventDefault(); if (busy || !context) return;
    const selected = repository, current = version, input = { number: context.number, baseSha: context.baseSha, headSha: context.headSha };
    busy = true; ready(); message("正在读取固定版本差异、提交检查和 Issue 引用…");
    try {
      const report = await request("/api/development/repositories/" + selected + "/pr/reports", { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Development": "1" }, body: JSON.stringify(input) });
      if (current !== version) return; $("#pr-review-dialog").close(); render(report); await loadReports();
    } catch (error) { if (current === version) message(error.message, true); }
    finally { if (current === version) { busy = false; ready(); } }
  });
  window.prPanel = { selectRepository(id) { if (id === repository) return; repository = id; version++; context = null; busy = false; $("#pr-review-dialog").close(); $("#pr-report-result").hidden = true; ready(); loadReports(); }, openPull, render };
})();
