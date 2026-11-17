import { useCallback, useEffect, useMemo, useState, type FormEvent, type ReactNode } from "react";
import { Link, useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { ArrowLeft, ChevronRight, Plus, Search } from "lucide-react";
import {
  procurementApi,
  type SupplierComparison,
} from "../../api/procurement";
import Button from "../../components/shared/Button";
import ConfirmDialog from "../../components/shared/ConfirmDialog";
import EmptyState from "../../components/shared/EmptyState";
import ErrorState from "../../components/shared/ErrorState";
import { SelectField, TextField } from "../../components/shared/Fields";
import LoadingState from "../../components/shared/LoadingState";
import StatusChip, { getStatusLabel } from "../../components/shared/StatusChip";
import Tabs from "../../components/shared/Tabs";

type LoadState<T> =
  | { status: "loading" }
  | { status: "error"; message: string }
  | { status: "ready"; data: T };

const PROCUREMENT_TABS = [
  { id: "pr", label: "Purchase Requisitions" },
  { id: "quotes", label: "Supplier Quotations" },
  { id: "spo", label: "Supplier POs" },
  { id: "approvals", label: "Approvals" },
  { id: "grn", label: "Receiving & Inventory" },
];

function useLoad<T>(loader: () => Promise<T>) {
  const [state, setState] = useState<LoadState<T>>({ status: "loading" });
  const reload = useCallback(async () => {
    setState({ status: "loading" });
    try {
      setState({ status: "ready", data: await loader() });
    } catch (error) {
      setState({ status: "error", message: error instanceof Error ? error.message : "The request failed." });
    }
  }, [loader]);
  useEffect(() => { void reload(); }, [reload]);
  return { state, reload };
}

function useNotice() {
  const [notice, setNotice] = useState<{ type: "success" | "error"; message: string } | null>(null);
  const show = (type: "success" | "error", message: string) => setNotice({ type, message });
  const content = notice && (
    <div role={notice.type === "error" ? "alert" : "status"} className={`mx-6 mt-3 rounded-lg border px-3 py-2 text-sm ${notice.type === "error" ? "border-[var(--error)] text-[var(--error)]" : "border-[var(--success)] text-[var(--success)]"}`}>
      {notice.message}
      <button type="button" className="ml-3 underline" onClick={() => setNotice(null)}>Dismiss</button>
    </div>
  );
  return { show, content };
}

function PageState<T>({ state, emptyTitle, children }: { state: LoadState<T>; emptyTitle: string; children: (data: T) => ReactNode }) {
  if (state.status === "loading") return <LoadingState label="Loading records from the customer API." />;
  if (state.status === "error") return <ErrorState body={state.message} />;
  if (Array.isArray(state.data) && state.data.length === 0) return <EmptyState title={emptyTitle} body="No matching records were returned by the customer API." />;
  return <>{children(state.data)}</>;
}

function Table({ headings, children }: { headings: string[]; children: ReactNode }) {
  return (
    <div className="overflow-auto rounded-xl border mx-6" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
      <table className="eoss-table min-w-full">
        <thead><tr>{headings.map((heading) => <th key={heading}>{heading}</th>)}</tr></thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

function ProcurementHeader({ title, backTo, children }: { title: string; backTo?: string; children?: ReactNode }) {
  return (
    <header className="flex items-center justify-between gap-3 px-6 py-4">
      <div className="flex items-center gap-3">
        {backTo && <Link to={backTo} aria-label="Back to Procurement" className="rounded-lg p-2 text-[var(--text-2)] hover:bg-[var(--surface-2)]"><ArrowLeft size={17} /></Link>}
        <h1 className="text-lg font-bold text-[var(--text)]">{title}</h1>
      </div>
      {children}
    </header>
  );
}

function RecordLink({ to, children }: { to: string; children: ReactNode }) {
  return <Link className="font-semibold text-[var(--brand)] hover:underline" to={to}>{children}</Link>;
}

export default function ProcurementWorkspace({ screenPath }: { screenPath: string }) {
  const location = useLocation();
  if (screenPath === "procurement") return <ProcurementList />;
  if (location.pathname === "/procurement/pr/new") return <PurchaseRequisitionCreate />;
  if (location.pathname === "/procurement/spo/new") return <SupplierPoCreate />;
  if (location.pathname.startsWith("/procurement/pr/")) return <PurchaseRequisitionDetail id={location.pathname.split("/")[3]} />;
  if (location.pathname.startsWith("/procurement/quote/")) return <QuotationDetail id={location.pathname.split("/")[3]} />;
  if (location.pathname.startsWith("/procurement/spo/")) return <SupplierPoDetail id={location.pathname.split("/")[3]} />;
  return <ProcurementList />;
}

function ProcurementList() {
  const [params, setParams] = useSearchParams();
  const tab = PROCUREMENT_TABS.some((item) => item.id === params.get("tab")) ? params.get("tab")! : "pr";
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState("all");
  const navigate = useNavigate();
  const loadPrs = useCallback(() => procurementApi.listPrs(`?page=0&size=50${status === "all" ? "" : `&status=${encodeURIComponent(status)}`}`), [status]);
  const prs = useLoad(loadPrs);
  const loadSpos = useCallback(() => procurementApi.listSpos(), []);
  const spos = useLoad(loadSpos);
  const loadQuotes = useCallback(async () => {
    const prPage = await procurementApi.listPrs("?page=0&size=50");
    const pages = await Promise.all(prPage.content.map((pr) => procurementApi.listQuotations(pr.id)));
    return pages.flatMap((page, index) => page.content.map((quote) => ({
      ...quote,
      prNumber: prPage.content[index].prNumber,
    })));
  }, []);
  const quotes = useLoad(loadQuotes);
  const setTab = (next: string) => {
    const updated = new URLSearchParams(params);
    updated.set("tab", next);
    setParams(updated, { replace: true });
  };
  const search = (value: string) => value.toLocaleLowerCase();
  const visiblePrs = useMemo(() => prs.state.status === "ready"
    ? prs.state.data.content.filter((pr) => `${pr.prNumber} ${pr.originType} ${pr.status}`.toLocaleLowerCase().includes(search(query)))
    : [], [prs.state, query]);
  const visibleQuotes = useMemo(() => quotes.state.status === "ready"
    ? quotes.state.data.filter((quote) => `${quote.supplierName} ${quote.status} ${quote.prId}`.toLocaleLowerCase().includes(search(query)))
    : [], [quotes.state, query]);
  const visibleSpos = useMemo(() => spos.state.status === "ready"
    ? spos.state.data.content.filter((spo) => `${spo.spoNumber} ${spo.supplierName} ${spo.status}`.toLocaleLowerCase().includes(search(query)))
    : [], [spos.state, query]);
  const activeState = tab === "quotes" ? quotes.state : tab === "spo" ? spos.state : prs.state;

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <ProcurementHeader title={tab === "pr" ? "Purchase Requisitions" : tab === "quotes" ? "Supplier Quotations" : tab === "spo" ? "Supplier POs" : tab === "approvals" ? "Approvals" : "Receiving & Inventory"}>
        {tab === "pr" && <Button onClick={() => navigate("/procurement/pr/new")}><Plus size={15} /> New Purchase Requisition</Button>}
      </ProcurementHeader>
      <Tabs tabs={PROCUREMENT_TABS} active={tab} onChange={setTab} />
      {tab !== "grn" && (
        <div className="flex flex-wrap items-center gap-2 px-6 py-3">
          <label className="eoss-search-wrap relative flex-1 min-w-56">
            <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-3)]" />
            <input aria-label="Search procurement records" className="eoss-search-input w-full pl-9" placeholder={tab === "pr" || tab === "approvals" ? "Search PR number or status" : tab === "quotes" ? "Search supplier quotation or PR" : "Search Supplier PO or supplier"} value={query} onChange={(event) => setQuery(event.target.value)} />
          </label>
          {(tab === "pr" || tab === "approvals") && <select aria-label="Purchase Requisition status" className="eoss-input min-h-[36px]" value={tab === "approvals" ? "submitted" : status} onChange={(event) => setStatus(event.target.value)} disabled={tab === "approvals"}>
            <option value="all">All PR statuses</option>
            {["draft", "submitted", "approved", "partially_ordered", "ordered", "rejected", "cancelled"].map((item) => <option key={item} value={item}>{getStatusLabel(item)}</option>)}
          </select>}
          {activeState.status === "error" && <Button variant="ghost" onClick={() => void (tab === "quotes" ? quotes.reload() : tab === "spo" ? spos.reload() : prs.reload())}>Retry</Button>}
        </div>
      )}
      {tab === "grn" ? (
        <div className="m-6 rounded-xl border p-5 text-sm text-[var(--text-2)]" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          Receiving and inventory posting are handled in Issue #51. No GRN actions are available here.
        </div>
      ) : tab === "pr" || tab === "approvals" ? (
        <PageState state={prs.state} emptyTitle="No purchase requisitions">
          {(page) => {
            const rows = tab === "approvals" ? page.content.filter((pr) => pr.status === "submitted") : visiblePrs;
            return rows.length === 0 ? <EmptyState title={tab === "approvals" ? "No approvals waiting" : "No purchase requisitions"} body="No matching requisitions were returned by the customer API." /> : (
              <Table headings={["PR Number", "Origin", "Required By", "Lines", "Quantity", "Status", "Created"]}>
                {rows.map((pr) => <tr key={pr.id}>
                  <td><RecordLink to={`/procurement/pr/${pr.id}`}>{pr.prNumber}</RecordLink></td>
                  <td>{getStatusLabel(pr.originType)}</td><td>{pr.requiredDate ?? "—"}</td><td>{pr.lines.length}</td><td>{pr.totalQty}</td><td><StatusChip status={pr.status} size="sm" /></td><td>{new Date(pr.createdAt).toLocaleDateString()}</td>
                </tr>)}
              </Table>
            );
          }}
        </PageState>
      ) : tab === "quotes" ? (
        <PageState state={quotes.state} emptyTitle="No supplier quotations">
          {() => visibleQuotes.length === 0 ? <EmptyState title="No supplier quotations" body="Quotation records for the loaded requisitions will appear here." /> : (
            <Table headings={["Supplier", "Purchase Requisition", "Valid Until", "Lines", "Status"]}>
              {visibleQuotes.map((quote) => <tr key={quote.id}>
                <td><RecordLink to={`/procurement/quote/${quote.id}`}>{quote.supplierName}</RecordLink></td>
                <td><RecordLink to={`/procurement/pr/${quote.prId}`}>{quote.prNumber}</RecordLink></td><td>{quote.validUntil ?? "—"}</td><td>{quote.lines.length}</td><td><StatusChip status={quote.status} size="sm" /></td>
              </tr>)}
            </Table>
          )}
        </PageState>
      ) : (
        <PageState state={spos.state} emptyTitle="No supplier purchase orders">
          {() => visibleSpos.length === 0 ? <EmptyState title="No supplier purchase orders" body="No matching orders were returned by the customer API." /> : (
            <Table headings={["SPO Number", "Supplier", "Purchase Requisition", "Value", "Status", "Created"]}>
              {visibleSpos.map((spo) => <tr key={spo.id}>
                <td><RecordLink to={`/procurement/spo/${spo.id}`}>{spo.spoNumber}</RecordLink></td><td>{spo.supplierName}</td>
                <td><RecordLink to={`/procurement/pr/${spo.prId}`}>{spo.prId}</RecordLink></td><td>{money(spo.totalAmount)}</td><td><StatusChip status={spo.status} size="sm" /></td><td>{new Date(spo.createdAt).toLocaleDateString()}</td>
              </tr>)}
            </Table>
          )}
        </PageState>
      )}
    </div>
  );
}

function PurchaseRequisitionCreate() {
  const navigate = useNavigate();
  const { show, content } = useNotice();
  const loadMasters = useCallback(async () => {
    const [items, uoms] = await Promise.all([procurementApi.listItems(), procurementApi.listUoms()]);
    return { items: items.content, uoms };
  }, []);
  const masters = useLoad(loadMasters);
  const [requiredDate, setRequiredDate] = useState("");
  const [priority, setPriority] = useState("normal");
  const [notes, setNotes] = useState("");
  const [step, setStep] = useState<1 | 2 | 3>(1);
  const [lines, setLines] = useState([{ itemId: "", qty: "", uomId: "", note: "", requiredDate: "" }]);
  const [saving, setSaving] = useState(false);
  const addLine = () => setLines((current) => [...current, { itemId: "", qty: "", uomId: "", note: "", requiredDate: "" }]);
  const updateLine = (index: number, patch: Partial<(typeof lines)[number]>) =>
    setLines((current) => current.map((line, position) => position === index ? { ...line, ...patch } : line));

  const hasValidLines = () => lines.length > 0 && lines.every((line) =>
    line.itemId && line.uomId && Number.isFinite(Number(line.qty)) && Number(line.qty) > 0);

  const continueStep = () => {
    if (step === 1 && !requiredDate) {
      show("error", "Choose a required-by date before continuing.");
      return;
    }
    if (step === 2 && !hasValidLines()) {
      show("error", "Choose an item and unit of measure and enter a quantity greater than zero for every line.");
      return;
    }
    setStep((current) => Math.min(3, current + 1) as 1 | 2 | 3);
  };

  const submit = async (event: FormEvent) => {
    event.preventDefault();
    if (!requiredDate || !hasValidLines()) {
      show("error", "Complete the required-by date and every material line before creating the requisition.");
      setStep(!requiredDate ? 1 : 2);
      return;
    }
    setSaving(true);
    try {
      const pr = await procurementApi.createPr({
        originType: "manual",
        requiredDate: requiredDate || null,
        priority,
        notes: notes.trim() || null,
        lines: lines.map((line) => ({ itemId: line.itemId, qty: Number(line.qty), uomId: line.uomId, requiredDate: line.requiredDate || null, note: line.note.trim() || null })),
      });
      navigate(`/procurement/pr/${pr.id}`);
    } catch (error) {
      show("error", error instanceof Error ? error.message : "Purchase Requisition could not be created.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="flex flex-col">
      <ProcurementHeader title="New Purchase Requisition" backTo="/procurement?tab=pr" />
      {content}
      <div className="mx-6 mb-3 flex max-w-4xl items-center gap-2">
        {(["Header", "Lines", "Review"] as const).map((label, index) => {
          const number = (index + 1) as 1 | 2 | 3;
          return <button key={label} type="button" disabled={number >= step} onClick={() => setStep(number)} className={`rounded-full px-3 py-1.5 text-xs font-semibold ${number === step ? "text-white" : "text-[var(--text-2)]"}`} style={{ background: number === step ? "var(--brand-fill)" : "var(--surface-2)" }}>
            {number < step ? "✓ " : `${number} `}{label}
          </button>;
        })}
      </div>
      <form className="mx-6 mb-8 max-w-4xl space-y-5" onSubmit={(event) => void submit(event)}>
        {step === 1 && <section className="eoss-card space-y-4 p-5">
          <h2 className="text-sm font-bold text-[var(--text)]">Requisition Header</h2>
          <div className="grid gap-4 md:grid-cols-2">
            <SelectField label="Origin" value="manual" disabled><option value="manual">Manual</option></SelectField>
            <TextField label="Required By" type="date" required value={requiredDate} onChange={(event) => setRequiredDate(event.target.value)} />
          </div>
          <SelectField label="Priority" value={priority} onChange={(event) => setPriority(event.target.value)}>
            {["low", "normal", "high", "urgent"].map((item) => <option key={item} value={item}>{getStatusLabel(item)}</option>)}
          </SelectField>
          <TextField label="Reason / Purpose and Notes" value={notes} onChange={(event) => setNotes(event.target.value)} placeholder="Additional procurement instructions or delivery notes" />
        </section>}
        {step === 2 && <section className="eoss-card space-y-4 p-5">
          <div className="flex items-center justify-between gap-2">
            <h2 className="text-sm font-bold text-[var(--text)]">Material Lines</h2>
            <Button type="button" variant="ghost" onClick={addLine}><Plus size={14} /> Add Line</Button>
          </div>
          {masters.state.status === "loading" && <LoadingState label="Loading item catalogue and units of measure." />}
          {masters.state.status === "error" && <ErrorState body={masters.state.message} />}
          {masters.state.status === "ready" && lines.map((line, index) => <div key={index} className="rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
            <div className="mb-3 text-xs font-semibold text-[var(--text-2)]">Line {index + 1}</div>
            <div className="grid items-end gap-3 md:grid-cols-[2fr_1fr_1fr]">
            <SelectField label="Select Catalog Material" value={line.itemId} onChange={(event) => {
                const item = masters.state.status === "ready" ? masters.state.data.items.find((candidate) => candidate.id === event.target.value) : undefined;
              updateLine(index, { itemId: event.target.value, uomId: item?.uomId ?? line.uomId });
            }}>
              <option value="">Select catalogue item</option>
                {masters.state.status === "ready" && masters.state.data.items.map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}
            </SelectField>
            <TextField label="Quantity" type="number" min="0.001" step="any" value={line.qty} onChange={(event) => updateLine(index, { qty: event.target.value })} />
            <SelectField label="UOM" value={line.uomId} onChange={(event) => updateLine(index, { uomId: event.target.value })}>
              <option value="">Select unit</option>
              {masters.state.status === "ready" && masters.state.data.uoms.map((uom) => <option key={uom.id} value={uom.id}>{uom.symbol} — {uom.name}</option>)}
            </SelectField>
            </div>
            <div className="mt-3 grid gap-3 md:grid-cols-2">
            <TextField label="Required By" type="date" value={line.requiredDate} onChange={(event) => updateLine(index, { requiredDate: event.target.value })} />
            <TextField label="Line Note" value={line.note} onChange={(event) => updateLine(index, { note: event.target.value })} />
            </div>
            {lines.length > 1 && <Button type="button" variant="ghost" className="mt-2" onClick={() => setLines((current) => current.filter((_, position) => position !== index))}>Remove Line</Button>}
          </div>)}
          {masters.state.status === "ready" && <div className="text-right text-sm font-semibold text-[var(--text)]">Total Quantity: {lines.reduce((sum, line) => sum + (Number(line.qty) || 0), 0)}</div>}
        </section>}
        {step === 3 && <section className="eoss-card space-y-4 p-5">
          <h2 className="text-sm font-bold text-[var(--text)]">Review & Submit</h2>
          <div className="grid gap-3 md:grid-cols-2">
            <SummaryCard label="Origin">Manual</SummaryCard>
            <SummaryCard label="Required By">{requiredDate}</SummaryCard>
            <SummaryCard label="Priority">{getStatusLabel(priority)}</SummaryCard>
            <SummaryCard label="Lines">{lines.length} material{lines.length === 1 ? "" : "s"}</SummaryCard>
            <SummaryCard label="Total Quantity">{lines.reduce((sum, line) => sum + (Number(line.qty) || 0), 0)}</SummaryCard>
            <SummaryCard label="Notes">{notes || "—"}</SummaryCard>
          </div>
          <Table headings={["#", "Item", "Quantity", "UOM", "Line Notes"]}>
            {lines.map((line, index) => {
              const item = masters.state.status === "ready" ? masters.state.data.items.find((candidate) => candidate.id === line.itemId) : undefined;
              const uom = masters.state.status === "ready" ? masters.state.data.uoms.find((candidate) => candidate.id === line.uomId) : undefined;
              return <tr key={index}><td>{index + 1}</td><td>{item ? `${item.code} — ${item.name}` : "—"}</td><td>{line.qty}</td><td>{uom?.symbol ?? "—"}</td><td>{line.note || "—"}</td></tr>;
            })}
          </Table>
        </section>}
        {masters.state.status === "ready" && <div className="flex justify-between gap-2">
          <div className="flex gap-2">
            <Button type="button" variant="ghost" onClick={() => navigate("/procurement?tab=pr")}>Cancel</Button>
            {step > 1 && <Button type="button" variant="ghost" onClick={() => setStep((current) => (current - 1) as 1 | 2 | 3)}>Back</Button>}
          </div>
          {step < 3
            ? <Button type="button" onClick={continueStep}>Continue</Button>
            : <Button type="submit" disabled={saving}>{saving ? "Creating…" : "Create Purchase Requisition"}</Button>}
        </div>}
      </form>
    </div>
  );
}

function PurchaseRequisitionDetail({ id }: { id: string }) {
  const navigate = useNavigate();
  const { show, content } = useNotice();
  const [supplierId, setSupplierId] = useState("");
  const [busy, setBusy] = useState(false);
  const [confirm, setConfirm] = useState<"submit" | "approve" | null>(null);
  const load = useCallback(async () => {
    const [pr, quotationPage] = await Promise.all([procurementApi.getPr(id), procurementApi.listQuotations(id)]);
    return { pr, quotations: quotationPage.content };
  }, [id]);
  const data = useLoad(load);
  const loadSuppliers = useCallback(async () => (await procurementApi.listSuppliers()).content, []);
  const suppliers = useLoad(loadSuppliers);
  const loadComparison = useCallback(() => procurementApi.compareQuotations(id), [id]);
  const comparison = useLoad(loadComparison);

  const runTransition = async () => {
    if (!confirm || data.state.status !== "ready") return;
    setBusy(true);
    try {
      if (confirm === "submit") await procurementApi.submitPr(id);
      else await procurementApi.approvePr(id);
      show("success", confirm === "submit" ? "Purchase Requisition submitted for approval." : "Purchase Requisition approved.");
      await data.reload();
    } catch (error) {
      show("error", error instanceof Error ? error.message : "The requisition could not be updated.");
    } finally {
      setBusy(false);
      setConfirm(null);
    }
  };

  const requestQuote = async () => {
    if (!supplierId) return show("error", "Select a supplier before requesting a quotation.");
    setBusy(true);
    try {
      const quote = await procurementApi.requestQuotation(id, supplierId);
      show("success", "Supplier quotation request created.");
      navigate(`/procurement/quote/${quote.id}`);
    } catch (error) {
      show("error", error instanceof Error ? error.message : "Quotation request failed.");
    } finally {
      setBusy(false);
    }
  };

  return <div className="flex flex-col">
    <ProcurementHeader title={data.state.status === "ready" ? data.state.data.pr.prNumber : "Purchase Requisition"} backTo="/procurement?tab=pr">
      {data.state.status === "ready" && <>
        {data.state.data.pr.status === "draft" && <Button onClick={() => setConfirm("submit")}>Submit for Approval</Button>}
        {data.state.data.pr.status === "submitted" && <Button onClick={() => setConfirm("approve")}>Approve</Button>}
      </>}
    </ProcurementHeader>
    {content}
    <PageState state={data.state} emptyTitle="Purchase Requisition not found">{({ pr, quotations }) => <>
      <div className="mx-6 mb-5 grid gap-3 md:grid-cols-4">
        <SummaryCard label="Status"><StatusChip status={pr.status} /></SummaryCard>
        <SummaryCard label="Origin">{getStatusLabel(pr.originType)}</SummaryCard>
        <SummaryCard label="Required By">{pr.requiredDate ?? "Not specified"}</SummaryCard>
        <SummaryCard label="Requested Quantity">{pr.totalQty}</SummaryCard>
      </div>
      {pr.notes && <p className="mx-6 mb-4 text-sm text-[var(--text-2)]">{pr.notes}</p>}
      <Table headings={["#", "Item", "Quantity", "Ordered", "UOM", "Required By", "Note"]}>
        {pr.lines.map((line, index) => <tr key={line.id}><td>{index + 1}</td><td>{line.itemCode} — {line.itemName}</td><td>{line.qty}</td><td>{line.orderedQty}</td><td>{line.uomSymbol}</td><td>{line.requiredDate ?? "—"}</td><td>{line.note ?? "—"}</td></tr>)}
      </Table>
      <section className="mx-6 my-5 eoss-card space-y-4 p-5">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div><h2 className="text-sm font-bold">Supplier Quotations</h2><p className="mt-1 text-xs text-[var(--text-2)]">Quotation requests, offers, and comparisons linked to this requisition.</p></div>
          <div className="flex items-end gap-2">
            {suppliers.state.status === "loading" && <LoadingState label="Loading suppliers." />}
            {suppliers.state.status === "error" && <ErrorState body={suppliers.state.message} />}
            {suppliers.state.status === "ready" && <>
              <SelectField label="Supplier" value={supplierId} onChange={(event) => setSupplierId(event.target.value)}>
                <option value="">Select supplier</option>{suppliers.state.data.map((supplier) => <option key={supplier.id} value={supplier.id}>{supplier.code} — {supplier.name}</option>)}
              </SelectField>
              <Button disabled={busy || pr.status !== "approved"} onClick={() => void requestQuote()}>Request Quotation</Button>
            </>}
          </div>
        </div>
        {quotations.length === 0 ? <EmptyState title="No quotations yet" body={pr.status === "approved" ? "Request a supplier quotation to begin comparison." : "Supplier quotations can be requested after the requisition is approved."} /> : (
          <div className="space-y-2">{quotations.map((quote) => <div key={quote.id} className="flex flex-wrap items-center justify-between gap-2 rounded-lg border p-3" style={{ borderColor: "var(--border)" }}>
            <RecordLink to={`/procurement/quote/${quote.id}`}>{quote.supplierName}</RecordLink><StatusChip status={quote.status} size="sm" /><span className="text-xs text-[var(--text-2)]">{quote.lines.length} lines · Valid until {quote.validUntil ?? "—"}</span>
          </div>)}</div>
        )}
      </section>
      <section className="mx-6 mb-8 eoss-card space-y-3 p-5">
        <h2 className="text-sm font-bold">Quotation Comparison</h2>
        {comparison.state.status === "loading" && <LoadingState label="Loading supplier comparison." />}
        {comparison.state.status === "error" && <ErrorState body={comparison.state.message} />}
        {comparison.state.status === "ready" && <ComparisonTable comparison={comparison.state.data} />}
      </section>
    </>}
    </PageState>
    <ConfirmDialog
      open={Boolean(confirm)}
      title={confirm === "approve" ? "Approve Purchase Requisition?" : "Submit Purchase Requisition?"}
      body={confirm === "approve" ? "This approves the submitted requisition and enables supplier quotation requests." : "This submits the draft requisition for approval."}
      confirmLabel={busy ? "Working…" : confirm === "approve" ? "Approve PR" : "Submit PR"}
      onCancel={() => { if (!busy) setConfirm(null); }}
      onConfirm={() => void runTransition()}
    />
  </div>;
}

function SummaryCard({ label, children }: { label: string; children: React.ReactNode }) {
  return <div className="eoss-card p-4"><div className="text-xs text-[var(--text-3)]">{label}</div><div className="mt-1 text-sm font-semibold text-[var(--text)]">{children}</div></div>;
}

function ComparisonTable({ comparison }: { comparison: SupplierComparison }) {
  if (comparison.lines.length === 0 || !comparison.lines.some((line) => line.offers.length > 0)) {
    return <EmptyState title="No received offers to compare" body="Supplier offers appear here after quotation details are received." />;
  }
  return <div className="space-y-4">{comparison.lines.map((line) => <div key={line.prLineId}>
    <h3 className="mb-2 text-sm font-semibold">{line.itemCode} — {line.itemName} <span className="font-normal text-[var(--text-2)]">· {line.quantity} {line.uomSymbol}</span></h3>
    <Table headings={["Supplier", "Unit Price", "Lead Time", "Valid Until", "Status"]}>
      {line.offers.map((offer) => <tr key={offer.supplierQuotationId}>
        <td><Link className="text-[var(--brand)] hover:underline" to={`/procurement/quote/${offer.supplierQuotationId}`}>{offer.supplierName}</Link></td><td>{money(offer.unitPrice)}</td><td>{offer.leadTimeDays == null ? "—" : `${offer.leadTimeDays} days`}</td><td>{offer.validUntil ?? "—"}</td><td><StatusChip status={offer.isSelected ? "selected" : offer.status} size="sm" /></td>
      </tr>)}
    </Table>
  </div>)}</div>;
}

function QuotationDetail({ id }: { id: string }) {
  const navigate = useNavigate();
  const { show, content } = useNotice();
  const [validUntil, setValidUntil] = useState("");
  const [lineValues, setLineValues] = useState<Record<string, { unitPrice: string; leadTimeDays: string }>>({});
  const [confirm, setConfirm] = useState<"select" | "reject" | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(async () => {
    const quote = await procurementApi.getQuotation(id);
    const [pr, comparison] = await Promise.all([procurementApi.getPr(quote.prId), procurementApi.compareQuotations(quote.prId)]);
    return { quote, pr, comparison };
  }, [id]);
  const data = useLoad(load);

  const loaded = data.state.status === "ready" ? data.state.data : undefined;
  useEffect(() => {
    if (!loaded) return;
    setLineValues(Object.fromEntries(loaded.pr.lines.map((line) => {
      const quoted = loaded.quote.lines.find((item) => item.prLineId === line.id);
      return [line.id, { unitPrice: quoted ? String(quoted.unitPrice) : "", leadTimeDays: quoted?.leadTimeDays == null ? "" : String(quoted.leadTimeDays) }];
    })));
    setValidUntil(loaded.quote.validUntil ?? "");
  }, [loaded]);

  const refresh = () => void data.reload();
  const receive = async () => {
    if (data.state.status !== "ready") return;
    const { pr, quote } = data.state.data;
    const lines = pr.lines.map((line) => ({ line, value: lineValues[line.id] }));
    if (!lines.length || lines.some(({ value }) => !value?.unitPrice || Number(value.unitPrice) < 0 || (value.leadTimeDays !== "" && Number(value.leadTimeDays) < 0))) {
      show("error", "Enter a non-negative unit price for each Purchase Requisition line.");
      return;
    }
    setBusy(true);
    try {
      await procurementApi.receiveQuotation(quote.id, {
        validUntil: validUntil || null,
        lines: lines.map(({ line, value }) => ({ prLineId: line.id, unitPrice: Number(value!.unitPrice), leadTimeDays: value!.leadTimeDays === "" ? null : Number(value!.leadTimeDays) })),
      });
      show("success", "Supplier quotation details recorded.");
      refresh();
    } catch (error) {
      show("error", error instanceof Error ? error.message : "Quotation details could not be recorded.");
    } finally { setBusy(false); }
  };
  const transition = async () => {
    if (data.state.status !== "ready" || !confirm) return;
    setBusy(true);
    try {
      if (confirm === "select") await procurementApi.selectQuotation(id);
      else await procurementApi.rejectQuotation(id);
      show("success", confirm === "select" ? "Supplier quotation selected." : "Supplier quotation rejected.");
      refresh();
    } catch (error) {
      show("error", error instanceof Error ? error.message : "Quotation status could not be changed.");
    } finally { setBusy(false); setConfirm(null); }
  };

  return <div className="flex flex-col">
    <ProcurementHeader title="Supplier Quotation" backTo="/procurement?tab=quotes" />
    {content}
    <PageState state={data.state} emptyTitle="Supplier quotation not found">{({ quote, pr, comparison }) => {
      const comparisonLines = comparison.lines.filter((line) => line.offers.some((offer) => offer.supplierQuotationId === quote.id));
      const editable = quote.status === "requested";
      return <>
        <div className="mx-6 mb-5 grid gap-3 md:grid-cols-3">
          <SummaryCard label="Supplier">{quote.supplierName}</SummaryCard>
          <SummaryCard label="Status"><StatusChip status={quote.status} /></SummaryCard>
          <SummaryCard label="Purchase Requisition"><RecordLink to={`/procurement/pr/${pr.id}`}>{pr.prNumber}</RecordLink></SummaryCard>
        </div>
        {editable && <section className="mx-6 mb-5 eoss-card space-y-4 p-5">
          <h2 className="text-sm font-bold">Enter Supplier Offer</h2>
          <TextField label="Valid Until" type="date" value={validUntil} onChange={(event) => setValidUntil(event.target.value)} />
          <Table headings={["Material", "Requested Quantity", "UOM", "Unit Price (₹)", "Lead Time (days)"]}>
            {pr.lines.map((line) => <tr key={line.id}>
              <td>{line.itemCode} — {line.itemName}</td><td>{line.qty}</td><td>{line.uomSymbol}</td>
              <td><input aria-label={`${line.itemName} unit price`} className="eoss-input w-32" type="number" min="0" step="any" value={lineValues[line.id]?.unitPrice ?? ""} onChange={(event) => setLineValues((current) => ({ ...current, [line.id]: { ...current[line.id], unitPrice: event.target.value } }))} /></td>
              <td><input aria-label={`${line.itemName} lead time`} className="eoss-input w-28" type="number" min="0" step="1" value={lineValues[line.id]?.leadTimeDays ?? ""} onChange={(event) => setLineValues((current) => ({ ...current, [line.id]: { ...current[line.id], leadTimeDays: event.target.value } }))} /></td>
            </tr>)}
          </Table>
          <Button disabled={busy} onClick={() => void receive()}>{busy ? "Saving…" : "Record Quotation"}</Button>
        </section>}
        <section className="mx-6 mb-5 eoss-card space-y-4 p-5">
          <div className="flex items-center justify-between gap-3"><h2 className="text-sm font-bold">Quoted Lines</h2><StatusChip status={quote.status} size="sm" /></div>
          {quote.lines.length === 0 ? <EmptyState title="Offer details not received" body="Enter the supplier's prices and lead times to record this quotation." /> : (
            <Table headings={["Material", "Unit Price (₹)", "Lead Time", "Selected"]}>
              {comparisonLines.map((line) => {
                const offer = line.offers.find((item) => item.supplierQuotationId === quote.id)!;
                return <tr key={line.prLineId}><td>{line.itemCode} — {line.itemName}</td><td>{money(offer.unitPrice)}</td><td>{offer.leadTimeDays == null ? "—" : `${offer.leadTimeDays} days`}</td><td>{offer.isSelected ? <StatusChip status="selected" size="sm" /> : "—"}</td></tr>;
              })}
            </Table>
          )}
          {quote.status === "received" && <Button disabled={busy} onClick={async () => {
            setBusy(true);
            try { await procurementApi.reviewQuotation(id); show("success", "Quotation moved to review."); refresh(); }
            catch (error) { show("error", error instanceof Error ? error.message : "Quotation could not be reviewed."); }
            finally { setBusy(false); }
          }}>Start Review</Button>}
          {quote.status === "under_review" && <div className="flex gap-2">
            <Button disabled={busy} onClick={() => setConfirm("select")}>Select Quotation</Button>
            <Button variant="danger" disabled={busy} onClick={() => setConfirm("reject")}>Reject</Button>
          </div>}
          {quote.status === "selected" && <Button onClick={() => navigate(`/procurement/spo/new?quotation=${quote.id}`)}>Create Supplier PO</Button>}
        </section>
      </>;
    }}</PageState>
    <ConfirmDialog open={Boolean(confirm)} title={confirm === "select" ? "Select this supplier quotation?" : "Reject this supplier quotation?"} body={confirm === "select" ? "The selected quotation will be the source for any Supplier PO." : "This quotation will be marked as rejected."} confirmLabel={busy ? "Working…" : confirm === "select" ? "Select Quotation" : "Reject Quotation"} variant={confirm === "reject" ? "danger" : "primary"} onCancel={() => { if (!busy) setConfirm(null); }} onConfirm={() => void transition()} />
  </div>;
}

function SupplierPoDetail({ id }: { id: string }) {
  const { show, content } = useNotice();
  const [confirm, setConfirm] = useState<"issue" | "acknowledge" | "cancel" | null>(null);
  const [busy, setBusy] = useState(false);
  const load = useCallback(() => procurementApi.getSpo(id), [id]);
  const data = useLoad(load);

  const changeStatus = async () => {
    if (!confirm || data.state.status !== "ready") return;
    setBusy(true);
    try {
      if (confirm === "issue") await procurementApi.issueSpo(id);
      else if (confirm === "acknowledge") await procurementApi.acknowledgeSpo(id);
      else await procurementApi.cancelSpo(id);
      show("success", `Supplier PO ${confirm === "issue" ? "issued" : confirm === "acknowledge" ? "acknowledged" : "cancelled"}.`);
      await data.reload();
    } catch (error) {
      show("error", error instanceof Error ? error.message : "Supplier PO status could not be updated.");
    } finally { setBusy(false); setConfirm(null); }
  };

  return <div className="flex flex-col">
    <ProcurementHeader title={data.state.status === "ready" ? data.state.data.spoNumber : "Supplier PO"} backTo="/procurement?tab=spo">
      {data.state.status === "ready" && <>
        {data.state.data.status === "draft" && <Button onClick={() => setConfirm("issue")}>Issue Supplier PO</Button>}
        {data.state.data.status === "issued" && <Button onClick={() => setConfirm("acknowledge")}>Record Acknowledgement</Button>}
        {["draft", "issued", "acknowledged", "partially_received"].includes(data.state.data.status) && <Button variant="danger" onClick={() => setConfirm("cancel")}>Cancel</Button>}
      </>}
    </ProcurementHeader>
    {content}
    <PageState state={data.state} emptyTitle="Supplier PO not found">{(spo) => <>
      <div className="mx-6 mb-5 grid gap-3 md:grid-cols-4">
        <SummaryCard label="Supplier">{spo.supplierName}</SummaryCard>
        <SummaryCard label="Status"><StatusChip status={spo.status} /></SummaryCard>
        <SummaryCard label="Purchase Requisition"><RecordLink to={`/procurement/pr/${spo.prId}`}>{spo.prId}</RecordLink></SummaryCard>
        <SummaryCard label="Total">{money(spo.totalAmount)}</SummaryCard>
      </div>
      <div className="mx-6 mb-5 text-sm"><RecordLink to={`/procurement/quote/${spo.supplierQuotationId}`}>Selected supplier quotation <ChevronRight className="inline" size={14} /></RecordLink></div>
      <Table headings={["Material", "Ordered", "Received", "UOM", "Unit Price", "Line Total", "Expected Delivery"]}>
        {spo.lines.map((line) => <tr key={line.id}><td>{line.itemCode} — {line.itemName}</td><td>{line.orderedQty}</td><td>{line.receivedQty}</td><td>—</td><td>{money(line.unitPrice)}</td><td>{money(line.lineTotal)}</td><td>{line.expectedDeliveryDate ?? "—"}</td></tr>)}
      </Table>
      <p className="mx-6 my-4 text-xs text-[var(--text-3)]">Goods receipt and inventory posting are handled in Issue #51.</p>
    </>}</PageState>
    <ConfirmDialog open={Boolean(confirm)} title={`${confirm === "issue" ? "Issue" : confirm === "acknowledge" ? "Record supplier acknowledgement for" : "Cancel"} Supplier PO?`} body="This action updates the order through the procurement API." confirmLabel={busy ? "Working…" : getStatusLabel(confirm ?? "")} variant={confirm === "cancel" ? "danger" : "primary"} onCancel={() => { if (!busy) setConfirm(null); }} onConfirm={() => void changeStatus()} />
  </div>;
}

function SupplierPoCreate() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const quotationId = searchParams.get("quotation") ?? "";
  const { show, content } = useNotice();
  const [busy, setBusy] = useState(false);
  const [confirmationOpen, setConfirmationOpen] = useState(false);
  const load = useCallback(async () => {
    const quotation = await procurementApi.getQuotation(quotationId);
    const pr = await procurementApi.getPr(quotation.prId);
    const comparison = await procurementApi.compareQuotations(pr.id);
    return { quotation, pr, comparison };
  }, [quotationId]);
  const data = useLoad(load);
  const loaded = data.state.status === "ready" ? data.state.data : undefined;
  const lines = loaded ? loaded.pr.lines
    .map((line) => {
      const quoteLine = loaded.quotation.lines.find((item) => item.prLineId === line.id);
      return { line, quoteLine, remaining: line.qty - line.orderedQty };
    })
    .filter((item) => item.quoteLine && item.remaining > 0) : [];

  const create = async () => {
    if (data.state.status !== "ready") return;
    const { quotation, pr } = data.state.data;
    setBusy(true);
    try {
      const spo = await procurementApi.createSpo({
        supplierId: quotation.supplierId,
        prId: pr.id,
        supplierQuotationId: quotation.id,
        lines: lines.map(({ line, quoteLine, remaining }) => ({
          prLineId: line.id,
          itemId: line.itemId,
          orderedQty: remaining,
          unitPrice: quoteLine!.unitPrice,
          uomId: line.uomId,
        })),
      });
      navigate(`/procurement/spo/${spo.id}`);
    } catch (error) {
      show("error", error instanceof Error ? error.message : "Supplier PO could not be created.");
    } finally { setBusy(false); setConfirmationOpen(false); }
  };

  return <div className="flex flex-col">
    <ProcurementHeader title="Create Supplier PO" backTo={quotationId ? `/procurement/quote/${quotationId}` : "/procurement?tab=spo"} />
    {content}
    <PageState state={data.state} emptyTitle="Selected quotation not found">{({ quotation, pr }) => <>
      <div className="mx-6 mb-5 grid gap-3 md:grid-cols-3">
        <SummaryCard label="Purchase Requisition"><RecordLink to={`/procurement/pr/${pr.id}`}>{pr.prNumber}</RecordLink></SummaryCard>
        <SummaryCard label="Supplier">{quotation.supplierName}</SummaryCard>
        <SummaryCard label="Selected quotation"><RecordLink to={`/procurement/quote/${quotation.id}`}>{quotation.id}</RecordLink></SummaryCard>
      </div>
      {quotation.status !== "selected" && <ErrorState body="A Supplier PO can only be created from a selected quotation." />}
      {lines.length === 0 && <EmptyState title="No remaining approved quantities" body="This Purchase Requisition has no quantities available to order." />}
      {quotation.status === "selected" && lines.length > 0 && <>
        <Table headings={["Material", "Remaining PR Quantity", "Quoted Unit Price", "UOM"]}>
          {lines.map(({ line, quoteLine, remaining }) => <tr key={line.id}><td>{line.itemCode} — {line.itemName}</td><td>{remaining}</td><td>{money(quoteLine!.unitPrice)}</td><td>{line.uomSymbol}</td></tr>)}
        </Table>
        <div className="mx-6 my-5 flex justify-end"><Button disabled={busy} onClick={() => setConfirmationOpen(true)}>Create Supplier PO</Button></div>
      </>}
    </>}</PageState>
    <ConfirmDialog open={confirmationOpen} title="Create Supplier PO?" body="The PO will be created from the selected supplier quotation and linked to this Purchase Requisition." confirmLabel={busy ? "Creating…" : "Create Supplier PO"} onCancel={() => { if (!busy) setConfirmationOpen(false); }} onConfirm={() => void create()} />
  </div>;
}

function money(value: number) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(value);
}
