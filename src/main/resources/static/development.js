"use strict";
const $ = selector => document.querySelector(selector);
let repositories = [], selectionVersion = 0, listVersion = 0, busy = false;
const failures = ["failure", "timed_out", "action_required", "startup_failure"];
function element(tag, text, className) { const node = document.createElement(tag); if (text != null) node.textContent = text; if (className) node.className = className; return node; }
function notice(text, error = false) { $("#development-notice").textContent = text; $("#development-notice").hidden = !text; $("#development-notice").classList.toggle("error", error); }
async function request(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  if (!response.ok) { const problem = await response.json().catch(() => ({})); throw new Error(problem.detail || "请求失败 (" + response.status + ")"); }
  return response.status === 204 ? null : response.json();
}
const writeHeaders = { "Content-Type": "application/json", "X-Triage-Development": "1" };
function time(value) { return value ? new Date(value).toLocaleString("zh-CN", { hour12: false }) : "—"; }
function link(text, url) {
  const anchor = element("a", text);
  try { const target = new URL(url); if (target.protocol === "https:" && target.hostname === "github.com") anchor.href = target.href; } catch {}
  anchor.target = "_blank"; anchor.rel = "noopener noreferrer"; return anchor;
}
function setBusy(value) {
  busy = value; $("#register-button").disabled = value; $("#repository-select").disabled = value;
  for (const id of ["#refresh-repository", "#remove-repository"]) $(id).disabled = value || !$("#repository-select").value;
}
function renderList(selector, section, render) {
  const target = $(selector); target.replaceChildren();
  if (!section.available) { target.append(element("p", section.message || "读取失败，请更新状态后重试。", "repository-error")); return; }
  if (!section.items.length) { target.append(element("p", "本次读取没有条目。", "repository-list-empty")); return; }
  target.append(...section.items.map(render));
}
function statistic(title, count, caption) { const card = element("div", null, "repository-statistic"); card.append(element("p", title), element("strong", count), element("small", caption)); return card; }
function scope(section, text) { return section.available ? text + (section.truncated ? " · 还有更多条目" : "") : "读取失败"; }
function workflowState(run) {
  if (run.status !== "completed") return [run.status === "in_progress" ? "执行中" : run.status === "queued" ? "排队中" : "等待执行", "running"];
  if (run.conclusion === "success") return ["通过", "success"];
  if (failures.includes(run.conclusion)) return [run.conclusion === "timed_out" ? "超时" : "失败", "failed"];
  return [{ cancelled: "已取消", skipped: "已跳过", neutral: "中立" }[run.conclusion] || "未提供结论", ""];
}
function renderHealth(snapshot) {
  const health = snapshot.health; $("#repository-empty").hidden = true; $("#repository-health").hidden = false;
  $("#repository-name").textContent = health.repository.fullName;
  $("#repository-description").textContent = health.repository.description || "仓库未填写描述。";
  $("#repository-link").href = snapshot.registration.url;
  $("#repository-freshness").textContent = "上次读取 GitHub：" + time(health.checkedAt) + " · 默认分支 " + health.repository.defaultBranch;
  const warning = snapshot.error || (snapshot.stale ? "这里是上次读取的结果。点击“更新 GitHub 状态”获取当前数据。" : health.repository.archived ? "此仓库已归档。" : "");
  $("#repository-warning").textContent = warning; $("#repository-warning").hidden = !warning;
  $("#repository-statistics").replaceChildren(
    statistic("开放 Issue 抽样", health.issues.available ? health.issues.items.length : "—", "最多 20 条；完整列表见 GitHub"),
    statistic("开放 PR 抽样", health.pullRequests.available ? health.pullRequests.items.length : "—", "最多 20 条；包括草稿 PR"),
    statistic("近期 CI 失败", health.workflows.available ? health.workflows.items.filter(run => run.status === "completed" && failures.includes(run.conclusion)).length : "—", "默认分支最近 20 次工作流"));
  $("#issue-scope").textContent = scope(health.issues, "开放事项中的 Issue"); $("#pull-scope").textContent = scope(health.pullRequests, "最近更新");
  $("#workflow-scope").textContent = scope(health.workflows, health.repository.defaultBranch + " · 最近 20 次");
  renderList("#repository-issues", health.issues, issue => {
    const row = element("div", null, "repository-item"); row.append(link("#" + issue.number + " " + issue.title, issue.url));
    const meta = element("div", null, "repository-item-meta"); meta.append(element("span", time(issue.updatedAt)), ...issue.labels.map(label => element("span", label, "repository-label"))); row.append(meta); return row;
  });
  renderList("#repository-pulls", health.pullRequests, pr => {
    const row = element("div", null, "repository-item"); row.append(link("#" + pr.number + " " + pr.title, pr.url));
    const meta = element("div", null, "repository-item-meta"); meta.append(element("span", time(pr.updatedAt))); if (pr.draft) meta.append(element("span", "草稿", "repository-label")); row.append(meta); return row;
  });
  renderList("#repository-workflows", health.workflows, run => {
    const row = element("div", null, "workflow-row"), [label, style] = workflowState(run);
    row.append(link(run.name, run.url), element("span", label, "workflow-state " + style), element("small", run.branch + " · " + run.headSha.substring(0, 7)), element("small", time(run.updatedAt))); return row;
  });
}
async function loadHealth() {
  const id = $("#repository-select").value, version = ++selectionVersion;
  if (!id) { $("#repository-health").hidden = true; $("#repository-empty").hidden = false; setBusy(false); return; }
  setBusy(true);
  try { const snapshot = await request("/api/development/repositories/" + id + "/health"); if (version === selectionVersion) renderHealth(snapshot); }
  catch (error) { if (version === selectionVersion) { $("#repository-health").hidden = true; notice(error.message, true); } }
  finally { if (version === selectionVersion) setBusy(false); }
}
async function loadRepositories(selected = $("#repository-select").value) {
  const version = ++listVersion;
  const values = await request("/api/development/repositories");
  if (version !== listVersion) return;
  repositories = values;
  const options = repositories.map(repo => { const option = element("option", repo.owner + "/" + repo.name); option.value = repo.id; return option; });
  if (!options.length) { const option = element("option", "尚未登记仓库"); option.value = ""; options.push(option); }
  $("#repository-select").replaceChildren(...options); $("#repository-select").value = repositories.some(repo => repo.id === selected) ? selected : repositories[0]?.id || "";
  await loadHealth();
}
$("#register-repository").addEventListener("submit", async event => {
  event.preventDefault(); if (busy) return; listVersion++; selectionVersion++; setBusy(true); notice("正在从 GitHub 读取仓库…");
  try { const result = await request("/api/development/repositories", { method: "POST", headers: writeHeaders, body: JSON.stringify({ repository: $("#repository-input").value.trim() }) }); $("#repository-input").value = ""; await loadRepositories(result.registration.id); notice("仓库已接入。"); }
  catch (error) { notice(error.message, true); } finally { setBusy(false); }
});
$("#repository-select").addEventListener("change", () => { notice(""); loadHealth(); });
$("#refresh-repository").addEventListener("click", async () => {
  if (busy) return; const id = $("#repository-select").value; setBusy(true); notice("正在更新 GitHub 状态…");
  try { renderHealth(await request("/api/development/repositories/" + id + "/refresh", { method: "POST", headers: writeHeaders })); notice("GitHub 状态已更新。"); }
  catch (error) { await loadHealth(); notice(error.message, true); } finally { setBusy(false); }
});
$("#remove-repository").addEventListener("click", () => { const repo = repositories.find(repo => repo.id === $("#repository-select").value); if (!repo || busy) return; $("#remove-detail").textContent = "移除 " + repo.owner + "/" + repo.name + " 的本机登记？"; $("#remove-repository-dialog").showModal(); });
$("#cancel-remove").addEventListener("click", () => $("#remove-repository-dialog").close());
$("#remove-repository-form").addEventListener("submit", async event => {
  event.preventDefault(); if (busy) return; const id = $("#repository-select").value; setBusy(true);
  try { await request("/api/development/repositories/" + id + "?confirmId=" + id, { method: "DELETE", headers: writeHeaders }); $("#remove-repository-dialog").close(); await loadRepositories(""); notice("仓库登记已移除。"); }
  catch (error) { notice(error.message, true); } finally { setBusy(false); }
});
Promise.all([loadRepositories(), request("/api/development/settings").then(settings => $("#github-access").textContent = settings.authenticated ? "已配置 GitHub 凭据" : "公开仓库访问")]).catch(error => notice(error.message, true));
