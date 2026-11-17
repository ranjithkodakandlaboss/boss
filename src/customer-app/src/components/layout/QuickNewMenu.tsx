import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ChevronDown, ClipboardCheck, FileInput, PackagePlus, Plus, ShoppingCart, Truck, Wrench, type LucideIcon } from "lucide-react";
import Modal from "../shared/Modal";
import EmptyState from "../shared/EmptyState";

type QuickAction = "receive" | "job" | "inspection" | "dispatch";

export const QUICK_ACTIONS: Array<{ icon: LucideIcon; label: string; path?: string; action?: QuickAction }> = [
  { icon: ShoppingCart, label: "Customer RFQ", path: "/sales/rfq/new" },
  { icon: FileInput, label: "Purchase Request", path: "/procurement/pr/new" },
  { icon: PackagePlus, label: "Receive Stock", action: "receive" },
  { icon: Wrench, label: "Update Job", action: "job" },
  { icon: ClipboardCheck, label: "Inspection", action: "inspection" },
  { icon: Truck, label: "Dispatch", action: "dispatch" },
];

export default function QuickNewMenu() {
  const navigate = useNavigate();
  const [menuOpen, setMenuOpen] = useState(false);
  const [picker, setPicker] = useState<string | null>(null);

  return (
    <>
      <div className="relative">
        <button
          type="button"
          onClick={() => setMenuOpen((open) => !open)}
          className="inline-flex items-center gap-1 px-2.5 sm:px-3 py-1.5 rounded-full text-xs font-semibold text-white shadow-sm hover:opacity-95"
          style={{ background: "var(--brand-fill)" }}
        >
          <Plus size={13} />
          <span>New</span>
          <ChevronDown size={11} className="hidden sm:inline opacity-80" />
        </button>
        {menuOpen && (
          <>
            <div className="fixed inset-0 z-30" onClick={() => setMenuOpen(false)} />
            <div className="absolute right-0 mt-2 w-48 rounded-xl border shadow-xl z-40 py-1.5" style={{ background: "var(--surface)", borderColor: "var(--border)" }}>
              {QUICK_ACTIONS.map((item) => (
                <button
                  key={item.label}
                  type="button"
                  className="w-full flex items-center gap-2 px-3 py-2 text-xs font-medium text-[var(--text)] hover:bg-[var(--surface-2)]"
                  onClick={() => {
                    setMenuOpen(false);
                    if (item.path) navigate(item.path);
                    else setPicker(item.label);
                  }}
                >
                  <item.icon size={14} />
                  {item.label}
                </button>
              ))}
            </div>
          </>
        )}
      </div>
      <Modal open={picker !== null} title={picker ?? "New"} onClose={() => setPicker(null)}>
        <EmptyState title="No records" body="There is no customer API record to attach to this action." />
      </Modal>
    </>
  );
}
