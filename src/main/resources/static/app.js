"use strict";
const $ = (selector) => document.querySelector(selector);
const statusText = {
  QUEUED: "排队中",
  RUNNING: "正在排查",
  SUCCEEDED: "排查完成",
  INSUFFICIENT_EVIDENCE: "证据不足",
  FAILED: "执行失败",
};
let activeRun = null;
let stream = null;
let latestSelection = 0;
let displayedEvents = new Set();

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

async function request(path, options) {
  const response = await fetch(path, options);
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || `请求失败 (${response.status})`);
  }
  return response.json();
}

function showError(message) {
  $("#form-error").hidden = false;
  $("#form-error").textContent = message;
}

function setStatus(status) {
  const badge = $("#run-status");
  badge.textContent = statusText[status] || status;
  badge.className = `status ${status === "FAILED" ? "failed" : status === "SUCCEEDED" ? "success" : ["QUEUED", "RUNNING"].includes(status) ? "running" : "neutral"}`;
}

function addEvent(event) {
  if (displayedEvents.has(event.sequence)) return;
  displayedEvents.add(event.sequence);
  const row = element("li");
  const time = element(
    "time",
    "",
    new Date(event.timestamp).toLocaleTimeString("zh-CN", { hour12: false }),
  );
  row.append(
    time,
    document.createTextNode(
      `${event.tool ? event.tool + " · " : ""}${event.message}`,
    ),
  );
  $("#events").append(row);
  const tool = [...document.querySelectorAll("[data-tool]")].find(
    (node) => node.dataset.tool === event.tool,
  );
  if (tool) {
    tool.className = `tool-card ${event.type === "TOOL_COMPLETED" ? "done" : event.type === "TOOL_FAILED" ? "error" : "active"}`;
    tool.querySelector(".tool-state").textContent =
      event.type === "TOOL_COMPLETED"
        ? "已完成"
        : event.type === "TOOL_FAILED"
          ? "失败"
          : "执行中";
  }
  if (event.type === "RUN_STARTED") setStatus("RUNNING");
}

function renderDiagnosis(run) {
  const target = $("#diagnosis");
  target.replaceChildren();
  if (run.failure) {
    target.append(
      element("p", "error", `${run.failure.code}：${run.failure.message}`),
    );
    return;
  }
  if (!run.diagnosis) {
    target.append(element("p", "muted", "正在收集证据，完成后显示结论。"));
    return;
  }
  const sections = [
    ["观察", run.diagnosis.observations],
    ["可能原因 / 当前判断", run.diagnosis.possibleCauses],
  ];
  for (const [title, findings] of sections) {
    target.append(element("h3", "result-heading", title));
    if (!findings.length)
      target.append(element("p", "muted", "当前证据不足以支持该项判断。"));
    for (const finding of findings) {
      const row = element("p", "finding", finding.text);
      row.append(document.createElement("br"));
      for (const id of finding.evidenceIds) {
        const link = element("a", "citation", id);
        link.href = "#evidence-" + encodeURIComponent(id);
        link.addEventListener("click", (event) => {
          event.preventDefault();
          const evidence = document.getElementById("evidence-" + id);
          if (evidence) {
            evidence.open = true;
            evidence.scrollIntoView({ behavior: "smooth", block: "center" });
          }
        });
        row.append(link);
      }
      target.append(row);
    }
  }
  target.append(element("h3", "result-heading", "下一步验证"));
  const steps = element("ol", "next-steps");
  run.diagnosis.nextSteps.forEach((step) =>
    steps.append(element("li", "", step)),
  );
  target.append(
    steps,
    element("div", "uncertainty", run.diagnosis.uncertainty),
  );
}

function renderEvidence(run) {
  $("#evidence-panel").hidden = run.evidence.length === 0;
  $("#evidence-count").textContent = `${run.evidence.length} 条 · SYNTHETIC`;
  $("#evidence-list").replaceChildren();
  for (const evidence of run.evidence) {
    const details = element("details", "evidence-item");
    details.id = "evidence-" + evidence.id;
    const summary = element("summary", "", evidence.title);
    summary.append(element("small", "", evidence.id));
    const body = element("div", "evidence-body", evidence.summary);
    body.append(element("pre", "", JSON.stringify(evidence.data, null, 2)));
    details.append(summary, body);
    $("#evidence-list").append(details);
  }
}

function renderRun(run) {
  activeRun = run.id;
  setStatus(run.status);
  $("#run-meta").textContent =
    `${run.scenario === "NORMAL" ? "正常对照" : "下游超时"} · 最近 ${run.windowMinutes} 分钟 · ${run.toolCalls} 次工具调用 · ${run.id.slice(0, 8)}`;
  run.events.forEach(addEvent);
  setStatus(run.status);
  renderDiagnosis(run);
  renderEvidence(run);
}

async function selectRun(id) {
  const selection = ++latestSelection;
  if (stream) {
    stream.close();
    stream = null;
  }
  try {
    const run = await request(`/api/runs/${id}`);
    if (selection !== latestSelection) return;
    displayedEvents = new Set();
    $("#events").replaceChildren();
    document.querySelectorAll("[data-tool]").forEach((node) => {
      node.className = "tool-card";
      node.querySelector(".tool-state").textContent = "待执行";
    });
    renderRun(run);
    document
      .querySelectorAll(".history-item")
      .forEach((node) =>
        node.classList.toggle("selected", node.dataset.id === id),
      );
    if (["QUEUED", "RUNNING"].includes(run.status)) {
      const source = new EventSource(`/api/runs/${id}/events`);
      stream = source;
      source.addEventListener("progress", (event) => {
        if (selection === latestSelection) addEvent(JSON.parse(event.data));
      });
      source.addEventListener("complete", (event) => {
        source.close();
        if (selection !== latestSelection) return;
        renderRun(JSON.parse(event.data));
        refreshHistory();
      });
      source.onerror = async () => {
        source.close();
        if (selection !== latestSelection) return;
        try {
          const latest = await request(`/api/runs/${id}`);
          if (selection !== latestSelection) return;
          renderRun(latest);
          if (["QUEUED", "RUNNING"].includes(latest.status))
            showError("事件连接中断。执行仍保存在服务端，请从记录中重新打开。");
          refreshHistory();
        } catch (error) {
          showError(error.message);
        }
      };
    }
  } catch (error) {
    if (selection === latestSelection) showError(error.message);
  }
}

async function refreshHistory() {
  try {
    const runs = await request("/api/runs?limit=20");
    $("#history").replaceChildren();
    if (!runs.length)
      $("#history").append(
        element("p", "muted", "还没有执行记录，开始第一次排查。"),
      );
    for (const run of runs) {
      const button = element(
        "button",
        `history-item${run.id === activeRun ? " selected" : ""}`,
      );
      button.type = "button";
      button.dataset.id = run.id;
      const detail = element("small");
      detail.append(
        element(
          "span",
          "",
          `${run.scenario === "NORMAL" ? "正常" : "超时"} · ${statusText[run.status]}`,
        ),
        element(
          "span",
          "",
          new Date(run.createdAt).toLocaleTimeString("zh-CN", {
            hour12: false,
          }),
        ),
      );
      button.append(element("strong", "", run.question), detail);
      button.addEventListener("click", () => selectRun(run.id));
      $("#history").append(button);
    }
  } catch (error) {
    $("#history").replaceChildren(
      element("p", "error", `记录加载失败：${error.message}`),
    );
  }
}

$("#investigate-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const question = $("#question").value.trim();
  if (!question) {
    showError("请输入排查问题。");
    return;
  }
  $("#form-error").hidden = true;
  $("#submit-button").disabled = true;
  try {
    const run = await request("/api/runs", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        question,
        service: $("#service").value,
        windowMinutes: Number($("#window").value),
        scenario: $("input[name=scenario]:checked").value,
      }),
    });
    await selectRun(run.id);
    await refreshHistory();
  } catch (error) {
    showError(error.message);
  } finally {
    $("#submit-button").disabled = false;
  }
});
$("#refresh-history").addEventListener("click", refreshHistory);
refreshHistory();
