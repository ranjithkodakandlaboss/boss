import { useEffect, type ReactNode } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { X } from "lucide-react";
import AppShell from "./components/layout/AppShell";
import { sheetParent } from "./components/layout/sheetRoutes";
import { CustomerProvider, useCustomer } from "./auth/CustomerState";
import { ShellProvider } from "./shell/ShellContext";
import { findScreen } from "./screens/catalog";
import ActivatePage from "./screens/ActivatePage";
import LoginPage from "./screens/LoginPage";
import OnboardingPage from "./screens/OnboardingPage";
import ScreenPage from "./screens/ScreenPage";

function Background({ pathname }: { pathname: string }) {
  const screen = findScreen(pathname.split("?")[0]);
  if (!screen) return null;
  return <ScreenPage screen={screen} />;
}

function SheetFrame({ parent, children }: { parent: string; children: ReactNode }) {
  const navigate = useNavigate();
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") navigate(parent);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [navigate, parent]);
  return (
    <div className="fixed inset-0 z-[60] flex justify-end bg-black/40">
      <button type="button" className="absolute inset-0" aria-label="Close" onClick={() => navigate(parent)} />
      <div role="dialog" aria-modal="true" className="relative flex h-full w-full flex-col bg-[var(--bg)] shadow-2xl md:w-[min(860px,94vw)]" style={{ borderLeft: "1px solid var(--border)" }}>
        <header className="flex h-12 items-center justify-between px-4 border-b" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          <span className="text-xs font-semibold uppercase tracking-wide text-[var(--text-3)]">Record</span>
          <button type="button" onClick={() => navigate(parent)} className="inline-flex h-8 items-center gap-1 rounded-lg px-2 text-sm font-semibold">
            <X size={16} /> Close
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-auto">{children}</div>
      </div>
    </div>
  );
}

function SheetLayout({ children }: { children: ReactNode }) {
  const location = useLocation();
  const parent = sheetParent(location.pathname);
  if (!parent) return children;
  return (
    <>
      <Background pathname={parent} />
      <SheetFrame parent={parent}>{children}</SheetFrame>
    </>
  );
}

function RoutedScreen() {
  const location = useLocation();
  const screen = findScreen(location.pathname);
  if (!screen) return <Navigate to="/" replace />;
  return (
    <SheetLayout>
      <ScreenPage screen={screen} />
    </SheetLayout>
  );
}

function Guard() {
  const location = useLocation();
  const customer = useCustomer();
  if (!customer.ready) return <p role="status">Loading session</p>;
  const open = location.pathname === "/login" || location.pathname === "/activate";
  if (!customer.user && !open) return <Navigate to="/login" replace />;
  if (customer.user && !customer.onboarding?.completed && location.pathname !== "/onboarding" && !open) {
    return <Navigate to="/onboarding" replace />;
  }
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route path="activate" element={<ActivatePage />} />
      <Route path="onboarding" element={<OnboardingPage />} />
      <Route element={<AppShell />}>
        <Route path="sales/customers" element={<Navigate to="/sales?tab=customers" replace />} />
        <Route path="settings" element={<Navigate to="/admin" replace />} />
        <Route path="admin/audit" element={<Navigate to="/audit" replace />} />
        <Route path="*" element={<RoutedScreen />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <ShellProvider>
      <CustomerProvider>
        <Guard />
      </CustomerProvider>
    </ShellProvider>
  );
}
