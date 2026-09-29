const $ = (selector) => document.querySelector(selector);
const statuses = { QUEUED: "排队中", RUNNING: "执行中", SUCCEEDED: "已完成", INSUFFICIENT_EVIDENCE: "证据不足", FAILED: "失败", CANCELLED: "已取消" };
let historyPage = null;
let historyCursor = null;
let previousCursors = [];
let filters = new URLSearchParams();
let historyRequest = 0;
let deleting = false;
let deleteSelection = null;

function node(tag, text, className) {
  const value = document.createElement(tag);
  if (text != null) value.textContent = text;
  if (className) value.className = className;
  return value;
}
async function request(url, options = {}) {
  const response = await fetch(url, { cache: "no-store", ...options });
  if (!response.ok) {
    const body = await response.json().catch(() => ({}));
    throw new Error(body.detail || "请求失败 (" + response.status + ")");
  }
  return response.status === 204 ? null : response.json();
}
function notice(message, error = false) {
  $("#workspace-notice").textContent = message;
  $("#workspace-notice").hidden = !message;
  $("#workspace-notice").classList.toggle("error", error);
}
function time(value) { return new Date(value).toLocaleString("zh-CN", { hour12: false }); }
function duration(value) { return value == null ? "—" : value < 1000 ? value + " ms" : (value / 1000).toFixed(2) + " s"; }
function statusClass(status) { return status === "FAILED" ? "failed" : status === "SUCCEEDED" ? "success" : ["RUNNING", "QUEUED"].includes(status) ? "running" : "neutral"; }

async function loadServices() {
  const entries = await request("/api/history/services");
  const value = $("#filter-service").value;
  $("#filter-service").replaceChildren(node("option", "全部服务"));
  $("#filter-service").firstChild.value = "";
  for (const entry of entries) {
    const option = node("option", entry.name + " · " + entry.id); option.value = entry.id;
    $("#filter-service").append(option);
  }
  if (value && !entries.some((entry) => entry.id === value)) {
    const option = node("option", value); option.value = value; $("#filter-service").append(option);
  }
  $("#filter-service").value = value;
}
function selectedFilters() {
  const result = new URLSearchParams();
  for (const [parameter, selector] of [["q", "#filter-query"], ["service", "#filter-service"], ["status", "#filter-status"], ["mode", "#filter-mode"]]) {
    const value = $(selector).value.trim(); if (value) result.set(parameter, value);
  }
  for (const [parameter, selector, extraDay] of [["from", "#filter-from", 0], ["until", "#filter-until", 1]]) {
    const value = $(selector).value;
    if (value) { const date = new Date(value + "T00:00:00"); date.setDate(date.getDate() + extraDay); result.set(parameter, date.toISOString()); }
  }
  if (result.has("from") && result.has("until") && result.get("from") >= result.get("until")) throw new Error("结束日期不能早于开始日期。");
  return result;
}
function renderHistory() {
  const target = $("#history-rows"); target.replaceChildren();
  $("#history-total").textContent = historyPage.total + " 条";
  $("#history-page-label").textContent = "第 " + (previousCursors.length + 1) + " 页";
  $("#history-count").textContent = "本页 " + historyPage.items.length + " 条 · 每页 20 条";
  if (!historyPage.items.length) {
    const cell = node("td", "没有匹配的记录，可调整筛选条件。", "table-empty"); cell.colSpan = 6;
    const row = node("tr"); row.append(cell); target.append(row);
  }
  for (const entry of historyPage.items) {
    const row = node("tr"); row.dataset.id = entry.id;
    const first = node("td"); const link = node("a", entry.question || "未记录问题", "history-question");
    link.href = "/?run=" + encodeURIComponent(entry.id); first.append(link, node("small", time(entry.createdAt)));
    const service = node("td", entry.serviceInfo?.name || entry.service); service.append(node("small", entry.service));
    const state = node("td"); state.append(node("span", statuses[entry.status], "status " + statusClass(entry.status)));
    const actions = node("td"); const group = node("div", null, "history-row-actions");
    const open = node("a", "查看"); open.href = link.href;
    const remove = node("button", "删除", "history-delete"); remove.type = "button";
    remove.disabled = ["QUEUED", "RUNNING"].includes(entry.status);
    remove.title = remove.disabled ? "执行结束或取消后可删除" : "删除本条记录";
    remove.addEventListener("click", () => openDelete(entry)); group.append(open, remove); actions.append(group);
    row.append(first, service, state, node("td", entry.mode === "MODEL" ? "模型" : "固定规则"), node("td", duration(entry.durationMillis)), actions);
    target.append(row);
  }
  $("#history-previous").disabled = !previousCursors.length;
  $("#history-next").disabled = !historyPage.nextCursor;
}
async function loadHistory(cursor = historyCursor, navigation = "refresh") {
  const version = ++historyRequest;
  $("#history-previous").disabled = true; $("#history-next").disabled = true;
  const parameters = new URLSearchParams(filters); parameters.set("pageSize", "20");
  if (cursor) parameters.set("cursor", cursor);
  try {
    const result = await request("/api/history?" + parameters);
    if (version !== historyRequest) return;
    if (navigation === "next") previousCursors.push(historyCursor);
    if (navigation === "previous") previousCursors.pop();
    historyCursor = cursor; historyPage = result; renderHistory();
  } catch (error) {
    if (version !== historyRequest) return;
    notice(error.message, true);
    $("#history-previous").disabled = !previousCursors.length;
    $("#history-next").disabled = !historyPage?.nextCursor;
  }
}
function openDelete(entry) {
  if (deleting) return;
  deleteSelection = entry;
  $("#delete-question").textContent = entry.question || "未记录问题";
  $("#delete-detail").textContent = (entry.serviceInfo?.name || entry.service) + " · " + time(entry.createdAt) + " · " + statuses[entry.status];
  $("#delete-error").hidden = true;
  $("#delete-history-dialog").showModal(); $("#cancel-delete").focus();
}
function closeDelete() { if (!deleting) { $("#delete-history-dialog").close(); deleteSelection = null; } }
$("#delete-history-dialog").addEventListener("cancel", (event) => { if (deleting) event.preventDefault(); });
for (const selector of ["#close-delete", "#cancel-delete"]) $(selector).addEventListener("click", closeDelete);
$("#delete-history-form").addEventListener("submit", async (event) => {
  event.preventDefault(); if (deleting || !deleteSelection) return;
  deleting = true; const id = deleteSelection.id;
  for (const selector of ["#confirm-delete", "#cancel-delete", "#close-delete"]) $(selector).disabled = true;
  try {
    await request("/api/history/" + id, { method: "DELETE", headers: { "Content-Type": "application/json", "X-Triage-History": "1" }, body: JSON.stringify({ confirmId: id }) });
    $("#delete-history-dialog").close(); deleteSelection = null;
    notice("排查记录已删除。");
    if (historyPage?.items.length === 1 && previousCursors.length) await loadHistory(previousCursors.at(-1), "previous");
    else await loadHistory();
    loadServices().catch(() => notice("记录已删除，服务筛选列表暂未刷新，请稍后刷新页面。"));
  } catch (error) { $("#delete-error").textContent = error.message; $("#delete-error").hidden = false; }
  finally { deleting = false; for (const selector of ["#confirm-delete", "#cancel-delete", "#close-delete"]) $(selector).disabled = false; }
});
$("#history-filter").addEventListener("submit", (event) => {
  event.preventDefault();
  try { filters = selectedFilters(); previousCursors = []; historyCursor = null; notice(""); loadHistory(); }
  catch (error) { notice(error.message, true); }
});
$("#clear-filters").addEventListener("click", () => { $("#history-filter").reset(); $("#history-filter").requestSubmit(); });
$("#history-next").addEventListener("click", () => { if (historyPage?.nextCursor) loadHistory(historyPage.nextCursor, "next"); });
$("#history-previous").addEventListener("click", () => { if (previousCursors.length) loadHistory(previousCursors.at(-1), "previous"); });
$("#refresh-workspace").addEventListener("click", () => { previousCursors = []; historyCursor = null; loadHistory(null); loadServices().catch((error) => notice(error.message, true)); });
loadServices().catch((error) => notice(error.message, true));
loadHistory(null);
window.addEventListener("pageshow", (event) => { if (!event.persisted) $("#history-filter").reset(); });
