import clsx from "clsx";

export default function Tabs({
  tabs,
  active,
  onChange,
}: {
  tabs: { id: string; label: string }[];
  active: string;
  onChange: (id: string) => void;
}) {
  return (
    <div className="flex-shrink-0 flex items-center gap-0 px-6 border-b overflow-x-auto" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
      {tabs.map((tab) => (
        <button
          key={tab.id}
          type="button"
          onClick={() => onChange(tab.id)}
          className={clsx("px-4 py-3 text-sm font-medium transition-colors relative whitespace-nowrap", active === tab.id ? "font-semibold" : "hover:opacity-80")}
          style={{ color: active === tab.id ? "var(--brand)" : "var(--text-2)" }}
        >
          {tab.label}
          {active === tab.id && <span className="absolute bottom-0 left-0 right-0 h-0.5 rounded-t-full" style={{ background: "var(--brand-fill)" }} />}
        </button>
      ))}
    </div>
  );
}
