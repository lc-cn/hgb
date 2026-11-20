/**
 * The light or dark look: following the system unless the viewer picked one. It is a choice of this browser, so it is
 * kept in local storage; storage may be unavailable (private windows), and then the system's look is used.
 */
export type Appearance = "auto" | "light" | "dark";
const KEY = "huganbao.appearance";

export function readAppearance(): Appearance {
  try { const saved = localStorage.getItem(KEY); return saved === "light" || saved === "dark" ? saved : "auto"; } catch { return "auto"; }
}

export function applyAppearance(next: Appearance) {
  if (next === "auto") delete document.documentElement.dataset.theme; else document.documentElement.dataset.theme = next;
  try { if (next === "auto") localStorage.removeItem(KEY); else localStorage.setItem(KEY, next); } catch { /* the choice lasts until reload */ }
}
