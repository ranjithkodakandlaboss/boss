import type { CSSProperties } from "react";
import { ArrowDown, ArrowUp, ArrowUpDown } from "lucide-react";

export type SortDirection = "asc" | "desc";

export default function SortableHeader({
  label,
  active = false,
  direction = "asc",
  onSort,
  className = "",
  style,
}: {
  label: string;
  active?: boolean;
  direction?: SortDirection;
  onSort?: () => void;
  className?: string;
  style?: CSSProperties;
}) {
  const isRight = className.includes("text-right");
  const isCenter = className.includes("text-center");
  const alignClass = isRight ? "justify-end text-right" : isCenter ? "justify-center text-center" : "justify-start text-left";

  if (!onSort) return <th className={`whitespace-nowrap ${className}`} style={style}>{label}</th>;
  return (
    <th className={`whitespace-nowrap ${className}`} style={style} aria-sort={active ? (direction === "asc" ? "ascending" : "descending") : "none"}>
      <button
        type="button"
        onClick={onSort}
        className={`w-full inline-flex items-center gap-1 ${alignClass} hover:opacity-70 focus:outline-none focus-visible:ring-2 focus-visible:ring-[var(--brand)] rounded`}
        title={`Sort by ${label}`}
      >
        {label}
        {active ? (direction === "asc" ? <ArrowUp size={12} /> : <ArrowDown size={12} />) : <ArrowUpDown size={12} className="opacity-40" />}
      </button>
    </th>
  );
}
