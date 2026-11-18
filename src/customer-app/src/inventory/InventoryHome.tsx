import { useEffect, useMemo, useState } from "react";
import { Link, NavLink, useNavigate } from "react-router-dom";
import { ArrowLeft, ArrowRight, Plus } from "lucide-react";
import { api } from "../auth/api";
import Button from "../components/shared/Button";
import ErrorState from "../components/shared/ErrorState";
import FilterBar from "../components/shared/FilterBar";
import LoadingState from "../components/shared/LoadingState";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";

type Page<T> = { content: T[]; page: number; totalElements: number };
type Position = {
  itemId: string;
  itemCode: string;
  itemName: string;
  storeId: string;
  storeName: string;
  totalQty: number;
  reservedQty: number;
  availableQty: number;
  inTransitQty?: number;
  reorderPoint: number | null;
  uomSymbol: string;
};
type Lot = { itemId: string; storeId: string; qty: number };
type StockRow = Position & { id: string; qcHoldQty: number };

const PAGE_SIZE = 50;
const STOCK_LINKS = [
  { to: "/inventory", label: "Stock on hand" },
  { to: "/products", label: "Item master" },
  { to: "/inventory/transfer", label: "Move stock" },
  { to: "/inventory/transfers", label: "Transfers" },
  { to: "/inventory/returns", label: "Returns" },
  { to: "/allocations", label: "Site need" },
];

async function allPages<T>(path: string): Promise<T[]> {
  const rows: T[] = [];
  const size = 200;
  let page = 0;
  let total = Number.POSITIVE_INFINITY;
  while (rows.length < total) {
    const result = await api<Page<T>>(`${path}${path.includes("?") ? "&" : "?"}page=${page}&size=${size}`);
    rows.push(...result.content);
    total = result.totalElements;
    page += 1;
    if (result.content.length === 0) break;
  }
  return rows;
}

function quantity(value: number, uom: string) {
  return `${new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 }).format(value)} ${uom}`;
}

export default function InventoryHome() {
  const navigate = useNavigate();
  const [page, setPage] = useState(0);
  const [belowReorderPoint, setBelowReorderPoint] = useState(false);
  const [search, setSearch] = useState("");
  const [rows, setRows] = useState<StockRow[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    const query = new URLSearchParams({
      page: String(page),
      size: String(PAGE_SIZE),
      belowReorderPoint: String(belowReorderPoint),
    });
    Promise.all([
      api<Page<Position>>(`/api/v1/inventory/positions?${query}`),
      allPages<Lot>("/api/v1/inventory/lots?status=quarantine"),
    ]).then(([positions, quarantinedLots]) => {
      if (!active) return;
      const quarantined = new Map<string, number>();
      for (const lot of quarantinedLots) {
        const key = `${lot.itemId}:${lot.storeId}`;
        quarantined.set(key, (quarantined.get(key) ?? 0) + lot.qty);
      }
      setRows(positions.content.map((position) => {
        const key = `${position.itemId}:${position.storeId}`;
        return {
          ...position,
          id: key,
          qcHoldQty: quarantined.get(key) ?? 0,
        };
      }));
      setTotal(positions.totalElements);
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "Inventory could not be loaded.");
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [belowReorderPoint, page]);

  const visibleRows = useMemo(() => {
    const term = search.trim().toLowerCase();
    if (!term) return rows;
    return rows.filter((row) =>
      `${row.itemCode} ${row.itemName} ${row.storeName}`.toLowerCase().includes(term));
  }, [rows, search]);

  const columns: RecordColumn<StockRow>[] = [
    { key: "itemCode", label: "Code", value: (row) => row.itemCode, render: (row) => <span className="font-semibold text-[var(--text)]">{row.itemCode}</span> },
    { key: "itemName", label: "Material / Specification", value: (row) => row.itemName, render: (row) => row.itemName },
    { key: "storeName", label: "Location", value: (row) => row.storeName, render: (row) => row.storeName },
    { key: "totalQty", label: "On Hand", align: "right", value: (row) => row.totalQty, render: (row) => quantity(row.totalQty, row.uomSymbol) },
    { key: "reservedQty", label: "Reserved", align: "right", value: (row) => row.reservedQty, render: (row) => quantity(row.reservedQty, row.uomSymbol) },
    { key: "qcHoldQty", label: "QC Hold", align: "right", value: (row) => row.qcHoldQty, render: (row) => quantity(row.qcHoldQty, row.uomSymbol) },
    { key: "inTransit", label: "In Transit", align: "right", value: (row) => row.inTransitQty ?? 0, render: (row) => quantity(row.inTransitQty ?? 0, row.uomSymbol) },
    { key: "availableQty", label: "Available", align: "right", value: (row) => row.availableQty, render: (row) => <span className="font-semibold">{quantity(row.availableQty, row.uomSymbol)}</span> },
    { key: "reorderPoint", label: "Reorder Pt", align: "right", value: (row) => row.reorderPoint ?? -1, render: (row) => row.reorderPoint == null ? "—" : quantity(row.reorderPoint, row.uomSymbol) },
    { key: "status", label: "Status", render: (row) => row.reorderPoint != null && row.availableQty < row.reorderPoint
      ? <span className="rounded-full px-2 py-1 text-xs font-medium" style={{ background: "var(--error-bg)", color: "var(--error)" }}>Below reorder</span>
      : <span className="rounded-full px-2 py-1 text-xs font-medium" style={{ background: "var(--success-bg)", color: "var(--success)" }}>In stock</span> },
  ];

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <nav aria-label="Inventory" className="flex gap-1 overflow-x-auto border-b px-3" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        {STOCK_LINKS.map((item) => (
          <NavLink key={item.to} to={item.to} end={item.to === "/inventory"}
            className="whitespace-nowrap px-3 py-2.5 text-sm font-medium"
            style={({ isActive }) => ({ color: isActive ? "var(--brand)" : "var(--text-2)" })}>
            {item.label}
          </NavLink>
        ))}
      </nav>
      <header className="flex items-start justify-between gap-3 px-6 py-4">
        <div>
          <h1 className="text-lg font-bold text-[var(--text)]">Stock on hand</h1>
          <p className="mt-1 text-xs text-[var(--text-2)]">{total} item and location positions</p>
        </div>
        <Link to="/procurement/pr/new" className="inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}>
          <Plus size={15} /> Create Purchase Requisition
        </Link>
      </header>
      <FilterBar search={search} onSearch={setSearch} placeholder="Search material or location">
        <label className="flex items-center gap-2 whitespace-nowrap text-xs font-medium text-[var(--text-2)]">
          <input type="checkbox" checked={belowReorderPoint}
            onChange={(event) => { setBelowReorderPoint(event.target.checked); setPage(0); }} />
          Below reorder point
        </label>
      </FilterBar>
      <div className="min-h-0 flex-1 overflow-auto">
        {loading ? <LoadingState label="Loading stock positions" />
          : error ? <ErrorState body={error} />
            : <RecordTable
              rows={visibleRows}
              columns={columns}
              empty="No stock positions"
              onRow={(row) => navigate(`/inventory/item/${row.itemId}`)}
            />}
      </div>
      {total > PAGE_SIZE && (
        <footer className="flex items-center justify-between border-t px-6 py-3 text-xs text-[var(--text-2)]" style={{ borderColor: "var(--border)" }}>
          <span>{page * PAGE_SIZE + 1}–{Math.min((page + 1) * PAGE_SIZE, total)} of {total}</span>
          <div className="flex gap-2">
            <Button variant="ghost" disabled={page === 0} onClick={() => setPage((value) => value - 1)}><ArrowLeft size={14} /> Previous</Button>
            <Button variant="ghost" disabled={(page + 1) * PAGE_SIZE >= total} onClick={() => setPage((value) => value + 1)}>Next <ArrowRight size={14} /></Button>
          </div>
        </footer>
      )}
    </div>
  );
}
