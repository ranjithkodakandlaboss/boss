import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { Box, FileText, Settings, ShieldCheck, ShoppingCart, Truck, Users } from "lucide-react";

function greeting() {
  const hour = new Date().getHours();
  if (hour < 12) return "Good morning";
  if (hour < 17) return "Good afternoon";
  return "Good evening";
}

const KPIS = [
  { label: "Sales", path: "/sales", icon: <span className="text-[#16A34A]">↗</span>, bg: "linear-gradient(135deg, #DCFCE7 0%, #BBF7D0 100%)" },
  { label: "Receivables", path: "/finance?tab=receivables", icon: <Users size={15} className="text-[#2563EB]" />, bg: "linear-gradient(135deg, #DBEAFE 0%, #BFDBFE 100%)" },
  { label: "Payables", path: "/finance?tab=payables", icon: <FileText size={15} className="text-[#DC2626]" />, bg: "linear-gradient(135deg, #FEE2E2 0%, #FECACA 100%)" },
  { label: "Stock Value", path: "/inventory", icon: <Box size={15} className="text-[#EA580C]" />, bg: "linear-gradient(135deg, #FFEDD5 0%, #FED7AA 100%)" },
];

const FLOW = [
  { name: "Sales Orders", detail: "—", path: "/sales", icon: <FileText size={20} className="text-purple-600" />, bg: "linear-gradient(180deg, #FAF8FF 0%, #EDE9FE 100%)" },
  { name: "Material", detail: "— Shortages", path: "/inventory", icon: <ShoppingCart size={20} className="text-orange-600" />, bg: "linear-gradient(180deg, #FFF9F5 0%, #FFEDD5 100%)" },
  { name: "Production", detail: "— In Progress", path: "/jobs", icon: <Settings size={20} className="text-blue-600" />, bg: "linear-gradient(180deg, #F5FAFF 0%, #DBEAFE 100%)" },
  { name: "Quality", detail: "— Open", path: "/quality", icon: <ShieldCheck size={20} className="text-amber-600" />, bg: "linear-gradient(180deg, #FFFCF2 0%, #FEF3C7 100%)" },
  { name: "Dispatch", detail: "— Ready", path: "/dispatch", icon: <Truck size={20} className="text-emerald-600" />, bg: "linear-gradient(180deg, #F3FDF7 0%, #DCFCE7 100%)" },
];

export default function HomePage() {
  const navigate = useNavigate();
  const [mobileTab, setMobileTab] = useState<"today" | "inbox">("today");
  return (
    <div className="p-3 pb-20 md:px-5 md:py-3 space-y-3">
      <h1 className="text-lg md:text-xl font-extrabold tracking-tight text-[var(--text)]">{greeting()}</h1>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
        {KPIS.map((kpi) => (
          <button key={kpi.label} type="button" onClick={() => navigate(kpi.path)} className="text-left px-3 py-2.5 rounded-2xl border flex items-center gap-2.5" style={{ background: "var(--card-gradient, #fff)", borderColor: "var(--border)" }}>
            <span className="w-8 h-8 rounded-xl flex items-center justify-center" style={{ background: kpi.bg }}>{kpi.icon}</span>
            <span>
              <span className="block text-[11px] text-[var(--text-2)]">{kpi.label}</span>
              <span className="block text-sm font-bold">—</span>
            </span>
          </button>
        ))}
      </div>
      <div className="md:hidden inline-flex rounded-xl border p-0.5" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <button type="button" onClick={() => setMobileTab("today")} className="px-3 py-1 rounded-lg text-xs font-semibold" style={{ background: mobileTab === "today" ? "var(--brand-fill)" : "transparent", color: mobileTab === "today" ? "#fff" : "var(--text-2)" }}>Today</button>
        <button type="button" onClick={() => setMobileTab("inbox")} className="px-3 py-1 rounded-lg text-xs font-semibold" style={{ background: mobileTab === "inbox" ? "var(--brand-fill)" : "transparent", color: mobileTab === "inbox" ? "#fff" : "var(--text-2)" }}>Inbox (0)</button>
      </div>
      <div className="hidden md:grid md:grid-cols-2 gap-4">
        <section className="eoss-card p-4">
          <h2 className="text-sm font-bold">Today</h2>
          <p className="text-xs text-[var(--text-2)] mt-2">No items need attention.</p>
        </section>
        <section className="eoss-card p-4">
          <h2 className="text-sm font-bold">Inbox</h2>
          <p className="text-xs text-[var(--text-2)] mt-2">No inbox messages.</p>
        </section>
      </div>
      <section className="eoss-card p-4 md:hidden">
        <h2 className="text-sm font-bold">{mobileTab === "inbox" ? "Inbox" : "Today"}</h2>
        <p className="text-xs text-[var(--text-2)] mt-2">{mobileTab === "inbox" ? "No inbox messages." : "No items need attention."}</p>
      </section>
      <div className="grid grid-cols-2 md:grid-cols-5 gap-2">
        {FLOW.map((step) => (
          <button key={step.name} type="button" onClick={() => navigate(step.path)} className="rounded-2xl border p-3 text-left" style={{ background: step.bg, borderColor: "var(--border)" }}>
            {step.icon}
            <div className="mt-2 text-sm font-bold">{step.name}</div>
            <div className="text-xs text-[var(--text-2)]">{step.detail}</div>
          </button>
        ))}
      </div>
      <section className="eoss-card p-4">
        <p className="text-xs font-bold uppercase tracking-wider">This week</p>
        <p className="text-xs text-[var(--text-2)] mt-2">No scheduled activity.</p>
      </section>
      <form className="eoss-card p-3" onSubmit={(event) => event.preventDefault()}>
        <label className="text-xs font-bold" htmlFor="ask-boss">Ask BOSS</label>
        <input id="ask-boss" className="eoss-input w-full mt-2" placeholder="Ask about late jobs, approvals, shortages…" />
        <p className="text-xs text-[var(--text-2)] mt-2">Ask BOSS is not connected. No answer is invented.</p>
      </form>
    </div>
  );
}
