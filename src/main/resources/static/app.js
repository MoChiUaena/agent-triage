"use strict";

const $ = (selector) => document.querySelector(selector);
const statusText = {
  QUEUED: "排队中",
  RUNNING: "排查中",
  SUCCEEDED: "已完成",
  INSUFFICIENT_EVIDENCE: "证据不足",
  FAILED: "执行失败",
  CANCELLED: "已取消",
};
const tools = {
  search_runbooks: { label: "排障文档", kind: "文档", icon: "file" },
  read_service_metrics: { label: "服务指标", kind: "指标", icon: "chart" },
  query_error_logs: { label: "错误日志", kind: "日志", icon: "logs" },
};
let activeRun = null;
let stream = null;
let selectionVersion = 0;
let historyVersion = 0;
let historyRuns = [];
let displayedEvents = new Set();
let submitting = false;
let runtimeConfig = null;
let configVersion = 0;
let labBusy = false;
let cancellingRun = null;
let endpointVersion = 0;
let endpointLoading = false;
let endpointChoices = [];
let endpointWindow = null;

async function loadEndpoints(preserve = false) {
  const version = ++endpointVersion;
  const config = runtimeConfig;
  $("#endpoint-controls").hidden = !config?.endpointSupported;
  if (!config?.endpointSupported) { endpointLoading = false; endpointChoices = []; $("#endpoint").value = ""; return; }
  endpointLoading = true; $("#submit-button").disabled = true; $("#refresh-endpoints").disabled = true;
  const selected = preserve ? $("#endpoint").value : "";
  const minutes = Number($("#window").value);
  $("#endpoint-note").textContent = "正在读取本窗口实际匹配到的接口…";
  try {
    const value = await request("/api/services/" + encodeURIComponent(config.service) + "/endpoints?windowMinutes=" + minutes);
    if (version !== endpointVersion || runtimeConfig !== config) return;
    endpointChoices = value.endpoints || []; endpointWindow = minutes;
    const all = element("option", "", "全部接口"); all.value = "";
    $("#endpoint").replaceChildren(all, ...endpointChoices.map(item => {
      const option = element("option", "", item.endpoint.httpMethod + " " + item.endpoint.routeTemplate + " · " + item.requestCount + " 次请求 · " + item.timeoutCount + " 次超时");
      option.value = item.endpoint.id; return option;
    }));
    $("#endpoint").value = endpointChoices.some(item => item.endpoint.id === selected) ? selected : "";
    $("#endpoint-note").textContent = endpointChoices.length ? "按 MVC 匹配的接口分别排查。处理方法匹配不等于完整执行轨迹。"
      + (value.otherEndpointRequestCount ? " 部分接口超出列表上限。" : "") + (value.unattributedRequestCount ? " 有 " + value.unattributedRequestCount + " 次请求未关联处理方法。" : "") : "本窗口没有可选接口，请先访问业务接口。";
  } catch (error) {
    if (version !== endpointVersion || runtimeConfig !== config) return;
    endpointChoices = []; endpointWindow = null; $("#endpoint").replaceChildren();
    $("#endpoint-note").textContent = error.message;
  } finally {
    if (version === endpointVersion) {
      endpointLoading = false; $("#refresh-endpoints").disabled = false;
      $("#submit-button").disabled = submitting || labBusy || !runtimeConfig?.observationAvailable || endpointWindow === null;
    }
  }
}

function configureSource(config) {
  const project = config.sourceProject;
  $("#include-source").checked = false;
  $("#include-source").disabled = !project?.available;
  $("#source-binding-note").textContent = project?.available ? "本次检索源码：" + project.name + " · 索引 v" + project.revision : "所选服务还没有绑定源码";
  $("#source-model-option").hidden = !project?.available;
  $("#allow-source-model").checked = false;
  $("#allow-source-model").disabled = true;
  $("#source-model-label").textContent = project?.modelSharing ? "本次允许模型查看候选代码 · " + config.model + "（可能产生费用）" : "模型读取未开启，可在项目源码页确认授权";
}

function renderSource(run) {
  const analysis = run.sourceAnalysis;
  $("#code-count").textContent = String(analysis?.excerpts?.length || 0);
  let message = analysis?.message;
  if (analysis && terminal(run) && ["QUEUED", "LOCAL_PENDING", "MODEL_PENDING"].includes(analysis.state))
    message = "本次源码检查未完成。" + (analysis.modelUsed ? "已按本次授权请求模型读取候选代码。" : "尚未向模型发送源码。");
  $("#code-note").textContent = analysis ? analysis.projectName + " · 索引 v" + analysis.revision + "。" + message : "本次没有开启源码检索。";
  sourceView.render($("#code-excerpts"), analysis?.excerpts || []);
  sourceView.graph($("#code-graph"), analysis?.graph, { evidenceLink: id => citation(run, id) });
}

function serviceInfo(run) {
  return run.serviceInfo || { id: run.service, name: run.service === "order-service" ? "订单服务" : run.service,
    downstreamId: "inventory-service", downstreamName: "库存服务" };
}
function scenarioText(scenario) {
  return scenario === "OBSERVED" ? "实际观测" : scenario === "NORMAL" ? "正常对照" : "下游超时";
}

async function loadConfiguration(serviceId = $("#service").value) {
  const version = ++configVersion;
  endpointVersion++; endpointLoading = false; endpointWindow = null;
  runtimeConfig = null;
  $("#submit-button").disabled = true;
  document.querySelectorAll("[data-lab-scenario]").forEach(button => button.disabled = true);
  $("#config-retry").disabled = true;
  try {
    const config = await request("/api/config" + (serviceId ? "?service=" + encodeURIComponent(serviceId) : ""));
    if (version !== configVersion) return;
    configureSource(config);
    if (!["DEMO", "MODEL"].includes(config.mode))
      throw new Error("运行模式无效。");
    runtimeConfig = config;
    $("#service").replaceChildren(...config.services.map(target => {
      const option = element("option", "", target.name + " · " + target.id);
      option.value = target.id;
      return option;
    }));
    $("#service").value = config.service;
    $("#workspace-service").textContent = config.service;
    $("#question").placeholder = "描述问题，例如：" + config.serviceInfo.name + "请求为什么变慢了？";
    const defaultQuestions = ["服务请求为什么变慢了？", ...config.services.map(target => target.name + "请求为什么变慢了？")];
    if (defaultQuestions.includes($("#question").value.trim()))
      $("#question").value = config.serviceInfo.name + "请求为什么变慢了？";
    const previousWindow = Number($("#window").value);
    const windows = [5, 15, 60].filter(value => value <= config.maxWindowMinutes);
    if (!windows.includes(config.maxWindowMinutes)) windows.push(config.maxWindowMinutes);
    $("#window").replaceChildren(...windows.sort((a, b) => a - b).map(value => {
      const option = element("option", "", "最近 " + value + " 分钟");
      option.value = String(value);
      return option;
    }));
    $("#window").value = String(windows.includes(previousWindow) ? previousWindow : Math.min(15, config.maxWindowMinutes));
    const live = config.observationSource === "LIVE";
    $("#live-lab").hidden = !config.labEnabled;
    configureLab(config);
    $("#scenario").disabled = live;
    $("#scenario-label").textContent = live ? "当前场景" : "场景";
    if (live && config.scenario) $("#scenario").value = config.scenario;
    $("#mode-label").textContent =
      config.mode === "MODEL" ? "模型模式" : live ? "实测演示" : "演示模式";
    if (live) {
      $("#mode-description").textContent = !config.observationAvailable
        ? config.observationMessage || "所选服务的观测接口未连接，请检查服务是否运行。"
        : config.mode === "MODEL"
          ? "读取 " + config.serviceInfo.name + " 的实际请求数据，并发送给模型 " +
            config.model +
            "。"
          : "读取所选服务的实际请求数据，按固定规则生成结论。";
      if (config.observationAvailable && config.observationRequestCount === 0)
        $("#mode-description").textContent = config.observationMessage;
      $("#composer-note").textContent = config.observationAvailable
        ? config.labEnabled ? "先生成请求，再排查实际观测" : "读取所选服务的实际观测"
        : "所选服务的观测接口未连接";
    } else {
      $("#mode-description").textContent =
        config.mode === "MODEL"
          ? "问题和所选合成观测会发送给模型 " +
            config.model +
            "。费用以配置的模型服务为准。"
          : "使用合成日志和指标，按固定规则生成结果，不调用模型服务。";
      $("#composer-note").textContent =
        config.mode === "MODEL"
          ? config.model + " · 合成观测数据"
          : "仅查询所选服务的演示数据";
    }
    $("#context-note").textContent = live
      ? "指标和错误事件来自 " + config.serviceInfo.name + " 的实际请求。"
      : "演示数据仅供本地测试。";
    $("#config-retry").hidden = !live || config.observationAvailable;
    document.querySelectorAll("[data-lab-scenario]").forEach((button) => {
      button.disabled = !config.observationAvailable;
    });
    if (live && !config.observationAvailable)
      $("#live-lab-status").textContent = "样例服务未连接";
    $("#submit-button").disabled =
      submitting || labBusy || (live && !config.observationAvailable);
    $("#form-error").hidden = true;
    await loadEndpoints();
  } catch (error) {
    if (version !== configVersion) return;
    runtimeConfig = null;
    const needsProvider = error.message.includes("环境变量中的模型配置不可用");
    $("#mode-label").textContent = needsProvider ? "需要模型配置" : "连接失败";
    $("#composer-note").textContent = needsProvider
      ? "请先添加模型服务"
      : "无法读取服务配置";
    $("#submit-button").disabled = true;
    $("#config-retry").hidden = false;
    showError(
      needsProvider
        ? "请从左侧打开“模型设置”，添加并启用模型服务。"
        : "无法读取运行配置，请确认服务已启动后重新连接。",
    );
  } finally {
    if (version === configVersion) $("#config-retry").disabled = false;
  }
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function configureLab(config) {
  const database = config.protocol === "DATABASE_V2";
  $("#live-lab-title").textContent = database ? "数据库连接池样例" : "本地订单样例";
  $("#live-lab").setAttribute("aria-label", database ? "数据库连接池样例" : "本地订单样例");
  $("#live-lab-description").textContent = database
    ? "请求会实际获取数据库连接并执行查询。可以触发连接等待，再释放连接验证恢复。"
    : "订单接口会实际调用库存接口。先生成一组请求，再查看 Agent 读取的指标和错误事件。";
  $("#live-lab-status").textContent = "等待生成请求";
  const choices = database ? [["NORMAL", "生成正常请求"], ["DB_POOL_EXHAUSTED", "触发连接池耗尽"], ["DB_POOL_RECOVERY", "释放连接并验证恢复"]]
    : [["NORMAL", "生成正常请求"], ["DOWNSTREAM_TIMEOUT", "触发库存超时"]];
  $(".live-lab-actions").replaceChildren(...choices.map(([scenario, text], index) => {
    const button = element("button", index === 1 ? "primary" : "secondary", text);
    button.type = "button"; button.dataset.labScenario = scenario;
    button.addEventListener("click", () => generateLabTraffic(scenario));
    return button;
  }));
}

function icon(name, className = "") {
  const svg = document.createElementNS("http://www.w3.org/2000/svg", "svg");
  svg.setAttribute("class", "icon " + className);
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
  use.setAttribute("href", "/icons.svg#" + name);
  svg.append(use);
  return svg;
}

function terminal(run) {
  return !["QUEUED", "RUNNING"].includes(run.status);
}

function statusClass(status) {
  return status === "FAILED"
    ? "failed"
    : status === "SUCCEEDED"
      ? "success"
      : ["QUEUED", "RUNNING"].includes(status)
        ? "running"
        : "neutral";
}

function timeText(value) {
  return new Date(value).toLocaleTimeString("zh-CN", { hour12: false });
}

function dateText(value) {
  return new Date(value).toLocaleString("zh-CN", {
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hour12: false,
  });
}

async function request(path, options) {
  const response = await fetch(path, options);
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || "请求失败 (" + response.status + ")");
  }
  return response.json();
}

async function generateLabTraffic(scenario) {
  if (submitting || labBusy || !runtimeConfig?.labEnabled || !runtimeConfig.observationAvailable) return;
  labBusy = true;
  $("#service").disabled = true;
  $("#submit-button").disabled = true;
  const buttons = document.querySelectorAll("[data-lab-scenario]");
  buttons.forEach((button) => (button.disabled = true));
  $("#live-lab-status").textContent = "正在产生实际请求…";
  try {
    const result = await request("/api/live-lab/traffic", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Triage-Lab": "1" },
      body: JSON.stringify({ scenario, count: 5, service: runtimeConfig.service }),
    });
    runtimeConfig.scenario = result.scenario;
    $("#scenario").value = result.scenario;
    $("#live-lab-status").textContent = runtimeConfig.protocol === "DATABASE_V2"
      ? (result.recoveryVerified ? "已验证连接释放后的恢复。" : "") + "已处理 " + result.requestCount + " 个数据库请求，获取连接超时 "
        + result.acquisitionTimeoutCount + " 次，SQL 查询失败 " + result.queryErrorCount + " 次。现在可以开始排查。"
      :
      "已处理 " +
      result.requestCount +
      " 个订单请求，库存超时 " +
      result.timeoutCount +
      " 次。现在可以开始排查。";
    $("#form-error").hidden = true;
  } catch (error) {
    $("#live-lab-status").textContent = "请求生成失败";
    showError(error.message);
  } finally {
    labBusy = false;
    $("#service").disabled = false;
    $("#submit-button").disabled = submitting || !runtimeConfig?.observationAvailable;
    buttons.forEach((button) => (button.disabled = false));
  }
}

function showError(message) {
  $("#form-error").textContent = message;
  $("#form-error").hidden = false;
}

function setStatus(status) {
  $("#run-status").textContent = statusText[status] || status;
  $("#run-status").className = "status " + statusClass(status);
}

function closeStream() {
  if (stream) stream.close();
  stream = null;
}

function setSidebar(open) {
  const mobile = window.matchMedia("(max-width: 720px)").matches;
  open = open && mobile;
  document.body.classList.toggle("sidebar-open", open);
  $("#sidebar-backdrop").hidden = !open;
  $("#sidebar-toggle").setAttribute("aria-expanded", String(open));
  $("#sidebar").inert = mobile && !open;
  $(".app-shell").inert = open;
  if (open) $("#new-run").focus();
}

function showTab(name, focus = false) {
  document.querySelectorAll("[data-tab]").forEach((button) => {
    const selected = button.dataset.tab === name;
    button.setAttribute("aria-selected", String(selected));
    button.tabIndex = selected ? 0 : -1;
    $("#" + button.getAttribute("aria-controls")).hidden = !selected;
    if (selected && focus) button.focus();
  });
}

function metadata(rows) {
  const list = $("#run-meta");
  list.replaceChildren();
  rows.forEach(([label, value]) => {
    const row = element("div");
    row.append(element("dt", "", label), element("dd", "", value));
    if (label === "模型调用") row.querySelector("dd").id = "model-call-count";
    list.append(row);
  });
}

function resetTools() {
  document.querySelectorAll("[data-tool]").forEach((node) => {
    node.className = "tool-row";
    node.querySelector(".tool-state").textContent = "待查询";
  });
  $("#source-count").textContent = "0 / 3";
  $("#source-note").textContent = "尚未调用工具";
}

function emptyResult(heading, message) {
  const empty = element("div", "empty-state");
  const mark = element("span", "empty-icon");
  mark.append(icon("search"));
  empty.append(mark, element("h3", "", heading), element("p", "", message));
  $("#diagnosis").replaceChildren(empty);
}

function resetResult() {
  $("#code-count").textContent = "0";
  $("#code-note").textContent = "勾选本次源码检索后，匹配的代码位置会显示在这里。";
  $("#code-excerpts").replaceChildren();
  $("#code-graph").replaceChildren();
  $("#cancel-run").hidden = true;
  $("#cancel-note").hidden = true;
  $("#model-usage").hidden = true;
  activeRun = null;
  displayedEvents = new Set();
  $("#events").replaceChildren(
    element("li", "panel-empty", "开始排查后可查看执行记录。"),
  );
  $("#events-count").textContent = "0";
  $("#evidence-count").textContent = "0";
  $("#result-question").hidden = true;
  $("#metrics").hidden = true;
  $("#metrics").replaceChildren();
  $("#evidence-list").replaceChildren(
    element("p", "panel-empty", "本次排查的文档、指标和日志会显示在这里。"),
  );
  resetTools();
  metadata([
    ["服务", "—"],
    ["时间窗口", "—"],
    ["场景", "—"],
    ["执行时间", "—"],
  ]);
  setStatus("未开始");
  emptyResult("还没有排查结果", "输入问题并开始排查，或从左侧打开已有记录。");
  showTab("overview");
}

function newRun() {
  window.history.replaceState(null, "", window.location.pathname);
  selectionVersion++;
  closeStream();
  $("#form-error").hidden = true;
  resetResult();
  $("#question").value = (runtimeConfig?.serviceInfo.name || "服务") + "请求为什么变慢了？";
  $("#window").value = String(Math.min(15, runtimeConfig?.maxWindowMinutes || 60));
  $("#scenario").value =
    runtimeConfig?.observationSource === "LIVE"
      ? runtimeConfig.scenario || "NORMAL"
      : "DOWNSTREAM_TIMEOUT";
  $("#history-search").value = "";
  renderHistory();
  setSidebar(false);
  $("#question").focus();
}

function eventText(event) {
  const label = tools[event.tool]?.label || event.tool;
  if (event.type === "TOOL_STARTED") return "查询" + label;
  if (event.type === "TOOL_COMPLETED")
    return label + "返回 " + event.evidenceIds.length + " 条证据";
  if (event.type === "TOOL_FAILED") return label + "查询失败";
  if (event.type === "RUN_QUEUED") return "任务已创建";
  return event.message;
}

function addEvent(event) {
  if (displayedEvents.has(event.sequence)) return;
  if (!displayedEvents.size) $("#events").replaceChildren();
  displayedEvents.add(event.sequence);
  const row = element(
    "li",
    "event-row" + (event.type.endsWith("FAILED") ? " event-failed" : ""),
  );
  row.dataset.eventType = event.type;
  const time = element("time", "", timeText(event.timestamp));
  time.dateTime = event.timestamp;
  row.append(
    time,
    element("span", "event-marker"),
    element("p", "", eventText(event)),
  );
  $("#events").append(row);
  if (event.type === "MODEL_STARTED" && $("#model-call-count"))
    $("#model-call-count").textContent =
      document.querySelectorAll('[data-event-type="MODEL_STARTED"]').length +
      " 轮";
  $("#events-count").textContent = String(displayedEvents.size);

  const tool = [...document.querySelectorAll("[data-tool]")].find(
    (node) => node.dataset.tool === event.tool,
  );
  if (
    tool &&
    ["TOOL_STARTED", "TOOL_COMPLETED", "TOOL_FAILED"].includes(event.type)
  ) {
    const state =
      event.type === "TOOL_COMPLETED"
        ? "done"
        : event.type === "TOOL_FAILED"
          ? "failed"
          : "active";
    tool.className = "tool-row " + state;
    tool.querySelector(".tool-state").textContent =
      state === "done" ? "已查询" : state === "failed" ? "失败" : "查询中";
    $("#source-count").textContent =
      document.querySelectorAll(".tool-row.done").length + " / 3";
  }
  $("#source-note").textContent = eventText(event);
  if (event.type === "RUN_STARTED") setStatus("RUNNING");
}

function renderMetrics(run) {
  const evidence = run.evidence.find(
    (item) => item.source === "read_service_metrics",
  );
  $("#metrics").replaceChildren();
  $("#metrics").hidden = !evidence;
  if (!evidence) return;
  const data = evidence.data;
  if (data.observationType === "DATABASE_POOL") {
    const pool = data.databasePool;
    const values = [["请求 p95", data.requestCount ? data.requestP95Ms : null, "ms", "最近 " + run.windowMinutes + " 分钟"],
      ["获取连接 p95", data.requestCount ? pool.acquisitionP95Ms : null, "ms", "与 SQL 执行阶段分开统计"],
      ["SQL 查询 p95", pool.queryCount ? pool.queryP95Ms : null, "ms", pool.queryCount ? "实际查询数 " + pool.queryCount : "本窗口未执行 SQL 查询"],
      ["连接占用峰值", pool.peakActiveConnections, "", "池上限 " + pool.maximumConnections],
      ["等待线程峰值", pool.peakPendingThreads, "", "窗口内采样峰值"],
      ["获取连接超时", pool.acquisitionTimeoutCount, "次", "窗口内请求数 " + data.requestCount]];
    for (const [label, value, unit, caption] of values) {
      const card = element("div", "metric" + (pool.acquisitionTimeoutCount > 0 && label !== "SQL 查询 p95" ? " warning" : ""));
      const number = element("div", "metric-value", value == null ? "—" : Number(value).toLocaleString("zh-CN"));
      if (value != null && unit) number.append(element("small", "", unit));
      card.append(element("div", "metric-label", label), number, element("div", "metric-caption", caption));
      $("#metrics").append(card);
    }
    return;
  }
  const hasRequests = data.requestCount > 0;
  const labels = serviceInfo(run);
  const requestP95 = data.requestP95Ms ?? data.orderP95Ms;
  const baseline = data.baselineRequestP95Ms ?? data.baselineOrderP95Ms;
  const values = [
    [
      labels.name + "请求 p95",
      hasRequests ? requestP95 : null,
      "ms",
      baseline == null ? "正常基线未采集" : "基线 " + baseline + " ms",
      baseline != null && requestP95 > baseline,
    ],
    [
      "下游调用 p95",
      hasRequests ? data.downstreamP95Ms : null,
      "ms",
      labels.downstreamId,
      false,
    ],
    [
      "下游超时率",
      hasRequests
        ? Number((data.downstreamTimeoutRate * 100).toFixed(2))
        : null,
      "%",
      "最近 " + run.windowMinutes + " 分钟",
      data.downstreamTimeoutRate > 0,
    ],
  ];
  for (const [label, value, unit, caption, warning] of values) {
    const item = element("div", "metric" + (warning ? " warning" : ""));
    const number = element(
      "div",
      "metric-value",
      value == null ? "—" : Number(value).toLocaleString("zh-CN"),
    );
    if (value != null) number.append(element("small", "", unit));
    item.append(
      element("div", "metric-label", label),
      number,
      element("div", "metric-caption", caption),
    );
    $("#metrics").append(item);
  }
}

function evidenceTitle(evidence) {
  return evidence.source === "read_service_metrics"
    ? evidence.data?.endpointScoped ? "接口窗口指标" : "服务窗口指标"
    : evidence.source === "query_error_logs"
      ? "近期错误日志"
      : evidence.title;
}

function citation(run, id) {
  const index = run.evidence.findIndex((item) => item.id === id);
  const evidence = run.evidence[index];
  const link = element("a", "citation");
  link.href = "#evidence-" + encodeURIComponent(id);
  link.title = id;
  link.append(
    element("span", "citation-number", "[" + (index + 1) + "]"),
    document.createTextNode(evidence ? evidenceTitle(evidence) : id),
  );
  link.addEventListener("click", (event) => {
    event.preventDefault();
    showTab("evidence");
    const target = document.getElementById("evidence-" + id);
    if (target) {
      target.open = true;
      target.querySelector("summary").focus({ preventScroll: true });
      target.scrollIntoView({ block: "nearest", behavior: "auto" });
    }
  });
  return link;
}

function renderDiagnosis(run) {
  const target = $("#diagnosis");
  target.replaceChildren();
  if (run.status === "CANCELLED") {
    emptyResult("本次排查已取消", "已采集的证据保留在本条记录中，可以继续查看。");
    if (run.mode === "MODEL") target.append(element("p", "panel-empty", "已发出的模型请求可能继续执行并计费；这里只展示已返回的用量。"));
    return;
  }
  if (run.failure) {
    const tips = failureHelp(run.failure.code);
    target.append(element("h3", "result-heading", tips[0]));
    target.append(
      element("p", "error-banner", run.failure.message),
      element(
        "p",
        "panel-empty",
        tips[1],
      ),
    );
    const actions = element("div", "failure-actions");
    const events = element("button", "secondary", "查看失败步骤"); events.type = "button"; events.addEventListener("click", () => showTab("events"));
    const retry = element("button", "secondary", "用此问题新建排查"); retry.type = "button";
    retry.addEventListener("click", async () => {
      newRun();
      if (runtimeConfig?.services.some(target => target.id === run.service)) await loadConfiguration(run.service);
      $("#question").value = run.question;
      if (run.windowMinutes <= runtimeConfig?.maxWindowMinutes) {
        const value = String(run.windowMinutes);
        if (![...$("#window").options].some(option => option.value === value)) { const option = element("option", "", "最近 " + value + " 分钟"); option.value = value; $("#window").append(option); }
        $("#window").value = value;
      }
      if (runtimeConfig?.observationSource !== "LIVE") $("#scenario").value = run.scenario;
      $("#question").focus();
    });
    actions.append(events, retry); target.append(actions, element("p", "panel-empty", "错误代码：" + run.failure.code));
    return;
  }
  if (!run.diagnosis) {
    emptyResult("正在排查", "完成后会在这里显示结果。");
    return;
  }
  for (const [title, findings, className] of [
    ["初步判断", run.diagnosis.possibleCauses, ""],
    ["观察到的情况", run.diagnosis.observations, "observations"],
  ]) {
    if (!findings.length && className) continue;
    const section = element("section", "diagnosis-section " + className);
    section.append(element("h3", "result-heading", title));
    if (!findings.length)
      section.append(element("p", "finding", "现有证据还不足以判断原因。"));
    for (const finding of findings) {
      const row = element("div", "finding", finding.text);
      const refs = element("div", "citations");
      finding.evidenceIds.forEach((id) => refs.append(citation(run, id)));
      row.append(refs);
      section.append(row);
    }
    target.append(section);
  }
  const next = element("section", "diagnosis-section");
  next.append(element("h3", "result-heading", "建议检查"));
  const steps = element("ol", "next-steps");
  run.diagnosis.nextSteps.forEach((step) =>
    steps.append(element("li", "", step)),
  );
  next.append(steps);
  const note = element("p", "uncertainty");
  note.append(
    element("strong", "", "说明"),
    document.createTextNode(run.diagnosis.uncertainty),
  );
  target.append(next, note);
}

function failureHelp(code) {
    const observationHelp = {
      OBSERVATION_UNAVAILABLE: ["服务未连接", "确认业务服务已启动，核对服务登记的端口后重新连接。"],
      OBSERVATION_TIMEOUT: ["观测接口响应超时", "检查业务服务负载和观测接口耗时，再重新排查。"],
      OBSERVATION_ENDPOINT_MISSING: ["缺少观测接口", "在业务服务中启用 Starter，或按接入说明实现只读观测接口。"],
      OBSERVATION_VERSION: ["观测接口版本不一致", "HTTP 观测使用 OBSERVATIONS_V1，数据库观测使用 DATABASE_V2；核对服务登记与组件配置。"],
      OBSERVATION_CONTRACT: ["观测数据未通过校验", "核对服务身份、窗口和字段格式，并按接入契约限制响应大小。"],
      OBSERVATION_ACCESS_DENIED: ["观测接口拒绝访问", "确认通过本机直接访问，检查服务端对观测接口的访问限制。"],
      OBSERVATION_WINDOW_LOST: ["观测窗口不完整", "缩小查询窗口，并确认组件保留时长与采样容量足够。"],
      OBSERVATION_HTTP_ERROR: ["观测接口返回错误", "检查业务服务日志及允许的窗口，再重新连接。"],
    };
    if (observationHelp[code]) return observationHelp[code];
  if (["RUN_TIMEOUT", "TOOL_TIMEOUT"].includes(code)) return ["排查超过等待时限", "检查所选服务是否可访问，确认查询窗口；必要时在本地配置中调整执行时限后重试。"];
  if (code === "MODEL_TIMEOUT") return ["模型响应超时", "到模型设置页检查响应时限和服务连接，稍后重新排查。"];
  if (code === "MODEL_HTTP_ERROR") return ["模型服务返回错误", "查看上方服务状态，核对启用的服务、Key、配额与服务可用性后重试。"];
  if (["MODEL_ERROR", "MODEL_NOT_CONFIGURED"].includes(code)) return ["模型请求未完成", "到模型设置页核对已启用的服务、地址、Key 和额度，并测试连接。"];
  if (["RUN_QUEUE_FULL", "MODEL_CAPACITY", "TOOL_CAPACITY"].includes(code)) return ["执行资源暂时已满", "等待其他任务结束，或取消不再需要的排查后重试。"];
  if (code === "SERVER_RESTARTED" || code === "RUN_INTERRUPTED") return ["排查已中断", "已采集的证据仍可查看；用原问题新建一条排查记录。"];
  if (code === "TOOL_ERROR") return ["读取观测失败", "检查所选服务的观测接口是否运行、是否符合接入契约，再重新排查。"];
  if (code.startsWith("MODEL_") || code.startsWith("INVALID_MODEL") || code === "INVALID_TOOL_ARGUMENTS") return ["模型结果未通过校验", "查看执行记录中的具体原因，核对模型配置后重试；当前回复没有保存为排障结论。"];
  return ["本次排查未完成", "查看失败步骤，检查本地配置和存储后重新排查。"];
}

function renderUsage(run) {
  const model = run.modelExecution;
  $("#model-usage").hidden = !model;
  if (!model) return;
  const known = model.knownUsage || model.usage;
  const completed = model.completedCalls ?? (model.usage ? model.calls : null);
  const reported = model.usageReportedCalls ?? (model.usage ? model.calls : null);
  const complete = terminal(run) && !!model.usage;
  $("#usage-coverage").textContent = complete ? "完整返回" : known ? "部分已知" : "尚无用量";
  $("#usage-values").replaceChildren(...[["输入 Token", known?.inputTokens], ["输出 Token", known?.outputTokens],
    [complete ? "合计 Token" : "已知 Token", known?.totalTokens], ["启动轮次", model.calls]].map(([label, value]) => {
    const cell = element("div"); cell.append(element("dt", "", label), element("dd", "", value == null ? "—" : Number(value).toLocaleString("zh-CN"))); return cell;
  }));
  $("#usage-note").textContent = model.calls === 0 ? "本次未调用模型。" :
    (completed == null ? "旧记录未保存返回轮次。" : "已返回 " + completed + "/" + model.calls + " 轮，用量已返回 " + reported + " 轮。") +
    (complete ? " 数值来自模型服务返回，费用以服务方账单为准。" : " 未返回部分不计入已知值，总用量和费用尚不能确认。");
}

async function cancelRun() {
  const run = activeRun;
  if (!run || terminal(run) || cancellingRun === run.id) return;
  const version = selectionVersion; cancellingRun = run.id;
  $("#cancel-run").disabled = true; $("#cancel-run").textContent = "正在取消…";
  try {
    const result = await request("/api/runs/" + run.id + "/cancel", { method: "POST", headers: { "X-Triage-Run": "1" } });
    if (version !== selectionVersion || activeRun?.id !== run.id) return;
    closeStream(); renderRun(result); refreshHistory();
    if (result.status !== "CANCELLED") showError("排查已结束，当前结果已保留。");
  } catch (error) { if (version === selectionVersion) showError(error.message); }
  finally {
    if (cancellingRun === run.id) cancellingRun = null;
    $("#cancel-run").disabled = false; $("#cancel-run").textContent = "取消排查";
  }
}

function renderEvidence(run) {
  const list = $("#evidence-list");
  list.replaceChildren();
  $("#evidence-count").textContent = String(run.evidence.length);
  if (!run.evidence.length) {
    list.append(
      element(
        "p",
        "panel-empty",
        terminal(run) ? "这次排查没有返回证据。" : "正在收集证据…",
      ),
    );
  }
  run.evidence.forEach((evidence, index) => {
    const details = element("details", "evidence-item");
    details.id = "evidence-" + evidence.id;
    const summary = element("summary");
    const info = tools[evidence.source] || { kind: "证据", icon: "file" };
    summary.append(
      element("span", "evidence-number", "[" + (index + 1) + "]"),
      icon(info.icon),
      element("strong", "", evidenceTitle(evidence)),
      element("span", "evidence-kind", info.kind),
      icon("chevron", "evidence-chevron"),
    );
    const body = element("div", "evidence-body");
    body.append(element("div", "evidence-id", evidence.id));
    if (evidence.source === "read_service_metrics") {
      const data = evidence.data;
      const table = element("dl", "metric-table");
      const rows = data.observationType === "DATABASE_POOL" ? [
        ["请求 p95", data.requestP95Ms + " ms"], ["获取连接 p95", data.databasePool.acquisitionP95Ms + " ms"],
        ["SQL 查询 p95", data.databasePool.queryCount ? data.databasePool.queryP95Ms + " ms" : "未执行"],
        ["实际 SQL 查询数", data.databasePool.queryCount], ["连接占用峰值 / 上限", data.databasePool.peakActiveConnections + " / " + data.databasePool.maximumConnections],
        ["等待线程峰值", data.databasePool.peakPendingThreads], ["连接池满载且有等待的采样数", data.databasePool.exhaustedSamples],
        ["获取连接超时 / 失败", data.databasePool.acquisitionTimeoutCount + " / " + data.databasePool.acquisitionErrorCount],
        ["SQL 查询失败", data.databasePool.queryErrorCount], ["窗口内请求数", data.requestCount],
        ["查询窗口", timeText(data.windowStart) + " – " + timeText(data.windowEnd)],
      ] : [
        [data.endpointScoped ? "接口请求 p95" : "服务请求 p95", (data.requestP95Ms ?? data.orderP95Ms) + " ms"],
        ["下游调用 p95", data.downstreamP95Ms + " ms"],
        [
          "下游超时率",
          Number((data.downstreamTimeoutRate * 100).toFixed(2)) + "%",
        ],
        ["窗口内请求数", data.requestCount.toLocaleString("zh-CN")],
        [
          "查询窗口",
          timeText(data.windowStart) + " – " + timeText(data.windowEnd),
        ],
      ];
      rows.forEach(([label, value]) => {
        const row = element("div");
        row.append(element("dt", "", label), element("dd", "", value));
        table.append(row);
      });
      body.append(table);
    } else if (evidence.source === "query_error_logs") {
      body.append(element("p", "", evidence.summary));
      const logs = element("ul", "log-entries");
      evidence.data.entries.forEach((entry) => {
        const row = element("li");
        const meta = element("div", "log-meta");
        meta.append(
          element("span", "log-level", entry.level),
          element("time", "", timeText(entry.timestamp)),
          element("span", "", entry.traceId),
        );
        row.append(meta, element("pre", "log-message", entry.message));
        logs.append(row);
      });
      body.append(logs);
    } else {
      const text = element("div", "runbook-copy");
      evidence.summary.split(/\n\s*\n/).forEach((paragraph) => {
        if (paragraph.startsWith("文档 ID：")) return;
        text.append(
          paragraph.startsWith("# ")
            ? element("h4", "", paragraph.slice(2))
            : element("p", "", paragraph),
        );
      });
      body.append(text);
    }
    const raw = element("details", "raw-data");
    raw.append(
      element("summary", "", "原始数据"),
      element("pre", "", JSON.stringify(evidence.data, null, 2)),
    );
    body.append(raw);
    details.append(summary, body);
    list.append(details);
  });
}

function renderRun(run) {
  if (activeRun?.id === run.id && (terminal(activeRun) && !terminal(run) || run.events.length < activeRun.events.length)) return;
  activeRun = run;
  $("#result-question").textContent = run.question;
  $("#result-question").hidden = false;
  run.events.forEach(addEvent);
  setStatus(run.status);
  $("#cancel-run").hidden = terminal(run);
  $("#cancel-run").disabled = cancellingRun === run.id;
  $("#cancel-note").hidden = terminal(run) || run.mode !== "MODEL";
  $("#cancel-note").textContent = "取消会停止后续排查；已发出的模型请求可能继续执行并计费。";
  renderUsage(run);
  renderMetrics(run);
  renderDiagnosis(run);
  renderEvidence(run);
  renderSource(run);
  const elapsed = run.finishedAt
    ? Math.max(0, new Date(run.finishedAt) - new Date(run.createdAt))
    : null;
  const info = [
    ["运行模式", run.mode === "MODEL" ? "模型" : "演示"],
    ["数据来源", run.synthetic ? "合成演示" : "实际服务请求"],
    ["服务", run.service],
    ["时间窗口", "最近 " + run.windowMinutes + " 分钟"],
    ["场景", scenarioText(run.scenario)],
    ["执行时间", dateText(run.createdAt)],
    [
      "耗时",
      elapsed === null
        ? "执行中"
        : elapsed < 1000
          ? elapsed + " ms"
          : (elapsed / 1000).toFixed(2) + " s",
    ],
    ["记录 ID", run.id.slice(0, 8)],
  ];
  if (run.endpoint) info.splice(3, 0, ["接口范围", run.endpoint.httpMethod + " " + run.endpoint.routeTemplate]);
  if (run.modelExecution) {
    const model = run.modelExecution;
    if (model.assessment)
      info.push([
        "窗口判断",
        {
          DOWNSTREAM_TIMEOUT_OBSERVED: "发现下游调用超时",
          NO_DOWNSTREAM_TIMEOUT_OBSERVED: "未发现下游调用超时",
          INSUFFICIENT_EVIDENCE: "证据不足",
          DB_POOL_EXHAUSTION_OBSERVED: "发现连接池耗尽的超时证据",
          NO_DB_POOL_EXHAUSTION_OBSERVED: "未发现连接池耗尽的超时证据",
        }[model.assessment] || "证据不足",
      ]);
    if (run.events.some((event) => event.type === "CONCLUSION_RENDERED"))
      info.push(["结论生成", "模型选证据 · 应用生成措辞"]);
    else if (
      run.events.some((event) =>
        ["SCOPE_GATE", "EVIDENCE_GATE"].includes(event.type),
      )
    )
      info.push(["结论生成", "应用证据门槛"]);
    else if (run.diagnosis) info.push(["结论生成", "旧版模型文本"]);
    if (model.requestedNextChecks && model.nextChecks)
      info.push([
        "检查建议",
        "候选 " + model.requestedNextChecks.length + " 项 · 按证据排列 " + model.nextChecks.length + " 项",
      ]);
    if (model.source)
      info.push(
        ["模型服务", model.source.displayName],
        ["配置版本", "v" + model.source.version],
      );
    info.push(
      ["模型", model.configuredModel],
      ["模型调用", model.calls + " 轮"],
      [
        "Token 用量",
        model.usage
          ? model.usage.totalTokens.toLocaleString("zh-CN")
          : model.knownUsage ? "已知 " + model.knownUsage.totalTokens.toLocaleString("zh-CN") + " · 不完整" : "未返回完整用量",
      ],
    );
    if (model.responseModel && model.responseModel !== model.configuredModel)
      info.push(["返回模型", model.responseModel]);
  }
  metadata(info);
  if (terminal(run)) {
    document.querySelectorAll("[data-tool]").forEach((node) => {
      if (node.classList.contains("active")) {
        node.className = "tool-row failed";
        node.querySelector(".tool-state").textContent = run.status === "CANCELLED" ? "已停止" : "未完成";
      } else if (node.className === "tool-row")
        node.querySelector(".tool-state").textContent = "未调用";
    });
    $("#source-note").textContent =
      run.toolCalls + " 次调用 · " + run.evidence.length + " 条证据";
  }
  renderHistory();
}

function connect(run, version) {
  if (terminal(run)) return;
  const source = new EventSource("/api/runs/" + run.id + "/events");
  stream = source;
  let reconnecting = false;
  source.onopen = () => {
    if (version === selectionVersion && reconnecting) {
      $("#source-note").textContent = "进度连接已恢复";
      reconnecting = false;
    }
  };
  source.addEventListener("progress", (event) => {
    if (version !== selectionVersion || activeRun?.id === run.id && terminal(activeRun)) return;
    const progress = JSON.parse(event.data); addEvent(progress);
    if (["MODEL_STARTED", "MODEL_COMPLETED", "MODEL_FAILED", "TOOL_COMPLETED"].includes(progress.type)) refreshSnapshot();
  });
  let refreshing = false, refreshAgain = false;
  async function refreshSnapshot() {
    if (refreshing) { refreshAgain = true; return; }
    refreshing = true;
    try {
      do {
        refreshAgain = false;
        const latest = await request("/api/runs/" + run.id);
        if (version !== selectionVersion || activeRun?.id !== run.id || terminal(activeRun)) return;
        renderRun(latest);
        if (terminal(latest)) { source.close(); refreshHistory(); return; }
      } while (refreshAgain);
    } catch (_) { /* SSE reconnect also refreshes the persisted snapshot. */ }
    finally { refreshing = false; }
  }
  source.addEventListener("complete", (event) => {
    source.close();
    if (version !== selectionVersion) return;
    renderRun(JSON.parse(event.data));
    refreshHistory();
  });
  source.onerror = async () => {
    if (version !== selectionVersion) return;
    reconnecting = true;
    try {
      const latest = await request("/api/runs/" + run.id);
      if (version !== selectionVersion) return;
      if (activeRun?.id === run.id && terminal(activeRun)) return;
      renderRun(latest);
      if (terminal(latest)) source.close();
      else $("#source-note").textContent = "连接中断，正在重新连接…";
      refreshHistory();
    } catch (error) {
      if (
        version === selectionVersion &&
        !(activeRun?.id === run.id && terminal(activeRun))
      )
        $("#source-note").textContent = "连接中断，正在重新连接…";
    }
  };
}

async function selectRun(id) {
  const version = ++selectionVersion;
  closeStream();
  $("#form-error").hidden = true;
  setSidebar(false);
  try {
    const run = await request("/api/runs/" + id);
    if (version !== selectionVersion) return;
    resetResult();
    $("#question").value = run.question;
    const registered = runtimeConfig?.services.some(target => target.id === run.service);
    if (registered) await loadConfiguration(run.service);
    if (version !== selectionVersion) return;
    const windowValue = String(run.windowMinutes);
    if (
      registered && run.windowMinutes <= runtimeConfig?.maxWindowMinutes &&
      ![...$("#window").options].some((option) => option.value === windowValue)
    ) {
      const option = element("option", "", "最近 " + windowValue + " 分钟");
      option.value = windowValue;
      $("#window").append(option);
    }
    if (registered && run.windowMinutes <= runtimeConfig?.maxWindowMinutes) $("#window").value = windowValue;
    if (runtimeConfig?.observationSource !== "LIVE") $("#scenario").value = run.scenario;
    renderRun(run);
    connect(run, version);
  } catch (error) {
    if (version === selectionVersion) showError(error.message);
  }
}

function renderHistory() {
  const query = $("#history-search").value.trim().toLocaleLowerCase();
  const runs = historyRuns.filter((run) =>
    (run.question + " " + (run.service || "order-service") + " " + (run.serviceInfo?.name || "")).toLocaleLowerCase().includes(query),
  );
  const target = $("#history");
  target.replaceChildren();
  if (!runs.length) {
    target.append(
      element(
        "p",
        "sidebar-empty",
        query ? "没有匹配的记录" : "还没有执行记录",
      ),
    );
    return;
  }
  let previousDate = "";
  for (const run of runs) {
    const date = new Date(run.createdAt);
    const today = date.toDateString() === new Date().toDateString();
    const label = today ? "今天" : date.toLocaleDateString("zh-CN");
    if (label !== previousDate) {
      target.append(element("h3", "history-group", label));
      previousDate = label;
    }
    const selected = run.id === activeRun?.id;
    const button = element(
      "button",
      "history-item" + (selected ? " selected" : ""),
    );
    button.type = "button";
    button.dataset.id = run.id;
    button.setAttribute("aria-pressed", String(selected));
    button.title = run.question + " · " + statusText[run.status];
    const detail = element("small");
    const time = element("time", "", timeText(run.createdAt).slice(0, 5));
    time.dateTime = run.createdAt;
    detail.append(
      element("span", "history-dot " + statusClass(run.status)),
      element(
        "span",
        "",
        (run.mode === "MODEL" ? "模型 · " : "") +
          (run.service || "order-service") + " · " + scenarioText(run.scenario) +
          " · " +
          statusText[run.status],
      ),
      time,
    );
    button.append(element("strong", "", run.question), detail);
    button.addEventListener("click", () => selectRun(run.id));
    target.append(button);
  }
}

async function refreshHistory() {
  const version = ++historyVersion;
  try {
    const runs = await request("/api/runs?limit=20");
    if (version !== historyVersion) return;
    historyRuns = runs;
    renderHistory();
  } catch (error) {
    if (version !== historyVersion) return;
    $("#history").replaceChildren(
      element("p", "sidebar-empty", "记录加载失败，请点击刷新重试。"),
    );
  }
}

$("#investigate-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  if (submitting || labBusy || endpointLoading) return;
  if (!runtimeConfig) return showError("请先连接服务，确认运行模式。");
  if (runtimeConfig.service !== $("#service").value) return showError("服务配置正在切换，请稍后重试。");
  if (runtimeConfig.endpointSupported && endpointWindow !== Number($("#window").value)) return showError("请先刷新所选时间窗口的接口列表。");
  const question = $("#question").value.trim();
  if (!question) return showError("请输入排查问题。");
  const version = ++selectionVersion;
  closeStream();
  $("#form-error").hidden = true;
  submitting = true;
  $("#submit-button").disabled = true;
  $("#submit-button span").textContent = "提交中…";
  const body = {
    question,
    service: $("#service").value,
    windowMinutes: Number($("#window").value),
    scenario: $("#scenario").value,
    expectedSelection: runtimeConfig.selectionToken,
    includeSource: $("#include-source").checked && !$("#include-source").disabled,
    allowSourceModel: $("#allow-source-model").checked && !$("#allow-source-model").disabled,
    expectedSourceRevision: runtimeConfig.sourceProject?.available ? runtimeConfig.sourceProject.revision : null,
    expectedSourceProjectId: runtimeConfig.sourceProject?.available ? runtimeConfig.sourceProject.id : null,
    endpointId: runtimeConfig.endpointSupported ? $("#endpoint").value || null : null,
  };
  resetResult();
  setStatus("QUEUED");
  emptyResult("正在提交", "正在创建排查记录。");
  renderHistory();
  try {
    const run = await request("/api/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json", "X-Triage-Source": "1" },
      body: JSON.stringify(body),
    });
    if (version === selectionVersion) {
      renderRun(run);
      connect(run, version);
    }
    await refreshHistory();
  } catch (error) {
    if (version === selectionVersion) {
      const changed = ["运行模式或模型配置已变更", "源码索引已变化", "源码项目已变化", "当前源码未授权", "所选接口"].some(message => error.message.includes(message));
      setStatus(changed ? "未开始" : "FAILED");
      emptyResult(
        changed ? "排查配置已变更" : "未能开始排查",
        changed ? "请确认服务、模型和源码后重新提交。" : "请检查连接后重试。",
      );
      if (changed) {
        const previousMode = runtimeConfig?.mode;
        await loadConfiguration();
        showError(
          previousMode !== "MODEL" && runtimeConfig?.mode === "MODEL"
            ? "已切换到模型模式。请确认服务后重新提交；模型调用可能产生费用。"
            : error.message,
        );
      } else showError(error.message);
    }
  } finally {
    submitting = false;
    $("#submit-button").disabled =
      !runtimeConfig || labBusy ||
      (runtimeConfig.observationSource === "LIVE" && !runtimeConfig.observationAvailable);
    $("#submit-button span").textContent = "开始排查";
  }
});

document.querySelectorAll("[data-tab]").forEach((button) => {
  button.addEventListener("click", () => showTab(button.dataset.tab));
  button.addEventListener("keydown", (event) => {
    const tabs = [...document.querySelectorAll("[data-tab]")];
    const index = tabs.indexOf(button);
    const next =
      event.key === "ArrowRight"
        ? (index + 1) % tabs.length
        : event.key === "ArrowLeft"
          ? (index + tabs.length - 1) % tabs.length
          : event.key === "Home"
            ? 0
            : event.key === "End"
              ? tabs.length - 1
              : null;
    if (next !== null) {
      event.preventDefault();
      showTab(tabs[next].dataset.tab, true);
    }
  });
});
$("#question").addEventListener("keydown", (event) => {
  if (
    (event.ctrlKey || event.metaKey) &&
    event.key === "Enter" &&
    !event.isComposing
  ) {
    event.preventDefault();
    $("#investigate-form").requestSubmit();
  }
});
$("#new-run").addEventListener("click", newRun);
$("#cancel-run").addEventListener("click", cancelRun);
document.querySelectorAll("[data-lab-scenario]").forEach((button) => {
  button.addEventListener("click", () =>
    generateLabTraffic(button.dataset.labScenario),
  );
});
$("#refresh-history").addEventListener("click", refreshHistory);
$("#config-retry").addEventListener("click", () => loadConfiguration());
$("#refresh-endpoints").addEventListener("click", () => loadEndpoints(true));
$("#window").addEventListener("change", () => loadEndpoints(true));
$("#include-source").addEventListener("change", () => {
  $("#allow-source-model").checked = false;
  $("#allow-source-model").disabled = !$("#include-source").checked || !runtimeConfig?.sourceProject?.modelSharing;
});
$("#service").addEventListener("change", () => {
  selectionVersion++;
  closeStream();
  resetResult();
  renderHistory();
  loadConfiguration();
});
$("#history-search").addEventListener("input", renderHistory);
$("#sidebar-toggle").addEventListener("click", () =>
  setSidebar(!document.body.classList.contains("sidebar-open")),
);
$("#sidebar-backdrop").addEventListener("click", () => setSidebar(false));
document.addEventListener("keydown", (event) => {
  if (event.key === "Tab" && document.body.classList.contains("sidebar-open")) {
    const controls = [
      ...$("#sidebar").querySelectorAll(
        "a[href], button:not(:disabled), input:not(:disabled)",
      ),
    ];
    const first = controls[0];
    const last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault();
      last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault();
      first.focus();
    }
  }
  if (
    event.key === "Escape" &&
    document.body.classList.contains("sidebar-open")
  ) {
    setSidebar(false);
    $("#sidebar-toggle").focus();
  }
});
const mobileLayout = window.matchMedia("(max-width: 720px)");
mobileLayout.addEventListener("change", () => setSidebar(false));
setSidebar(false);
loadConfiguration().then(() => {
  const requestedRun = new URLSearchParams(window.location.search).get("run");
  if (requestedRun && /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(requestedRun)) selectRun(requestedRun);
});
refreshHistory();
