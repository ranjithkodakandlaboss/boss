import { useState } from "react";
import { Outlet } from "react-router-dom";
import clsx from "clsx";
import Sidebar from "./Sidebar";
import TopBar from "./TopBar";
import BottomNav from "./BottomNav";
import { useShell } from "../../shell/ShellContext";

export default function AppShell() {
  const { collapsed } = useShell();
  const [mobileNavOpen, setMobileNavOpen] = useState(false);
  return (
    <div className="min-h-screen bg-[var(--app-bg,var(--bg))] text-[var(--text)]">
      {mobileNavOpen && <button type="button" aria-label="Close menu" className="md:hidden fixed inset-0 z-40 bg-black/40" onClick={() => setMobileNavOpen(false)} />}
      <Sidebar mobileOpen={mobileNavOpen} onClose={() => setMobileNavOpen(false)} />
      <TopBar onOpenMenu={() => setMobileNavOpen(true)} />
      <main className={clsx("pt-[var(--topbar-h)] pb-16 md:pb-0 min-h-screen", collapsed ? "md:ml-[var(--sidebar-w-collapsed)]" : "md:ml-[var(--sidebar-width,var(--sidebar-w))]")}>
        <Outlet />
      </main>
      <BottomNav />
    </div>
  );
}
