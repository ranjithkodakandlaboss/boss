import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import LoadingState from "../components/shared/LoadingState";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import StatusChip from "../components/shared/StatusChip";
import { loadLots, mutate, quantity, type Lot } from "./api";
import StockNav from "./stockNav";

type Filter = "all" | "remnants" | "scrap";

export default function RemnantsPage() {
  const navigate = useNavigate();
  const [lots, setLots] = useState<Lot[]>([]);
  const [filter, setFilter] = useState<Filter>("all");
  const [query, setQuery] = useState("");
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [sortKey, setSortKey] = useState("lotNumber");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  function load() {
    setLoading(true);
    Promise.all([
      loadLots("status=disposed"),
      loadLots("status=available"),
      loadLots("status=reserved"),
    ])
      .then(([disposed, available, reserved]) => setLots([...disposed, ...available, ...reserved]))
      .catch((reason: unknown) =>
        setError(reason instanceof Error ? reason.message : "Lots could not be loaded.")
      )
      .finally(() => setLoading(false));
  }

  useEffect(() => {
    load();
  }, []);

  function handleSort(key: string) {
    if (sortKey === key) {
      setSortDir((dir) => (dir === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  }

  const scrap = lots.filter((lot) => lot.status === "disposed");
  const remnants = lots.filter((lot) => isRemnant(lot));
  const base =
    filter === "scrap" ? scrap : filter === "remnants" ? remnants : dedupe([...remnants, ...scrap]);

  const display = useMemo(() => {
    const term = query.trim().toLowerCase();
    if (!term) return base;
    return base.filter((lot) =>
      `${lot.lotNumber ?? ""} ${lot.itemName ?? ""} ${lot.storeName ?? ""}`.toLowerCase().includes(term)
    );
  }, [base, query]);

  async function scrapLot(lot: Lot) {
    setError("");
    try {
      await mutate(`/api/v1/inventory/lots/${lot.id}/movements`, "POST", {
        movementType: "scrap",
        qty: Number(lot.qty),
        referenceType: "scrap",
        note: "Scrapped from remnants",
      });
      setNotice(`${lot.lotNumber || lot.itemCode} moved to scrap. Stock reduced.`);
      setConfirmId(null);
      load();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The lot was not scrapped.");
    }
  }

  const columns: RecordColumn<Lot>[] = [
    {
      key: "lotNumber",
      label: "Lot",
      value: (row) => row.lotNumber || row.id,
      render: (row) => (
        <button
          type="button"
          className="font-semibold text-[var(--brand)]"
          onClick={() => navigate(`/inventory/lot/${row.id}`)}
        >
          {row.lotNumber || row.batchRef || row.id.slice(0, 8)}
        </button>
      ),
    },
    {
      key: "itemName",
      label: "Material",
      value: (row) => row.itemName ?? "",
      render: (row) => <span className="text-xs">{row.itemName}</span>,
    },
    {
      key: "qty",
      label: "Quantity",
      value: (row) => Number(row.qty),
      render: (row) => (
        <span className="font-mono text-xs">{quantity(row.qty, row.uomSymbol)}</span>
      ),
    },
    {
      key: "status",
      label: "Status",
      value: (row) => row.status,
      render: (row) => <StatusChip status={row.status === "disposed" ? "scrapped" : row.status} />,
    },
    {
      key: "actions",
      label: "",
      render: (row) =>
        row.status !== "disposed" &&
        Number(row.reservedQty) === 0 &&
        Number(row.qty) > 0 ? (
          confirmId === row.id ? (
            <span className="flex gap-2">
              <button
                type="button"
                className="text-xs font-semibold text-[var(--error)]"
                onClick={() => void scrapLot(row)}
              >
                Confirm
              </button>
              <button type="button" className="text-xs" onClick={() => setConfirmId(null)}>
                Cancel
              </button>
            </span>
          ) : (
            <button
              type="button"
              className="text-xs font-semibold text-[var(--error)]"
              onClick={() => setConfirmId(row.id)}
            >
              Scrap
            </button>
          )
        ) : null,
    },
  ];

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <header className="border-b px-6 py-4" style={{ borderColor: "var(--border)" }}>
        <h1 className="text-lg font-bold text-[var(--text)]">Remnants &amp; Scrap</h1>
        <p className="mt-1 text-xs text-[var(--text-2)]">
          Scrap is a disposed lot. A lot can be scrapped only when none of it is reserved.
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input
            aria-label="Search remnants"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search lot, material or location…"
            className="eoss-input max-w-sm flex-1 text-sm"
          />
          {(["all", "remnants", "scrap"] as const).map((value) => (
            <button
              key={value}
              type="button"
              onClick={() => setFilter(value)}
              className="rounded-lg px-3 py-1.5 text-xs font-semibold"
              style={
                filter === value
                  ? { background: "var(--brand-fill)", color: "#fff" }
                  : { border: "1px solid var(--border)", color: "var(--text-2)" }
              }
            >
              {value === "all" ? "All" : value === "remnants" ? "Remnants" : "Scrap"}
            </button>
          ))}
        </div>
      </header>
      {notice && (
        <p role="status" className="px-6 pt-3 text-sm text-[var(--success)]">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="px-6 pt-3 text-sm text-[var(--error)]">
          {error}
        </p>
      )}
      <div className="min-h-0 flex-1 overflow-auto">
        {loading ? (
          <LoadingState label="Loading remnants and scrap" />
        ) : (
          <RecordTable
            rows={display}
            columns={columns}
            sortKey={sortKey}
            sortDir={sortDir}
            onSort={handleSort}
            empty="No items in this view."
          />
        )}
      </div>
    </div>
  );
}

function isRemnant(lot: Lot) {
  const note = `${lot.batchRef ?? ""} ${lot.lotNumber ?? ""}`.toLowerCase();
  return (
    lot.status !== "disposed" &&
    (note.includes("remnant") || note.includes("offcut") || note.includes("off-cut"))
  );
}

function dedupe(lots: Lot[]) {
  const seen = new Set<string>();
  return lots.filter((lot) => (seen.has(lot.id) ? false : seen.add(lot.id)));
}
