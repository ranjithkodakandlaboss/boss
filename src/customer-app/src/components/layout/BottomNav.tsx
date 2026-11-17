import { useState } from "react";
import { NavLink, useNavigate } from "react-router-dom";
import { Briefcase, ClipboardCheck, LayoutDashboard, Package, Plus, X } from "lucide-react";
import clsx from "clsx";
import { QUICK_ACTIONS } from "./QuickNewMenu";
import EmptyState from "../shared/EmptyState";

const LEFT_NAV = [
  { path: "/", label: "Home", icon: <LayoutDashboard size={20} />, end: true },
  { path: "/jobs", label: "Jobs", icon: <ClipboardCheck size={20} /> },
];
const RIGHT_NAV = [
  { path: "/inventory", label: "Stock", icon: <Package size={20} /> },
  { path: "/sales", label: "Sales", icon: <Briefcase size={20} /> },
];

export default function BottomNav() {
  const [open, setOpen] = useState(false);
  const [blocked, setBlocked] = useState<string | null>(null);
  const navigate = useNavigate();
  return (
    <nav className="fixed bottom-0 left-0 right-0 z-40 bg-[var(--surface)] border-t border-[var(--border)] flex md:hidden" style={{ paddingBottom: "env(safe-area-inset-bottom, 0px)" }}>
      {LEFT_NAV.map((item) => (
        <NavLink key={item.path} to={item.path} end={item.end} className={({ isActive }) => clsx("flex-1 flex flex-col items-center gap-0.5 py-2 text-[10px] font-medium", isActive ? "text-[var(--brand)]" : "text-[var(--text-3)]")}>
          {item.icon}
          {item.label}
        </NavLink>
      ))}
      <div className="flex items-center justify-center px-3">
        <button type="button" aria-label="Quick actions" onClick={() => { setBlocked(null); setOpen(true); }} className="w-12 h-12 -mt-6 rounded-full text-white shadow-lg flex items-center justify-center" style={{ background: "var(--brand-fill)" }}>
          <Plus size={22} />
        </button>
      </div>
      {RIGHT_NAV.map((item) => (
        <NavLink key={item.path} to={item.path} className={({ isActive }) => clsx("flex-1 flex flex-col items-center gap-0.5 py-2 text-[10px] font-medium", isActive ? "text-[var(--brand)]" : "text-[var(--text-3)]")}>
          {item.icon}
          {item.label}
        </NavLink>
      ))}
      {open && (
        <div className="fixed inset-0 z-50 bg-black/40 md:hidden" onClick={() => setOpen(false)}>
          <div className="absolute bottom-0 left-0 right-0 rounded-t-2xl bg-[var(--surface)] p-4" style={{ borderTop: "1px solid var(--border)" }} onClick={(event) => event.stopPropagation()}>
            <div className="flex items-center justify-between mb-3">
              <h2 className="text-sm font-bold">Quick Actions</h2>
              <button type="button" aria-label="Close quick actions" onClick={() => setOpen(false)}><X size={16} /></button>
            </div>
            {blocked ? (
              <EmptyState title="No records" body="There is no customer API record to attach to this action." />
            ) : (
              <div className="grid grid-cols-2 gap-2">
                {QUICK_ACTIONS.map((item) => (
                  <button
                    key={item.label}
                    type="button"
                    className="flex items-center gap-2 rounded-xl border border-[var(--border)] px-3 py-3 text-left text-sm font-semibold"
                    onClick={() => {
                      if (item.path) {
                        setOpen(false);
                        navigate(item.path);
                        return;
                      }
                      setBlocked(item.label);
                    }}
                  >
                    <item.icon size={16} />
                    {item.label}
                  </button>
                ))}
              </div>
            )}
          </div>
        </div>
      )}
    </nav>
  );
}
