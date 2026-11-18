import { useEffect, useState } from "react";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import { loadLots, loadPositions, loadStores, mutate, quantity, type Lot, type Position, type Store } from "./api";
import StockNav from "./stockNav";

type Line = Position & { counted: string };

export default function CycleCountPage() {
  const [stores, setStores] = useState<Store[]>([]);
  const [positions, setPositions] = useState<Position[]>([]);
  const [storeId, setStoreId] = useState("all");
  const [lines, setLines] = useState<Line[] | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    Promise.all([loadStores(), loadPositions()])
      .then(([nextStores, nextPositions]) => { setStores(nextStores); setPositions(nextPositions); })
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Stock could not be loaded."))
      .finally(() => setLoading(false));
  }, []);

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

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <div className="mx-auto w-full max-w-5xl space-y-5 p-4 md:p-6">
        <div>
          <h1 className="text-xl font-bold">Cycle Count</h1>
          <p className="mt-1 text-sm text-[var(--text-2)]">Physical count of a store. A difference posts an adjustment on the lot and changes on-hand quantity.</p>
        </div>
        {loading ? <LoadingState label="Loading stock for count" /> : error && !lines ? <ErrorState body={error} /> : !lines ? (
          <div className="max-w-md space-y-4 rounded-2xl border p-6" style={{ borderColor: "var(--border)" }}>
            <h2 className="font-semibold">Start count session</h2>
            <label className="block text-xs font-semibold">Store location
              <select aria-label="Store location" className="eoss-input mt-1 w-full" value={storeId} onChange={(event) => setStoreId(event.target.value)}>
                <option value="all">All locations</option>
                {locations.map((store) => <option key={store.id} value={store.id}>{store.name}</option>)}
              </select>
            </label>
            <button type="button" onClick={begin} className="w-full rounded-lg py-2.5 text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Begin count</button>
          </div>
        ) : (
          <>
            <div className="text-sm">Progress: <strong>{lines.filter((line) => line.counted !== "").length}/{lines.length}</strong> {variances.length > 0 && <span className="ml-3 text-amber-700">{variances.length} variance{variances.length === 1 ? "" : "s"}</span>}</div>
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
                      <td className="px-3 py-2 text-xs font-bold">{diff == null || Number.isNaN(diff) ? "" : `${diff > 0 ? "+" : ""}${diff.toFixed(3)} ${line.uomSymbol}`}</td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
            {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
            <div className="flex justify-end gap-3">
              <button type="button" onClick={() => setLines(null)} className="rounded-lg border px-4 py-2 text-sm" style={{ borderColor: "var(--border)" }}>Discard</button>
              <button type="button" disabled={saving} onClick={() => void submit()} className="rounded-lg px-4 py-2 text-sm font-semibold text-white disabled:opacity-40" style={{ background: "var(--brand-fill)" }}>{saving ? "Posting…" : "Submit count"}</button>
            </div>
          </>
        )}
        {notice && <p role="status" className="text-sm text-[var(--success)]">{notice}</p>}
      </div>
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
