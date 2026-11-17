import { ReactNode, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ArrowUpRight, ChevronRight, Clock, DollarSign, FileText, Pencil, Plus, XCircle } from "lucide-react";
import StatusChip from "../components/shared/StatusChip";
import FilterBar from "../components/shared/FilterBar";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import DetailList from "../components/shared/DetailList";
import ConfirmDialog from "../components/shared/ConfirmDialog";
import type { SortDirection } from "../components/shared/SortableHeader";
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
  const [sortDir, setSortDir] = useState<SortDirection>(params.get("dir") === "asc" ? "asc" : "desc");
  const [selectedPoId, setSelectedPoId] = useState<string | null>(null);
  const [pendingCancel, setPendingCancel] = useState<{ kind: "po" | "rfq"; id: string } | null>(null);
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

  async function confirmCancel() {
    if (!pendingCancel) return;
    const target = pendingCancel;
    setPendingCancel(null);
    if (target.kind === "po") await salesApi.cpoStatus(target.id, "cancelled");
    else await salesApi.rfqStatus(target.id, "cancelled");
    window.location.reload();
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
      <FilterBar
        search={search}
        onSearch={setSearch}
        placeholder={tab === "rfqs" ? "Search RFQ number or customer name" : tab === "quotations" ? "Search quotation number, RFQ or customer" : tab === "customers" ? "Search customer name, GSTIN or city" : "Search Customer PO number or customer name"}
      >
        {tab === "pos" && <select aria-label="Customer PO status" value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} className="eoss-input min-h-[36px]"><option value="all">All Customer PO statuses</option><option value="pending">Pending</option><option value="active">Active</option><option value="partial">Partial</option><option value="complete">Complete</option><option value="on_hold">On Hold</option><option value="cancelled">Cancelled</option></select>}
        {tab === "rfqs" && <select aria-label="Customer RFQ status" value={rfqStatusFilter} onChange={(event) => setRfqStatusFilter(event.target.value)} className="eoss-input min-h-[36px]"><option value="all">All Customer RFQ statuses</option>{["open", "quoted", "converted", "lost", "cancelled"].map((status) => <option key={status} value={status}>{status[0].toUpperCase() + status.slice(1)}</option>)}</select>}
        {tab === "customers" && <select aria-label="Customer status" value={customerStatus} onChange={(event) => setCustomerStatus(event.target.value)} className="eoss-input min-h-[36px]"><option value="all">All customer statuses</option><option value="active">Active</option><option value="inactive">Inactive</option></select>}
      </FilterBar>
      <div className="flex flex-1 overflow-hidden">
        <div className={selected && tab === "pos" ? "w-[58%] border-r overflow-auto" : "w-full overflow-auto"} style={{ borderColor: "var(--border)" }}>
          {tab === "pos" && <PoTable rows={poRows} loading={pos.loading} error={pos.error} selectedId={selectedPoId} sortKey={sortKey} sortDir={sortDir} onSort={sort} onSelect={setSelectedPoId} onOpen={(id) => navigate(`/sales/po/${id}`)} onAmend={(id) => navigate(`/sales/po/${id}/amend`)} onCancel={(id) => setPendingCancel({ kind: "po", id })} />}
          {tab === "rfqs" && <RfqTable rows={rfqRows} loading={rfqs.loading} error={rfqs.error} quoteByRfq={quoteByRfq} sortKey={sortKey} sortDir={sortDir} onSort={sort} onOpen={(id) => navigate(`/sales/rfq/${id}`)} onOpenQuote={(id) => navigate(`/sales/quotation/${id}`)} onQuote={(id) => navigate(`/sales/rfq/${id}/quotation/new`)} onCancel={(id) => setPendingCancel({ kind: "rfq", id })} />}
          {tab === "quotations" && <QuoteTable rows={quoteRows} loading={quotes.loading} error={quotes.error} sortKey={sortKey} sortDir={sortDir} onSort={sort} onOpen={(id) => navigate(`/sales/quotation/${id}`)} />}
          {tab === "customers" && <CustomerTable rows={customerRows} loading={customers.loading} error={customers.error} pos={pos.data ?? []} rfqs={rfqs.data ?? []} sortKey={sortKey} sortDir={sortDir} onSort={sort} onOpen={(id) => navigate(`/sales/customers/${id}`)} />}
        </div>
        {tab === "pos" && selected && <PoPreview po={selected} onOpen={() => navigate(`/sales/po/${selected.id}`)} onAmend={() => navigate(`/sales/po/${selected.id}/amend`)} onClose={() => setSelectedPoId(null)} />}
      </div>
      <ConfirmDialog
        open={pendingCancel !== null}
        title={pendingCancel?.kind === "rfq" ? "Cancel Customer RFQ" : "Cancel Customer PO"}
        body="The record stays in history. This does not delete it."
        confirmLabel="Cancel record"
        variant="danger"
        onConfirm={confirmCancel}
        onCancel={() => setPendingCancel(null)}
      />
    </div>
  );
}

function PoTable({ rows, loading, error, selectedId, sortKey, sortDir, onSort, onSelect, onOpen, onAmend, onCancel }: {
  rows: Cpo[]; loading: boolean; error: string; selectedId: string | null; sortKey: string; sortDir: SortDirection;
  onSort: (key: string) => void; onSelect: (id: string | null) => void; onOpen: (id: string) => void; onAmend: (id: string) => void; onCancel: (id: string) => void;
}) {
  const columns: RecordColumn<Cpo>[] = [
    { key: "number", label: "PO Number", value: (po) => po.cpoNumber, render: (po) => <span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{po.cpoNumber}</span> },
    { key: "name", label: "Customer", value: (po) => po.customerName, render: (po) => <span className="font-medium">{po.customerName}</span> },
    { key: "date", label: "Date", value: (po) => po.orderDate ?? "", render: (po) => <span className="text-xs text-[var(--text-2)]">{when(po.orderDate)}</span> },
    { key: "value", label: "Value", align: "right", value: (po) => poValue(po), render: (po) => <span className="font-mono font-semibold text-xs">{lakhs(poValue(po))}</span> },
    { key: "lines", label: "Lines", align: "center", value: (po) => po.lines.length, render: (po) => <span className="text-xs text-[var(--text-2)]">{po.lines.length}</span> },
    { key: "terms", label: "Terms", value: (po) => po.paymentTerms ?? 0, render: (po) => <span className="text-xs text-[var(--text-2)]">{terms(po.paymentTerms)}</span> },
    { key: "status", label: "Status", value: (po) => poChip(po.status), render: (po) => <StatusChip status={poChip(po.status)} /> },
  ];
  return (
    <RecordTable rows={rows} columns={columns} sortKey={sortKey} sortDir={sortDir} onSort={onSort} selectedId={selectedId} loading={loading} error={error} empty="No POs match the current filters" onRow={(po) => onSelect(po.id === selectedId ? null : po.id)} trailing={(po) => {
      const closed = po.status === "cancelled" || po.status === "fulfilled";
      return (
        <div className="flex items-center gap-1">
          <button type="button" onClick={() => onOpen(po.id)} className="text-xs px-2 py-1 rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Open</button>
          {!closed && <button type="button" title="Amend Customer PO" onClick={() => onAmend(po.id)} className="p-1 rounded hover:bg-[var(--surface-2)]"><Pencil size={12} style={{ color: "var(--text-3)" }} /></button>}
          {!closed && <button type="button" title="Cancel Customer PO" onClick={() => onCancel(po.id)} className="p-1 rounded hover:bg-[var(--surface-2)]"><XCircle size={12} style={{ color: "var(--error)" }} /></button>}
        </div>
      );
    }} />
  );
}

function RfqTable({ rows, loading, error, quoteByRfq, sortKey, sortDir, onSort, onOpen, onOpenQuote, onQuote, onCancel }: {
  rows: Rfq[]; loading: boolean; error: string; quoteByRfq: Map<string, Quotation>; sortKey: string; sortDir: SortDirection;
  onSort: (key: string) => void; onOpen: (id: string) => void; onOpenQuote: (id: string) => void; onQuote: (id: string) => void; onCancel: (id: string) => void;
}) {
  const columns: RecordColumn<Rfq>[] = [
    { key: "number", label: "RFQ Number", value: (rfq) => rfq.rfqNumber, render: (rfq) => <span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{rfq.rfqNumber}</span> },
    { key: "name", label: "Customer", value: (rfq) => rfq.customerName, render: (rfq) => <span className="font-medium">{rfq.customerName}</span> },
    { key: "date", label: "Received", value: (rfq) => rfq.receivedDate ?? "", render: (rfq) => <span className="text-xs text-[var(--text-2)]">{when(rfq.receivedDate)}</span> },
    { key: "requiredBy", label: "Required By", value: (rfq) => rfq.requiredDate ?? "", render: (rfq) => <span className="text-xs text-[var(--text-2)]">{when(rfq.requiredDate)}</span> },
    { key: "lines", label: "Lines", align: "center", value: (rfq) => rfq.lines.length, render: (rfq) => <span className="text-xs">{rfq.lines.length}</span> },
    { key: "channel", label: "Channel", value: (rfq) => rfq.receivedVia ?? "", render: (rfq) => <span className="text-xs text-[var(--text-2)]">{rfq.receivedVia || "—"}</span> },
    { key: "quotation", label: "Quotation", value: (rfq) => quoteByRfq.get(rfq.id)?.quotationNumber ?? "", render: (rfq) => {
      const quote = quoteByRfq.get(rfq.id);
      return quote ? <button type="button" className="font-mono font-semibold text-xs text-[var(--brand)]" onClick={(event) => { event.stopPropagation(); onOpenQuote(quote.id); }}>{quote.quotationNumber}</button> : <span className="text-xs text-[var(--text-3)]">Pending</span>;
    } },
    { key: "status", label: "Status", value: (rfq) => rfqChip(rfq, quoteByRfq.has(rfq.id)), render: (rfq) => <StatusChip status={rfqChip(rfq, quoteByRfq.has(rfq.id))} /> },
    { key: "owner", label: "Owner", value: () => "", render: () => <span className="text-xs text-[var(--text-2)]">—</span> },
  ];
  return (
    <RecordTable rows={rows} columns={columns} sortKey={sortKey} sortDir={sortDir} onSort={onSort} loading={loading} error={error} empty="No RFQs found" onRow={(rfq) => onOpen(rfq.id)} trailing={(rfq) => {
      const chip = rfqChip(rfq, quoteByRfq.has(rfq.id));
      return (
        <div className="flex gap-1">
          {chip === "open" && <button type="button" onClick={() => onQuote(rfq.id)} className="text-xs px-2 py-1 rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Quote</button>}
          {chip !== "cancelled" && chip !== "converted" && <button type="button" title="Cancel Customer RFQ" onClick={() => onCancel(rfq.id)}><XCircle size={12} style={{ color: "var(--error)" }} /></button>}
        </div>
      );
    }} />
  );
}

function QuoteTable({ rows, loading, error, sortKey, sortDir, onSort, onOpen }: {
  rows: Quotation[]; loading: boolean; error: string; sortKey: string; sortDir: SortDirection; onSort: (key: string) => void; onOpen: (id: string) => void;
}) {
  const columns: RecordColumn<Quotation>[] = [
    { key: "number", label: "Quotation #", value: (quote) => quote.quotationNumber, render: (quote) => <span className="font-mono text-xs font-bold text-[var(--brand)]">{quote.quotationNumber}{quote.revision > 1 ? ` · Rev ${quote.revision}` : ""}</span> },
    { key: "name", label: "Customer", value: (quote) => quote.customerName, render: (quote) => <span className="font-medium">{quote.customerName}</span> },
    { key: "rfq", label: "RFQ Ref", value: (quote) => quote.rfqNumber ?? "", render: (quote) => <span className="font-mono text-xs text-[var(--text-2)]">{quote.rfqNumber ?? "—"}</span> },
    { key: "date", label: "Quoted Date", value: (quote) => quote.createdAt ?? "", render: (quote) => <span className="text-xs text-[var(--text-2)]">{when(quote.createdAt)}</span> },
    { key: "valid", label: "Valid Until", value: (quote) => quote.validUntil ?? "", render: (quote) => <span className="text-xs text-[var(--text-2)]">{when(quote.validUntil)}</span> },
    { key: "value", label: "Total Value", align: "right", value: (quote) => quoteValue(quote), render: (quote) => <span className="font-mono text-xs font-semibold">{inr(quoteValue(quote))}</span> },
    { key: "status", label: "Status", value: (quote) => quote.status, render: (quote) => <StatusChip status={quote.status === "sent" ? "submitted" : quote.status} /> },
  ];
  return <RecordTable rows={rows} columns={columns} sortKey={sortKey} sortDir={sortDir} onSort={onSort} loading={loading} error={error} empty="No quotations found" onRow={(quote) => onOpen(quote.id)} />;
}

function CustomerTable({ rows, loading, error, pos, rfqs, sortKey, sortDir, onSort, onOpen }: {
  rows: Customer[]; loading: boolean; error: string; pos: Cpo[]; rfqs: Rfq[]; sortKey: string; sortDir: SortDirection; onSort: (key: string) => void; onOpen: (id: string) => void;
}) {
  const columns: RecordColumn<Customer>[] = [
    { key: "customer", label: "Customer", value: (customer) => customer.name, render: (customer) => <><div className="font-semibold">{customer.name}</div><div className="text-xs text-[var(--text-3)]">{customer.address?.city}{customer.address?.state ? `, ${customer.address.state}` : ""}</div></> },
    { key: "gstin", label: "GSTIN / State", value: (customer) => customer.gstin ?? "", render: (customer) => <span className="text-xs font-mono">{customer.gstin || "—"}<div className="text-[var(--text-3)]">{customer.address?.state || ""}</div></span> },
    { key: "contact", label: "Contact", value: (customer) => customer.contacts?.[0]?.name ?? "", render: (customer) => <span className="text-xs">{customer.contacts?.[0]?.name || "—"}<div className="text-[var(--text-3)]">{customer.contacts?.[0]?.phone || customer.contacts?.[0]?.email || ""}</div></span> },
    { key: "terms", label: "Terms", value: (customer) => customer.creditDays ?? 0, render: (customer) => <span className="text-xs">{terms(customer.creditDays)}</span> },
    { key: "pos", label: "Customer POs", value: (customer) => pos.filter((po) => po.customerId === customer.id).length, render: (customer) => <span className="text-xs">{pos.filter((po) => po.customerId === customer.id).length}</span> },
    { key: "rfqs", label: "RFQs", value: (customer) => rfqs.filter((rfq) => rfq.customerId === customer.id).length, render: (customer) => <span className="text-xs">{rfqs.filter((rfq) => rfq.customerId === customer.id).length}</span> },
    { key: "rating", label: "Rating", value: () => "", render: () => <span className="text-xs text-[var(--text-3)]">—</span> },
    { key: "status", label: "Status", value: (customer) => customer.status || "active", render: (customer) => <StatusChip status={customer.status || "active"} /> },
  ];
  return <RecordTable rows={rows} columns={columns} sortKey={sortKey} sortDir={sortDir} onSort={onSort} loading={loading} error={error} empty="No customers found" onRow={(customer) => onOpen(customer.id)} />;
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
          <DetailList items={[
            { label: "Accepted Date", value: when(po.orderDate) },
            { label: "Customer reference", value: po.customerPoRef || "—" },
            { label: "Quotation Ref", value: po.quotationNumber ? `${po.quotationNumber} rev ${po.quotationRevision ?? ""}` : "—" },
          ]} />
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
