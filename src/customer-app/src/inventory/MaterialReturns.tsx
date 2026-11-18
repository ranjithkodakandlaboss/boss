import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
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
};

export default function MaterialReturns() {
  const [rows, setRows] = useState<ReturnRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    loadJobs().then((jobs) => setRows(jobs.flatMap((job) => returnsFor(job))))
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Material returns could not be loaded."))
      .finally(() => setLoading(false));
  }, []);

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <div className="mx-auto w-full max-w-5xl space-y-4 p-4 md:p-6">
        <div>
          <h1 className="text-xl font-bold">Material returns</h1>
          <p className="mt-1 text-sm text-[var(--text-2)]">A production return is posted straight back onto its original lot. Inventory does not open a second quarantine inspection for that movement.</p>
        </div>
        {loading ? <LoadingState label="Loading material returns" /> : error ? <ErrorState body={error} /> : rows.length === 0 ? (
          <div className="rounded-xl border p-10 text-center text-sm text-[var(--text-3)]" style={{ borderColor: "var(--border)" }}>No material returns raised.</div>
        ) : rows.map((row) => (
          <article key={row.id} className="rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
            <div className="flex flex-wrap items-start justify-between gap-3">
              <div>
                <div className="font-semibold">{row.material} · {quantity(row.quantity, row.uom)}</div>
                <div className="mt-1 text-xs text-[var(--text-2)]">
                  Job <Link to={`/jobs/${row.jobId}`} className="font-semibold text-[var(--brand)]">{row.jobNumber}</Link>
                  {row.lotId && <> · <Link to={`/inventory/lot/${row.lotId}`} className="text-[var(--brand)]">Source lot</Link></>}
                </div>
              </div>
              <span className="rounded-full px-2 py-1 text-xs font-medium" style={{ background: "var(--success-bg)", color: "var(--success)" }}>Posted</span>
            </div>
            <div className="mt-2 text-xs text-[var(--text-3)]">{row.createdAt ? new Date(row.createdAt).toLocaleString("en-IN") : "Return recorded on the job"}</div>
          </article>
        ))}
      </div>
    </div>
  );
}

function returnsFor(job: ProductionJob): ReturnRow[] {
  const names = new Map((job.materials ?? []).map((row) => [row.id, `${row.itemCode ?? ""} ${row.itemName ?? ""}`.trim()]));
  const uoms = new Map((job.materials ?? []).map((row) => [row.id, row.uomSymbol]));
  return (job.movements ?? []).filter((movement) => movement.issueType === "return").map((movement) => ({
    id: movement.id,
    jobId: job.id,
    jobNumber: job.jobNumber,
    material: names.get(movement.requirementId ?? "") || "Returned material",
    quantity: movement.quantity,
    uom: uoms.get(movement.requirementId ?? ""),
    createdAt: movement.createdAt,
    lotId: movement.lotId,
  }));
}
