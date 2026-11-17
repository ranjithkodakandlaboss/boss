import { useEffect, useState } from "react";
import { Search, X } from "lucide-react";
import EmptyState from "../shared/EmptyState";

export default function GlobalSearchModal({ open, onClose }: { open: boolean; onClose: () => void }) {
  const [query, setQuery] = useState("");

  useEffect(() => {
    if (!open) setQuery("");
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onClose, open]);

  if (!open) return null;
  return (
    <div className="fixed inset-0 z-50 flex items-start justify-center bg-black/40 p-4 pt-[12vh]">
      <div role="dialog" aria-modal="true" aria-label="Global record search" className="w-full max-w-xl rounded-2xl border bg-[var(--surface)] shadow-2xl" style={{ borderColor: "var(--border)" }}>
        <div className="flex items-center gap-2 border-b px-4" style={{ borderColor: "var(--border)" }}>
          <Search size={16} className="text-[var(--text-3)]" />
          <input
            autoFocus
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search orders, quotes, jobs, approvals, materials..."
            className="h-12 flex-1 bg-transparent text-sm outline-none"
          />
          <button type="button" onClick={onClose} aria-label="Close search"><X size={16} /></button>
        </div>
        <EmptyState title="No matching records" body={query ? `No customer API results for “${query}”.` : "Search runs against the customer API. No records are loaded."} />
      </div>
    </div>
  );
}
