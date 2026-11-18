import { useEffect, useMemo, useState } from "react";
import { ArrowLeft, MapPin } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { api, safeRandomUUID } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import LoadingState from "../components/shared/LoadingState";
import { type ProductionJob } from "../production/api";
import { loadJobs, loadPositions, loadReservations, loadStores, quantity, type JobReservation, type Position, type Store } from "./api";

type Need = {
  id: string;
  job: ProductionJob;
  itemId: string;
  code: string;
  name: string;
  uom?: string;
  required: number;
  issued: number;
  available: number;
  reserved: number;
  shortage: number;
  plannedEnd?: string;
};

const CLOSED = new Set(["completed", "cancelled"]);

export default function SiteNeed() {
  const navigate = useNavigate();
  const { user } = useCustomer();
  const canReserve = (user?.roles ?? []).some((role) => ["OWNER", "OPS_MANAGER", "STORE_KEEPER", "PRODUCTION_SUPERVISOR"].includes(role.role));
  const [rows, setRows] = useState<Need[]>([]);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<"all" | "shortage" | "covered">("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [busy, setBusy] = useState<string | null>(null);

  function load() {
    setLoading(true);
    Promise.all([loadJobs(), loadPositions(), loadStores(), loadReservations()])
      .then(([jobs, positions, stores, reservations]) => {
        setRows(buildNeeds(jobs, positions, stores, reservations));
        setError("");
      })
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Site need could not be loaded."))
      .finally(() => setLoading(false));
  }

  useEffect(() => { load(); }, []);

  const visible = useMemo(() => {
    const term = query.trim().toLowerCase();
    return rows.filter((row) => {
      const text = !term || `${row.job.jobNumber} ${row.job.siteName ?? ""} ${row.name}`.toLowerCase().includes(term);
      const kind = row.shortage > 0 ? "shortage" : "covered";
      return text && (status === "all" || status === kind);
    }).sort((left, right) => right.shortage - left.shortage);
  }, [rows, query, status]);

  async function reserve(row: Need) {
    setBusy(row.job.id);
    setNotice("");
    setError("");
    try {
      await api(`/api/v1/inventory/reservations/${row.job.id}/requirements?partial=true`, {
        method: "POST",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({}),
      });
      setNotice(`Reserved available material for ${row.job.jobNumber}.`);
      load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Material was not reserved.");
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className="p-4 md:p-6 max-w-5xl mx-auto space-y-5">
      <button type="button" onClick={() => navigate("/inventory")} className="flex items-center gap-1.5 text-sm text-[var(--text-2)] hover:text-[var(--brand)] mb-3">
        <ArrowLeft size={14} /> Inventory
      </button>
      <div className="flex items-center gap-2">
        <MapPin size={20} className="text-[var(--brand)]" />
        <h1 className="text-xl font-bold">Site Need</h1>
      </div>
      <p className="text-sm text-[var(--text-2)] mt-1">Demand and fulfilment planning by site. Reserving uses the production job reservation, which keeps one stock reservation per job.</p>

      <div className="flex flex-wrap items-center gap-2">
        <input aria-label="Search site need" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search job, site or item…" className="eoss-input max-w-sm flex-1 text-sm" />
        {(["all", "shortage", "covered"] as const).map((value) => (
          <button key={value} type="button" onClick={() => setStatus(value)} className="rounded-lg px-2.5 py-1 text-xs font-medium" style={{ background: status === value ? "var(--brand-fill)" : "transparent", color: status === value ? "#fff" : "var(--text-2)", border: "1px solid var(--border)" }}>
            {value === "all" ? "All" : value === "shortage" ? "Shortage" : "Available"}
          </button>
        ))}
      </div>
      {notice && <p role="status" className="text-sm text-[var(--success)]">{notice}</p>}
      {error && !loading && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      {loading ? <LoadingState label="Loading site need" /> : (
        <table className="w-full text-sm">
          <thead><tr>{["Site", "Item", "Required", "Available", "Reserved", "Shortage", "Required by", "Source / action"].map((label) => <th key={label} className="px-3 py-2 text-left text-xs">{label}</th>)}</tr></thead>
          <tbody>
            {visible.length === 0 && <tr><td colSpan={8} className="py-10 text-center text-sm text-[var(--text-3)]">No active site requirements.</td></tr>}
            {visible.map((row) => {
              const uncovered = Math.max(row.shortage, 0);
              const pr = new URLSearchParams({
                name: row.name,
                code: row.code,
                materialId: row.itemId,
                qty: String(uncovered),
                uom: row.uom ?? "",
                site: row.job.siteId ?? "",
              });
              return (
                <tr key={row.id}>
                  <td className="px-3 py-2"><Link to={`/jobs/${row.job.id}`} className="text-xs font-semibold text-[var(--brand)]">{row.job.siteName || "Site"}</Link><div className="text-[11px] text-[var(--text-3)]">{row.job.jobNumber}</div></td>
                  <td className="px-3 py-2 text-sm font-semibold">{row.name}<div className="text-xs font-normal text-[var(--text-3)]">{row.uom}</div></td>
                  <td className="px-3 py-2 font-mono text-xs">{quantity(row.required)}</td>
                  <td className="px-3 py-2 font-mono text-xs">{quantity(row.available)}</td>
                  <td className="px-3 py-2 font-mono text-xs">{quantity(row.reserved)}</td>
                  <td className="px-3 py-2 font-mono text-xs font-bold" style={{ color: row.shortage > 0 ? "var(--error)" : "var(--success)" }}>{quantity(row.shortage)}</td>
                  <td className="px-3 py-2 text-xs">{row.plannedEnd || "—"}</td>
                  <td className="px-3 py-2">
                    <div className="flex flex-wrap gap-1.5">
                      {row.shortage > 0 && row.available > 0 && canReserve && <button type="button" disabled={busy === row.job.id} onClick={() => void reserve(row)} className="rounded px-2 py-1 text-xs font-semibold text-white disabled:opacity-50" style={{ background: "var(--brand-fill)" }}>{busy === row.job.id ? "Reserving…" : "Reserve"}</button>}
                      {row.shortage > 0 && <button type="button" onClick={() => navigate(`/procurement/pr/new?${pr}`)} className="rounded border px-2 py-1 text-xs font-semibold" style={{ borderColor: "var(--brand)", color: "var(--brand)" }}>Create PR</button>}
                      {row.shortage <= 0 && <span className="text-xs font-semibold text-[var(--success)]">Available</span>}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
    </div>
  );
}

function buildNeeds(jobs: ProductionJob[], positions: Position[], stores: Store[], reservations: JobReservation[]): Need[] {
  const siteByStore = new Map(stores.map((store) => [store.id, store.siteId]));
  return jobs.filter((job) => !CLOSED.has(job.status)).flatMap((job) => (job.materials ?? []).map((material) => {
    const stock = positions.filter((position) => position.itemId === material.itemId && siteByStore.get(position.storeId) === job.siteId);
    const available = stock.reduce((sum, position) => sum + Number(position.availableQty), 0);
    const reserved = reservations.filter((entry) => entry.jobId === job.id && entry.itemId === material.itemId)
      .reduce((sum, entry) => sum + Number(entry.reservedQty), 0);
    const remaining = Math.max(0, Number(material.requiredQty) - Number(material.netIssuedQty ?? 0));
    return {
      id: material.id,
      job,
      itemId: material.itemId,
      code: material.itemCode || "",
      name: material.itemName || material.itemCode || "Material",
      uom: material.uomSymbol,
      required: Number(material.requiredQty),
      issued: Number(material.netIssuedQty ?? 0),
      available,
      reserved,
      shortage: Math.max(0, remaining - reserved - available),
      plannedEnd: job.plannedEnd,
    };
  }));
}
