import { ReactNode, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ArrowUpRight, ChevronRight, Clock, DollarSign, FileText, Pencil, Plus, Search, XCircle } from "lucide-react";
import StatusChip from "../components/shared/StatusChip";
import { Cpo, Customer, Quotation, Rfq, inr, salesApi, when } from "./api";
import { useLoad } from "./load";

type Tab = "pos" | "rfqs" | "quotations" | "customers";

function poChip(status: string) {
  if (status === "open") return "active";
  if (status === "fulfilled") return "complete";
  if (status === "on_hold") return "hold";
  return status;
}

function poMatches(status: string, filter: string) {
  if (filter === "all") return true;
  if (filter === "active") return status === "open";
  if (filter === "complete") return status === "fulfilled";
  if (filter === "pending") return false;
  return status === filter;
}

function rfqChip(rfq: Rfq, quoted: boolean) {
  if (rfq.status === "cancelled") return "cancelled";
  if (rfq.status === "closed") return "converted";
  if (quoted) return "quoted";
  return "open";
}

function lakhs(value: number) {
  return `₹${(value / 100000).toFixed(1)}L`;
}

function poValue(po: Cpo) {
  return po.lines.reduce((sum, line) => sum + line.lineTotal, 0) - (po.discountAmount ?? 0);
}

function quoteValue(quote: Quotation) {
  return quote.lines.reduce((sum, line) => sum + (line.lineTotal ?? 0), 0) - (quote.discountAmount ?? 0);
}

function terms(days?: number) {
  return days != null ? `Net ${days}` : "—";
}

export function SalesHome() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = (params.get("tab") as Tab) || "pos";
  const [search, setSearch] = useState(params.get("q") ?? "");
  const [statusFilter, setStatusFilter] = useState(params.get("status") ?? "all");
  const [rfqStatusFilter, setRfqStatusFilter] = useState(params.get("rfqStatus") ?? "all");
  const [customerStatus, setCustomerStatus] = useState(params.get("customerStatus") ?? "all");
  const [sortKey, setSortKey] = useState(params.get("sort") ?? "date");
  const [sortDir, setSortDir] = useState<"asc" | "desc">(params.get("dir") === "asc" ? "asc" : "desc");
  const [selectedPoId, setSelectedPoId] = useState<string | null>(null);
  const pos = useLoad(() => salesApi.cpos(), []);
  const rfqs = useLoad(() => salesApi.rfqs(), []);
  const quotes = useLoad(() => salesApi.quotations(), []);
  const customers = useLoad(() => salesApi.customers(), []);
  const quoteByRfq = useMemo(() => {
    const map = new Map<string, Quotation>();
    (quotes.data ?? []).forEach((quote) => { if (quote.rfqId && !map.has(quote.rfqId)) map.set(quote.rfqId, quote); });
    return map;
  }, [quotes.data]);

  function setTab(next: Tab) {
    const updated = new URLSearchParams(params);
    updated.set("tab", next);
    setParams(updated, { replace: true });
    setSelectedPoId(null);
  }

  function sort(key: string) {
    if (sortKey === key) setSortDir((direction) => direction === "asc" ? "desc" : "asc");
    else { setSortKey(key); setSortDir("asc"); }
  }

  const query = search.trim().toLowerCase();
  const poRows = (pos.data ?? []).filter((po) => poMatches(po.status, statusFilter) && (!query || po.cpoNumber.toLowerCase().includes(query) || po.customerName.toLowerCase().includes(query)));
  const rfqRows = (rfqs.data ?? []).filter((rfq) => {
    const chip = rfqChip(rfq, quoteByRfq.has(rfq.id));
    return (rfqStatusFilter === "all" || chip === rfqStatusFilter) && (!query || rfq.rfqNumber.toLowerCase().includes(query) || rfq.customerName.toLowerCase().includes(query));
  });
  const quoteRows = (quotes.data ?? []).filter((quote) => !query || quote.quotationNumber.toLowerCase().includes(query) || quote.customerName.toLowerCase().includes(query) || (quote.rfqNumber ?? "").toLowerCase().includes(query));
  const customerRows = (customers.data ?? []).filter((customer) => {
    const status = customer.status || "active";
    const haystack = `${customer.name} ${customer.legalName ?? ""} ${customer.gstin ?? ""} ${customer.address?.city ?? ""}`.toLowerCase();
    return (customerStatus === "all" || status === customerStatus) && (!query || haystack.includes(query));
  });
  const activePos = (pos.data ?? []).filter((po) => po.status === "open");
  const activeValue = activePos.reduce((sum, po) => sum + poValue(po), 0);
  const selected = (pos.data ?? []).find((po) => po.id === selectedPoId);

  return (
    <div className="flex flex-col h-full min-h-[calc(100vh-var(--topbar-h))]">
      <div className="flex-shrink-0 px-6 py-4 border-b flex items-center justify-between gap-4" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <div>
          <h1 className="text-lg font-bold" style={{ color: "var(--text)" }}>{tab === "pos" ? "Customer POs" : tab === "rfqs" ? "Customer RFQs" : tab === "quotations" ? "Quotations" : "Customer Directory"}</h1>
          <p className="text-xs mt-0.5" style={{ color: "var(--text-2)" }}>
            <button type="button" onClick={() => { setTab("pos"); setStatusFilter("active"); }} className="font-semibold" style={{ color: "var(--brand)" }}>{activePos.length} Active Customer POs</button>
            {" "}· {(customers.data ?? []).filter((customer) => customer.status !== "inactive").length} Active Customers · {lakhs(activeValue)} in progress
          </p>
        </div>
        <div className="flex items-center gap-2">
          {tab === "pos" && <button type="button" onClick={() => navigate("/sales/po/new")} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}><Plus size={15} /> Add Customer PO</button>}
          {tab === "rfqs" && <>
            <button type="button" onClick={() => navigate("/sales/quotation/new")} className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold border border-[var(--border)] bg-[var(--surface)] text-[var(--text)]"><Plus size={14} /> Direct Quotation</button>
            <button type="button" onClick={() => navigate("/sales/rfq/new")} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}><Plus size={15} /> Add RFQ</button>
          </>}
          {tab === "customers" && <button type="button" onClick={() => navigate("/sales/customers/new")} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}><Plus size={15} /> Add New Customer</button>}
        </div>
      </div>
      <div className="flex-shrink-0 flex items-center px-6 border-b" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        {([
          ["pos", `Customer POs (${poRows.length})`],
          ["rfqs", `RFQs (${rfqRows.length})`],
          ["quotations", `Quotations (${quoteRows.length})`],
          ["customers", "Customer Directory"],
        ] as [Tab, string][]).map(([id, label]) => (
          <button key={id} type="button" onClick={() => setTab(id)} className="px-4 py-3 text-sm relative whitespace-nowrap" style={{ color: tab === id ? "var(--brand)" : "var(--text-2)", fontWeight: tab === id ? 600 : 500 }}>
            {label}
            {tab === id && <span className="absolute bottom-0 left-0 right-0 h-0.5 rounded-t-full" style={{ background: "var(--brand-fill)" }} />}
          </button>
        ))}
      </div>
      {tab !== "customers" && <FilterBar search={search} setSearch={setSearch} placeholder={tab === "rfqs" ? "Search RFQ number or customer name" : tab === "quotations" ? "Search quotation number, RFQ or customer" : "Search Customer PO number or customer name"}>
        {tab === "pos" && <select aria-label="Customer PO status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} className="eoss-input min-h-[36px]"><option value="all">All Customer PO statuses</option><option value="pending">Pending</option><option value="active">Active</option><option value="partial">Partial</option><option value="complete">Complete</option><option value="on_hold">On Hold</option><option value="cancelled">Cancelled</option></select>}
        {tab === "rfqs" && <select aria-label="Customer RFQ status" value={rfqStatusFilter} onChange={(event) => setRfqStatusFilter(event.target.value)} className="eoss-input min-h-[36px]"><option value="all">All Customer RFQ statuses</option>{["open", "quoted", "converted", "lost", "cancelled"].map((status) => <option key={status} value={status}>{status[0].toUpperCase() + status.slice(1)}</option>)}</select>}
      </FilterBar>}
      {tab === "customers" && <FilterBar search={search} setSearch={setSearch} placeholder="Search customer name, GSTIN or city">
        <select aria-label="Customer status" value={customerStatus} onChange={(event) => setCustomerStatus(event.target.value)} className="eoss-input min-h-[36px]"><option value="all">All customer statuses</option><option value="active">Active</option><option value="inactive">Inactive</option></select>
      </FilterBar>}
      <div className="flex flex-1 overflow-hidden">
        <div className={selected && tab === "pos" ? "w-[58%] border-r overflow-auto" : "w-full overflow-auto"} style={{ borderColor: "var(--border)" }}>
          {tab === "pos" && <PoTable rows={poRows} loading={pos.loading} error={pos.error} selectedId={selectedPoId} sortKey={sortKey} sortDir={sortDir} onSort={sort} onSelect={setSelectedPoId} onOpen={(id) => navigate(`/sales/po/${id}`)} onAmend={(id) => navigate(`/sales/po/${id}/amend`)} onCancel={(id) => salesApi.cpoStatus(id, "cancelled").then(() => window.location.reload())} />}
          {tab === "rfqs" && <RfqTable rows={rfqRows} loading={rfqs.loading} error={rfqs.error} quoteByRfq={quoteByRfq} sortKey={sortKey} sortDir={sortDir} onSort={sort} onOpen={(id) => navigate(`/sales/rfq/${id}`)} onOpenQuote={(id) => navigate(`/sales/quotation/${id}`)} onQuote={(id) => navigate(`/sales/rfq/${id}/quotation/new`)} onCancel={(id) => salesApi.rfqStatus(id, "cancelled").then(() => window.location.reload())} />}
          {tab === "quotations" && <QuoteTable rows={quoteRows} loading={quotes.loading} error={quotes.error} onOpen={(id) => navigate(`/sales/quotation/${id}`)} />}
          {tab === "customers" && <CustomerTable rows={customerRows} loading={customers.loading} error={customers.error} pos={pos.data ?? []} rfqs={rfqs.data ?? []} sortKey={sortKey} sortDir={sortDir} onSort={sort} onOpen={(id) => navigate(`/sales/customers/${id}`)} />}
        </div>
        {tab === "pos" && selected && <PoPreview po={selected} onOpen={() => navigate(`/sales/po/${selected.id}`)} onAmend={() => navigate(`/sales/po/${selected.id}/amend`)} onClose={() => setSelectedPoId(null)} />}
      </div>
    </div>
  );
}

function FilterBar({ search, setSearch, placeholder, children }: { search: string; setSearch: (value: string) => void; placeholder: string; children?: ReactNode }) {
  return (
    <div className="flex-shrink-0 flex items-center gap-3 px-6 py-3 border-b" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
      <div className="eoss-search-wrap flex-1" style={{ width: "auto", maxWidth: "none" }}>
        <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--text-3)" }} />
        <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder={placeholder} className="eoss-search-input" />
      </div>
      {children}
      {search && <button type="button" onClick={() => setSearch("")} className="text-xs underline" style={{ color: "var(--text-3)" }}>Clear</button>}
    </div>
  );
}

function Head({ label, sortKey, active, dir, onSort }: { label: string; sortKey: string; active: string; dir: "asc" | "desc"; onSort: (key: string) => void }) {
  return <th className="text-left px-4 py-2.5 text-xs font-semibold" style={{ color: "var(--text-3)" }}><button type="button" onClick={() => onSort(sortKey)}>{label}{active === sortKey ? (dir === "asc" ? " ↑" : " ↓") : ""}</button></th>;
}

function PoTable({ rows, loading, error, selectedId, sortKey, sortDir, onSort, onSelect, onOpen, onAmend, onCancel }: {
  rows: Cpo[]; loading: boolean; error: string; selectedId: string | null; sortKey: string; sortDir: "asc" | "desc";
  onSort: (key: string) => void; onSelect: (id: string | null) => void; onOpen: (id: string) => void; onAmend: (id: string) => void; onCancel: (id: string) => void;
}) {
  const sorted = [...rows].sort((a, b) => compare(sortKey, sortDir, a, b, (po, key) => key === "number" ? po.cpoNumber : key === "name" ? po.customerName : key === "value" ? poValue(po) : key === "lines" ? po.lines.length : key === "terms" ? (po.paymentTerms ?? 0) : key === "status" ? poChip(po.status) : po.orderDate ?? ""));
  if (loading) return <p className="p-6 text-sm text-[var(--text-2)]">Loading Customer POs</p>;
  if (error) return <p className="p-6 text-sm text-[var(--error)]">{error}</p>;
  return (
    <table className="eoss-table">
      <thead className="sticky top-0 z-10" style={{ background: "var(--surface-2)" }}>
        <tr style={{ borderBottom: "1px solid var(--border)" }}>
          {[["PO Number", "number"], ["Customer", "name"], ["Date", "date"], ["Value", "value"], ["Lines", "lines"], ["Terms", "terms"], ["Status", "status"]].map(([label, key]) => <Head key={key} label={label} sortKey={key} active={sortKey} dir={sortDir} onSort={onSort} />)}
          <th />
        </tr>
      </thead>
      <tbody>
        {sorted.map((po) => {
          const selected = po.id === selectedId;
          const closed = po.status === "cancelled" || po.status === "fulfilled";
          return (
            <tr key={po.id} onClick={() => onSelect(selected ? null : po.id)} className="cursor-pointer" style={{ borderBottom: "1px solid var(--border)", background: selected ? "var(--surface-2)" : "var(--surface)", borderLeft: selected ? "3px solid var(--brand)" : "3px solid transparent" }}>
              <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{po.cpoNumber}</td>
              <td className="px-4 py-3 font-medium">{po.customerName}</td>
              <td className="px-4 py-3 text-xs text-[var(--text-2)]">{when(po.orderDate)}</td>
              <td className="px-4 py-3 font-mono font-semibold text-xs">{lakhs(poValue(po))}</td>
              <td className="px-4 py-3 text-center text-xs text-[var(--text-2)]">{po.lines.length}</td>
              <td className="px-4 py-3 text-xs text-[var(--text-2)]">{terms(po.paymentTerms)}</td>
              <td className="px-4 py-3"><StatusChip status={poChip(po.status)} /></td>
              <td className="px-4 py-3" onClick={(event) => event.stopPropagation()}>
                <div className="flex items-center gap-1">
                  <button type="button" onClick={() => onOpen(po.id)} className="text-xs px-2 py-1 rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Open</button>
                  {!closed && <button type="button" title="Amend Customer PO" onClick={() => onAmend(po.id)} className="p-1 rounded hover:bg-[var(--surface-2)]"><Pencil size={12} style={{ color: "var(--text-3)" }} /></button>}
                  {!closed && <button type="button" title="Cancel Customer PO" onClick={() => onCancel(po.id)} className="p-1 rounded hover:bg-[var(--surface-2)]"><XCircle size={12} style={{ color: "var(--error)" }} /></button>}
                </div>
              </td>
            </tr>
          );
        })}
        {sorted.length === 0 && <tr><td colSpan={8} className="text-center py-12 text-sm" style={{ color: "var(--text-3)" }}>No POs match the current filters</td></tr>}
      </tbody>
    </table>
  );
}

function RfqTable({ rows, loading, error, quoteByRfq, sortKey, sortDir, onSort, onOpen, onOpenQuote, onQuote, onCancel }: {
  rows: Rfq[]; loading: boolean; error: string; quoteByRfq: Map<string, Quotation>; sortKey: string; sortDir: "asc" | "desc";
  onSort: (key: string) => void; onOpen: (id: string) => void; onOpenQuote: (id: string) => void; onQuote: (id: string) => void; onCancel: (id: string) => void;
}) {
  if (loading) return <p className="p-6 text-sm text-[var(--text-2)]">Loading RFQs</p>;
  if (error) return <p className="p-6 text-sm text-[var(--error)]">{error}</p>;
  const sorted = [...rows].sort((a, b) => compare(sortKey, sortDir, a, b, (rfq, key) => key === "number" ? rfq.rfqNumber : key === "name" ? rfq.customerName : key === "lines" ? rfq.lines.length : key === "requiredBy" ? (rfq.requiredDate ?? "") : (rfq.receivedDate ?? "")));
  return (
    <table className="eoss-table">
      <thead className="sticky top-0 z-10" style={{ background: "var(--surface-2)" }}>
        <tr>{[["RFQ Number", "number"], ["Customer", "name"], ["Received", "date"], ["Required By", "requiredBy"], ["Lines", "lines"], ["Channel", "channel"], ["Quotation", "quotation"], ["Status", "status"], ["Owner", "owner"]].map(([label, key]) => <Head key={key} label={label} sortKey={key} active={sortKey} dir={sortDir} onSort={onSort} />)}<th /></tr>
      </thead>
      <tbody>
        {sorted.map((rfq) => {
          const quote = quoteByRfq.get(rfq.id);
          const chip = rfqChip(rfq, Boolean(quote));
          return (
            <tr key={rfq.id} onClick={() => onOpen(rfq.id)} className="cursor-pointer" style={{ borderBottom: "1px solid var(--border)" }}>
              <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{rfq.rfqNumber}</td>
              <td className="px-4 py-3 font-medium">{rfq.customerName}</td>
              <td className="px-4 py-3 text-xs text-[var(--text-2)]">{when(rfq.receivedDate)}</td>
              <td className="px-4 py-3 text-xs text-[var(--text-2)]">{when(rfq.requiredDate)}</td>
              <td className="px-4 py-3 text-center text-xs">{rfq.lines.length}</td>
              <td className="px-4 py-3 text-xs text-[var(--text-2)]">{rfq.receivedVia || "—"}</td>
              <td className="px-4 py-3 text-xs" onClick={(event) => event.stopPropagation()}>{quote ? <button type="button" className="font-mono font-semibold text-[var(--brand)]" onClick={() => onOpenQuote(quote.id)}>{quote.quotationNumber}</button> : <span className="text-[var(--text-3)]">Pending</span>}</td>
              <td className="px-4 py-3"><StatusChip status={chip} /></td>
              <td className="px-4 py-3 text-xs text-[var(--text-2)]">—</td>
              <td className="px-4 py-3" onClick={(event) => event.stopPropagation()}>
                <div className="flex gap-1">
                  {chip === "open" && <button type="button" onClick={() => onQuote(rfq.id)} className="text-xs px-2 py-1 rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Quote</button>}
                  {chip !== "cancelled" && chip !== "converted" && <button type="button" title="Cancel Customer RFQ" onClick={() => onCancel(rfq.id)}><XCircle size={12} style={{ color: "var(--error)" }} /></button>}
                </div>
              </td>
            </tr>
          );
        })}
        {sorted.length === 0 && <tr><td colSpan={10} className="text-center py-12 text-sm text-[var(--text-3)]">No RFQs found</td></tr>}
      </tbody>
    </table>
  );
}

function QuoteTable({ rows, loading, error, onOpen }: { rows: Quotation[]; loading: boolean; error: string; onOpen: (id: string) => void }) {
  if (loading) return <p className="p-6 text-sm">Loading quotations</p>;
  if (error) return <p className="p-6 text-sm text-[var(--error)]">{error}</p>;
  return (
    <table className="w-full text-sm">
      <thead style={{ background: "var(--surface-2)", borderBottom: "1px solid var(--border)" }}>
        <tr>{["Quotation #", "Customer", "RFQ Ref", "Quoted Date", "Valid Until", "Total Value", "Status"].map((label) => <th key={label} className="text-left px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">{label}</th>)}</tr>
      </thead>
      <tbody>
        {rows.map((quote) => (
          <tr key={quote.id} onClick={() => onOpen(quote.id)} className="cursor-pointer hover:bg-[var(--surface-2)]" style={{ borderBottom: "1px solid var(--border)" }}>
            <td className="px-4 py-3 font-mono text-xs font-bold text-[var(--brand)]">{quote.quotationNumber}{quote.revision > 1 ? ` · Rev ${quote.revision}` : ""}</td>
            <td className="px-4 py-3 font-medium">{quote.customerName}</td>
            <td className="px-4 py-3 font-mono text-xs text-[var(--text-2)]">{quote.rfqNumber ?? "—"}</td>
            <td className="px-4 py-3 text-xs text-[var(--text-2)]">{when(quote.createdAt)}</td>
            <td className="px-4 py-3 text-xs text-[var(--text-2)]">{when(quote.validUntil)}</td>
            <td className="px-4 py-3 text-right font-mono text-xs font-semibold">{inr(quoteValue(quote))}</td>
            <td className="px-4 py-3"><StatusChip status={quote.status === "sent" ? "submitted" : quote.status} /></td>
          </tr>
        ))}
        {rows.length === 0 && <tr><td colSpan={7} className="text-center py-12 text-sm text-[var(--text-3)]">No quotations found</td></tr>}
      </tbody>
    </table>
  );
}

function CustomerTable({ rows, loading, error, pos, rfqs, sortKey, sortDir, onSort, onOpen }: {
  rows: Customer[]; loading: boolean; error: string; pos: Cpo[]; rfqs: Rfq[]; sortKey: string; sortDir: "asc" | "desc"; onSort: (key: string) => void; onOpen: (id: string) => void;
}) {
  if (loading) return <p className="p-6 text-sm">Loading customers</p>;
  if (error) return <p className="p-6 text-sm text-[var(--error)]">{error}</p>;
  const sorted = [...rows].sort((a, b) => compare(sortKey, sortDir, a, b, (customer, key) => key === "gstin" ? (customer.gstin ?? "") : key === "contact" ? (customer.contacts?.[0]?.name ?? "") : key === "terms" ? (customer.creditDays ?? 0) : key === "pos" ? pos.filter((po) => po.customerId === customer.id).length : key === "rfqs" ? rfqs.filter((rfq) => rfq.customerId === customer.id).length : customer.name));
  return (
    <table className="eoss-table">
      <thead><tr style={{ background: "var(--surface-2)" }}>{[["Customer", "customer"], ["GSTIN / State", "gstin"], ["Contact", "contact"], ["Terms", "terms"], ["Customer POs", "pos"], ["RFQs", "rfqs"], ["Rating", "rating"], ["Status", "status"]].map(([label, key]) => <Head key={key} label={label} sortKey={key} active={sortKey} dir={sortDir} onSort={onSort} />)}</tr></thead>
      <tbody>
        {sorted.map((customer) => (
          <tr key={customer.id} onClick={() => onOpen(customer.id)} className="cursor-pointer" style={{ borderBottom: "1px solid var(--border)" }}>
            <td className="px-4 py-3"><div className="font-semibold">{customer.name}</div><div className="text-xs text-[var(--text-3)]">{customer.address?.city}{customer.address?.state ? `, ${customer.address.state}` : ""}</div></td>
            <td className="px-4 py-3 text-xs font-mono">{customer.gstin || "—"}<div className="text-[var(--text-3)]">{customer.address?.state || ""}</div></td>
            <td className="px-4 py-3 text-xs">{customer.contacts?.[0]?.name || "—"}<div className="text-[var(--text-3)]">{customer.contacts?.[0]?.phone || customer.contacts?.[0]?.email || ""}</div></td>
            <td className="px-4 py-3 text-xs">{terms(customer.creditDays)}</td>
            <td className="px-4 py-3 text-xs">{pos.filter((po) => po.customerId === customer.id).length}</td>
            <td className="px-4 py-3 text-xs">{rfqs.filter((rfq) => rfq.customerId === customer.id).length}</td>
            <td className="px-4 py-3 text-xs text-[var(--text-3)]">—</td>
            <td className="px-4 py-3"><StatusChip status={customer.status || "active"} /></td>
          </tr>
        ))}
        {sorted.length === 0 && <tr><td colSpan={8} className="text-center py-12 text-sm text-[var(--text-3)]">No customers found</td></tr>}
      </tbody>
    </table>
  );
}

function PoPreview({ po, onOpen, onAmend, onClose }: { po: Cpo; onOpen: () => void; onAmend: () => void; onClose: () => void }) {
  return (
    <div className="w-[42%] overflow-auto flex flex-col" style={{ background: "var(--surface)" }}>
      <div className="px-5 py-4 border-b flex items-start justify-between" style={{ borderColor: "var(--border)" }}>
        <div>
          <div className="flex items-center gap-2"><span className="font-mono font-bold" style={{ color: "var(--brand)" }}>{po.cpoNumber}</span><StatusChip status={poChip(po.status)} /></div>
          <p className="text-sm mt-0.5 font-medium">{po.customerName}</p>
        </div>
        <button type="button" onClick={onOpen} className="flex items-center gap-1 text-xs font-semibold px-3 py-1.5 rounded-lg border" style={{ borderColor: "var(--brand)", color: "var(--brand)" }}>Open full record <ArrowUpRight size={12} /></button>
      </div>
      <div className="px-5 py-4 border-b grid grid-cols-3 gap-4" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
        <Metric icon={<DollarSign size={14} />} label="Total Value" value={lakhs(poValue(po))} />
        <Metric icon={<FileText size={14} />} label="Line Items" value={String(po.lines.length)} />
        <Metric icon={<Clock size={14} />} label="Terms" value={terms(po.paymentTerms)} />
      </div>
      <div className="px-5 py-4 space-y-4">
        <section>
          <h3 className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: "var(--text-3)" }}>PO Details</h3>
          <Field label="Accepted Date" value={when(po.orderDate)} />
          <Field label="Customer reference" value={po.customerPoRef || "—"} />
          <Field label="Quotation Ref" value={po.quotationNumber ? `${po.quotationNumber} rev ${po.quotationRevision ?? ""}` : "—"} />
          <Field label="Delivery Terms" value="—" />
        </section>
        <section>
          <h3 className="text-xs font-semibold uppercase tracking-wide mb-2" style={{ color: "var(--text-3)" }}>Linked Jobs (0)</h3>
          <p className="text-xs" style={{ color: "var(--text-3)" }}>No jobs linked to this PO yet.</p>
        </section>
        <div className="flex gap-2">
          <button type="button" onClick={onOpen} className="flex-1 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Open Full Record</button>
          <button type="button" onClick={onAmend} className="px-4 py-2 rounded-lg text-sm font-semibold border" style={{ borderColor: "var(--border)" }}>Amend</button>
          <button type="button" onClick={onClose} className="px-3 py-2 text-sm" aria-label="Close preview"><ChevronRight size={14} /></button>
        </div>
      </div>
    </div>
  );
}

function Metric({ icon, label, value }: { icon: ReactNode; label: string; value: string }) {
  return <div><div className="flex items-center gap-1 text-[10px] uppercase" style={{ color: "var(--text-3)" }}>{icon}{label}</div><div className="text-sm font-semibold mt-1">{value}</div></div>;
}

function Field({ label, value }: { label: string; value: string }) {
  return <div className="flex justify-between gap-3 py-1 text-sm"><span style={{ color: "var(--text-3)" }}>{label}</span><span>{value}</span></div>;
}

function compare<T>(key: string, dir: "asc" | "desc", a: T, b: T, value: (row: T, key: string) => string | number) {
  const left = value(a, key);
  const right = value(b, key);
  const result = typeof left === "number" && typeof right === "number" ? left - right : String(left).localeCompare(String(right));
  return dir === "asc" ? result : -result;
}
