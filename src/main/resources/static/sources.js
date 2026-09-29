"use strict";
const $ = selector => document.querySelector(selector);
let projects = [];
let services = [];
let busy = false;
let sharing = null;
let searchVersion = 0;
let editing = null;
let removal = null;

async function api(path, options = {}) {
  const response = await fetch(path, { cache: "no-store", ...options });
  const value = response.status === 204 ? null : await response.json();
  if (!response.ok) throw new Error(value.detail || "请求失败，请重试。");
  return value;
}
function write(path, body, method = "POST") {
  return api(path, { method, headers: { "Content-Type": "application/json", "X-Triage-Source": "1" }, body: JSON.stringify(body) });
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
  document.querySelectorAll("main button").forEach(button => button.disabled = value || button.dataset.unbound === "true");
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
    header.append(node("h3", "", project.name), node("span", "active-tag", !project.service ? "未绑定服务" : project.sharingActive ? "模型授权有效" : "本机检索"));
    const target = services.find(service => service.id === project.service);
    card.append(header, node("p", "", (target?.name || project.service || "未绑定服务") + " · " + project.files + " 个文件 · " + project.symbols + " 个符号 · 索引 v" + project.revision), node("p", "", project.root));
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
    enable.dataset.unbound = String(!project.service);
    enable.addEventListener("click", () => openSharing(project));
    actions.append(reindex, enable);
    const edit = node("button", "secondary", "修改项目");
    edit.type = "button";
    edit.addEventListener("click", () => openEdit(project));
    actions.append(edit);
    if (project.service) {
      const unbind = node("button", "secondary", "解除绑定");
      unbind.type = "button";
      unbind.addEventListener("click", () => operate(async () => {
        await write("/api/source-projects/" + project.id, { revision: project.revision, name: project.name, directory: project.root, service: null }, "PUT");
        clearSearch(); notice("服务绑定已解除，项目仍可在本机检索，模型授权已关闭。");
      }));
      actions.append(unbind);
    }
    const remove = node("button", "secondary source-remove", "删除索引");
    remove.type = "button";
    remove.addEventListener("click", () => {
      removal = project;
      $("#delete-source-name").textContent = project.name + " · 索引 v" + project.revision;
      $("#delete-source-error").hidden = true;
      $("#delete-source-dialog").showModal();
    });
    actions.append(remove);
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
  if (!$("#source-service").options.length) {
    const unavailable = node("option", "", "所有服务均已绑定源码"); unavailable.value = "";
    $("#source-service").append(unavailable);
  }
}
function openEdit(project) {
  if (busy) return;
  editing = project;
  $("#edit-source-name").value = project.name;
  $("#edit-source-directory").value = project.root;
  const unbound = node("option", "", "暂不绑定服务"); unbound.value = "";
  $("#edit-source-service").replaceChildren(unbound, ...services.filter(service => !projects.some(other => other.id !== project.id && other.service === service.id)).map(service => {
    const option = node("option", "", service.name); option.value = service.id; return option;
  }));
  $("#edit-source-service").value = project.service || "";
  $("#edit-source-error").hidden = true;
  $("#edit-source-dialog").showModal();
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
$("#edit-source-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!editing || busy) return;
  lock(true); $("#save-source-edit").disabled = true;
  try {
    await write("/api/source-projects/" + editing.id, { revision: editing.revision, name: $("#edit-source-name").value.trim(), directory: $("#edit-source-directory").value.trim(), service: $("#edit-source-service").value || null }, "PUT");
    $("#edit-source-dialog").close(); clearSearch(); await reload();
    notice("项目已保存。发生修改时，旧模型授权会关闭。");
  } catch (error) { $("#edit-source-error").textContent = error.message; $("#edit-source-error").hidden = false; }
  finally { lock(false); $("#save-source-edit").disabled = false; }
});
$("#delete-source-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!removal || busy) return;
  lock(true); $("#confirm-delete-source").disabled = true;
  try {
    await write("/api/source-projects/" + removal.id, { confirmId: removal.id, revision: removal.revision }, "DELETE");
    $("#delete-source-dialog").close(); clearSearch(); await reload();
    notice("登记与索引已删除，源码文件和历史排查仍保留。");
  } catch (error) { $("#delete-source-error").textContent = error.message; $("#delete-source-error").hidden = false; }
  finally { lock(false); $("#confirm-delete-source").disabled = false; }
});
for (const [id, dialog] of [["#close-source-edit", "#edit-source-dialog"], ["#cancel-source-edit", "#edit-source-dialog"], ["#cancel-source-delete", "#delete-source-dialog"]])
  $(id).addEventListener("click", () => $(dialog).close());
$("#edit-source-dialog").addEventListener("close", () => { editing = null; });
$("#delete-source-dialog").addEventListener("close", () => { removal = null; });
$("#refresh-sources").addEventListener("click", () => operate(async () => { clearSearch(); notice(""); }));
$("#search-project").addEventListener("change", clearSearch);
lock(true);
reload().catch(error => notice(error.message, true)).finally(() => lock(false));
