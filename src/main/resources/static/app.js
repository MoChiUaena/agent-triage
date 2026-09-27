"use strict";

const $ = (selector) => document.querySelector(selector);
const statusText = {
  QUEUED: "排队中",
  RUNNING: "排查中",
  SUCCEEDED: "已完成",
  INSUFFICIENT_EVIDENCE: "证据不足",
  FAILED: "执行失败",
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

async function loadConfiguration() {
  $("#config-retry").disabled = true;
  try {
    const config = await request("/api/config");
    if (!["DEMO", "MODEL"].includes(config.mode))
      throw new Error("运行模式无效。");
    runtimeConfig = config;
    $("#mode-label").textContent =
      config.mode === "MODEL" ? "模型模式" : "演示模式";
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
    $("#config-retry").hidden = true;
    $("#submit-button").disabled = submitting;
    $("#form-error").hidden = true;
  } catch (error) {
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
    $("#config-retry").disabled = false;
  }
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
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
  selectionVersion++;
  closeStream();
  $("#form-error").hidden = true;
  resetResult();
  $("#question").value = "订单查询接口为什么变慢了？";
  $("#window").value = "15";
  $("#scenario").value = "DOWNSTREAM_TIMEOUT";
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
  if (tool) {
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
  const values = [
    [
      "订单查询 p95",
      data.orderP95Ms,
      "ms",
      "基线 " + data.baselineOrderP95Ms + " ms",
      data.orderP95Ms > data.baselineOrderP95Ms,
    ],
    ["库存调用 p95", data.downstreamP95Ms, "ms", "inventory-service", false],
    [
      "下游超时率",
      Number((data.downstreamTimeoutRate * 100).toFixed(2)),
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
      Number(value).toLocaleString("zh-CN"),
    );
    number.append(element("small", "", unit));
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
    ? "订单服务指标"
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
  if (run.failure) {
    target.append(
      element("p", "error-banner", run.failure.message),
      element(
        "p",
        "panel-empty",
        "错误代码：" + run.failure.code + "。可在执行记录中查看失败步骤。",
      ),
    );
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
      [
        ["订单查询 p95", data.orderP95Ms + " ms"],
        ["库存调用 p95", data.downstreamP95Ms + " ms"],
        [
          "下游超时率",
          Number((data.downstreamTimeoutRate * 100).toFixed(2)) + "%",
        ],
        ["窗口内请求数", data.requestCount.toLocaleString("zh-CN")],
        [
          "查询窗口",
          timeText(data.windowStart) + " – " + timeText(data.windowEnd),
        ],
      ].forEach(([label, value]) => {
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
  activeRun = run;
  $("#result-question").textContent = run.question;
  $("#result-question").hidden = false;
  run.events.forEach(addEvent);
  setStatus(run.status);
  renderMetrics(run);
  renderDiagnosis(run);
  renderEvidence(run);
  const elapsed = run.finishedAt
    ? Math.max(0, new Date(run.finishedAt) - new Date(run.createdAt))
    : null;
  const info = [
    ["运行模式", run.mode === "MODEL" ? "模型" : "演示"],
    ["服务", run.service],
    ["时间窗口", "最近 " + run.windowMinutes + " 分钟"],
    ["场景", run.scenario === "NORMAL" ? "正常对照" : "下游超时"],
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
  if (run.modelExecution) {
    const model = run.modelExecution;
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
          : "未返回完整用量",
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
        node.querySelector(".tool-state").textContent = "未完成";
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
    if (version === selectionVersion) addEvent(JSON.parse(event.data));
  });
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
    $("#service").value = run.service;
    const windowValue = String(run.windowMinutes);
    if (
      ![...$("#window").options].some((option) => option.value === windowValue)
    ) {
      const option = element("option", "", "最近 " + windowValue + " 分钟");
      option.value = windowValue;
      $("#window").append(option);
    }
    $("#window").value = windowValue;
    $("#scenario").value = run.scenario;
    renderRun(run);
    connect(run, version);
  } catch (error) {
    if (version === selectionVersion) showError(error.message);
  }
}

function renderHistory() {
  const query = $("#history-search").value.trim().toLocaleLowerCase();
  const runs = historyRuns.filter((run) =>
    run.question.toLocaleLowerCase().includes(query),
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
          (run.scenario === "NORMAL" ? "正常" : "超时") +
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
  if (submitting) return;
  if (!runtimeConfig) return showError("请先连接服务，确认运行模式。");
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
  };
  resetResult();
  setStatus("QUEUED");
  emptyResult("正在提交", "正在创建排查记录。");
  renderHistory();
  try {
    const run = await request("/api/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(body),
    });
    if (version === selectionVersion) {
      renderRun(run);
      connect(run, version);
    }
    await refreshHistory();
  } catch (error) {
    if (version === selectionVersion) {
      const changed = error.message.includes("运行模式或模型配置已变更");
      setStatus(changed ? "未开始" : "FAILED");
      emptyResult(
        changed ? "运行配置已变更" : "未能开始排查",
        changed ? "请确认当前模型后重新提交。" : "请检查连接后重试。",
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
    $("#submit-button").disabled = false;
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
$("#refresh-history").addEventListener("click", refreshHistory);
$("#config-retry").addEventListener("click", loadConfiguration);
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
loadConfiguration();
refreshHistory();
