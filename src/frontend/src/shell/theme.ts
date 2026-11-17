export type Palette = "aurora" | "ocean" | "graphite";
export type ColorMode = "light" | "dark";

export const PALETTES: { id: Palette; label: string; accent: string }[] = [
  { id: "aurora", label: "Theme 1 · Aurora Indigo", accent: "#7048F5" },
  { id: "ocean", label: "Theme 2 · Ocean Teal", accent: "#087F8C" },
  { id: "graphite", label: "Theme 3 · Graphite Amber", accent: "#E58A36" },
];

const PALETTE_KEY = "eoss-palette";
const MODE_KEY = "eoss-theme";
const SIDEBAR_KEY = "eoss-sidebar-collapsed";

function root(): HTMLElement | null {
  return typeof document === "undefined" ? null : document.documentElement;
}

function storage(): Storage | null {
  const candidate = globalThis.localStorage;
  if (!candidate || typeof candidate.getItem !== "function") return null;
  return candidate;
}

export function readPalette(): Palette {
  const value = storage()?.getItem(PALETTE_KEY);
  return value === "ocean" || value === "graphite" ? value : "aurora";
}

export function readMode(): ColorMode {
  return storage()?.getItem(MODE_KEY) === "dark" ? "dark" : "light";
}

export function readSidebarCollapsed(): boolean {
  return storage()?.getItem(SIDEBAR_KEY) === "1";
}

export function applyTheme(palette: Palette, mode: ColorMode) {
  const element = root();
  if (!element) return;
  element.classList.remove("theme-aurora", "theme-ocean", "theme-graphite");
  element.classList.add(`theme-${palette}`);
  element.setAttribute("data-theme", mode);
  storage()?.setItem(PALETTE_KEY, palette);
  storage()?.setItem(MODE_KEY, mode);
}

export function storeSidebarCollapsed(collapsed: boolean) {
  storage()?.setItem(SIDEBAR_KEY, collapsed ? "1" : "0");
}
