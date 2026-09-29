"use strict";

window.sourceView = {
  render(container, excerpts) {
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
      container.append(details);
    }
  },
};
