import type { ReactNode } from "react";
import { Search } from "lucide-react";

export default function FilterBar({
  search,
  onSearch,
  placeholder,
  children,
}: {
  search: string;
  onSearch: (value: string) => void;
  placeholder: string;
  children?: ReactNode;
}) {
  return (
    <div className="flex-shrink-0 flex items-center gap-3 px-6 py-3 border-b" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
      <div className="eoss-search-wrap flex-1" style={{ width: "auto", maxWidth: "none" }}>
        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--text-3)" }} />
        <input value={search} onChange={(event) => onSearch(event.target.value)} placeholder={placeholder} aria-label={placeholder} className="eoss-search-input" />
      </div>
      {children}
      {search && <button type="button" onClick={() => onSearch("")} className="text-xs underline" style={{ color: "var(--text-3)" }}>Clear</button>}
    </div>
  );
}
