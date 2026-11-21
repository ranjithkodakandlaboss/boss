import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ArrowRightLeft, ExternalLink, Plus, ShieldAlert, SlidersHorizontal, Trash2 } from "lucide-react";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordSheet from "../components/shared/RecordSheet";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import { loadLots, loadPositions, loadStores, mutate, quantity, type Lot, type Position, type Store } from "./api";
import StockNav from "./stockNav";

type StockRow = Position & { id: string; qcHoldQty: number; lotCount: number; status: string };

const STATUSES = ["Available", "Shortage", "Quality Hold", "Reserved", "Awaiting goods receipt"] as const;

function rowStatus(row: { availableQty: number; reservedQty: number; qcHoldQty: number; inTransitQty?: number; reorderPoint: number | null }): StockRow["status"] {
  if (row.qcHoldQty > 0 && row.availableQty <= 0) return "Quality Hold";
  if (row.availableQty <= 0 && (row.inTransitQty ?? 0) > 0 && row.qcHoldQty === 0) return "Awaiting goods receipt";
  if (row.reorderPoint != null && row.availableQty < row.reorderPoint) return "Shortage";
  if (row.reservedQty > 0 && row.availableQty <= 0) return "Reserved";
  return "Available";
}

export default function InventoryHome() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<StockRow[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [search, setSearch] = useState("");
  const [storeId, setStoreId] = useState("all");
  const [status, setStatus] = useState("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [storeWarning, setStoreWarning] = useState("");
  const [adjustRow, setAdjustRow] = useState<StockRow | null>(null);
  const [showAddStock, setShowAddStock] = useState(false);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    Promise.all([
      loadPositions(),
      loadLots("status=quarantine"),
      loadLots("status=available"),
      loadLots("status=reserved"),
      loadStores().then((s) => ({ stores: s, failed: false })).catch(() => ({ stores: [] as Store[], failed: true })),
    ]).then(([positions, quarantined, available, reserved, company]) => {
      const companyStores = company.stores;
      if (!active) return;
      const held = new Map<string, number>();
      const holdLotsByKey = new Map<string, Lot>();
      for (const lot of quarantined) {
        const key = `${lot.itemId}:${lot.storeId}`;
        held.set(key, (held.get(key) ?? 0) + Number(lot.qty));
        if (!holdLotsByKey.has(key)) holdLotsByKey.set(key, lot);
      }
      const lotCountMap = new Map<string, number>();
      for (const lot of [...available, ...reserved]) {
        const key = `${lot.itemId}:${lot.storeId}`;
        lotCountMap.set(key, (lotCountMap.get(key) ?? 0) + 1);
      }
      setStores(companyStores);
      setStoreWarning(company.failed ? "Stores could not be loaded. Locations are taken from the stock rows." : "");
      const positionRows: StockRow[] = positions.map((position) => {
        const qcHoldQty = held.get(`${position.itemId}:${position.storeId}`) ?? 0;
        const lotCount = lotCountMap.get(`${position.itemId}:${position.storeId}`) ?? 0;
        const next = { ...position, id: `${position.itemId}:${position.storeId}`, qcHoldQty, lotCount, status: "" };
        return { ...next, status: rowStatus(next) };
      });
      // DEF-044: add rows for items that only have quarantine lots (no available/reserved position)
      const positionKeys = new Set(positionRows.map((r) => `${r.itemId}:${r.storeId}`));
      const holdOnlyRows: StockRow[] = [];
      for (const [key, lot] of holdLotsByKey) {
        if (!positionKeys.has(key)) {
          const holdQty = held.get(key) ?? 0;
          const synthetic: StockRow = {
            itemId: lot.itemId,
            itemCode: lot.itemCode,
            itemName: lot.itemName,
            storeId: lot.storeId,
            storeName: lot.storeName,
            totalQty: 0,
            reservedQty: 0,
            availableQty: 0,
            inTransitQty: 0,
            reorderPoint: null,
            uomSymbol: lot.uomSymbol,
            id: key,
            qcHoldQty: holdQty,
            lotCount: 0,
            status: "Quality Hold",
            valuationAmt: 0,
            lastMovedAt: null,
          } as unknown as StockRow;
          holdOnlyRows.push(synthetic);
        }
      }
      setRows([...positionRows, ...holdOnlyRows]);
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "Inventory could not be loaded.");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, []);

  const storeOptions = stores.length > 0 ? stores : uniqueStores(rows);
  const scoped = rows.filter((row) => storeId === "all" || row.storeId === storeId);
  const counts = Object.fromEntries(STATUSES.map((name) => [name, scoped.filter((row) => row.status === name).length]));
  const shortageCount = scoped.filter((row) => row.status === "Shortage").length;
  const visible = useMemo(() => {
    const term = search.trim().toLowerCase();
    return scoped.filter((row) => {
      const text = !term || `${row.itemCode} ${row.itemName} ${row.storeName}`.toLowerCase().includes(term);
      return text && (status === "all" || row.status === status);
    });
  }, [scoped, search, status]);
  // DEF-052: group available/hold/reserved by UOM instead of summing across different units
  const uomTotals = useMemo(() => {
    const map = new Map<string, { available: number; hold: number; reserved: number }>();
    for (const row of visible) {
      const uom = row.uomSymbol || "";
      const entry = map.get(uom) ?? { available: 0, hold: 0, reserved: 0 };
      entry.available += Number(row.availableQty);
      entry.hold += row.qcHoldQty;
      entry.reserved += Number(row.reservedQty);
      map.set(uom, entry);
    }
    return map;
  }, [visible]);

  const columns: RecordColumn<StockRow>[] = [
    { key: "itemCode", label: "Code", value: (row) => row.itemCode, render: (row) => <span className="font-semibold text-[var(--brand)]">{row.itemCode}</span> },
    {
      key: "itemName", label: "Material / Specification", value: (row) => row.itemName, render: (row) => (
        <div>
          <div>{row.itemName}</div>
          {row.lotCount > 0 && <div className="mt-0.5 text-[11px] text-[var(--text-3)]">{row.lotCount} lot{row.lotCount === 1 ? "" : "s"}</div>}
        </div>
      )
    },
    { key: "storeName", label: "Location", value: (row) => row.storeName, render: (row) => row.storeName },
    { key: "totalQty", label: "On Hand", align: "right", value: (row) => row.totalQty, render: (row) => quantity(row.totalQty, row.uomSymbol) },
    { key: "reservedQty", label: "Reserved", align: "right", value: (row) => row.reservedQty, render: (row) => quantity(row.reservedQty, row.uomSymbol) },
    { key: "qcHoldQty", label: "QC Hold", align: "right", value: (row) => row.qcHoldQty, render: (row) => quantity(row.qcHoldQty, row.uomSymbol) },
    { key: "inTransit", label: "In Transit", align: "right", value: (row) => row.inTransitQty ?? 0, render: (row) => quantity(row.inTransitQty ?? 0, row.uomSymbol) },
    { key: "availableQty", label: "Available", align: "right", value: (row) => row.availableQty, render: (row) => <span className="font-semibold">{quantity(row.availableQty, row.uomSymbol)}</span> },
    { key: "reorderPoint", label: "Reorder Pt", align: "right", value: (row) => row.reorderPoint ?? -1, render: (row) => row.reorderPoint == null ? "—" : quantity(row.reorderPoint, row.uomSymbol) },
    { key: "valuationAmt", label: "Stock Value", align: "right", value: (row) => (row as StockRow).valuationAmt ?? 0, render: (row) => (row as StockRow).valuationAmt ? `₹${Number((row as StockRow).valuationAmt).toLocaleString("en-IN", { maximumFractionDigits: 0 })}` : "—" },
    { key: "status", label: "Status", value: (row) => row.status, render: (row) => <span className="rounded-full px-2 py-1 text-xs font-medium" style={chipStyle(row.status)}>{row.status}</span> },
  ];

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <header className="flex flex-wrap items-center gap-3 border-b px-5 py-3" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <input aria-label="Search material or location" value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search material or location" className="eoss-input min-w-[200px] flex-1 max-w-xs text-sm" />
        <select aria-label="Stock status filter" value={status} onChange={(event) => setStatus(event.target.value)} className="eoss-input text-xs">
          <option value="all">All Statuses ({scoped.length})</option>
          {STATUSES.map((name) => <option key={name} value={name}>{name} ({counts[name] ?? 0})</option>)}
        </select>
        {(search || status !== "all" || storeId !== "all") && (
          <button type="button" className="text-xs" onClick={() => { setSearch(""); setStatus("all"); setStoreId("all"); }}>Clear filters</button>
        )}
        <div className="flex-1" />
        {shortageCount > 0 && (
          <button type="button" onClick={() => setStatus((current) => current === "Shortage" ? "all" : "Shortage")} className="inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-xs font-semibold" style={{ color: "var(--error)", borderColor: status === "Shortage" ? "var(--error)" : "var(--border)" }}>
            <ShieldAlert size={13} /> {shortageCount} shortage{shortageCount === 1 ? "" : "s"}
          </button>
        )}
        <button type="button" onClick={() => navigate("/inventory/transfer")} className="inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold" style={{ borderColor: "var(--border)", color: "var(--text-2)" }}>
          <ArrowRightLeft size={13} /> Transfer
        </button>
        <button type="button" onClick={() => setShowAddStock(true)} className="inline-flex items-center gap-1.5 rounded-lg border px-3 py-1.5 text-xs font-semibold" style={{ borderColor: "var(--border)", color: "var(--text-2)" }}>
          <Plus size={13} /> Add Stock
        </button>
        <Link to="/procurement/pr/new" className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}>
          <Plus size={13} /> Create Purchase Requisition
        </Link>
      </header>
      <div className="flex gap-2 overflow-x-auto border-b px-5 py-2.5" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
        <StoreTile label="All stores" detail={`${rows.length} items`} selected={storeId === "all"} onClick={() => setStoreId("all")} />
        {storeOptions.map((store) => {
          const items = rows.filter((row) => row.storeId === store.id);
          return (
            <StoreTile
              key={store.id}
              label={store.siteCode ? `${store.siteCode} · ${store.code ?? store.name}` : store.name}
              detail={`${items.length} item${items.length === 1 ? "" : "s"}`}
              selected={storeId === store.id}
              alert={items.some((row) => row.status === "Shortage")}
              onClick={() => setStoreId(storeId === store.id ? "all" : store.id)}
            />
          );
        })}
      </div>
      <div className="min-h-0 flex-1 overflow-auto">
        {storeWarning && <p role="alert" className="px-5 pt-3 text-sm text-[var(--error)]">{storeWarning}</p>}
        {loading ? <LoadingState label="Loading stock positions" />
          : error ? <ErrorState body={error} />
            : <RecordTable
              rows={visible}
              columns={columns}
              empty="No stock positions"
              onRow={(row) => navigate(`/inventory/item/${row.itemId}`)}
              trailing={(row) => <RowActions row={row} rows={rows} onAdjust={() => setAdjustRow(row)} />}
            />}
      </div>
      <footer className="flex flex-wrap gap-4 border-t px-5 py-2 text-xs" style={{ borderColor: "var(--border)", color: "var(--text-3)" }}>
        <span>{visible.length} of {rows.length} stock rows</span>
        {[...uomTotals.entries()].map(([uom, t]) => (
          <span key={uom}>
            {quantity(t.available, uom)} avail
            {t.hold > 0 && <> · {quantity(t.hold, uom)} QC hold</>}
            {t.reserved > 0 && <> · {quantity(t.reserved, uom)} reserved</>}
          </span>
        ))}
      </footer>
      {adjustRow && <AdjustStockSheet row={adjustRow} onClose={() => setAdjustRow(null)} onDone={() => { setAdjustRow(null); window.location.reload(); }} />}
      {showAddStock && <AddStockModal onClose={() => setShowAddStock(false)} onDone={() => { setShowAddStock(false); window.location.reload(); }} />}
    </div>
  );
}

function RowActions({ row, rows, onAdjust }: { row: StockRow; rows: StockRow[]; onAdjust: () => void }) {
  const source = rows.find((other) => other.itemId === row.itemId && other.storeId !== row.storeId && other.availableQty > 0);
  const qty = Math.max((row.reorderPoint ?? 0) - row.availableQty, row.reorderPoint || 1);
  const pr = new URLSearchParams({ code: row.itemCode, materialId: row.itemId, name: row.itemName, uom: row.uomSymbol, qty: String(qty) });
  return (
    <div className="flex items-center justify-end gap-2">
      <button type="button" title="Adjust stock" className="text-[var(--text-3)] hover:text-[var(--text)]" onClick={(event) => { event.stopPropagation(); onAdjust(); }}>
        <SlidersHorizontal size={13} />
      </button>
      <Link to={`/inventory/item/${row.itemId}`} className="text-[var(--text-3)] hover:text-[var(--text)]" title="View item" onClick={(event) => event.stopPropagation()}>
        <ExternalLink size={13} />
      </Link>
      <Link to={`/procurement/pr/new?${pr}`} className="rounded-lg px-2 py-1 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={(event) => event.stopPropagation()}>Create PR</Link>
      {source && (
        <Link to={`/inventory/transfer?from=${encodeURIComponent(source.storeName)}&to=${encodeURIComponent(row.storeName)}&material=${encodeURIComponent(row.itemCode)}&qty=${encodeURIComponent(String(Math.min(source.availableQty, qty)))}`} className="text-xs font-semibold text-[var(--brand)]" onClick={(event) => event.stopPropagation()}>Move stock</Link>
      )}
    </div>
  );
}

function AdjustStockSheet({ row, onClose, onDone }: { row: StockRow; onClose: () => void; onDone: () => void }) {
  const [direction, setDirection] = useState<"add" | "reduce">("add");
  const [qty, setQty] = useState("");
  const [unitCost, setUnitCost] = useState("");
  const [reason, setReason] = useState("");
  const [availableLots, setAvailableLots] = useState<Lot[]>([]);
  const [selectedLotId, setSelectedLotId] = useState("");
  const [loadingLots, setLoadingLots] = useState(true);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");

  useEffect(() => {
    loadLots(`itemId=${row.itemId}&storeId=${row.storeId}&status=available`)
      .then((lots) => {
        setAvailableLots(lots);
        if (lots.length > 0) {
          setSelectedLotId(lots[0].id);
        }
      })
      .catch(() => {})
      .finally(() => setLoadingLots(false));
  }, [row.itemId, row.storeId]);

  const selectedLot = availableLots.find((l) => l.id === selectedLotId) || availableLots[0];

  const handleSave = async () => {
    const q = Number(qty);
    if (!q || q <= 0) { setErr("Enter a positive quantity."); return; }
    if (!reason.trim()) { setErr("Reason is required."); return; }
    setSaving(true);
    setErr("");
    try {
      if (direction === "add") {
        await mutate("/api/v1/inventory/lots", "POST", {
          itemId: row.itemId,
          storeId: row.storeId,
          qty: q,
          unitCost: unitCost ? Number(unitCost) : null,
          batchRef: reason ? `ADJ: ${reason.slice(0, 30)}` : "ADJUSTMENT-ADD",
        });
      } else {
        if (!selectedLot) {
          setErr("No available lot found to reduce.");
          setSaving(false);
          return;
        }
        if (q > (selectedLot.availableQty ?? selectedLot.qty)) {
          setErr(`Quantity exceeds available lot quantity (${selectedLot.availableQty ?? selectedLot.qty} ${row.uomSymbol}).`);
          setSaving(false);
          return;
        }
        await mutate(`/api/v1/inventory/lots/${selectedLot.id}/movements`, "POST", {
          movementType: "adjustment_reduce",
          qty: q,
          referenceType: "manual_adjustment",
          note: reason.trim(),
        });
      }
      onDone();
    } catch (e: unknown) {
      setErr(e instanceof Error ? e.message : "Adjustment failed.");
      setSaving(false);
    }
  };

  return (
    <RecordSheet
      title={`Adjust Stock — ${row.itemCode}`}
      subtitle={`${row.itemName} · ${row.storeName}`}
      onClose={onClose}
      primary={{ label: saving ? "Saving…" : "Save Adjustment", onClick: handleSave, disabled: saving }}
      secondary={{ label: "Cancel", onClick: onClose }}
    >
      <div className="space-y-4 text-sm">
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="block text-xs text-[var(--text-3)] mb-1">Current Available</label>
            <div className="eoss-input bg-[var(--surface-2)]">{quantity(row.availableQty, row.uomSymbol)}</div>
          </div>
          <div>
            <label className="block text-xs text-[var(--text-3)] mb-1">QC Hold</label>
            <div className="eoss-input bg-[var(--surface-2)]">{quantity(row.qcHoldQty, row.uomSymbol)}</div>
          </div>
        </div>
        <div>
          <label className="block text-xs text-[var(--text-3)] mb-1">Direction</label>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setDirection("add")}
              className={`flex-1 rounded-lg border py-2 text-xs font-semibold ${
                direction === "add"
                  ? "border-[var(--success)] text-[var(--success)] bg-[var(--success-bg)]"
                  : "border-[var(--border)] text-[var(--text-2)]"
              }`}
            >
              + Add
            </button>
            <button
              type="button"
              onClick={() => setDirection("reduce")}
              className={`flex-1 rounded-lg border py-2 text-xs font-semibold ${
                direction === "reduce"
                  ? "border-[var(--error)] text-[var(--error)] bg-[var(--error-bg)]"
                  : "border-[var(--border)] text-[var(--text-2)]"
              }`}
            >
              − Reduce
            </button>
          </div>
        </div>

        {direction === "add" ? (
          <div>
            <label className="block text-xs text-[var(--text-3)] mb-1">Unit Cost / Purchase Price (₹)</label>
            <input
              type="number"
              min="0"
              step="any"
              className="eoss-input w-full font-mono"
              value={unitCost}
              onChange={(e) => setUnitCost(e.target.value)}
              placeholder="0.00"
            />
            <p className="text-[11px] text-[var(--text-3)] mt-1">This cost will be retained for this specific addition in the item valuation.</p>
          </div>
        ) : (
          <div>
            <label className="block text-xs text-[var(--text-3)] mb-1">Select Lot to Reduce</label>
            {loadingLots ? (
              <div className="text-xs text-[var(--text-3)]">Loading available lots…</div>
            ) : availableLots.length === 0 ? (
              <div className="text-xs text-[var(--error)]">No lots available for reduction.</div>
            ) : (
              <select
                className="eoss-input w-full text-xs font-mono"
                value={selectedLotId}
                onChange={(e) => setSelectedLotId(e.target.value)}
              >
                {availableLots.map((l) => (
                  <option key={l.id} value={l.id}>
                    {l.lotNumber || l.id.slice(0, 8)} ({quantity(l.availableQty ?? l.qty, row.uomSymbol)}) — ₹
                    {l.unitCost != null ? Number(l.unitCost).toFixed(2) : "0.00"} / {row.uomSymbol}
                  </option>
                ))}
              </select>
            )}
            {selectedLot && (
              <div className="mt-1 text-[11px] text-[var(--text-2)] flex justify-between">
                <span>Applicable unit cost: <strong>₹{selectedLot.unitCost != null ? Number(selectedLot.unitCost).toFixed(2) : "0.00"}</strong></span>
                <span>Max reducible: <strong>{quantity(selectedLot.availableQty ?? selectedLot.qty, row.uomSymbol)}</strong></span>
              </div>
            )}
          </div>
        )}

        <div>
          <label className="block text-xs text-[var(--text-3)] mb-1">Quantity ({row.uomSymbol}) *</label>
          <input
            type="number"
            min="0.001"
            step="any"
            className="eoss-input w-full font-mono"
            value={qty}
            onChange={(e) => setQty(e.target.value)}
            placeholder="0.000"
          />
        </div>
        <div>
          <label className="block text-xs text-[var(--text-3)] mb-1">Reason *</label>
          <input
            className="eoss-input w-full"
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            placeholder="e.g. Physical count correction, damaged stock write-off…"
          />
        </div>
        {err && <p className="text-xs text-[var(--error)]">{err}</p>}
      </div>
    </RecordSheet>
  );
}

function StoreTile({ label, detail, selected, alert, onClick }: { label: string; detail: string; selected: boolean; alert?: boolean; onClick: () => void }) {
  return (
    <button type="button" onClick={onClick} className="min-w-[120px] rounded-xl border px-3 py-2 text-left" style={{ borderColor: selected ? "var(--brand)" : "var(--border)", background: selected ? "var(--surface)" : "transparent", color: "var(--text)" }}>
      <div className="flex items-center justify-between gap-2 text-[10px] font-bold uppercase" style={{ color: selected ? "var(--brand)" : "var(--text-3)" }}>
        <span className="truncate">{label}</span>
        {alert && <span className="h-1.5 w-1.5 rounded-full bg-red-500" />}
      </div>
      <div className="mt-0.5 text-xs font-semibold">{detail}</div>
    </button>
  );
}

function uniqueStores(rows: StockRow[]): Store[] {
  const seen = new Map<string, Store>();
  rows.forEach((row) => { if (!seen.has(row.storeId)) seen.set(row.storeId, { id: row.storeId, siteId: "", name: row.storeName }); });
  return [...seen.values()];
}

function chipStyle(status: string) {
  if (status === "Shortage" || status === "Quality Hold") return { background: "var(--error-bg)", color: "var(--error)" };
  if (status === "Reserved" || status === "Awaiting goods receipt") return { background: "var(--surface-2)", color: "var(--warning, #B45309)" };
  return { background: "var(--success-bg)", color: "var(--success)" };
}
