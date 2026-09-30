"use strict";

(() => {
  const sidebar = document.querySelector("#sidebar");
  const backdrop = document.querySelector("#sidebar-backdrop");
  const toggle = document.querySelector("#sidebar-toggle");
  const shell = document.querySelector(".app-shell");
  if (!sidebar || !backdrop || !toggle || !shell) return;
  const mobile = window.matchMedia("(max-width: 720px)");

  function setOpen(requested, returnFocus = false) {
    const open = requested && mobile.matches;
    document.body.classList.toggle("sidebar-open", open);
    backdrop.hidden = !open;
    toggle.setAttribute("aria-expanded", String(open));
    sidebar.inert = mobile.matches && !open;
    shell.inert = open;
    if (open) sidebar.querySelector('[aria-current="page"]')?.focus();
    else if (returnFocus) toggle.focus();
  }

  toggle.addEventListener("click", () => setOpen(!document.body.classList.contains("sidebar-open")));
  backdrop.addEventListener("click", () => setOpen(false, true));
  mobile.addEventListener("change", () => setOpen(false));
  document.addEventListener("keydown", (event) => {
    if (!document.body.classList.contains("sidebar-open")) return;
    if (event.key === "Escape") {
      setOpen(false, true);
      return;
    }
    if (event.key !== "Tab") return;
    const controls = [...sidebar.querySelectorAll('a[href], button:not(:disabled)')];
    const first = controls[0], last = controls[controls.length - 1];
    if (event.shiftKey && document.activeElement === first) {
      event.preventDefault(); last.focus();
    } else if (!event.shiftKey && document.activeElement === last) {
      event.preventDefault(); first.focus();
    }
  });
  setOpen(false);
})();
