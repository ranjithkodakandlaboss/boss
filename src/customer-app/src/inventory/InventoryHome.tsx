import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ArrowRightLeft, Pencil, Plus, ShieldAlert } from "lucide-react";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import { loadLots, loadPositions, loadStores, quantity, type Lot, type Position, type Store } from "./api";
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
      for (const lot of quarantined) {
        const key = `${lot.itemId}:${lot.storeId}`;
        held.set(key, (held.get(key) ?? 0) + Number(lot.qty));
      }
      const lotCountMap = new Map<string, number>();
      for (const lot of [...available, ...reserved]) {
        const key = `${lot.itemId}:${lot.storeId}`;
        lotCountMap.set(key, (lotCountMap.get(key) ?? 0) + 1);
      }
      setStores(companyStores);
      setStoreWarning(company.failed ? "Stores could not be loaded. Locations are taken from the stock rows." : "");
      setRows(positions.map((position) => {
        const qcHoldQty = held.get(`${position.itemId}:${position.storeId}`) ?? 0;
        const lotCount = lotCountMap.get(`${position.itemId}:${position.storeId}`) ?? 0;
        const next = { ...position, id: `${position.itemId}:${position.storeId}`, qcHoldQty, lotCount, status: "" };
        return { ...next, status: rowStatus(next) };
      }));
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
  const totals = visible.reduce((sum, row) => ({
    available: sum.available + Number(row.availableQty),
    reserved: sum.reserved + Number(row.reservedQty),
    hold: sum.hold + row.qcHoldQty,
  }), { available: 0, reserved: 0, hold: 0 });

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
              trailing={(row) => <RowActions row={row} rows={rows} />}
            />}
      </div>
      <footer className="flex flex-wrap gap-4 border-t px-5 py-2 text-xs" style={{ borderColor: "var(--border)", color: "var(--text-3)" }}>
        <span>{visible.length} of {rows.length} stock rows</span>
        <span>{quantity(totals.available)} available</span>
        <span>{quantity(totals.reserved)} reserved</span>
        <span>{quantity(totals.hold)} QC hold</span>
      </footer>
    </div>
  );
}

function RowActions({ row, rows }: { row: StockRow; rows: StockRow[] }) {
  const source = rows.find((other) => other.itemId === row.itemId && other.storeId !== row.storeId && other.availableQty > 0);
  const qty = Math.max((row.reorderPoint ?? 0) - row.availableQty, row.reorderPoint || 1);
  const pr = new URLSearchParams({ code: row.itemCode, materialId: row.itemId, name: row.itemName, uom: row.uomSymbol, qty: String(qty) });
  return (
    <div className="flex items-center justify-end gap-2">
      <Link to={`/inventory/item/${row.itemId}?edit=1`} className="text-[var(--text-3)] hover:text-[var(--text)]" title="Edit material" onClick={(event) => event.stopPropagation()}>
        <Pencil size={13} />
      </Link>
      <Link to={`/procurement/pr/new?${pr}`} className="rounded-lg px-2 py-1 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={(event) => event.stopPropagation()}>Create PR</Link>
      {source && (
        <Link to={`/inventory/transfer?from=${encodeURIComponent(source.storeName)}&to=${encodeURIComponent(row.storeName)}&material=${encodeURIComponent(row.itemCode)}&qty=${encodeURIComponent(String(Math.min(source.availableQty, qty)))}`} className="text-xs font-semibold text-[var(--brand)]" onClick={(event) => event.stopPropagation()}>Move stock</Link>
      )}
    </div>
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
