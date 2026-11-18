import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import StatusChip from "../components/shared/StatusChip";
import { type ProductionJob } from "../production/api";
import { loadJobs, quantity } from "./api";
import StockNav from "./stockNav";

type ReturnRow = {
  id: string;
  jobId: string;
  jobNumber: string;
  material: string;
  quantity: number;
  uom?: string;
  createdAt?: string;
  lotId?: string;
  status: string;
};

const STATUSES = ["posted", "pending", "rejected"] as const;

export default function MaterialReturns() {
  const [rows, setRows] = useState<ReturnRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [sortKey, setSortKey] = useState("material");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");

  useEffect(() => {
    loadJobs()
      .then((jobs) => setRows(jobs.flatMap((job) => returnsFor(job))))
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Material returns could not be loaded."))
      .finally(() => setLoading(false));
  }, []);

  function handleSort(key: string) {
    if (sortKey === key) {
      setSortDir((dir) => (dir === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  }

  const visible = useMemo(() => {
    const term = query.trim().toLowerCase();
    return rows.filter((row) => {
      const matchText = !term || `${row.jobNumber} ${row.material}`.toLowerCase().includes(term);
      const matchStatus = statusFilter === "all" || row.status === statusFilter;
      return matchText && matchStatus;
    });
  }, [rows, query, statusFilter]);

  const columns: RecordColumn<ReturnRow>[] = [
    {
      key: "jobNumber",
      label: "Return",
      value: (row) => row.jobNumber,
      render: (row) => (
        <Link to={`/jobs/${row.jobId}`} className="font-semibold text-[var(--brand)]">
          {row.jobNumber}
        </Link>
      ),
    },
    {
      key: "material",
      label: "Material",
      value: (row) => row.material,
      render: (row) => (
        <div>
          <div className="font-medium">{row.material}</div>
          {row.lotId && (
            <Link to={`/inventory/lot/${row.lotId}`} className="text-[11px] text-[var(--brand)]">
              Source lot
            </Link>
          )}
        </div>
      ),
    },
    {
      key: "quantity",
      label: "Quantity",
      value: (row) => row.quantity,
      render: (row) => (
        <span className="font-mono text-xs">{quantity(row.quantity, row.uom)}</span>
      ),
    },
    {
      key: "status",
      label: "Status",
      value: (row) => row.status,
      render: (row) => <StatusChip status={row.status} />,
    },
  ];

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <header className="border-b px-6 py-4" style={{ borderColor: "var(--border)" }}>
        <h1 className="text-lg font-bold text-[var(--text)]">Material Returns</h1>
        <p className="mt-1 text-xs text-[var(--text-2)]">
          A production return is posted straight back onto its original lot.
        </p>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input
            aria-label="Search material returns"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search job or material…"
            className="eoss-input max-w-sm flex-1 text-sm"
          />
          <select
            aria-label="Return status"
            className="eoss-input min-h-[36px] text-sm"
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value)}
          >
            <option value="all">All statuses</option>
            {STATUSES.map((status) => (
              <option key={status} value={status}>
                {status.charAt(0).toUpperCase() + status.slice(1)}
              </option>
            ))}
          </select>
        </div>
      </header>
      <div className="min-h-0 flex-1 overflow-auto">
        {loading ? (
          <LoadingState label="Loading material returns" />
        ) : error ? (
          <ErrorState body={error} />
        ) : (
          <RecordTable
            rows={visible}
            columns={columns}
            sortKey={sortKey}
            sortDir={sortDir}
            onSort={handleSort}
            empty="No material returns found."
          />
        )}
      </div>
    </div>
  );
}

function returnsFor(job: ProductionJob): ReturnRow[] {
  const names = new Map(
    (job.materials ?? []).map((row) => [
      row.id,
      `${row.itemCode ?? ""} ${row.itemName ?? ""}`.trim(),
    ])
  );
  const uoms = new Map((job.materials ?? []).map((row) => [row.id, row.uomSymbol]));
  return (job.movements ?? [])
    .filter((movement) => movement.issueType === "return")
    .map((movement) => ({
      id: movement.id,
      jobId: job.id,
      jobNumber: job.jobNumber,
      material: names.get(movement.requirementId ?? "") || "Returned material",
      quantity: movement.quantity,
      uom: uoms.get(movement.requirementId ?? ""),
      createdAt: movement.createdAt,
      lotId: movement.lotId,
      status: "posted",
    }));
}
