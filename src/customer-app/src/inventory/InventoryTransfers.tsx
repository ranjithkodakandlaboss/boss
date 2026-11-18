import { useCallback, useEffect, useState } from "react";
import { ArrowLeft, ArrowRightLeft, Plus } from "lucide-react";
import { Link, useNavigate } from "react-router-dom";
import { api, safeRandomUUID } from "../auth/api";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import StatusChip from "../components/shared/StatusChip";

type TransferLine = {
  id: string;
  itemCode: string;
  itemName: string;
  qty: number;
  uomSymbol: string;
};
type Transfer = {
  id: string;
  transferNumber: string;
  fromStoreName: string;
  toStoreName: string;
  status: string;
  transferDate: string;
  lines: TransferLine[];
};
type Page<T> = { content: T[]; totalElements: number };

export default function InventoryTransfers() {
  const navigate = useNavigate();
  const [rows, setRows] = useState<Transfer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [receiving, setReceiving] = useState<string | null>(null);

  const load = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const result = await api<Page<Transfer>>("/api/v1/inter-site-transfers?page=0&size=50");
      setRows(result.content);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Transfers could not be loaded.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function receive(transfer: Transfer) {
    setReceiving(transfer.id);
    setActionError("");
    try {
      await api<Transfer>(`/api/v1/inter-site-transfers/${transfer.id}/receive`, {
        method: "POST",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({}),
      });
      await load();
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : "Transfer could not be received.");
    } finally {
      setReceiving(null);
    }
  }

  return (
    <div className="p-4 md:p-6 max-w-5xl mx-auto space-y-5">
      <button type="button" onClick={() => navigate("/inventory")} className="flex items-center gap-1.5 text-sm text-[var(--text-2)] hover:text-[var(--brand)] mb-3">
        <ArrowLeft size={14} /> Inventory
      </button>
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <ArrowRightLeft size={20} className="text-[var(--brand)]" />
          <h1 className="text-xl font-bold">Inventory Transfers</h1>
        </div>
        <Link to="/inventory/transfer" className="inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}>
          <Plus size={15} /> New transfer
        </Link>
      </div>
      <p className="text-sm text-[var(--text-2)] mt-1">Inter-site stock movements. Dispatched stock is in transit until received at the destination.</p>

      {actionError && <p role="alert" className="text-sm text-[var(--error)]">{actionError}</p>}
      {loading ? <LoadingState label="Loading transfers" />
        : error ? <ErrorState body={error} />
          : rows.length === 0 ? (
            <div className="rounded-xl border p-10 text-center text-sm text-[var(--text-3)]" style={{ borderColor: "var(--border)" }}>No transfers recorded.</div>
          ) : (
            <div className="space-y-3">
              {rows.map((row) => (
                <article key={row.id} className="rounded-xl border p-4" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <div className="flex items-center gap-2">
                        <span className="font-semibold font-mono text-sm">{row.transferNumber}</span>
                        <StatusChip status={row.status} size="sm" />
                      </div>
                      <div className="mt-1 text-sm">
                        <span className="font-medium">{row.fromStoreName}</span>
                        <span className="mx-1.5 text-[var(--text-3)]">→</span>
                        <span className="font-medium">{row.toStoreName}</span>
                      </div>
                      <div className="mt-1 text-xs text-[var(--text-3)]">
                        {row.lines.map((line) => `${line.itemCode} · ${line.qty} ${line.uomSymbol}`).join(" · ")}
                      </div>
                    </div>
                    <div className="flex flex-col items-end gap-2">
                      <span className="text-xs text-[var(--text-3)]">{row.transferDate}</span>
                      {row.status === "in_transit" && (
                        <button type="button" className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50" style={{ background: "var(--brand-fill)" }} disabled={receiving === row.id} onClick={() => void receive(row)}>
                          {receiving === row.id ? "Receiving…" : "Receive at destination"}
                        </button>
                      )}
                    </div>
                  </div>
                </article>
              ))}
            </div>
          )}
    </div>
  );
}
