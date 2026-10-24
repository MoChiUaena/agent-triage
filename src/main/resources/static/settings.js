"use strict";

const $ = (selector) => document.querySelector(selector);
let state = null;
let busy = false;
let editing = null;
let removing = null;
const tests = new Map();
const presets = {
  DEEPSEEK: {
    label: "DeepSeek",
    baseUrl: "https://api.deepseek.com",
    model: "deepseek-flash",
    temperature: 0,
  },
  DASHSCOPE: {
    label: "阿里云百炼",
    baseUrl: "https://dashscope.aliyuncs.com/compatible-mode/v1",
    model: "qwen-plus",
    temperature: 0,
  },
  GLM: {
    label: "智谱 GLM",
    baseUrl: "https://open.bigmodel.cn/api/paas/v4",
    model: "glm-5.3",
    temperature: 1,
  },
  KIMI: {
    label: "Kimi",
    baseUrl: "https://api.moonshot.cn/v1",
    model: "kimi-k2.6",
    temperature: 0.6,
  },
  LM_STUDIO: {
    label: "LM Studio",
    baseUrl: "http://localhost:1234/v1",
    model: "",
    temperature: 0,
    apiKey: "local",
  },
  OPENAI_COMPATIBLE: {
    label: "兼容接口",
    baseUrl: "",
    model: "",
    temperature: 0,
  },
};

function node(tag, className, text) {
  const item = document.createElement(tag);
  if (className) item.className = className;
  if (text !== undefined) item.textContent = text;
  return item;
}

async function api(path, method = "GET", body) {
  const headers = { "X-Triage-Settings": "1" };
  if (body !== undefined) headers["Content-Type"] = "application/json";
  let response;
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      cache: "no-store",
      credentials: "same-origin",
      signal: AbortSignal.timeout(15000),
    });
  } catch (error) {
    throw new Error(
      error.name === "TimeoutError"
        ? "请求超时，请刷新列表确认操作结果。"
        : "无法连接服务，请稍后重试。",
    );
  }
  if (!response.ok) {
    const problem = await response.json().catch(() => ({}));
    throw new Error(problem.detail || "请求失败（" + response.status + "）。");
  }
  return response.status === 204 ? null : response.json();
}

function notice(message, failed = false) {
  const target = $("#settings-notice");
  target.textContent = message;
  target.className = "settings-notice" + (failed ? " failure" : "");
  target.hidden = !message;
}

function field(list, label, value) {
  const line = node("div");
  line.append(node("dt", "", label), node("dd", "", value));
  list.append(line);
}

function action(label, className, disabled, handler) {
  const button = node("button", className, label);
  button.type = "button";
  button.disabled = disabled;
  button.addEventListener("click", handler);
  return button;
}

function render() {
  if (!state) return;
  const current = state.selection;
  const selected = state.providers.find((p) => p.id === current.providerId);
  $("#current-provider").textContent =
    current.mode === "DEMO"
      ? "演示模式"
      : selected?.displayName || "环境变量配置";
  $("#current-description").textContent =
    current.mode === "DEMO"
      ? "按固定规则生成结论，不请求模型服务；观测来源在排查页显示。"
      : selected
        ? selected.model + " · 新任务将使用此配置"
        : "模型由环境变量配置，可添加页面配置后切换。";
  $("#use-demo").disabled = busy || current.mode === "DEMO";
  $("#add-provider").disabled = busy || state.providers.length >= 10;
  $("#refresh-providers").disabled = busy;
  $("#provider-count").textContent = String(state.providers.length);
  const list = $("#providers");
  list.replaceChildren();
  if (!state.providers.length) {
    list.append(
      node(
        "p",
        "settings-empty",
        "还没有模型服务。添加服务后，可以在这里测试并启用。",
      ),
    );
    return;
  }
  for (const provider of state.providers) {
    const card = node(
      "article",
      "provider-card" + (provider.active ? " active" : ""),
    );
    const heading = node("div", "provider-card-header");
    const mark = node("span", "provider-mark");
    const image = document.createElementNS("http://www.w3.org/2000/svg", "svg");
    image.setAttribute("class", "icon");
    image.setAttribute("aria-hidden", "true");
    const use = document.createElementNS("http://www.w3.org/2000/svg", "use");
    use.setAttribute("href", "/icons.svg#server");
    image.append(use);
    mark.append(image);
    const title = node("div");
    title.append(
      node("h3", "", provider.displayName),
      node("p", "", presets[provider.protocol]?.label || "兼容接口"),
    );
    heading.append(mark, title);
    if (provider.active) heading.append(node("span", "active-tag", "正在使用"));
    card.append(heading);
    const details = node("dl", "provider-details");
    field(details, "Base URL", provider.baseUrl);
    field(details, "模型", provider.model);
    field(details, "API Key", provider.keyConfigured ? "已保存" : "未设置");
    field(
      details,
      "参数",
      "温度 " +
        provider.temperature +
        " · " +
        provider.timeoutSeconds +
        "s · " +
        provider.maxRounds +
        " 轮",
    );
    card.append(details);
    const actions = node("div", "provider-actions");
    actions.append(
      action("编辑", "", busy, () => openForm(provider)),
      action("测试", "", busy, () => testProvider(provider)),
      action(
        provider.active ? "正在使用" : "设为当前模型",
        "enable",
        busy || provider.active,
        () => enableProvider(provider),
      ),
      action("删除", "remove", busy || provider.active, () =>
        openDelete(provider),
      ),
    );
    card.append(actions);
    const result = tests.get(provider.id + ":" + provider.version);
    if (result)
      card.append(
        node(
          "p",
          "probe-result" + (result.success ? "" : " failure"),
          result.message + "（" + result.elapsedMs + " ms）",
        ),
      );
    list.append(card);
  }
}

async function reload() {
  state = await api("/api/settings");
  render();
}

async function perform(operation, success) {
  if (busy) return;
  busy = true;
  render();
  try {
    await operation();
    await reload();
    notice(success);
  } catch (error) {
    notice(error.message, true);
  } finally {
    busy = false;
    render();
  }
}

function formError(message) {
  $("#provider-error").textContent = message;
  $("#provider-error").hidden = !message;
}

function openForm(provider = null) {
  editing = provider;
  const preset = presets[provider?.protocol || "DEEPSEEK"];
  $("#provider-form").reset();
  formError("");
  $("#provider-dialog-title").textContent = provider
    ? "编辑模型服务"
    : "添加模型服务";
  $("#key-hint").textContent = provider ? "留空保留已保存的 Key" : "必填";
  $("#key-description").textContent = provider
    ? "服务地址变更时需重新填写 Key。不会在页面中回显已保存的 Key。"
    : "Key 加密保存在本机；本地服务不校验 Key 时，可填写 local。";
  $("#provider-name").value = provider?.displayName || preset.label;
  $("#provider-protocol").value = provider?.protocol || "DEEPSEEK";
  $("#provider-url").value = provider?.baseUrl || preset.baseUrl;
  $("#provider-url").placeholder =
    preset.baseUrl || "https://api.example.com/v1";
  $("#provider-model").value = provider?.model || preset.model;
  $("#provider-model").placeholder = preset.model || "输入模型 ID";
  $("#provider-key").value = provider ? "" : preset.apiKey || "";
  $("#provider-temperature").value = String(
    provider?.temperature ?? preset.temperature,
  );
  $("#provider-timeout").value = String(provider?.timeoutSeconds ?? 20);
  $("#provider-rounds").value = String(provider?.maxRounds ?? 4);
  $("#provider-tokens").value = String(provider?.maxTokens ?? 1600);
  $("#provider-dialog").showModal();
  $("#provider-name").focus();
}

function closeForm() {
  if (busy) return;
  $("#provider-dialog").close();
  $("#provider-key").value = "";
  editing = null;
}

async function saveForm(event) {
  event.preventDefault();
  if (busy) return;
  const original = editing;
  const payload = {
    displayName: $("#provider-name").value.trim(),
    protocol: $("#provider-protocol").value,
    baseUrl: $("#provider-url").value.trim(),
    model: $("#provider-model").value.trim(),
    apiKey: $("#provider-key").value.trim(),
    temperature: Number($("#provider-temperature").value),
    timeoutSeconds: Number($("#provider-timeout").value),
    maxRounds: Number($("#provider-rounds").value),
    maxTokens: Number($("#provider-tokens").value),
    version: original?.version || 0,
  };
  busy = true;
  $("#save-provider").disabled = true;
  $("#cancel-provider").disabled = true;
  $("#close-provider").disabled = true;
  render();
  try {
    await api(
      original
        ? "/api/settings/providers/" + original.id
        : "/api/settings/providers",
      original ? "PUT" : "POST",
      payload,
    );
    $("#provider-dialog").close();
    $("#provider-key").value = "";
    editing = null;
    await reload();
    notice("配置已保存。可测试连接，或设为当前模型。");
  } catch (error) {
    formError(error.message);
  } finally {
    busy = false;
    $("#save-provider").disabled = false;
    $("#cancel-provider").disabled = false;
    $("#close-provider").disabled = false;
    render();
  }
}

async function testProvider(provider) {
  await perform(async () => {
    const result = await api(
      "/api/settings/providers/" +
        provider.id +
        "/test?version=" +
        provider.version,
      "POST",
    );
    tests.set(provider.id + ":" + provider.version, result);
    if (!result.success) throw new Error(result.message);
  }, "模型已返回测试文本。测试不会更改当前模型。");
}

async function enableProvider(provider) {
  await perform(
    () =>
      api("/api/settings/selection", "PUT", {
        mode: "MODEL",
        providerId: provider.id,
      }),
    "已设为当前模型，后续任务会使用此配置。",
  );
}

function openDelete(provider) {
  removing = provider;
  $("#delete-name").textContent = provider.displayName;
  $("#delete-error").hidden = true;
  $("#delete-dialog").showModal();
}

async function deleteProvider() {
  if (!removing || busy) return;
  busy = true;
  $("#confirm-delete").disabled = true;
  $("#cancel-delete").disabled = true;
  const provider = removing;
  try {
    await api(
      "/api/settings/providers/" + provider.id + "?version=" + provider.version,
      "DELETE",
    );
    $("#delete-dialog").close();
    removing = null;
    await reload();
    notice("模型服务已删除。");
  } catch (error) {
    $("#delete-error").textContent = error.message;
    $("#delete-error").hidden = false;
  } finally {
    busy = false;
    $("#confirm-delete").disabled = false;
    $("#cancel-delete").disabled = false;
    render();
  }
}

$("#provider-form").addEventListener("submit", saveForm);
$("#add-provider").addEventListener("click", () => openForm());
$("#refresh-providers").addEventListener("click", () =>
  reload()
    .then(() => notice(""))
    .catch((error) => notice(error.message, true)),
);
$("#use-demo").addEventListener("click", () =>
  perform(
    () => api("/api/settings/selection", "PUT", { mode: "DEMO" }),
    "已切换到演示模式。",
  ),
);
$("#cancel-provider").addEventListener("click", closeForm);
$("#close-provider").addEventListener("click", closeForm);
$("#provider-dialog").addEventListener("cancel", (event) => {
  if (busy) event.preventDefault();
  else closeForm();
});
$("#provider-protocol").addEventListener("change", () => {
  if (editing) return;
  const preset = presets[$("#provider-protocol").value];
  $("#provider-name").value = preset.label;
  $("#provider-url").value = preset.baseUrl;
  $("#provider-url").placeholder =
    preset.baseUrl || "https://api.example.com/v1";
  $("#provider-model").value = preset.model;
  $("#provider-model").placeholder = preset.model || "输入模型 ID";
  $("#provider-temperature").value = String(preset.temperature);
  $("#provider-key").value = preset.apiKey || "";
  if (!preset.baseUrl) $("#provider-url").focus();
});
$("#cancel-delete").addEventListener("click", () => {
  if (!busy) {
    $("#delete-dialog").close();
    removing = null;
  }
});
$("#confirm-delete").addEventListener("click", deleteProvider);
$("#delete-dialog").addEventListener("cancel", (event) => {
  if (busy) event.preventDefault();
  else removing = null;
});
reload().catch((error) => notice(error.message, true));

// 检索辅助服务（可选）：与聊天模型相互独立的单条 Embedding / Rerank 配置。
let searchState = null;
let editingSearch = null;
const searchTests = new Map();

function searchModelLabel(kind) {
  return kind === "rerank" ? "Rerank 重排服务" : "Embedding 服务";
}

function searchModelScope(kind) {
  return kind === "rerank"
    ? "混合检索启用重排时，融合后的候选摘录（每段最多 1000 字符）会发送到此服务；未启用重排时不发送任何内容。"
    : "建立向量索引时，当前有效知识分段的正文会发送到此服务；混合检索时检索词会发送到此服务。修改模型或地址后需重建各范围的向量索引。";
}

function renderSearchModels() {
  const target = $("#search-models");
  target.replaceChildren();
  if (!searchState) return;
  for (const kind of ["embedding", "rerank"]) {
    const view = searchState[kind];
    const card = node("article", "provider-card");
    card.append(node("h3", "", searchModelLabel(kind) + (view ? " · " + view.displayName : "（未配置）")));
    const details = node("dl", "provider-details");
    if (view) {
      field(details, "Base URL", view.baseUrl);
      field(details, "模型", view.model);
      field(details, "API Key", view.keyConfigured ? "已保存" : "未设置");
      field(details, "超时", view.timeoutSeconds + "s");
    } else {
      field(details, "状态", "未配置；相关功能保持关闭，不影响关键词检索");
    }
    card.append(details, node("p", "settings-empty", searchModelScope(kind)));
    const actions = node("div", "provider-actions");
    actions.append(
      action(view ? "编辑" : "添加", "", busy, () => openSearchForm(kind, view)),
    );
    if (view) {
      actions.append(
        action("测试", "", busy, () => testSearchModel(kind)),
        action("删除", "remove", busy, () => deleteSearchModel(kind, view)),
      );
    }
    const result = searchTests.get(kind + ":" + (view?.version ?? 0));
    if (result)
      card.append(
        node(
          "p",
          "probe-result" + (result.success ? "" : " failure"),
          result.message + "（" + result.elapsedMs + " ms）",
        ),
      );
    card.append(actions);
    target.append(card);
  }
}

async function reloadSearchModels() {
  searchState = await api("/api/settings/search-models");
  renderSearchModels();
}

function searchFormError(message) {
  $("#search-model-error").textContent = message;
  $("#search-model-error").hidden = !message;
}

function openSearchForm(kind, view) {
  editingSearch = { kind, view: view || null };
  $("#search-model-form").reset();
  searchFormError("");
  $("#search-model-kind").value = kind;
  $("#search-model-title").textContent =
    (view ? "编辑" : "配置") + searchModelLabel(kind);
  $("#search-model-key-hint").textContent = view ? "留空保留已保存的 Key" : "必填";
  $("#search-model-scope").textContent = searchModelScope(kind);
  $("#search-model-name").value = view?.displayName || "";
  $("#search-model-url").value = view?.baseUrl || "";
  $("#search-model-model").value = view?.model || "";
  $("#search-model-key").value = "";
  $("#search-model-timeout").value = String(view?.timeoutSeconds ?? 20);
  $("#search-model-dialog").showModal();
  $("#search-model-name").focus();
}

function closeSearchForm() {
  if (busy) return;
  $("#search-model-dialog").close();
  $("#search-model-key").value = "";
  editingSearch = null;
}

async function saveSearchModel(event) {
  event.preventDefault();
  const kind = editingSearch?.kind;
  if (!kind || busy) return;
  const original = editingSearch.view;
  const payload = {
    displayName: $("#search-model-name").value.trim(),
    baseUrl: $("#search-model-url").value.trim(),
    model: $("#search-model-model").value.trim(),
    apiKey: $("#search-model-key").value.trim(),
    timeoutSeconds: Number($("#search-model-timeout").value),
    version: original?.version || 0,
  };
  busy = true;
  renderSearchModels();
  try {
    await api("/api/settings/search-models/" + kind, "PUT", payload);
    $("#search-model-dialog").close();
    $("#search-model-key").value = "";
    editingSearch = null;
    await reloadSearchModels();
    notice(
      kind === "embedding"
        ? "Embedding 服务已保存。若修改了模型或地址，请在项目源码页重建相关范围的向量索引。"
        : "Rerank 服务已保存。",
    );
  } catch (error) {
    searchFormError(error.message);
  } finally {
    busy = false;
    renderSearchModels();
  }
}

async function testSearchModel(kind) {
  if (busy) return;
  busy = true;
  renderSearchModels();
  try {
    const result = await api("/api/settings/search-models/" + kind + "/test", "POST");
    const view = searchState?.[kind];
    searchTests.set(kind + ":" + (view?.version ?? 0), result);
    await reloadSearchModels();
    if (!result.success) notice(result.message, true);
    else notice("服务已响应一次测试请求，可能产生少量费用。");
  } catch (error) {
    notice(error.message, true);
  } finally {
    busy = false;
    renderSearchModels();
  }
}

async function deleteSearchModel(kind, view) {
  if (busy) return;
  if (
    !window.confirm(
      "删除" +
        searchModelLabel(kind) +
        "及其保存的 Key？相关检索功能将回退为不可用，并明确说明原因；已建立的向量索引会保留但不再被使用。",
    )
  )
    return;
  busy = true;
  renderSearchModels();
  try {
    await api("/api/settings/search-models/" + kind + "?version=" + view.version, "DELETE");
    searchTests.clear();
    await reloadSearchModels();
    notice(searchModelLabel(kind) + "已删除。");
  } catch (error) {
    notice(error.message, true);
  } finally {
    busy = false;
    renderSearchModels();
  }
}

$("#search-model-form").addEventListener("submit", saveSearchModel);
$("#cancel-search-model").addEventListener("click", closeSearchForm);
$("#close-search-model").addEventListener("click", closeSearchForm);
$("#search-model-dialog").addEventListener("cancel", (event) => {
  if (busy) event.preventDefault();
  else closeSearchForm();
});
reloadSearchModels().catch(() => renderSearchModels());
