import { createContext, useContext, useMemo, useState, type ReactNode } from "react";
import { applyTheme, readMode, readPalette, readSidebarCollapsed, storeSidebarCollapsed, type ColorMode, type Palette } from "./theme";

type ShellContextValue = {
  collapsed: boolean;
  toggleCollapsed: () => void;
  palette: Palette;
  mode: ColorMode;
  setPalette: (palette: Palette) => void;
  setMode: (mode: ColorMode) => void;
};

const ShellContext = createContext<ShellContextValue | null>(null);

export function ShellProvider({ children }: { children: ReactNode }) {
  const [collapsed, setCollapsed] = useState(readSidebarCollapsed);
  const [palette, setPaletteState] = useState<Palette>(readPalette);
  const [mode, setModeState] = useState<ColorMode>(readMode);

  const value = useMemo<ShellContextValue>(() => ({
    collapsed,
    toggleCollapsed: () => {
      setCollapsed((current) => {
        storeSidebarCollapsed(!current);
        return !current;
      });
    },
    palette,
    mode,
    setPalette: (next) => {
      setPaletteState(next);
      applyTheme(next, mode);
    },
    setMode: (next) => {
      setModeState(next);
      applyTheme(palette, next);
    },
  }), [collapsed, mode, palette]);

  return <ShellContext.Provider value={value}>{children}</ShellContext.Provider>;
}

export function useShell() {
  const value = useContext(ShellContext);
  if (!value) throw new Error("ShellProvider is missing");
  return value;
}
