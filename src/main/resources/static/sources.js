"use strict";
const $ = selector => document.querySelector(selector);
let projects = [];
let services = [];
let busy = false;
let sharing = null;
let searchVersion = 0;

async function api(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  const value = await response.json();
  if (!response.ok) throw new Error(value.detail || "请求失败，请重试。");
  return value;
}
function write(path, body) {
  return api(path, { method: "POST", headers: { "Content-Type": "application/json", "X-Triage-Source": "1" }, body: JSON.stringify(body) });
}
function node(tag, className, text) {
  const value = document.createElement(tag);
  if (className) value.className = className;
  if (text != null) value.textContent = text;
  return value;
}
function notice(text, failure = false) {
  $("#source-notice").textContent = text;
  $("#source-notice").hidden = !text;
  $("#source-notice").classList.toggle("failure", failure);
}
function lock(value) {
  busy = value;
  document.querySelectorAll("main button").forEach(button => button.disabled = value);
  $("#register-source").disabled = value || !$("#source-service").value;
  $("#search-source").disabled = value || !projects.length;
}
function render() {
  const previous = $("#search-project").value;
  $("#source-project-count").textContent = String(projects.length);
  $("#source-projects").replaceChildren();
  for (const project of projects) {
    const card = node("article", "source-project");
    const header = node("header");
    header.append(node("h3", "", project.name), node("span", "active-tag", project.sharingActive ? "模型授权有效" : "本机检索"));
    const target = services.find(service => service.id === project.service);
    card.append(header, node("p", "", (target?.name || project.service) + " · " + project.files + " 个文件 · " + project.symbols + " 个符号 · 索引 v" + project.revision), node("p", "", project.root));
    card.append(node("p", "", "更新时间：" + new Date(project.indexedAt).toLocaleString("zh-CN") + " · 跳过 " + project.skippedFiles + " 个文件 · 语法错误 " + project.parseFailures + " 个"));
    if (project.modelSharing && !project.sharingActive) card.append(node("p", "", "之前的授权已不适用于当前模型，请重新确认。"));
    const actions = node("div", "source-actions");
    const reindex = node("button", "secondary", "重新索引");
    reindex.type = "button";
    reindex.addEventListener("click", () => operate(async () => {
      await write("/api/source-projects/" + project.id + "/reindex", {});
      clearSearch();
      notice("索引已更新，模型授权已关闭。");
    }));
    const enable = node("button", "secondary", project.sharingActive ? "重新确认模型授权" : "允许模型读取");
    enable.type = "button";
    enable.addEventListener("click", () => openSharing(project));
    actions.append(reindex, enable);
    if (project.modelSharing) {
      const revoke = node("button", "secondary", "关闭模型读取");
      revoke.type = "button";
      revoke.addEventListener("click", () => operate(async () => {
        await write("/api/source-projects/" + project.id + "/sharing", { revision: project.revision, enabled: false });
        notice("模型读取已关闭。");
      }));
      actions.append(revoke);
    }
    card.append(actions);
    $("#source-projects").append(card);
  }
  if (!projects.length) $("#source-projects").append(node("p", "settings-empty", "还没有登记项目。填写本机目录并绑定服务后，可以检索源码。"));
  $("#search-project").replaceChildren(...projects.map(project => {
    const option = node("option", "", project.name); option.value = project.id; return option;
  }));
  if (projects.some(project => project.id === previous)) $("#search-project").value = previous;
  $("#source-service").replaceChildren(...services.filter(service => !projects.some(project => project.service === service.id)).map(service => {
    const option = node("option", "", service.name); option.value = service.id; return option;
  }));
  if (!$("#source-service").options.length) $("#source-service").append(node("option", "", "所有服务均已绑定源码"));
  if (!$("#source-service").options[0]?.value || projects.length === services.length) $("#source-service").value = "";
}
function clearSearch() { searchVersion++; $("#source-matches").replaceChildren(); $("#source-search-note").textContent = "检索和预览在本机完成。"; }
async function reload() {
  [projects, services] = await Promise.all([api("/api/source-projects"), api("/api/source-projects/services")]);
  render();
}
async function operate(action) {
  if (busy) return;
  lock(true);
  try { await action(); await reload(); } catch (error) { notice(error.message, true); } finally { lock(false); }
}
async function openSharing(project) {
  if (busy) return;
  lock(true);
  try {
    const disclosure = await api("/api/source-projects/disclosure");
    if (!disclosure.available) throw new Error("请先在模型设置页启用一个已保存的模型服务。");
    sharing = { project, disclosure };
    $("#sharing-project").textContent = "项目：" + project.name + " · 索引 v" + project.revision;
    $("#sharing-model").textContent = "接收服务：" + disclosure.provider + " · " + disclosure.model + " · 配置 v" + disclosure.providerVersion;
    $("#sharing-error").hidden = true;
    $("#source-sharing-dialog").showModal();
  } catch (error) { notice(error.message, true); } finally { lock(false); }
}
$("#source-registration").addEventListener("submit", event => {
  event.preventDefault();
  operate(async () => {
    $("#register-source").textContent = "正在索引…";
    try {
      await write("/api/source-projects", { name: $("#source-name").value.trim(), service: $("#source-service").value, directory: $("#source-directory").value.trim() });
      $("#source-registration").reset();
      notice("项目已登记，可以检索代码或返回排查页关联运行观测。");
    } finally { $("#register-source").textContent = "登记并索引"; }
  });
});
$("#source-search").addEventListener("submit", async event => {
  event.preventDefault();
  if (busy) return;
  lock(true);
  const version = ++searchVersion;
  try {
    const result = await api("/api/source-projects/" + $("#search-project").value + "/search?q=" + encodeURIComponent($("#source-query").value.trim()));
    if (version !== searchVersion) return;
    sourceView.render($("#source-matches"), result);
    $("#source-search-note").textContent = result.length ? "找到 " + result.length + " 个引用。显示的是当前索引对应的代码片段。" : "没有匹配结果，请试试类名、方法名或接口路径。";
  } catch (error) { clearSearch(); notice(error.message, true); } finally { lock(false); }
});
$("#source-sharing-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!sharing || $("#confirm-sharing").disabled) return;
  $("#confirm-sharing").disabled = true;
  try {
    const { project, disclosure } = sharing;
    await write("/api/source-projects/" + project.id + "/sharing", { revision: project.revision, enabled: true, providerId: disclosure.providerId, providerVersion: disclosure.providerVersion, selection: disclosure.selection });
    $("#source-sharing-dialog").close();
    await reload();
    notice("授权已保存。排查页勾选本次模型读取后，才会发送候选代码。");
  } catch (error) { $("#sharing-error").textContent = error.message; $("#sharing-error").hidden = false; }
  finally { $("#confirm-sharing").disabled = false; lock(false); }
});
for (const id of ["#close-sharing", "#cancel-sharing"]) $(id).addEventListener("click", () => $("#source-sharing-dialog").close());
$("#source-sharing-dialog").addEventListener("close", () => { sharing = null; });
$("#refresh-sources").addEventListener("click", () => operate(async () => { clearSearch(); notice(""); }));
$("#search-project").addEventListener("change", clearSearch);
lock(true);
reload().catch(error => notice(error.message, true)).finally(() => lock(false));
