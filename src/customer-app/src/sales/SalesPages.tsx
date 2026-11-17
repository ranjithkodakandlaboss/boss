import { FormEvent, ReactNode, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ApiError } from "../auth/api";
import StatusChip from "../components/shared/StatusChip";
import Tabs from "../components/shared/Tabs";
import Button from "../components/shared/Button";
import { SelectField, TextField } from "../components/shared/Fields";
import EmptyState from "../components/shared/EmptyState";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import {
  Cpo, Customer, ItemOption, Quotation, QuoteLine, Rfq, RfqLine, UomOption,
  inr, salesApi, when,
} from "./api";

function message(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : "The request failed";
}

function useLoad<T>(loader: () => Promise<T>, deps: unknown[]) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let live = true;
    setLoading(true);
    loader().then((value) => { if (live) setData(value); }).catch((caught) => { if (live) setError(message(caught)); }).finally(() => { if (live) setLoading(false); });
    return () => { live = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, deps);
  return { data, error, loading };
}

function Back({ to, label }: { to: string; label: string }) {
  return <Link to={to} className="text-sm font-semibold text-[var(--brand)]">{label}</Link>;
}

function Card({ title, children }: { title: string; children: ReactNode }) {
  return (
    <section className="rounded-xl border p-5 space-y-3" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
      <h2 className="text-sm font-bold">{title}</h2>
      {children}
    </section>
  );
}

export function SalesHome() {
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") || "pos";
  const q = params.get("q") ?? "";
  const [query, setQuery] = useState(q);
  const customerId = params.get("customer") ?? "";
  const pos = useLoad(() => salesApi.cpos(q, customerId), [q, customerId, tab]);
  const rfqs = useLoad(() => salesApi.rfqs(q, customerId), [q, customerId, tab]);
  const quotes = useLoad(() => salesApi.quotations(q, customerId), [q, customerId, tab]);
  const customers = useLoad(() => salesApi.customers(q), [q, tab]);

  function setTab(next: string) {
    const updated = new URLSearchParams(params);
    updated.set("tab", next);
    setParams(updated, { replace: true });
  }

  const activeCustomers = (customers.data ?? []).filter((customer) => customer.status !== "inactive").length;
  const openValue = (pos.data ?? []).filter((po) => po.status === "open").reduce((sum, po) => sum + po.lines.reduce((lineSum, line) => lineSum + line.lineTotal, 0), 0);

  return (
    <div className="flex flex-col min-h-[calc(100vh-var(--topbar-h))]">
      <div className="px-6 py-4 border-b flex items-center justify-between gap-4" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <div>
          <h1 className="text-lg font-bold">{tab === "rfqs" ? "Customer RFQs" : tab === "quotations" ? "Quotations" : tab === "customers" ? "Customer Directory" : "Customer POs"}</h1>
          <p className="text-xs mt-0.5 text-[var(--text-2)]">{(pos.data ?? []).filter((po) => po.status === "open").length} open Customer POs · {activeCustomers} active customers · {inr(openValue)} in progress</p>
        </div>
        <div className="flex gap-2">
          {tab === "pos" && <Link to="/sales/po/new"><Button>Add Customer PO</Button></Link>}
          {tab === "rfqs" && <>
            <Link to="/sales/quotation/new"><Button variant="ghost">Direct Quotation</Button></Link>
            <Link to="/sales/rfq/new"><Button>Add RFQ</Button></Link>
          </>}
          {tab === "customers" && <Link to="/sales/customers/new"><Button>Add New Customer</Button></Link>}
        </div>
      </div>
      <Tabs tabs={[
        { id: "pos", label: `Customer POs (${pos.data?.length ?? 0})` },
        { id: "rfqs", label: `RFQs (${rfqs.data?.length ?? 0})` },
        { id: "quotations", label: `Quotations (${quotes.data?.length ?? 0})` },
        { id: "customers", label: "Customer Directory" },
      ]} active={tab} onChange={setTab} />
      <form className="px-6 py-3" onSubmit={(event) => { event.preventDefault(); const updated = new URLSearchParams(params); if (query) updated.set("q", query); else updated.delete("q"); setParams(updated, { replace: true }); }}>
        <input className="eoss-input w-full max-w-md" value={query} onChange={(event) => setQuery(event.target.value)} placeholder={tab === "customers" ? "Search customers" : tab === "quotations" ? "Search quotation number, RFQ or customer" : tab === "rfqs" ? "Search RFQ number or customer name" : "Search Customer PO number or customer name"} />
      </form>
      <div className="px-6 pb-8 overflow-auto">
        {tab === "pos" && <RecordTable loading={pos.loading} error={pos.error} rows={pos.data ?? []} empty="No records" columns={["PO Number", "Customer", "Date", "Value", "Lines", "Terms", "Status"]} render={(po: Cpo) => [
          <Link key="n" to={`/sales/po/${po.id}`} className="font-semibold text-[var(--brand)]">{po.cpoNumber}</Link>,
          <Link key="c" to={`/sales/customers/${po.customerId}`}>{po.customerName}</Link>,
          when(po.orderDate), inr(po.lines.reduce((sum, line) => sum + line.lineTotal, 0) - (po.discountAmount ?? 0)), String(po.lines.length), po.paymentTerms ? `Net ${po.paymentTerms}` : "—", <StatusChip key="s" status={po.status} />,
        ]} />}
        {tab === "rfqs" && <RecordTable loading={rfqs.loading} error={rfqs.error} rows={rfqs.data ?? []} empty="No records" columns={["RFQ Number", "Customer", "Received", "Required By", "Lines", "Channel", "Status"]} render={(rfq: Rfq) => [
          <Link key="n" to={`/sales/rfq/${rfq.id}`} className="font-semibold text-[var(--brand)]">{rfq.rfqNumber}</Link>,
          <Link key="c" to={`/sales/customers/${rfq.customerId}`}>{rfq.customerName}</Link>,
          when(rfq.receivedDate), when(rfq.requiredDate), String(rfq.lines.length), rfq.receivedVia || "—", <StatusChip key="s" status={rfq.status} />,
        ]} />}
        {tab === "quotations" && <RecordTable loading={quotes.loading} error={quotes.error} rows={quotes.data ?? []} empty="No records" columns={["Quotation #", "Customer", "RFQ Ref", "Quoted Date", "Valid Until", "Total Value", "Revision", "Status"]} render={(quote: Quotation) => [
          <Link key="n" to={`/sales/quotation/${quote.id}`} className="font-semibold text-[var(--brand)]">{quote.quotationNumber}</Link>,
          <Link key="c" to={`/sales/customers/${quote.customerId}`}>{quote.customerName}</Link>,
          quote.rfqId ? <Link key="r" to={`/sales/rfq/${quote.rfqId}`}>{quote.rfqNumber || "RFQ"}</Link> : "—",
          when(quote.createdAt), when(quote.validUntil), inr(quote.lines.reduce((sum, line) => sum + (line.lineTotal ?? 0), 0) - (quote.discountAmount ?? 0)), `Rev ${quote.revision}`, <StatusChip key="s" status={quote.status} />,
        ]} />}
        {tab === "customers" && <RecordTable loading={customers.loading} error={customers.error} rows={customers.data ?? []} empty="No records" columns={["Customer", "GSTIN / State", "Contact", "Terms", "Status"]} render={(customer: Customer) => [
          <Link key="n" to={`/sales/customers/${customer.id}`} className="font-semibold text-[var(--brand)]">{customer.name}</Link>,
          `${customer.gstin || "—"} / ${customer.address?.state || "—"}`,
          customer.contacts?.[0]?.name || "—",
          customer.creditDays != null ? `Net ${customer.creditDays}` : "—",
          <StatusChip key="s" status={customer.status || "active"} />,
        ]} />}
      </div>
    </div>
  );
}

function RecordTable<T extends { id: string }>({ columns, rows, render, loading, error, empty }: { columns: string[]; rows: T[]; render: (row: T) => ReactNode[]; loading: boolean; error: string; empty: string }) {
  if (loading) return <LoadingState label="Loading records" />;
  if (error) return <ErrorState body={error} />;
  if (!rows.length) return <EmptyState title={empty} body="Nothing is stored for this search yet." />;
  return (
    <div className="overflow-auto">
      <table className="eoss-table">
        <thead><tr>{columns.map((column) => <th key={column}>{column}</th>)}</tr></thead>
        <tbody>{rows.map((row) => <tr key={row.id}>{render(row).map((cell, index) => <td key={index}>{cell}</td>)}</tr>)}</tbody>
      </table>
    </div>
  );
}

export function CustomerFormPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const existing = useLoad(() => id ? salesApi.customer(id) : Promise.resolve(null), [id]);
  const [error, setError] = useState("");
  const customer = existing.data;
  if (id && existing.loading) return <LoadingState label="Loading customer" />;
  if (existing.error) return <ErrorState body={existing.error} />;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const legalName = String(data.get("legalName") || "").trim();
    const city = String(data.get("city") || "").trim();
    const contact = String(data.get("contact") || "").trim();
    if (!legalName || !city || !contact) { setError("Legal name, city and contact person are required."); return; }
    const gstin = String(data.get("gstin") || "").trim().toUpperCase();
    if (gstin && !/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/.test(gstin)) { setError("GSTIN must match the GST format."); return; }
    const code = String(data.get("code") || customer?.code || legalName.replace(/[^A-Za-z0-9]/g, "").slice(0, 12).toUpperCase() || "CUST");
    const body = {
      code, name: String(data.get("tradingName") || legalName), legalName, gstin: gstin || null, pan: String(data.get("pan") || "").toUpperCase() || null,
      address: { line1: String(data.get("line1") || ""), city, state: String(data.get("state") || "") },
      creditLimit: Number(data.get("creditLimit") || 0), creditDays: Number(data.get("creditDays") || 30),
      contacts: [{ name: contact, email: String(data.get("email") || ""), phone: String(data.get("phone") || ""), isPrimary: true }],
    };
    try {
      const saved = await salesApi.saveCustomer(body, id);
      navigate(`/sales/customers/${saved.id}`);
    } catch (caught) { setError(message(caught)); }
  }

  const contact = customer?.contacts?.[0];
  return (
    <form onSubmit={submit} className="p-6 max-w-3xl mx-auto space-y-5">
      <Back to={id ? `/sales/customers/${id}` : "/sales?tab=customers"} label="Back" />
      <h1 className="text-lg font-bold">{id ? "Edit Customer" : "Add New Customer"}</h1>
      <Card title="Business identity and contact">
        <div className="grid md:grid-cols-2 gap-4">
          <TextField label="Legal / Business Name *" name="legalName" defaultValue={customer?.legalName || customer?.name || ""} required />
          <TextField label="Trading Name" name="tradingName" defaultValue={customer?.name || ""} />
          <TextField label="Customer Code *" name="code" defaultValue={customer?.code || ""} required />
          <TextField label="Primary Contact *" name="contact" defaultValue={contact?.name || ""} required />
          <TextField label="Email" name="email" type="email" defaultValue={contact?.email || ""} />
          <TextField label="Phone" name="phone" defaultValue={contact?.phone || ""} />
          <TextField label="GSTIN" name="gstin" defaultValue={customer?.gstin || ""} />
          <TextField label="PAN" name="pan" defaultValue={customer?.pan || ""} />
          <TextField label="Address" name="line1" defaultValue={customer?.address?.line1 || ""} />
          <TextField label="City *" name="city" defaultValue={customer?.address?.city || ""} required />
          <TextField label="State" name="state" defaultValue={customer?.address?.state || ""} />
        </div>
      </Card>
      <Card title="Commercial terms">
        <div className="grid md:grid-cols-2 gap-4">
          <TextField label="Payment terms (days)" name="creditDays" type="number" min={0} defaultValue={customer?.creditDays ?? 30} />
          <TextField label="Credit Limit (₹)" name="creditLimit" type="number" min={0} defaultValue={customer?.creditLimit ?? 0} />
        </div>
      </Card>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Button type="submit">Save Customer</Button>
    </form>
  );
}

export function CustomerDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const customer = useLoad(() => salesApi.customer(id), [id]);
  const rfqs = useLoad(() => salesApi.rfqs("", id), [id]);
  const quotes = useLoad(() => salesApi.quotations("", id), [id]);
  const pos = useLoad(() => salesApi.cpos("", id), [id]);
  const history = useLoad(() => salesApi.history("CUSTOMER", id), [id]);
  const [tab, setTab] = useState("overview");
  const [error, setError] = useState("");
  if (customer.loading) return <LoadingState label="Loading customer" />;
  if (customer.error || !customer.data) return <ErrorState body={customer.error || "Customer not found"} />;
  const record = customer.data;
  async function deactivate() {
    try { await salesApi.deactivateCustomer(id); navigate("/sales?tab=customers"); }
    catch (caught) { setError(message(caught)); }
  }
  return (
    <div className="p-6 space-y-4">
      <Back to="/sales?tab=customers" label="Customer Directory" />
      <div className="flex items-center justify-between gap-3">
        <div><h1 className="text-lg font-bold">{record.name}</h1><p className="text-xs text-[var(--text-2)]">{record.code} · {record.legalName}</p></div>
        <div className="flex gap-2"><StatusChip status={record.status || "active"} /><Link to={`/sales/customers/${id}/edit`}><Button variant="ghost">Edit</Button></Link>{record.status !== "inactive" && <Button variant="danger" onClick={deactivate}>Deactivate</Button>}</div>
      </div>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Tabs tabs={[{ id: "overview", label: "Overview" }, { id: "orders", label: "Orders" }, { id: "rfqs", label: "RFQs" }, { id: "quotes", label: "Quotations" }, { id: "contacts", label: "Contacts" }, { id: "history", label: "History" }]} active={tab} onChange={setTab} />
      {tab === "overview" && <Card title="Identity"><p>GSTIN {record.gstin || "—"} · PAN {record.pan || "—"}</p><p>{record.address?.line1} {record.address?.city} {record.address?.state}</p><p>Payment terms {record.creditDays ?? "—"} days · Credit limit {inr(record.creditLimit)}</p><p className="text-xs text-[var(--text-3)]">Invoices and receipts are not part of this Sales release.</p></Card>}
      {tab === "orders" && <LinkedList rows={pos.data ?? []} href={(row) => `/sales/po/${row.id}`} label={(row) => `${row.cpoNumber} · ${row.status}`} />}
      {tab === "rfqs" && <LinkedList rows={rfqs.data ?? []} href={(row) => `/sales/rfq/${row.id}`} label={(row) => `${row.rfqNumber} · ${row.status}`} />}
      {tab === "quotes" && <LinkedList rows={quotes.data ?? []} href={(row) => `/sales/quotation/${row.id}`} label={(row) => `${row.quotationNumber} rev ${row.revision} · ${row.status}`} />}
      {tab === "contacts" && <ul>{(record.contacts ?? []).map((contact) => <li key={contact.name}>{contact.name} · {contact.email} · {contact.phone}</li>)}</ul>}
      {tab === "history" && <History entries={history.data ?? []} />}
    </div>
  );
}

function LinkedList<T extends { id: string }>({ rows, href, label }: { rows: T[]; href: (row: T) => string; label: (row: T) => string }) {
  if (!rows.length) return <EmptyState title="No records" body="Nothing is linked yet." />;
  return <ul className="space-y-2">{rows.map((row) => <li key={row.id}><Link className="text-[var(--brand)] font-semibold" to={href(row)}>{label(row)}</Link></li>)}</ul>;
}

function History({ entries }: { entries: { id: string; action: string; createdAt: string; actorId?: string }[] }) {
  if (!entries.length) return <EmptyState title="No history" body="Lifecycle actions will appear here." />;
  return <ul className="space-y-1 text-sm">{entries.map((entry) => <li key={entry.id}>{when(entry.createdAt)} · {entry.action}{entry.actorId ? ` · ${entry.actorId}` : ""}</li>)}</ul>;
}

function blankLine(item?: ItemOption, uom?: UomOption): RfqLine {
  return { itemId: item?.id || "", uomId: item?.uomId || uom?.id || "", qty: 1, description: item?.name || "" };
}

export function RfqFormPage() {
  const { id } = useParams();
  const navigate = useNavigate();
  const existing = useLoad(() => id ? salesApi.rfq(id) : Promise.resolve(null), [id]);
  const customers = useLoad(() => salesApi.customers(), []);
  const items = useLoad(() => salesApi.items(), []);
  const uoms = useLoad(() => salesApi.uoms(), []);
  const [lines, setLines] = useState<RfqLine[]>([blankLine()]);
  const [error, setError] = useState("");
  useEffect(() => { if (existing.data) setLines(existing.data.lines); }, [existing.data]);
  if (existing.loading || customers.loading || items.loading) return <LoadingState label="Loading RFQ" />;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    if (lines.some((line) => !line.itemId || !line.uomId || line.qty <= 0)) { setError("Each line needs an item, unit and quantity."); return; }
    const body = {
      customerId: String(data.get("customerId") || existing.data?.customerId),
      requiredDate: String(data.get("requiredDate") || "") || null,
      receivedDate: String(data.get("receivedDate") || "") || null,
      receivedVia: String(data.get("receivedVia") || ""),
      customerRef: String(data.get("customerRef") || ""),
      paymentTerms: Number(data.get("paymentTerms") || 30),
      deliveryTerms: String(data.get("deliveryTerms") || ""),
      notes: String(data.get("notes") || ""),
      lines: lines.map((line) => ({ ...line, qty: Number(line.qty) })),
    };
    try {
      const saved = await salesApi.saveRfq(body, id);
      navigate(`/sales/rfq/${saved.id}`);
    } catch (caught) { setError(message(caught)); }
  }

  return (
    <form onSubmit={submit} className="p-6 max-w-4xl mx-auto space-y-5">
      <Back to="/sales?tab=rfqs" label="Customer RFQs" />
      <h1 className="text-lg font-bold">{id ? "Edit RFQ" : "New RFQ"}</h1>
      <Card title="Header">
        <div className="grid md:grid-cols-2 gap-4">
          {!id && <SelectField label="Customer *" name="customerId" required defaultValue="">{(customers.data ?? []).filter((customer) => customer.status !== "inactive").map((customer) => <option key={customer.id} value={customer.id}>{customer.name}</option>)}</SelectField>}
          <TextField label="Received Date" name="receivedDate" type="date" defaultValue={existing.data?.receivedDate || new Date().toISOString().slice(0, 10)} />
          <TextField label="Required By" name="requiredDate" type="date" defaultValue={existing.data?.requiredDate || ""} />
          <TextField label="Received Via" name="receivedVia" defaultValue={existing.data?.receivedVia || ""} placeholder="Email, phone, WhatsApp" />
          <TextField label="Customer reference" name="customerRef" defaultValue={existing.data?.customerRef || ""} />
          <TextField label="Payment Terms (days)" name="paymentTerms" type="number" defaultValue={existing.data?.paymentTerms ?? 30} />
          <TextField label="Delivery Terms" name="deliveryTerms" defaultValue={existing.data?.deliveryTerms || ""} />
          <TextField label="Internal notes" name="notes" defaultValue={existing.data?.notes || ""} />
        </div>
      </Card>
      <Card title="Line items">
        {lines.map((line, index) => (
          <div key={index} className="grid md:grid-cols-4 gap-2 border-t pt-3">
            <SelectField label="Item *" value={line.itemId} onChange={(event) => {
              const item = (items.data ?? []).find((option) => option.id === event.target.value);
              setLines(lines.map((entry, entryIndex) => entryIndex === index ? { ...entry, itemId: event.target.value, uomId: item?.uomId || entry.uomId, description: entry.description || item?.name || "" } : entry));
            }}><option value="">Select item</option>{(items.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}</SelectField>
            <TextField label="Drawing No." value={line.drawingNumber || ""} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, drawingNumber: event.target.value } : entry))} />
            <TextField label="Revision" value={line.drawingRevision || ""} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, drawingRevision: event.target.value } : entry))} />
            <TextField label="Material" value={line.material || ""} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, material: event.target.value } : entry))} />
            <TextField label="Description" value={line.description || ""} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, description: event.target.value } : entry))} />
            <TextField label="Qty *" type="number" min="0.001" step="0.001" value={line.qty} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, qty: Number(event.target.value) } : entry))} />
            <SelectField label="UOM" value={line.uomId} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, uomId: event.target.value } : entry))}>{(uoms.data ?? []).map((uom) => <option key={uom.id} value={uom.id}>{uom.symbol}</option>)}</SelectField>
            <TextField label="Notes" value={line.notes || ""} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, notes: event.target.value } : entry))} />
          </div>
        ))}
        <Button variant="ghost" onClick={() => setLines([...lines, blankLine(items.data?.[0], uoms.data?.[0])])}>Add line</Button>
      </Card>
      <p className="text-xs text-[var(--text-3)]">Documents are not stored on the RFQ in this release. Specifications stay on the line.</p>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Button type="submit">{id ? "Save RFQ" : "Create RFQ"}</Button>
    </form>
  );
}

export function RfqDetailPage() {
  const { id = "" } = useParams();
  const record = useLoad(() => salesApi.rfq(id), [id]);
  const quotes = useLoad(() => salesApi.quotations("", ""), [id]);
  const history = useLoad(() => salesApi.history("RFQ", id), [id]);
  const [tab, setTab] = useState("overview");
  const [error, setError] = useState("");
  if (record.loading) return <LoadingState label="Loading RFQ" />;
  if (record.error || !record.data) return <ErrorState body={record.error || "RFQ not found"} />;
  const rfq = record.data;
  const linked = (quotes.data ?? []).filter((quote) => quote.rfqId === id);
  async function status(next: string) {
    try { await salesApi.rfqStatus(id, next); window.location.reload(); }
    catch (caught) { setError(message(caught)); }
  }
  return (
    <div className="p-6 space-y-4">
      <Back to="/sales?tab=rfqs" label="Customer RFQs" />
      <div className="flex justify-between gap-3"><div><h1 className="text-lg font-bold">{rfq.rfqNumber}</h1><Link className="text-sm text-[var(--brand)]" to={`/sales/customers/${rfq.customerId}`}>{rfq.customerName}</Link></div><StatusChip status={rfq.status} /></div>
      <div className="flex flex-wrap gap-2">
        {rfq.status === "draft" && <Link to={`/sales/rfq/${id}/edit`}><Button variant="ghost">Edit</Button></Link>}
        {rfq.status === "draft" && <Button onClick={() => status("sent")}>Mark received</Button>}
        {rfq.status === "sent" && <Button onClick={() => status("closed")}>Close RFQ</Button>}
        {["draft", "sent"].includes(rfq.status) && <Button variant="danger" onClick={() => status("cancelled")}>Cancel RFQ</Button>}
        <Link to={`/sales/rfq/${id}/quotation/new`}><Button>Create quotation</Button></Link>
      </div>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Tabs tabs={[{ id: "overview", label: "Overview" }, { id: "lines", label: "Line Items" }, { id: "technical", label: "Technical & Quality" }, { id: "commercial", label: "Commercial & Delivery" }, { id: "history", label: "History" }]} active={tab} onChange={setTab} />
      {tab === "overview" && <Card title="Source"><p>Received {when(rfq.receivedDate)} via {rfq.receivedVia || "—"} · Customer ref {rfq.customerRef || "—"}</p><p>Required by {when(rfq.requiredDate)}</p><div className="space-y-1">{linked.map((quote) => <Link key={quote.id} className="block text-[var(--brand)]" to={`/sales/quotation/${quote.id}`}>{quote.quotationNumber} rev {quote.revision} · {quote.status}</Link>)}{!linked.length && <p>No quotation yet.</p>}</div></Card>}
      {tab === "lines" && <LineTable lines={rfq.lines} />}
      {tab === "technical" && <ul>{rfq.lines.map((line, index) => <li key={line.id || index}>{line.drawingNumber || "—"} rev {line.drawingRevision || "—"} · {line.material || "—"} · {line.description || line.itemName} · {line.notes || ""}</li>)}</ul>}
      {tab === "commercial" && <p>Payment terms {rfq.paymentTerms ?? "—"} days · {rfq.deliveryTerms || "No delivery terms"} · {rfq.notes || "No internal notes"}</p>}
      {tab === "history" && <History entries={history.data ?? []} />}
    </div>
  );
}

function LineTable({ lines }: { lines: RfqLine[] }) {
  return <table className="eoss-table"><thead><tr><th>#</th><th>Item</th><th>Qty</th><th>UOM</th><th>Drawing</th></tr></thead><tbody>{lines.map((line, index) => <tr key={line.id || index}><td>{line.seq || index + 1}</td><td>{line.itemCode} {line.itemName}</td><td>{line.qty}</td><td>{line.uomSymbol}</td><td>{line.drawingNumber || "—"}</td></tr>)}</tbody></table>;
}

export function QuotationFormPage() {
  const { rfqId } = useParams();
  const navigate = useNavigate();
  const source = useLoad(() => rfqId ? salesApi.rfq(rfqId) : Promise.resolve(null), [rfqId]);
  const customers = useLoad(() => salesApi.customers(), []);
  const items = useLoad(() => salesApi.items(), []);
  const [lines, setLines] = useState<QuoteLine[]>([]);
  const [error, setError] = useState("");
  useEffect(() => {
    if (source.data && !lines.length) {
      setLines(source.data.lines.map((line) => ({ itemId: line.itemId, uomId: line.uomId, qty: line.qty, unitPrice: 0, drawingNumber: line.drawingNumber, drawingRevision: line.drawingRevision, description: line.description || line.itemName, gstRate: 18, discountPct: 0 })));
    }
  }, [source.data, lines.length]);
  if (source.loading || items.loading || customers.loading) return <LoadingState label="Loading quotation" />;

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const customerId = String(data.get("customerId") || source.data?.customerId || "");
    if (!customerId || !lines.length || lines.some((line) => !line.itemId || line.unitPrice < 0)) { setError("Choose a customer and price every line."); return; }
    const days = Number(data.get("validDays") || 15);
    const validUntil = new Date(Date.now() + days * 86400000).toISOString().slice(0, 10);
    try {
      const saved = await salesApi.saveQuotation({
        customerId, rfqId: rfqId || null, validUntil, paymentTerms: Number(data.get("paymentTerms") || 30),
        deliveryTerms: String(data.get("deliveryTerms") || ""), notes: String(data.get("notes") || ""),
        discountAmount: Number(data.get("discountAmount") || 0), lines,
      });
      navigate(`/sales/quotation/${saved.id}`);
    } catch (caught) { setError(message(caught)); }
  }

  return (
    <form onSubmit={submit} className="p-6 max-w-4xl mx-auto space-y-5">
      <Back to={rfqId ? `/sales/rfq/${rfqId}` : "/sales?tab=quotations"} label="Back" />
      <h1 className="text-lg font-bold">New Quotation</h1>
      {source.data && <p className="text-sm">Source RFQ {source.data.rfqNumber} · {source.data.customerName}</p>}
      <Card title="Commercial terms">
        <div className="grid md:grid-cols-2 gap-4">
          {!rfqId && <SelectField label="Customer *" name="customerId" required><option value="">Select customer</option>{(customers.data ?? []).filter((customer) => customer.status !== "inactive").map((customer) => <option key={customer.id} value={customer.id}>{customer.name}</option>)}</SelectField>}
          <TextField label="Valid for (days)" name="validDays" type="number" min={1} defaultValue={15} />
          <TextField label="Payment Terms (days)" name="paymentTerms" type="number" defaultValue={source.data?.paymentTerms ?? 30} />
          <TextField label="Delivery commitment" name="deliveryTerms" defaultValue={source.data?.deliveryTerms || ""} />
          <TextField label="Discount (₹)" name="discountAmount" type="number" min={0} defaultValue={0} />
          <TextField label="Notes" name="notes" />
        </div>
      </Card>
      <Card title="Lines">
        {(lines.length ? lines : [{ itemId: "", uomId: "", qty: 1, unitPrice: 0 }]).map((line, index) => (
          <div key={index} className="grid md:grid-cols-4 gap-2">
            <SelectField label="Item" value={line.itemId} onChange={(event) => {
              const item = (items.data ?? []).find((option) => option.id === event.target.value);
              const next = lines.length ? [...lines] : [{ itemId: "", uomId: "", qty: 1, unitPrice: 0 }];
              next[index] = { ...next[index], itemId: event.target.value, uomId: item?.uomId || next[index].uomId, gstRate: item?.gstRate ?? next[index].gstRate };
              setLines(next);
            }}><option value="">Select</option>{(items.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.code}</option>)}</SelectField>
            <TextField label="Qty" type="number" value={line.qty} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, qty: Number(event.target.value) } : entry))} />
            <TextField label="Unit Price (₹)" type="number" min={0} value={line.unitPrice} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, unitPrice: Number(event.target.value) } : entry))} />
            <TextField label="GST %" type="number" value={line.gstRate ?? 0} onChange={(event) => setLines(lines.map((entry, i) => i === index ? { ...entry, gstRate: Number(event.target.value) } : entry))} />
          </div>
        ))}
        <Button variant="ghost" onClick={() => setLines([...lines, { itemId: "", uomId: items.data?.[0]?.uomId || "", qty: 1, unitPrice: 0, gstRate: 18, discountPct: 0 }])}>Add line</Button>
      </Card>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Button type="submit">Save draft quotation</Button>
    </form>
  );
}

function isQuotation(result: unknown): result is Quotation {
  return !!result && typeof result === "object" && "quotationNumber" in result && "revision" in result;
}

function isCustomerPo(result: unknown): result is Cpo {
  return !!result && typeof result === "object" && "cpoNumber" in result
    && typeof (result as Cpo).cpoNumber === "string" && !("revision" in result);
}

export function QuotationDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const record = useLoad(() => salesApi.quotation(id), [id]);
  const [current, setCurrent] = useState<Quotation | null>(null);
  const [error, setError] = useState("");
  const [note, setNote] = useState("");
  const [reference, setReference] = useState("");
  const [reason, setReason] = useState("");
  useEffect(() => { if (record.data) setCurrent(record.data); }, [record.data]);
  if (record.loading) return <LoadingState label="Loading quotation" />;
  if (record.error || !record.data || !current) return <ErrorState body={record.error || "Quotation not found"} />;
  const quote = current;
  const goods = quote.lines.reduce((sum, line) => sum + (line.lineTotal ?? 0), 0);
  async function run(action: () => Promise<unknown>, next?: string) {
    try {
      const result = await action();
      if (next) navigate(next);
      else if (isQuotation(result) && result.id !== id) navigate(`/sales/quotation/${result.id}`);
      else if (isQuotation(result)) setCurrent(result);
      else if (isCustomerPo(result)) navigate(`/sales/po/${result.id}`);
      else window.location.reload();
    } catch (caught) { setError(message(caught)); }
  }
  return (
    <div className="p-6 space-y-4 max-w-5xl">
      <Back to="/sales?tab=quotations" label="Quotations" />
      <div className="flex justify-between gap-3"><div><h1 className="text-lg font-bold">{quote.quotationNumber} · Rev {quote.revision}</h1><Link className="text-sm text-[var(--brand)]" to={`/sales/customers/${quote.customerId}`}>{quote.customerName}</Link></div><StatusChip status={quote.status} /></div>
      <p className="text-sm text-[var(--text-2)]">Valid until {when(quote.validUntil)} · Payment {quote.paymentTerms ?? "—"} days · {quote.deliveryTerms || "No delivery commitment"}</p>
      {quote.decisionChannel && <p className="text-sm">Decision recorded by the owner ({quote.decisionChannel}) {quote.decidedAt ? `on ${when(quote.decidedAt)}` : ""}{quote.decisionNote ? ` · ${quote.decisionNote}` : ""}. This is not customer self-service acceptance.</p>}
      <div className="flex flex-wrap gap-2">
        {quote.status === "draft" && <Button onClick={() => run(() => salesApi.quotationStatus(id, "sent"))}>Submit to Customer</Button>}
        {["sent", "negotiating"].includes(quote.status) && <Button onClick={() => run(() => salesApi.accept(id, { customerPoRef: reference, confirmationNote: note }))}>Record customer acceptance</Button>}
        {["sent", "negotiating"].includes(quote.status) && <Button variant="danger" onClick={() => run(() => salesApi.quotationStatus(id, "rejected", note))}>Record decline</Button>}
        {!["accepted", "superseded", "expired"].includes(quote.status) && <Button variant="ghost" onClick={() => run(() => salesApi.revise(id, { changeReason: reason || "Customer requested changes", lines: quote.lines.map((line) => ({ itemId: line.itemId, qty: line.qty, uomId: line.uomId, unitPrice: line.unitPrice, deliveryWeeks: line.deliveryWeeks, gstRate: line.gstRate, discountPct: line.discountPct, drawingNumber: line.drawingNumber, drawingRevision: line.drawingRevision, description: line.description })) }))}>Revise quotation</Button>}
        <Button variant="ghost" onClick={() => salesApi.quotationPdf(id).catch((caught) => setError(message(caught)))}>Download PDF</Button>
      </div>
      {["sent", "negotiating"].includes(quote.status) && <p className="text-xs text-[var(--text-3)]">No response leaves this quotation awaiting a decision. Nothing is accepted and no Customer PO is created. Submit to Customer marks the quotation sent inside BOSS. It does not send email or WhatsApp.</p>}
      <div className="grid md:grid-cols-2 gap-3">
        <TextField label="Offline confirmation note" value={note} onChange={(event) => setNote(event.target.value)} />
        <TextField label="Customer PO reference" value={reference} onChange={(event) => setReference(event.target.value)} />
        <TextField label="Revision reason" value={reason} onChange={(event) => setReason(event.target.value)} />
      </div>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Card title="Lines">
        <table className="eoss-table"><thead><tr><th>#</th><th>Drawing No.</th><th>Rev</th><th>Description</th><th>Qty</th><th>UOM</th><th>Unit Price</th><th>Line Value</th></tr></thead><tbody>{quote.lines.map((line, index) => <tr key={line.id || index}><td>{index + 1}</td><td>{line.drawingNumber || "—"}</td><td>{line.drawingRevision || "—"}</td><td>{line.description || line.itemName}</td><td>{line.qty}</td><td>{line.uomSymbol}</td><td>{inr(line.unitPrice)}</td><td>{inr(line.lineTotal)}</td></tr>)}</tbody></table>
        <p>Goods {inr(goods)} · Discount {inr(quote.discountAmount)} · Before tax {inr(goods - (quote.discountAmount ?? 0))}</p>
      </Card>
      <Card title="Revisions and links">
        {(quote.revisions ?? []).map((revision) => <Link key={revision.id} className="block" to={`/sales/quotation/${revision.id}`}>{quote.quotationNumber} rev {revision.revision} · {revision.status}{revision.id === quote.id ? " (this revision)" : ""}</Link>)}
        {quote.rfqId && <Link className="block" to={`/sales/rfq/${quote.rfqId}`}>RFQ {quote.rfqNumber}</Link>}
        {quote.cpoId && <Link className="block" to={`/sales/po/${quote.cpoId}`}>Customer PO {quote.cpoNumber}</Link>}
      </Card>
      <Card title="Negotiation history">
        {(quote.negotiationHistory ?? []).length === 0 ? <p>No counter-offers recorded.</p> : (quote.negotiationHistory ?? []).map((entry) => <p key={entry.id}>Round {entry.round} · {entry.partyType} · {entry.note || "No note"} · {when(entry.createdAt)}</p>)}
      </Card>
    </div>
  );
}

export function PoCreatePage() {
  const navigate = useNavigate();
  const quotes = useLoad(() => salesApi.quotations(), []);
  const accepted = useMemo(() => (quotes.data ?? []).filter((quote) => ["sent", "negotiating"].includes(quote.status) && !quote.cpoId), [quotes.data]);
  const [choice, setChoice] = useState("");
  const [note, setNote] = useState("");
  const [reference, setReference] = useState("");
  const [error, setError] = useState("");
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (!choice) { setError("Choose the quotation the customer accepted."); return; }
    try {
      const po = await salesApi.accept(choice, { customerPoRef: reference, confirmationNote: note });
      navigate(`/sales/po/${po.id}`);
    } catch (caught) { setError(message(caught)); }
  }
  return (
    <form onSubmit={submit} className="p-6 max-w-3xl mx-auto space-y-4">
      <Back to="/sales?tab=pos" label="Customer POs" />
      <h1 className="text-lg font-bold">Add Customer PO</h1>
      <Card title="Accepted quotation source">
        {quotes.loading && <LoadingState label="Loading quotations" />}
        <SelectField label="Quotation awaiting a decision" value={choice} onChange={(event) => setChoice(event.target.value)}>
          <option value="">Select quotation</option>
          {accepted.map((quote) => <option key={quote.id} value={quote.id}>{quote.quotationNumber} rev {quote.revision} · {quote.customerName}</option>)}
        </SelectField>
        <TextField label="Offline confirmation note" value={note} onChange={(event) => setNote(event.target.value)} />
        <TextField label="Customer reference" value={reference} onChange={(event) => setReference(event.target.value)} />
        <p className="text-xs text-[var(--text-3)]">Recording acceptance creates one internal Customer PO for that exact revision. Direct orders without an accepted quotation are outside this release.</p>
      </Card>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Button type="submit">Record acceptance and create Customer PO</Button>
    </form>
  );
}

export function PoDetailPage() {
  const { id = "" } = useParams();
  const record = useLoad(() => salesApi.cpo(id), [id]);
  const history = useLoad(() => salesApi.history("CPO", id), [id]);
  const [tab, setTab] = useState("overview");
  const [error, setError] = useState("");
  const [notes, setNotes] = useState("");
  const [reference, setReference] = useState("");
  useEffect(() => {
    if (record.data) { setNotes(record.data.notes || ""); setReference(record.data.customerPoRef || ""); }
  }, [record.data]);
  if (record.loading) return <LoadingState label="Loading Customer PO" />;
  if (record.error || !record.data) return <ErrorState body={record.error || "Customer PO not found"} />;
  const po = record.data;
  const editable = po.status === "open" || po.status === "on_hold";
  async function save(event: FormEvent) {
    event.preventDefault();
    try { await salesApi.updateCpo(id, { customerPoRef: reference, notes, requiredByDate: po.requiredByDate, paymentTerms: po.paymentTerms }); window.location.reload(); }
    catch (caught) { setError(message(caught)); }
  }
  async function status(next: string) {
    try { await salesApi.cpoStatus(id, next); window.location.reload(); }
    catch (caught) { setError(message(caught)); }
  }
  const goods = po.lines.reduce((sum, line) => sum + line.lineTotal, 0);
  return (
    <div className="p-6 space-y-4">
      <Back to="/sales?tab=pos" label="Customer POs" />
      <div className="flex justify-between"><div><h1 className="text-lg font-bold">{po.cpoNumber}</h1><Link className="text-sm text-[var(--brand)]" to={`/sales/customers/${po.customerId}`}>{po.customerName}</Link></div><StatusChip status={po.status} /></div>
      <div className="flex gap-2">{editable && po.status === "open" && <Button variant="ghost" onClick={() => status("on_hold")}>Hold</Button>}{po.status === "on_hold" && <Button onClick={() => status("open")}>Release hold</Button>}{["open", "on_hold", "partial"].includes(po.status) && <Button variant="danger" onClick={() => status("cancelled")}>Cancel</Button>}</div>
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <Tabs tabs={[{ id: "overview", label: "Overview" }, { id: "lines", label: "Line Items" }, { id: "history", label: "History" }]} active={tab} onChange={setTab} />
      {tab === "overview" && <Card title="Source and decision">
        <p>Order date {when(po.orderDate)} · Required {when(po.requiredByDate)} · Terms {po.paymentTerms ?? "—"} days</p>
        {po.quotationId && <Link className="block text-[var(--brand)]" to={`/sales/quotation/${po.quotationId}`}>Quotation {po.quotationNumber} rev {po.quotationRevision}</Link>}
        {po.rfqId && <Link className="block text-[var(--brand)]" to={`/sales/rfq/${po.rfqId}`}>RFQ {po.rfqNumber}</Link>}
        <p>Owner-recorded decision {po.decisionChannel || "—"}{po.decisionNote ? ` · ${po.decisionNote}` : ""}</p>
        <p>Goods {inr(goods)} · Discount {inr(po.discountAmount)} · Before tax {inr(goods - (po.discountAmount ?? 0))}</p>
        <p className="text-xs text-[var(--text-3)]">Accepted unit prices stay locked. Production, dispatch and invoice steps are later in the order journey.</p>
        {editable && <form onSubmit={save} className="grid md:grid-cols-2 gap-3"><TextField label="Customer reference" value={reference} onChange={(event) => setReference(event.target.value)} /><TextField label="Notes" value={notes} onChange={(event) => setNotes(event.target.value)} /><Button type="submit">Save permitted fields</Button></form>}
      </Card>}
      {tab === "lines" && <table className="eoss-table"><thead><tr><th>Item</th><th>Qty</th><th>UOM</th><th>Unit Price</th><th>Line Value</th></tr></thead><tbody>{po.lines.map((line) => <tr key={line.id}><td>{line.itemCode} {line.itemName}<div className="text-xs">{line.drawingNumber} {line.description}</div></td><td>{line.qty}</td><td>{line.uomSymbol}</td><td>{inr(line.unitPrice)}</td><td>{inr(line.lineTotal)}</td></tr>)}</tbody></table>}
      {tab === "history" && <History entries={history.data ?? []} />}
    </div>
  );
}
