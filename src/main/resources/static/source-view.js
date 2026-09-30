"use strict";

window.sourceView = {
  version(container, version) {
    const states = {MATCHED:"构建源码摘要一致", DIFFERENT:"源码摘要不同", UNKNOWN:"未提供构建摘要", UNAVAILABLE:"没有对应源码"};
    const value = version || {state:"UNKNOWN",message:"旧记录未保存构建源码摘要，尚未核对版本。"};
    const box = document.createElement("div"); box.className = "source-version " + value.state.toLowerCase();
    const badge = document.createElement("span"); badge.textContent = states[value.state] || "未核对版本";
    const note = document.createElement("p"); note.textContent = value.message;
    box.append(badge, note);
    if (value.runtimeSourceHash || value.indexedSourceHash) {
      const details = document.createElement("details"); const summary = document.createElement("summary"); summary.textContent = "查看摘要";
      const hashes = document.createElement("p"); hashes.className = "source-version-hashes";
      hashes.textContent = "运行构建：" + (value.runtimeSourceHash || "未提供") + "\n本机索引：" + (value.indexedSourceHash || "未确定唯一文件");
      details.append(summary, hashes); box.append(details);
    }
    container.append(box);
  },
  render(container, excerpts, options = {}) {
    container.replaceChildren();
    for (const excerpt of excerpts || []) {
      const details = document.createElement("details");
      details.className = "source-excerpt";
      details.open = true;
      const heading = document.createElement("summary");
      heading.textContent = excerpt.path + ":" + excerpt.startLine + "–" + excerpt.endLine;
      const label = document.createElement("p");
      label.className = "source-symbol";
      label.textContent = excerpt.className + (excerpt.method ? " · " + excerpt.method + "()" : "") +
        (excerpt.route ? " · " + excerpt.httpMethods.join(" / ") + " " + excerpt.route : "");
      const code = document.createElement("pre");
      code.setAttribute("aria-label", "代码片段，起始行 " + excerpt.startLine);
      excerpt.content.split("\n").forEach((text, index) => {
        const line = document.createElement("span");
        line.className = "source-code-line";
        if (options.focusLine === excerpt.startLine + index) line.classList.add("source-code-focus");
        const number = document.createElement("span");
        number.className = "source-line-number";
        number.textContent = String(excerpt.startLine + index);
        number.setAttribute("aria-hidden", "true");
        const value = document.createElement("code");
        value.textContent = text || " ";
        line.append(number, value);
        code.append(line);
      });
      details.append(heading, label, code);
      if (options.onChain && excerpt.method) {
        const actions = document.createElement("div"); actions.className = "source-excerpt-actions";
        const button = document.createElement("button"); button.type = "button"; button.className = "secondary"; button.textContent = "查看调用关系";
        button.addEventListener("click", () => options.onChain(excerpt)); actions.append(button); details.append(actions);
      }
      container.append(details);
    }
  },
  failures(container, matches, options = {}) {
    container.replaceChildren();
    if (!matches?.length) return;
    const element = (tag, className, text) => {
      const value = document.createElement(tag); value.className = className || ""; if (text != null) value.textContent = text; return value;
    };
    const wrapper = element("section", "failure-locations");
    wrapper.append(element("h3", "call-graph-heading", "错误观测中的代码位置"),
      element("p", "source-result-note", "按错误时的业务调用位置匹配源码，请核对当前文件与运行版本。这些位置仅在本机展示。"));
    const labels = { LINE_MATCH: "位置对应", CANDIDATE: "方法候选", LAMBDA_CANDIDATE: "合成方法候选", AMBIGUOUS: "多个候选", LINE_MISMATCH: "行号不符", FILE_MISMATCH: "文件名不符", UNMATCHED: "索引外位置", STALE: "需要重新索引", SOURCE_MISMATCH:"源码不同" };
    for (const [index, match] of matches.entries()) {
      const details = element("details", "failure-event"); details.open = index === 0;
      const heading = element("summary", "", match.kind === "HTTP_CLIENT_FAILURE" ? "HTTP 调用失败时的线程位置" : "请求异常栈中的位置");
      details.append(heading, element("p", "call-location", new Date(match.timestamp).toLocaleString("zh-CN", {hour12:false}) + " · 请求标识 " + match.traceId),
        element("p", "call-message", "异常类型：" + match.exceptionTypes.join(" → ")));
      if (options.evidenceLink) { const references = element("div", "citations"); references.append(options.evidenceLink(match.evidenceId)); details.append(references); }
      if (!match.frames.length) details.append(element("p", "call-message", "没有采集到所配置业务包中的位置，请检查包范围与调试行号。"));
      const list = element("ol", "call-steps");
      for (const location of match.frames) {
        const row = element("li", "call-step"); const header = element("div", "call-step-heading");
        const method = element("strong", "", location.frame.className.split(".").at(-1) + "." + location.frame.methodName);
        method.title = location.frame.className + "." + location.frame.methodName;
        header.append(method,
          element("span", "call-state" + (location.state === "LINE_MATCH" ? "" : " uncertain"), labels[location.state] || "未匹配"));
        row.append(header, element("p", "call-location", (location.frame.fileName || "文件名未知") + ":" + (location.frame.lineNumber || "行号未知")),
          element("p", "call-message", location.state === "LINE_MATCH" ? "在当前源码中找到对应位置。" : location.message));
        sourceView.version(row, location.version);
        const actions = element("div", "call-actions"); const preview = element("div", "call-preview"); preview.hidden = true;
        for (const excerpt of location.excerpts) {
          const button = element("button", "secondary", "查看 " + excerpt.path + (location.frame.lineNumber ? ":" + location.frame.lineNumber : "")); button.type = "button";
          button.addEventListener("click", () => { sourceView.render(preview, [excerpt], {focusLine:location.frame.lineNumber}); preview.hidden = false; }); actions.append(button);
        }
        row.append(actions, preview); list.append(row);
      }
      details.append(list);
      if (match.truncated) details.append(element("p", "call-limit", "仅保留有界的位置摘要：最多 4 个异常类型、8 个业务位置。其他位置可能未展示。"));
      wrapper.append(details);
    }
    container.append(wrapper);
  },
  graph(container, graph, options = {}) {
    container.replaceChildren();
    if (!graph) return;
    const element = (tag, className, text) => {
      const value = document.createElement(tag); value.className = className || ""; if (text != null) value.textContent = text; return value;
    };
    const nodes = new Map((graph.nodes || []).map(value => [value.excerpt.id, value]));
    const method = id => {
      const value = nodes.get(id);
      return value ? value.excerpt.className.split(".").pop() + "." + (value.signature || value.excerpt.method + "()") : "未展开的方法";
    };
    const states = { RESOLVED: "静态匹配", CANDIDATE: "实现候选", AMBIGUOUS: "多个候选", UNRESOLVED: "未确定", LIMIT: "展开受限", BOUNDARY: "外部调用", EXTERNAL: "索引外调用" };
    const kinds = { HTTP: "HTTP 请求", DATABASE_ACQUIRE: "获取数据库连接", DATABASE_QUERY: "数据库操作" };
    const wrapper = element("section", "call-graph");
    wrapper.append(element("h3", "call-graph-heading", "本机静态调用关系"), element("p", "source-result-note", graph.message));
    const matches = graph.endpointMatches || [];
    if (graph.rootIds?.length) wrapper.append(element("p", "call-root", (matches.length ? "按请求匹配的入口：" : "入口候选：") + graph.rootIds.map(method).join(" / ")));
    for (const match of matches) {
      const box = element("div", "call-entry-match");
      box.append(element("strong", "", match.endpoint.httpMethod + " " + match.endpoint.routeTemplate + " · " + match.requestCount + " 次请求 · " + match.timeoutCount + " 次超时"),
        element("p", "", match.endpoint.handlerClass + "." + match.endpoint.handlerMethod + "(" + match.endpoint.parameterTypes.join(", ") + ")"),
        element("p", "", match.message + " MVC 匹配信息不证明处理方法体或后续调用已执行。"));
      sourceView.version(box, match.version);
      wrapper.append(box);
    }
    for (const link of graph.evidenceLinks || []) {
      const box = element("div", "call-evidence");
      box.append(element("strong", "", "运行证据与核查位置"), element("p", "", link.message));
      if (options.evidenceLink) {
        const references = element("div", "citations");
        link.evidenceIds.forEach(id => references.append(options.evidenceLink(id)));
        box.append(references);
      }
      wrapper.append(box);
    }
    const linkedEdges = new Set((graph.evidenceLinks || []).flatMap(value => value.edgeIds));
    const renderEdge = edge => {
      const card = element("li", "call-step" + (linkedEdges.has(edge.id) ? " has-observation" : ""));
      const header = element("div", "call-step-heading");
      const heading = element("strong", "", method(edge.fromId) + " → " + (edge.targetIds.length ? edge.targetIds.map(method).join(" / ") : kinds[edge.kind] || edge.call));
      const badge = element("span", "call-state" + (["CANDIDATE", "AMBIGUOUS", "UNRESOLVED", "LIMIT"].includes(edge.resolution) ? " uncertain" : ""), states[edge.resolution] || "未确定");
      header.append(heading, badge); card.append(header, element("p", "call-location", edge.callSite.path + ":" + edge.line + " · " + edge.call), element("p", "call-message", edge.message));
      const actions = element("div", "call-actions"); const preview = element("div", "call-preview"); preview.hidden = true;
      const addPreview = (label, excerpt) => {
        const button = element("button", "secondary", label); button.type = "button";
        button.addEventListener("click", () => { sourceView.render(preview, [excerpt]); preview.hidden = false; }); actions.append(button);
      };
      addPreview("查看调用位置", edge.callSite);
      edge.targetIds.forEach(id => { const node = nodes.get(id); if (node) addPreview("查看 " + method(id), node.excerpt); });
      card.append(actions, preview); return card;
    };
    const primary = (graph.edges || []).filter(value => value.kind !== "LIBRARY" && value.kind !== "DEFERRED");
    const other = (graph.edges || []).filter(value => value.kind === "LIBRARY" || value.kind === "DEFERRED");
    const list = element("ol", "call-steps"); primary.forEach(edge => list.append(renderEdge(edge))); wrapper.append(list);
    if (other.length) {
      const details = element("details", "call-other"); details.append(element("summary", "", "其他与未展开的调用（" + other.length + "）"));
      const rows = element("ol", "call-steps"); other.forEach(edge => rows.append(renderEdge(edge))); details.append(rows); wrapper.append(details);
    }
    if (graph.truncated) wrapper.append(element("p", "call-limit", "展开范围已达到上限：最多 4 层、12 个方法、32 条调用，每个方法索引最多 30 条调用。部分候选或调用尚未展示。"));
    container.append(wrapper);
  },
};
