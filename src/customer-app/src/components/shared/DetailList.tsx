import type { ReactNode } from "react";

export default function DetailList({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl>
      {items.map((item) => (
        <div key={item.label} className="flex justify-between gap-3 py-1.5 text-sm" style={{ borderBottom: "1px solid var(--border)" }}>
          <dt style={{ color: "var(--text-3)" }}>{item.label}</dt>
          <dd className="text-right">{item.value || "—"}</dd>
        </div>
      ))}
    </dl>
  );
}
