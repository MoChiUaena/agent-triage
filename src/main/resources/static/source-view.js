"use strict";

window.sourceView = {
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
    if (graph.rootIds?.length) wrapper.append(element("p", "call-root", "入口候选：" + graph.rootIds.map(method).join(" / ")));
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
