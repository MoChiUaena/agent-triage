const $ = (selector) => document.querySelector(selector);
const statuses = { QUEUED: "排队中", RUNNING: "执行中", SUCCEEDED: "已完成", INSUFFICIENT_EVIDENCE: "证据不足", FAILED: "失败", CANCELLED: "已取消" };
let historyPage = null;
let historyCursor = null;
let previousCursors = [];
let filters = new URLSearchParams();
let historyRequest = 0;
let deleting = false;
let deleteSelection = null;
let retentionPreview = null;
let retentionRequest = 0;
let retentionBusy = false;
let activeTab = "history";
let statisticsRequest = 0;
let serviceRequest = 0;
let serviceListRequest = 0;
const endpointRequests = { filter: 0, statistics: 0 };

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
function resetRetentionPreview(message = "选择留存时间后预览，不会立即删除。") {
  retentionRequest++;
  retentionPreview = null;
  $("#retention-preview").disabled = false;
  $("#retention-open").hidden = true;
  $("#retention-summary").textContent = message;
}
function time(value) { return new Date(value).toLocaleString("zh-CN", { hour12: false }); }
function duration(value) { return value == null ? "—" : value < 1000 ? Math.round(value) + " ms" : (value / 1000).toFixed(2) + " s"; }
function statusClass(status) { return status === "FAILED" ? "failed" : status === "SUCCEEDED" ? "success" : ["RUNNING", "QUEUED"].includes(status) ? "running" : "neutral"; }
function endpointLabel(endpoint, handler = false) {
  if (!endpoint) return "整个服务";
  const route = endpoint.httpMethod + " " + endpoint.routeTemplate;
  const signature = endpoint.handlerClass.split(".").at(-1) + "." + endpoint.handlerMethod + "(" + endpoint.parameterTypes.map((type) => type.split(".").at(-1)).join(", ") + ")";
  return handler ? route + " · " + signature : route;
}
async function loadEndpoints(prefix, preserve = true) {
  const version = ++endpointRequests[prefix];
  const service = $("#" + prefix + "-service").value;
  const field = $("#" + prefix + "-endpoint");
  const value = preserve ? field.value : "";
  const previousLabel = field.selectedOptions[0]?.textContent;
  const all = node("option", "全部范围"); all.value = "";
  const whole = node("option", "整个服务"); whole.value = "SERVICE";
  field.replaceChildren(all, whole);
  if (value && value !== "SERVICE") { const option = node("option", previousLabel); option.value = value; field.append(option); }
  field.value = value;
  if (!service) { field.disabled = false; return; }
  field.disabled = true;
  try {
    const entries = await request("/api/history/endpoints?" + new URLSearchParams({ service }));
    if (version !== endpointRequests[prefix]) return;
    field.replaceChildren(all, whole);
    for (const entry of entries) { const option = node("option", endpointLabel(entry, true)); option.value = entry.id; field.append(option); }
    if (value && value !== "SERVICE" && !entries.some((entry) => entry.id === value)) {
      const option = node("option", previousLabel || "所选接口（暂无记录）"); option.value = value; field.append(option);
    }
    field.value = value;
  } catch (error) {
    if (version !== endpointRequests[prefix]) return;
    notice("接口筛选列表暂未刷新，请稍后重试。", true);
  } finally { if (version === endpointRequests[prefix]) field.disabled = false; }
}

async function loadServices() {
  const version = ++serviceListRequest;
  const entries = await request("/api/history/services");
  if (version !== serviceListRequest) return;
  for (const selector of ["#filter-service", "#statistics-service"]) {
    const field = $(selector); if (!field) continue;
    const value = field.value;
    field.replaceChildren(node("option", "全部服务")); field.firstChild.value = "";
    for (const entry of entries) { const option = node("option", entry.name + " · " + entry.id); option.value = entry.id; field.append(option); }
    if (value && !entries.some((entry) => entry.id === value)) { const option = node("option", value); option.value = value; field.append(option); }
    field.value = value;
  }
  await Promise.all([loadEndpoints("filter"), loadEndpoints("statistics")]);
}
function selectedFilters() {
  const result = new URLSearchParams();
  for (const [parameter, selector] of [["q", "#filter-query"], ["service", "#filter-service"], ["endpointId", "#filter-endpoint"], ["status", "#filter-status"], ["mode", "#filter-mode"]]) {
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
    link.href = "/?run=" + encodeURIComponent(entry.id);
    const scope = node("span", endpointLabel(entry.endpoint), "history-scope" + (entry.endpoint ? " endpoint" : ""));
    scope.title = endpointLabel(entry.endpoint, true);
    first.append(link, scope, node("small", time(entry.createdAt)));
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
  $("#delete-detail").textContent = (entry.serviceInfo?.name || entry.service) + " · " + endpointLabel(entry.endpoint) + " · " + time(entry.createdAt) + " · " + statuses[entry.status];
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
    resetRetentionPreview();
    notice("排查记录已删除。");
    if (historyPage?.items.length === 1 && previousCursors.length) await loadHistory(previousCursors.at(-1), "previous");
    else await loadHistory();
    loadServices().catch(() => notice("记录已删除，服务筛选列表暂未刷新，请稍后刷新页面。"));
  } catch (error) { $("#delete-error").textContent = error.message; $("#delete-error").hidden = false; }
  finally { deleting = false; for (const selector of ["#confirm-delete", "#cancel-delete", "#close-delete"]) $(selector).disabled = false; }
});
$("#retention-days").addEventListener("change", () => resetRetentionPreview());
$("#retention-preview").addEventListener("click", async () => {
  const version = ++retentionRequest;
  const button = $("#retention-preview"); button.disabled = true;
  try {
    const preview = await request("/api/history/retention?" + new URLSearchParams({ days: $("#retention-days").value }));
    if (version !== retentionRequest) return;
    retentionPreview = preview;
    $("#retention-summary").textContent = "截止 " + time(preview.cutoff) + " 之前，符合条件的已结束记录 " + preview.eligibleCount + " 条。";
    $("#retention-open").hidden = preview.eligibleCount === 0;
  } catch (error) {
    if (version === retentionRequest) { resetRetentionPreview("预览失败，请稍后重试。"); notice(error.message, true); }
  } finally { if (version === retentionRequest) button.disabled = false; }
});
$("#retention-open").addEventListener("click", () => {
  if (!retentionPreview || retentionBusy) return;
  $("#retention-detail").textContent = "将删除 " + retentionPreview.eligibleCount + " 条在 " + time(retentionPreview.cutoff) + " 之前创建的已结束记录。";
  $("#retention-confirm-text").value = "";
  $("#retention-error").hidden = true;
  $("#retention-dialog").showModal(); $("#retention-confirm-text").focus();
});
function closeRetention() { if (!retentionBusy) $("#retention-dialog").close(); }
$("#retention-dialog").addEventListener("cancel", (event) => { if (retentionBusy) event.preventDefault(); });
for (const selector of ["#retention-close", "#retention-cancel"]) $(selector).addEventListener("click", closeRetention);
$("#retention-form").addEventListener("submit", async (event) => {
  event.preventDefault(); if (retentionBusy || !retentionPreview) return;
  if ($("#retention-confirm-text").value.trim() !== "删除旧记录") {
    $("#retention-error").textContent = "请输入“删除旧记录”后再确认。"; $("#retention-error").hidden = false; return;
  }
  retentionBusy = true;
  for (const selector of ["#retention-confirm", "#retention-cancel", "#retention-close"]) $(selector).disabled = true;
  try {
    const preview = retentionPreview;
    const result = await request("/api/history/retention", { method: "POST",
      headers: { "Content-Type": "application/json", "X-Triage-History": "1" },
      body: JSON.stringify({ cutoff: preview.cutoff, expectedCount: preview.eligibleCount, confirmation: "删除旧记录" }) });
    $("#retention-dialog").close();
    resetRetentionPreview("已清理 " + result.deletedCount + " 条旧记录。需要继续清理时请重新预览。");
    notice("已清理 " + result.deletedCount + " 条旧记录。");
    previousCursors = []; historyCursor = null; await loadHistory(null);
    loadServices().catch(() => notice("记录已清理，服务筛选列表暂未刷新，请稍后刷新页面。"));
  } catch (error) { $("#retention-error").textContent = error.message; $("#retention-error").hidden = false; }
  finally { retentionBusy = false; for (const selector of ["#retention-confirm", "#retention-cancel", "#retention-close"]) $(selector).disabled = false; }
});
$("#history-filter").addEventListener("submit", (event) => {
  event.preventDefault();
  try { filters = selectedFilters(); previousCursors = []; historyCursor = null; notice(""); loadHistory(); }
  catch (error) { notice(error.message, true); }
});
$("#clear-filters").addEventListener("click", () => { $("#history-filter").reset(); loadEndpoints("filter", false); $("#history-filter").requestSubmit(); });
for (const prefix of ["filter", "statistics"]) $("#" + prefix + "-service").addEventListener("change", () => loadEndpoints(prefix, false));
$("#history-next").addEventListener("click", () => { if (historyPage?.nextCursor) loadHistory(historyPage.nextCursor, "next"); });
$("#history-previous").addEventListener("click", () => { if (previousCursors.length) loadHistory(previousCursors.at(-1), "previous"); });
function renderStatistics(data, scope) {
  $("#statistics-scope").textContent = "统计范围：" + scope;
  $("#statistics-range").textContent = time(data.from) + " — " + time(data.until);
  $("#statistics-summary").replaceChildren(...[["排查记录", data.total], ["工具调用", data.toolCalls], ["模型调用轮次", data.modelCalls]].map(([label, value]) => {
    const card = node("div", null, "summary-card"); card.append(node("h3", label), node("strong", Number(value).toLocaleString("zh-CN"))); return card;
  }));
  $("#statistics-statuses").replaceChildren(...["SUCCEEDED", "INSUFFICIENT_EVIDENCE", "FAILED", "CANCELLED", "RUNNING", "QUEUED"].map((status) => {
    const row = node("div", null, "status-bar-row"); const track = node("div", null, "status-bar-track");
    const fill = node("div", null, "status-bar-fill " + status); const count = Number(data.statuses[status] || 0);
    fill.style.width = (data.total ? count / data.total * 100 : 0) + "%"; track.append(fill);
    row.append(node("span", statuses[status]), track, node("span", String(count))); return row;
  }));
  const usage = data.usage.knownUsage;
  $("#statistics-tokens").replaceChildren(...[["输入 Token", usage?.inputTokens], ["输出 Token", usage?.outputTokens], ["合计 Token", usage?.totalTokens]].map(([label, value]) => {
    const column = node("div"); column.append(node("div", label, "token-label"), node("div", value == null ? "—" : Number(value).toLocaleString("zh-CN"), "token-value")); return column;
  }));
  $("#statistics-coverage").textContent = data.usage.calledRuns === 0 ? "本时段没有模型调用。" :
    "有模型调用的 " + data.usage.calledRuns + " 条记录中：完整 " + data.usage.completeRuns + " 条，部分已知 " + data.usage.partialRuns + " 条，未返回用量 " + data.usage.missingRuns + " 条。以上仅汇总已知用量。";
  $("#statistics-duration-count").textContent = "有终态时间的 " + data.durations.samples + " 条记录";
  $("#statistics-durations").replaceChildren(...[["平均耗时", data.durations.averageMillis], ["p95 耗时", data.durations.p95Millis], ["最长耗时", data.durations.maximumMillis]].map(([label, value]) => {
    const card = node("div", null, "summary-card"); card.append(node("h3", label), node("strong", duration(value))); return card;
  }));
}
async function loadStatistics() {
  const version = ++statisticsRequest;
  const params = new URLSearchParams({ days: $("#statistics-days").value });
  if ($("#statistics-service").value) params.set("service", $("#statistics-service").value);
  if ($("#statistics-endpoint").value) params.set("endpointId", $("#statistics-endpoint").value);
  const scope = $("#statistics-service").selectedOptions[0].textContent + " · " + $("#statistics-endpoint").selectedOptions[0].textContent;
  try { const data = await request("/api/statistics?" + params); if (version === statisticsRequest) renderStatistics(data, scope); }
  catch (error) { if (version === statisticsRequest) notice(error.message, true); }
}
function selectTab(value) {
  activeTab = ["history", "statistics", "services"].includes(value) ? value : "history";
  const title = { history: "历史记录", statistics: "运行统计", services: "服务状态" }[activeTab];
  $("#workspace-title").textContent = title; $("#page-title").textContent = title; document.title = title + " · Agent Triage";
  $("#page-description").textContent = { history: "按服务、接口、执行状态和日期查找已保存的排查。", statistics: "按服务和接口查看排查结果、耗时与已知用量。", services: "核对服务连通性、观测协议和当前窗口请求。" }[activeTab];
  document.querySelectorAll("[data-workspace-tab]").forEach((button) => button.setAttribute("aria-selected", String(button.dataset.workspaceTab === activeTab)));
  $("#history-panel").hidden = activeTab !== "history"; $("#statistics-panel").hidden = activeTab !== "statistics";
  $("#services-panel").hidden = activeTab !== "services";
  notice(""); if (activeTab === "statistics") loadStatistics(); if (activeTab === "services") loadServiceChecks();
}
async function loadServiceChecks() {
  const version = ++serviceRequest;
  try {
    const data = await request("/api/services/status"); if (version !== serviceRequest) return;
    const stateLabels = { AVAILABLE: "可读取", EMPTY: "暂无请求", UNAVAILABLE: "不可用", SYNTHETIC: "合成演示" };
    const protocols = { LAB: "订单样例", OBSERVATIONS_V1: "HTTP V1", DATABASE_V2: "数据库 V2", OBSERVATIONS_V3: "HTTP 接口 V3", HTTP_REQUESTS_V4: "入站 HTTP V4" };
    $("#service-checks").replaceChildren(...data.map((check) => {
      const card = node("article", null, "management-card service-check"); const heading = node("div", null, "management-heading");
      const identity = node("div"); identity.append(node("h2", check.service.name), node("p", check.service.id, "service-check-id"));
      heading.append(identity, node("span", stateLabels[check.state] || check.state, "status " + (check.state === "UNAVAILABLE" ? "failed" : check.state === "AVAILABLE" ? "success" : "neutral")));
      const values = node("dl");
      for (const [label, value] of [["数据来源", check.source === "LIVE" ? "实际观测" : "合成演示"], ["协议", protocols[check.service.protocol]],
        ["允许窗口", "1—" + check.service.maxWindowMinutes + " 分钟"], ["检查窗口", check.windowMinutes == null ? "—" : "最近 " + check.windowMinutes + " 分钟"],
        ["窗口请求", check.requestCount == null ? "—" : check.requestCount + " 次"], ["检查耗时", duration(check.responseMillis)]]) {
        const pair = node("div"); pair.append(node("dt", label), node("dd", value)); values.append(pair);
      }
      card.append(heading, values, node("p", check.message, "service-check-message"), node("p", "检查于 " + time(check.checkedAt), "service-check-time"));
      return card;
    }));
  } catch (error) { if (version === serviceRequest) notice(error.message, true); }
}
document.querySelectorAll("[data-workspace-tab]").forEach((button) => button.addEventListener("click", () => { window.location.hash = button.dataset.workspaceTab; }));
window.addEventListener("hashchange", () => selectTab(window.location.hash.slice(1)));
$("#statistics-filter").addEventListener("submit", (event) => { event.preventDefault(); notice(""); loadStatistics(); });
$("#refresh-workspace").addEventListener("click", () => {
  if (activeTab === "statistics") loadStatistics(); else if (activeTab === "services") loadServiceChecks(); else { previousCursors = []; historyCursor = null; loadHistory(null); }
  loadServices().catch((error) => notice(error.message, true));
});
$("#history-filter").reset(); $("#statistics-filter").reset();
loadServices().catch((error) => notice(error.message, true));
loadHistory(null);
selectTab(window.location.hash.slice(1));
