import { FormEvent, ReactNode, useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../auth/api";
import StatusChip from "../components/shared/StatusChip";
import Tabs from "../components/shared/Tabs";
import Button from "../components/shared/Button";
import { SelectField, TextField } from "../components/shared/Fields";
import EmptyState from "../components/shared/EmptyState";
import RecordSheet from "../components/shared/RecordSheet";
import DetailList from "../components/shared/DetailList";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import {
  AuditEntry, Cpo, ItemOption, Quotation, QuoteLine, RfqLine, UomOption,
  inr, salesApi, when,
} from "./api";
import { useLoad } from "./load";

export { SalesHome } from "./SalesWorkspace";

function message(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : "The request failed";
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
  const back = id ? `/sales/customers/${id}` : "/sales?tab=customers";
  return (
    <RecordSheet title={id ? "Edit Customer" : "Add New Customer"} subtitle="Business identity, contact, and commercial terms" width="lg" onClose={() => navigate(back)} secondary={{ label: "Cancel", onClick: () => navigate(back) }} primary={{ label: "Save Customer", onClick: () => document.querySelector<HTMLFormElement>("#customer-form")?.requestSubmit() }}>
    <form id="customer-form" onSubmit={submit} className="space-y-5">
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
    </form>
    </RecordSheet>
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
      {tab === "overview" && <Card title="Identity"><DetailList items={[{ label: "GSTIN", value: record.gstin || "—" }, { label: "PAN", value: record.pan || "—" }, { label: "Address", value: [record.address?.line1, record.address?.city, record.address?.state].filter(Boolean).join(", ") || "—" }, { label: "Payment terms", value: record.creditDays != null ? `${record.creditDays} days` : "—" }, { label: "Credit limit", value: inr(record.creditLimit) }]} /><p className="text-xs text-[var(--text-3)] mt-3">Invoices and receipts are not part of this Sales release.</p></Card>}
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

function actionLabel(action: string) {
  return action.toLowerCase().replaceAll("_", " ").replace(/^\w/, (letter) => letter.toUpperCase());
}

function History({ entries }: { entries: AuditEntry[] }) {
  const [query, setQuery] = useState("");
  const [newestFirst, setNewestFirst] = useState(true);
  const [open, setOpen] = useState<string | null>(null);
  const list = Array.isArray(entries) ? entries : [];
  const filtered = useMemo(() => {
    const needle = query.trim().toLowerCase();
    const rows = list.filter((entry) => !needle || `${entry.action} ${entry.actorName || ""} ${(entry.changes ?? []).map((change) => `${change.field} ${change.previousValue} ${change.newValue}`).join(" ")}`.toLowerCase().includes(needle));
    return newestFirst ? rows : [...rows].reverse();
  }, [list, query, newestFirst]);
  if (!list.length) return <EmptyState title="No history" body="Lifecycle actions will appear here." />;
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm font-semibold">Activity &amp; Audit History <span className="text-[var(--text-3)] font-normal">({filtered.length})</span></p>
        <div className="flex gap-2">
          <input className="eoss-input text-sm" placeholder="Filter history" value={query} onChange={(event) => setQuery(event.target.value)} />
          <Button variant="ghost" onClick={() => setNewestFirst((value) => !value)}>{newestFirst ? "Newest first" : "Oldest first"}</Button>
        </div>
      </div>
      {filtered.map((entry) => (
        <div key={entry.id} className="rounded-lg border border-[var(--border)]">
          <button type="button" className="w-full text-left px-3 py-2" onClick={() => setOpen(open === entry.id ? null : entry.id)}>
            <div className="flex flex-wrap items-center gap-2 text-sm">
              <StatusChip status={entry.action.toLowerCase()} label={actionLabel(entry.action)} size="sm" />
              <span className="font-semibold">{actionLabel(entry.action)}</span>
              <span className="text-[var(--text-3)]">{when(entry.createdAt)}</span>
              <span>{entry.actorName || "System"}</span>
            </div>
          </button>
          {open === entry.id && (
            <table className="eoss-table">
              <thead><tr><th>Field</th><th>Previous value</th><th>New value</th></tr></thead>
              <tbody>
                {(entry.changes ?? []).length === 0
                  ? <tr><td colSpan={3}>No field-level change was stored for this action.</td></tr>
                  : entry.changes?.map((change) => <tr key={change.field}><td>{change.field}</td><td>{change.previousValue}</td><td>{change.newValue}</td></tr>)}
              </tbody>
            </table>
          )}
        </div>
      ))}
    </div>
  );
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

  const back = "/sales?tab=rfqs";
  return (
    <RecordSheet title={id ? "Edit RFQ" : "Add RFQ"} subtitle="Header and line items saved on the RFQ" width="lg" onClose={() => navigate(back)} secondary={{ label: "Cancel", onClick: () => navigate(back) }} primary={{ label: id ? "Save RFQ" : "Create RFQ", onClick: () => document.querySelector<HTMLFormElement>("#rfq-form")?.requestSubmit() }}>
    <form id="rfq-form" onSubmit={submit} className="space-y-5">
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
    </form>
    </RecordSheet>
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
      {tab === "overview" && <Card title="Source"><DetailList items={[{ label: "Received", value: when(rfq.receivedDate) }, { label: "Channel", value: rfq.receivedVia || "—" }, { label: "Customer reference", value: rfq.customerRef || "—" }, { label: "Required by", value: when(rfq.requiredDate) }]} /><div className="space-y-1 mt-3">{linked.map((quote) => <Link key={quote.id} className="block text-[var(--brand)]" to={`/sales/quotation/${quote.id}`}>{quote.quotationNumber} rev {quote.revision} · {quote.status}</Link>)}{!linked.length && <p>No quotation yet.</p>}</div></Card>}
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

  const back = rfqId ? `/sales/rfq/${rfqId}` : "/sales?tab=quotations";
  return (
    <RecordSheet title="New Quotation" subtitle={source.data ? `Source RFQ ${source.data.rfqNumber} · ${source.data.customerName}` : "Direct quotation"} width="lg" onClose={() => navigate(back)} secondary={{ label: "Cancel", onClick: () => navigate(back) }} primary={{ label: "Save draft quotation", onClick: () => document.querySelector<HTMLFormElement>("#quotation-form")?.requestSubmit() }}>
    <form id="quotation-form" onSubmit={submit} className="space-y-5">
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
    </form>
    </RecordSheet>
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
  const history = useLoad(() => salesApi.history("QUOTATION", id), [id]);
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
        {["sent", "negotiating"].includes(quote.status) && <Button onClick={() => run(() => salesApi.accept(id, { customerPoRef: reference, confirmationNote: note }))}>Mark Accepted</Button>}
        {["sent", "negotiating"].includes(quote.status) && <Button variant="danger" onClick={() => run(() => salesApi.quotationStatus(id, "rejected", note))}>Record decline</Button>}
        {!["accepted", "superseded", "expired"].includes(quote.status) && <Button variant="ghost" onClick={() => run(() => salesApi.revise(id, { changeReason: reason || "Customer requested changes", lines: quote.lines.map((line) => ({ itemId: line.itemId, qty: line.qty, uomId: line.uomId, unitPrice: line.unitPrice, deliveryWeeks: line.deliveryWeeks, gstRate: line.gstRate, discountPct: line.discountPct, drawingNumber: line.drawingNumber, drawingRevision: line.drawingRevision, description: line.description })) }))}>Revise Quotation</Button>}
        <Button variant="ghost" onClick={() => salesApi.quotationPdf(id).catch((caught) => setError(message(caught)))}>PDF / Print</Button>
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
      <Card title="Activity & Audit History"><History entries={history.data ?? []} /></Card>
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
    <RecordSheet title="Add Customer PO" subtitle="One internal Customer PO from the accepted quotation" width="lg" onClose={() => navigate("/sales?tab=pos")} secondary={{ label: "Cancel", onClick: () => navigate("/sales?tab=pos") }} primary={{ label: "Record acceptance", onClick: () => document.querySelector<HTMLFormElement>("#po-form")?.requestSubmit() }}>
    <form id="po-form" onSubmit={submit} className="space-y-4">
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
    </form>
    </RecordSheet>
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
      <Tabs tabs={[{ id: "overview", label: "Overview" }, { id: "lines", label: "Line Items" }, { id: "amendments", label: "Amendments" }, { id: "jobs", label: "Linked Jobs (0)" }, { id: "documents", label: "Documents" }, { id: "history", label: "History" }]} active={tab} onChange={setTab} />
      {tab === "overview" && <div className="grid lg:grid-cols-2 gap-4">
        <Card title="PO Details">
          <DetailList items={[{ label: "Accepted date", value: when(po.orderDate) }, { label: "Required by", value: when(po.requiredByDate) }, { label: "Payment terms", value: po.paymentTerms != null ? `Net ${po.paymentTerms}` : "—" }, { label: "Customer reference", value: po.customerPoRef || "—" }, { label: "Currency", value: "INR" }]} />
        </Card>
        <Card title="Source">
          {po.quotationId && <Link className="block text-[var(--brand)]" to={`/sales/quotation/${po.quotationId}`}>Quotation {po.quotationNumber} rev {po.quotationRevision}</Link>}
          {po.rfqId && <Link className="block text-[var(--brand)]" to={`/sales/rfq/${po.rfqId}`}>RFQ {po.rfqNumber}</Link>}
          <p>Owner-recorded decision {po.decisionChannel || "—"}{po.decisionNote ? ` · ${po.decisionNote}` : ""}</p>
          <p>Goods {inr(goods)} · Discount {inr(po.discountAmount)} · Before tax {inr(goods - (po.discountAmount ?? 0))}</p>
          <p className="text-xs text-[var(--text-3)]">Accepted unit prices stay locked. Production, dispatch and invoice steps appear here when those modules are connected.</p>
          {editable && <form onSubmit={save} className="grid md:grid-cols-2 gap-3"><TextField label="Customer reference" value={reference} onChange={(event) => setReference(event.target.value)} /><TextField label="Notes" value={notes} onChange={(event) => setNotes(event.target.value)} /><Button type="submit">Save permitted fields</Button></form>}
        </Card>
      </div>}
      {tab === "lines" && <table className="eoss-table"><thead><tr><th>#</th><th>Drawing / Part No.</th><th>Rev</th><th>Description</th><th>Qty</th><th>UOM</th><th>Rate</th><th>Value</th></tr></thead><tbody>{po.lines.map((line, index) => <tr key={line.id}><td>{index + 1}</td><td>{line.drawingNumber || line.itemCode}</td><td>{line.drawingRevision || "—"}</td><td>{line.description || line.itemName}</td><td>{line.qty}</td><td>{line.uomSymbol}</td><td>{inr(line.unitPrice)}</td><td>{inr(line.lineTotal)}</td></tr>)}</tbody></table>}
      {tab === "amendments" && <p className="text-sm text-[var(--text-2)]">No amendments yet. Create a revised Customer PO from the header. Accepted prices stay locked until a later commercial revision is approved.</p>}
      {tab === "jobs" && <p className="text-sm text-[var(--text-2)]">No jobs linked to this PO yet.</p>}
      {tab === "documents" && <p className="text-sm text-[var(--text-2)]">No documents are stored on this Customer PO yet.</p>}
      {tab === "history" && <History entries={history.data ?? []} />}
    </div>
  );
}

export function PoAmendPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const record = useLoad(() => salesApi.cpo(id), [id]);
  const [reason, setReason] = useState("delivery_date");
  const [note, setNote] = useState("");
  const [paymentTerms, setPaymentTerms] = useState("");
  const [requiredBy, setRequiredBy] = useState("");
  const [error, setError] = useState("");
  if (record.loading) return <LoadingState label="Loading Customer PO" />;
  if (record.error || !record.data) return <ErrorState body={record.error || "Customer PO not found"} />;
  const po = record.data;
  const locked = po.status === "cancelled" || po.status === "fulfilled";
  async function submit(event: FormEvent) {
    event.preventDefault();
    if (reason === "price_revision" || reason === "qty_change") {
      setError("Accepted quantities and prices stay locked. Choose a delivery date or commercial-terms change.");
      return;
    }
    try {
      await salesApi.updateCpo(id, {
        customerPoRef: po.customerPoRef,
        notes: note || po.notes,
        requiredByDate: requiredBy || po.requiredByDate,
        paymentTerms: paymentTerms ? Number(paymentTerms) : po.paymentTerms,
      });
      navigate(`/sales/po/${id}`);
    } catch (caught) { setError(message(caught)); }
  }
  if (locked) return <div className="p-8 max-w-xl mx-auto text-center"><h1 className="text-lg font-bold">Revision unavailable</h1><p className="text-sm text-[var(--text-2)] mt-2">Commercial revisions are available only while the Customer PO is open. This PO remains preserved as history.</p><Button onClick={() => navigate(`/sales/po/${id}`)}>Back to Customer PO</Button></div>;
  return (
    <RecordSheet title="Create Revised Customer PO" subtitle={`${po.cpoNumber} · ${po.customerName}`} width="lg" onClose={() => navigate(`/sales/po/${id}`)} secondary={{ label: "Cancel", onClick: () => navigate(`/sales/po/${id}`) }} primary={{ label: "Save revision", onClick: () => document.querySelector<HTMLFormElement>("#amend-form")?.requestSubmit() }}>
    <form id="amend-form" onSubmit={submit} className="space-y-5">
      <p className="text-xs rounded-xl p-3" style={{ background: "rgba(245,158,11,0.08)" }}>The current PO remains binding. Accepted line rates and quantities stay locked. A delivery date, payment terms, or note change is saved on this PO.</p>
      <SelectField label="Amendment Reason *" value={reason} onChange={(event) => setReason(event.target.value)}>
        <option value="qty_change">Quantity Change</option>
        <option value="price_revision">Price Revision</option>
        <option value="delivery_date">Delivery Date Change</option>
        <option value="spec_change">Specification / Drawing Revision</option>
        <option value="commercial_terms">Commercial Terms</option>
        <option value="other">Other</option>
      </SelectField>
      <div className="grid md:grid-cols-2 gap-3">
        {po.lines.map((line) => <p key={line.id} className="text-sm">{line.drawingNumber || line.itemCode} · {line.description || line.itemName} · qty {line.qty} · {inr(line.unitPrice)}</p>)}
        <TextField label={`Payment terms (current ${po.paymentTerms ?? "—"})`} value={paymentTerms} onChange={(event) => setPaymentTerms(event.target.value)} placeholder="Leave blank to keep current" />
        <TextField label="Required date" type="date" value={requiredBy} onChange={(event) => setRequiredBy(event.target.value)} />
      </div>
      <TextField label="Change description *" value={note} onChange={(event) => setNote(event.target.value)} required />
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
    </form>
    </RecordSheet>
  );
}
