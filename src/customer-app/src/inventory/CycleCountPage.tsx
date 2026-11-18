import { useEffect, useState } from "react";
import { ArrowLeft, ClipboardList } from "lucide-react";
import { useNavigate } from "react-router-dom";
import { useCustomer } from "../auth/CustomerState";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import { loadLots, loadPositions, loadStores, mutate, quantity, type Lot, type Position, type Store } from "./api";
import { createStore, storeFields } from "./inlineCreate";

type Line = Position & { counted: string };

export default function CycleCountPage() {
  const navigate = useNavigate();
  const { user } = useCustomer();
  const canCreateStore = roleAllowed(user?.roles, ["OWNER"]);
  const [stores, setStores] = useState<Store[]>([]);
  const [storeError, setStoreError] = useState("");
  const [positions, setPositions] = useState<Position[]>([]);
  const [storeId, setStoreId] = useState("all");
  const [lines, setLines] = useState<Line[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  function load() {
    setLoading(true);
    setStoreError("");
    setError("");
    Promise.all([loadStores(), loadPositions()])
      .then(([nextStores, nextPositions]) => { setStores(nextStores); setPositions(nextPositions); })
      .catch((reason: unknown) => setStoreError(reason instanceof Error ? reason.message : "Stock could not be loaded."))
      .finally(() => setLoading(false));
  }

  useEffect(() => { load(); }, []);

  const locations = stores.length > 0 ? stores : uniqueStores(positions);

  function begin() {
    const selected = positions.filter((row) => storeId === "all" || row.storeId === storeId);
    setLines(selected.map((row) => ({ ...row, counted: "" })));
    setNotice("");
    setError("");
  }

  async function submit() {
    if (!lines) return;
    const counted = lines.filter((line) => line.counted !== "");
    if (counted.length === 0) return;
    setSaving(true);
    setError("");
    try {
      const lots = await loadLots(storeId === "all" ? "" : `storeId=${storeId}`);
      for (const line of counted) {
        const variance = Number(line.counted) - Number(line.totalQty);
        if (!Number.isFinite(variance) || Math.abs(variance) < 0.001) continue;
        await postVariance(line, lots.filter((lot) => lot.itemId === line.itemId && lot.storeId === line.storeId), variance);
      }
      setNotice("Cycle count posted. Variances are stock adjustments on the counted lots.");
      setLines(null);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The count was not posted.");
    } finally {
      setSaving(false);
    }
  }

  const variances = (lines ?? []).filter((line) => line.counted !== "" && Number(line.counted) !== Number(line.totalQty));
  const hasCountEntry = (lines ?? []).some((line) => line.counted !== "");
  const countedCount = (lines ?? []).filter((line) => line.counted !== "").length;
  const userName = user?.name || user?.email || "Unknown";
  const today = new Date().toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });

  return (
    <div className="p-4 md:p-6 max-w-5xl mx-auto space-y-5">
      <button type="button" onClick={() => navigate("/inventory")} className="flex items-center gap-1.5 text-sm text-[var(--text-2)] hover:text-[var(--brand)] mb-3">
        <ArrowLeft size={14} /> Inventory
      </button>
      <div className="flex items-center gap-2">
        <ClipboardList size={20} className="text-[var(--brand)]" />
        <h1 className="text-xl font-bold">Cycle Count</h1>
      </div>
      <p className="text-sm text-[var(--text-2)] mt-1">Physical count of a store. A difference posts an adjustment on the lot and changes on-hand quantity.</p>

      {loading ? <LoadingState label="Loading stock for count" /> : error && !lines ? <ErrorState body={error} /> : !lines ? (
        <div className="max-w-md space-y-4 rounded-2xl border p-6" style={{ borderColor: "var(--border)" }}>
          <h2 className="font-semibold">Start count session</h2>
          <div className="block text-xs font-semibold">Store location
            <CreatableSelect label="store" ariaLabel="Store location" value={storeId} onChange={setStoreId} pinned={[{ id: "all", label: "All locations" }]} error={storeError} onRetry={load} creatable={canCreateStore} options={locations.map((store) => ({ id: store.id, label: store.name }))} prepare={storeFields} onCreate={async (values) => {
              const created = await createStore(values);
              setStores((current) => [...current.filter((store) => store.id !== created.id), created]);
              return { id: created.id, label: created.name };
            }} />
          </div>
          <button type="button" onClick={begin} className="w-full rounded-lg py-2.5 text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Begin count</button>
        </div>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-3 text-sm">
            <span>Counted by <strong>{userName}</strong> · {today}</span>
            <span className="text-[var(--text-3)]">Progress: <strong>{countedCount}/{lines.length}</strong></span>
            {variances.length > 0 && (
              <span className="rounded-full px-2 py-0.5 text-xs font-semibold" style={{ background: "var(--error-bg)", color: "var(--error)" }}>
                {variances.length} variance{variances.length === 1 ? "" : "s"}
              </span>
            )}
          </div>
          <table className="w-full text-sm">
            <thead><tr>{["Material", "Location", "System qty", "Counted qty", "Variance"].map((label) => <th key={label} className="px-3 py-2 text-left text-xs">{label}</th>)}</tr></thead>
            <tbody>
              {lines.map((line) => {
                const diff = line.counted === "" ? null : Number(line.counted) - Number(line.totalQty);
                return (
                  <tr key={`${line.itemId}:${line.storeId}`}>
                    <td className="px-3 py-2"><div className="text-xs font-semibold">{line.itemName}</div><div className="text-[11px] text-[var(--text-2)]">{line.itemCode}</div></td>
                    <td className="px-3 py-2 text-xs">{line.storeName}</td>
                    <td className="px-3 py-2 text-xs">{quantity(line.totalQty, line.uomSymbol)}</td>
                    <td className="px-3 py-2"><input aria-label={`Counted ${line.itemCode}`} className="eoss-input w-24 text-right text-xs" type="number" min="0" step="0.001" value={line.counted} onChange={(event) => setLines((current) => current?.map((row) => row.itemId === line.itemId && row.storeId === line.storeId ? { ...row, counted: event.target.value } : row) ?? null)} /></td>
                    <td className="px-3 py-2 text-xs font-bold" style={{ color: diff != null && !Number.isNaN(diff) && diff !== 0 ? "var(--error)" : undefined }}>{diff == null || Number.isNaN(diff) ? "" : `${diff > 0 ? "+" : ""}${diff.toFixed(3)} ${line.uomSymbol}`}</td>
                  </tr>
                );
              })}
            </tbody>
          </table>
          {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
          <div className="flex justify-end gap-3">
            <button type="button" onClick={() => setLines(null)} className="rounded-lg border px-4 py-2 text-sm" style={{ borderColor: "var(--border)" }}>Discard</button>
            <button type="button" disabled={saving || !hasCountEntry} onClick={() => void submit()} className="rounded-lg px-4 py-2 text-sm font-semibold text-white disabled:opacity-40" style={{ background: "var(--brand-fill)" }}>{saving ? "Posting…" : "Submit Count"}</button>
          </div>
        </>
      )}
      {notice && <p role="status" className="text-sm text-[var(--success)]">{notice}</p>}
    </div>
  );
}

async function postVariance(line: Position, lots: Lot[], variance: number) {
  const usable = lots.filter((lot) => lot.status === "available" || lot.status === "reserved" || lot.status === "quarantine");
  if (variance > 0) {
    const target = usable[0];
    if (!target) throw new Error(`${line.itemCode} has no lot at ${line.storeName} to receive the count increase.`);
    await mutate(`/api/v1/inventory/lots/${target.id}/movements`, "POST", {
      movementType: "adjustment_add",
      qty: round(variance),
      referenceType: "cycle_count",
      note: "Cycle count increase",
    });
    return;
  }
  let remaining = round(Math.abs(variance));
  for (const lot of usable) {
    if (remaining <= 0) break;
    const free = round(Number(lot.qty) - Number(lot.reservedQty));
    if (free <= 0) continue;
    const qty = Math.min(free, remaining);
    await mutate(`/api/v1/inventory/lots/${lot.id}/movements`, "POST", {
      movementType: "adjustment_reduce",
      qty,
      referenceType: "cycle_count",
      note: "Cycle count decrease",
    });
    remaining = round(remaining - qty);
  }
  if (remaining > 0) throw new Error(`${line.itemCode} does not have enough unreserved quantity for the counted decrease.`);
}

function round(value: number) {
  return Math.round(value * 1000) / 1000;
}

function uniqueStores(rows: Position[]): Store[] {
  const seen = new Map<string, Store>();
  rows.forEach((row) => { if (!seen.has(row.storeId)) seen.set(row.storeId, { id: row.storeId, siteId: "", name: row.storeName }); });
  return [...seen.values()];
}

export { postVariance };
