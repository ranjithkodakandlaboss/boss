import { useEffect, useState } from "react";
import { Link, useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ArrowLeft, PackageOpen } from "lucide-react";
import { api } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import ConfirmDialog from "../components/shared/ConfirmDialog";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import StatusChip from "../components/shared/StatusChip";
import { type ProductionJob } from "../production/api";
import { loadJobs, loadLots, loadPositions, loadReservations, quantity, type Item, type JobReservation, type Lot, type Position } from "./api";
import { ItemForm } from "./ItemMaster";

export default function ItemDetail() {
  const { id = "" } = useParams();
  const [search] = useSearchParams();
  const navigate = useNavigate();
  const { user } = useCustomer();
  const canWrite = (user?.roles ?? []).some((role) => ["OWNER", "OPS_MANAGER", "STORE_KEEPER", "PURCHASE_MANAGER"].includes(role.role));
  const canDeactivate = (user?.roles ?? []).some((role) => ["OWNER", "OPS_MANAGER"].includes(role.role));
  const [item, setItem] = useState<Item | null>(null);
  const [positions, setPositions] = useState<Position[]>([]);
  const [lots, setLots] = useState<Lot[]>([]);
  const [jobs, setJobs] = useState<ProductionJob[]>([]);
  const [reservations, setReservations] = useState<JobReservation[]>([]);
  const [editing, setEditing] = useState(search.get("edit") === "1");
  const [deactivateConfirm, setDeactivateConfirm] = useState(false);
  const [reactivateConfirm, setReactivateConfirm] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  function load() {
    setLoading(true);
    Promise.all([
      api<Item>(`/api/v1/items/${id}`),
      loadPositions(`?itemId=${id}`),
      loadLots(`itemId=${id}`),
      loadJobs(),
      loadReservations(),
    ]).then(([next, stock, itemLots, jobPage, reserved]) => {
      setItem(next);
      setPositions(stock);
      setLots(itemLots);
      setJobs(jobPage);
      setReservations(reserved.filter((row) => row.itemId === id));
    }).catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Inventory item not found."))
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    if (!id) {
      setLoading(false);
      setError("Select an item to view its stock, lots and sources.");
      return;
    }
    load();
  }, [id]);

  if (loading) return <LoadingState label="Loading inventory item" />;
  if (error || !item) return <ErrorState body={error || "Inventory item not found."} />;

  const onHand = positions.reduce((sum, row) => sum + Number(row.totalQty), 0);
  const available = positions.reduce((sum, row) => sum + Number(row.availableQty), 0);
  const reserved = positions.reduce((sum, row) => sum + Number(row.reservedQty), 0);
  const qcHold = lots.filter((lot) => lot.status === "quarantine").reduce((sum, lot) => sum + Number(lot.qty), 0);
  const uom = item.uomSymbol || positions[0]?.uomSymbol || lots[0]?.uomSymbol || "";
  const usage = jobs.flatMap((job) => (job.materials ?? []).filter((row) => row.itemId === id).map((row) => ({ job, row })));

  async function deactivate() {
    try {
      await api(`/api/v1/items/${item!.id}`, { method: "DELETE" });
      setDeactivateConfirm(false);
      load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Item could not be deactivated.");
      setDeactivateConfirm(false);
    }
  }

  async function reactivate() {
    try {
      await api(`/api/v1/items/${item!.id}`, { method: "PATCH", body: JSON.stringify({ status: "active" }) });
      setReactivateConfirm(false);
      load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Item could not be reactivated.");
      setReactivateConfirm(false);
    }
  }

  return (
    <div className="p-4 md:p-6 max-w-5xl mx-auto space-y-5">
      <div className="flex items-start gap-3">
        <button type="button" onClick={() => navigate(-1)} aria-label="Back" className="text-[var(--text-3)] hover:text-[var(--text)]"><ArrowLeft size={16} /></button>
        <PackageOpen size={20} className="mt-0.5 shrink-0 text-[var(--brand)]" />
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono text-sm font-bold text-[var(--brand)]">{item.code}</span>
            <StatusChip status={item.status === "inactive" ? "inactive" : "active"} size="sm" />
          </div>
          <h1 className="text-xl font-bold mt-0.5">{item.name}</h1>
          {item.categoryLabel && <p className="text-sm text-[var(--text-2)]">{item.categoryLabel}</p>}
        </div>
        <div className="flex items-center gap-2 shrink-0">
          <Link to={`/procurement/pr/new?code=${encodeURIComponent(item.code)}&name=${encodeURIComponent(item.name)}&uom=${encodeURIComponent(uom)}`} className="rounded-lg px-3 py-2 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Create Purchase Requisition</Link>
          {canWrite && <button type="button" className="text-xs font-semibold text-[var(--brand)]" onClick={() => setEditing(true)}>Edit</button>}
          {canDeactivate && item.status !== "inactive" && <button type="button" className="text-xs font-semibold text-[var(--error)]" onClick={() => setDeactivateConfirm(true)}>Deactivate</button>}
          {canDeactivate && item.status === "inactive" && <button type="button" className="text-xs font-semibold text-[var(--success)]" onClick={() => setReactivateConfirm(true)}>Reactivate</button>}
        </div>
      </div>

      <div className="grid gap-3 sm:grid-cols-4">
        <Fact label="Item code" value={item.code} />
        <Fact label="Category" value={item.categoryLabel || "—"} />
        <Fact label="UOM" value={item.uomSymbol || "—"} />
        <Fact label="Tracking" value={(item.itemType ?? "").replaceAll("_", " ") || "—"} />
      </div>

      <section className="rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
        <div className="flex items-center justify-between mb-3">
          <h3 className="text-sm font-semibold">Stock summary</h3>
          <Link to="/inventory" className="text-xs font-semibold text-[var(--brand)]">Open Stock on Hand →</Link>
        </div>
        <div className="grid gap-3 sm:grid-cols-4">
          <Fact label="On hand" value={quantity(onHand, uom)} />
          <Fact label="Available" value={quantity(available, uom)} />
          <Fact label="Reserved" value={quantity(reserved, uom)} />
          <Fact label="Quality hold" value={quantity(qcHold, uom)} />
        </div>
      </section>

      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}

      {item.itemType === "FINISHED_GOOD" && <section className="rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
        <h2 className="text-sm font-bold">Product specifications</h2>
        <p className="mt-1 text-xs text-[var(--text-2)]">Product kind: {item.productKind ? item.productKind.toLowerCase() : "Not specified"}. Configuration is fixed at creation; sales and BOM integration is not yet available.</p>
        {(item.specifications ?? []).length === 0 ? <p className="mt-2 text-xs">No specifications configured.</p> : <ul className="mt-2 space-y-1">
          {item.specifications?.map((spec) => <li key={spec.key} className="text-xs"><strong>{spec.label}</strong> ({spec.key}) · {spec.uomSymbol || spec.uomId} · min {spec.minValue ?? "—"} · max {spec.maxValue ?? "—"} · standard {spec.defaultValue ?? "—"}</li>)}
        </ul>}
      </section>}

      <section className="overflow-hidden rounded-xl border" style={{ borderColor: "var(--border)" }}>
        <h2 className="border-b px-4 py-3 text-sm font-bold" style={{ borderColor: "var(--border)" }}>Lot and location</h2>
        <table className="w-full text-sm">
          <thead><tr>{["Lot", "Supplier Lot / Heat", "Grade", "Quantity", "Location", "Status", "Inspection", ""].map((label) => <th key={label} className="px-3 py-2 text-left text-xs">{label}</th>)}</tr></thead>
          <tbody>
            {lots.length === 0 && <tr><td colSpan={8} className="px-3 py-6 text-center text-sm text-[var(--text-3)]">No lots recorded for this item.</td></tr>}
            {lots.map((lot) => (
              <tr key={lot.id}>
                <td className="px-3 py-2 font-mono text-xs">{lot.lotNumber || lot.id.slice(0, 8)}</td>
                <td className="px-3 py-2 text-xs">{lot.batchRef || "—"}</td>
                <td className="px-3 py-2 text-xs">{(lot as { grade?: string }).grade || "—"}</td>
                <td className="px-3 py-2 font-mono text-xs">{quantity(lot.qty, lot.uomSymbol)}</td>
                <td className="px-3 py-2 text-xs">{lot.storeName}</td>
                <td className="px-3 py-2 text-xs">{lot.status}</td>
                <td className="px-3 py-2 text-xs">{(lot as { inspectionResult?: string }).inspectionResult || "—"}</td>
                <td className="px-3 py-2"><Link to={`/inventory/lot/${lot.id}`} className="text-xs font-semibold text-[var(--brand)]">Open lot</Link></td>
              </tr>
            ))}
          </tbody>
        </table>
      </section>
      <section className="overflow-hidden rounded-xl border" style={{ borderColor: "var(--border)" }}>
        <h2 className="border-b px-4 py-3 text-sm font-bold" style={{ borderColor: "var(--border)" }}>Used by jobs</h2>
        <table className="w-full text-sm">
          <thead><tr>{["Job", "Site", "Required", "Reserved", "Issued", "Issue reference"].map((label) => <th key={label} className="px-3 py-2 text-left text-xs">{label}</th>)}</tr></thead>
          <tbody>
            {usage.length === 0 && <tr><td colSpan={6} className="px-3 py-6 text-center text-sm text-[var(--text-3)]">No job allocation or consumption is recorded.</td></tr>}
            {usage.map(({ job, row }) => {
              const reservedQty = reservations.filter((entry) => entry.jobId === job.id).reduce((sum, entry) => sum + Number(entry.reservedQty), 0);
              return (
                <tr key={`${job.id}-${row.id}`}>
                  <td className="px-3 py-2"><Link to={`/jobs/${job.id}`} className="text-xs font-semibold text-[var(--brand)]">{job.jobNumber}</Link></td>
                  <td className="px-3 py-2 text-xs">{job.siteName}</td>
                  <td className="px-3 py-2 font-mono text-xs">{quantity(row.requiredQty, row.uomSymbol)}</td>
                  <td className="px-3 py-2 font-mono text-xs">{quantity(reservedQty, row.uomSymbol)}</td>
                  <td className="px-3 py-2 font-mono text-xs">{quantity(row.issuedQty, row.uomSymbol)}</td>
                  <td className="px-3 py-2 text-xs">{(row as { issueReference?: string }).issueReference || "—"}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </section>

      {editing && <ItemForm item={item} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); load(); }} />}

      <ConfirmDialog
        open={deactivateConfirm}
        title="Deactivate item?"
        body={`${item.name} will be marked inactive. It will no longer appear in active lists but its history is preserved.`}
        confirmLabel="Deactivate"
        variant="danger"
        onConfirm={() => void deactivate()}
        onCancel={() => setDeactivateConfirm(false)}
      />
      <ConfirmDialog
        open={reactivateConfirm}
        title="Reactivate item?"
        body={`${item.name} will be restored to active status and appear in item master lists.`}
        confirmLabel="Reactivate"
        onConfirm={() => void reactivate()}
        onCancel={() => setReactivateConfirm(false)}
      />
    </div>
  );
}

function Fact({ label, value }: { label: string; value: string }) {
  return <div className="rounded-lg border p-3" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}><div className="text-[11px] text-[var(--text-3)]">{label}</div><div className="mt-1 text-sm font-semibold">{value}</div></div>;
}
