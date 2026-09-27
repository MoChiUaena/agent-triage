"use strict";
(() => {
  const themes = [
    { id: "azure", label: "A · 云岚蓝" },
    { id: "graphite", label: "B · 石墨夜" },
    { id: "linen", label: "C · 暖纸本" },
  ];
  const allowed = new Set(themes.map((theme) => theme.id));
  const params = new URLSearchParams(location.search);
  let selected = allowed.has(params.get("theme"))
    ? params.get("theme")
    : "azure";
  document.documentElement.dataset.previewTheme = selected;

  function updateLinks() {
    for (const link of document.querySelectorAll('a[href^="/"]')) {
      const url = new URL(link.getAttribute("href"), location.origin);
      if (url.pathname !== "/" && url.pathname !== "/settings.html") continue;
      url.searchParams.set("theme", selected);
      link.href = url.pathname + url.search;
    }
  }

  function show(theme) {
    selected = theme;
    document.documentElement.dataset.previewTheme = theme;
    const url = new URL(location.href);
    url.searchParams.set("theme", theme);
    history.replaceState(null, "", url);
    updateLinks();
    document.querySelectorAll(".theme-option").forEach((button) => {
      const active = button.dataset.theme === theme;
      button.setAttribute("aria-pressed", String(active));
    });
  }

  document.addEventListener("DOMContentLoaded", () => {
    if (document.body.classList.contains("style-gallery")) return;
    updateLinks();
    const bar = document.createElement("aside");
    bar.className = "theme-picker";
    bar.setAttribute("aria-label", "界面风格预览");
    const title = document.createElement("span");
    title.className = "theme-picker-label";
    title.textContent = "界面风格";
    bar.append(title);
    for (const theme of themes) {
      const button = document.createElement("button");
      button.type = "button";
      button.className = "theme-option";
      button.dataset.theme = theme.id;
      button.textContent = theme.label;
      button.addEventListener("click", () => show(theme.id));
      bar.append(button);
    }
    const overview = document.createElement("a");
    overview.href = "/style-preview.html";
    overview.className = "theme-overview-link";
    overview.textContent = "查看三版";
    bar.append(overview);
    document.body.append(bar);
    show(selected);
  });
})();
