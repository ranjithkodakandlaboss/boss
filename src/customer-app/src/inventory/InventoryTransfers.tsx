import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Plus } from "lucide-react";
import { api, safeRandomUUID } from "../auth/api";
import Button from "../components/shared/Button";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import StockNav from "./stockNav";

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

  const columns: RecordColumn<Transfer>[] = [
    { key: "transferNumber", label: "Transfer", value: (row) => row.transferNumber, render: (row) => <span className="font-semibold">{row.transferNumber}</span> },
    { key: "fromStoreName", label: "From", value: (row) => row.fromStoreName, render: (row) => row.fromStoreName },
    { key: "toStoreName", label: "To", value: (row) => row.toStoreName, render: (row) => row.toStoreName },
    { key: "lines", label: "Lines", value: (row) => row.lines.length, render: (row) => row.lines.map((line) => `${line.itemCode} · ${line.qty} ${line.uomSymbol}`).join(", ") },
    { key: "transferDate", label: "Date", value: (row) => row.transferDate, render: (row) => row.transferDate },
    { key: "status", label: "Status", value: (row) => row.status, render: (row) => row.status.replaceAll("_", " ") },
    { key: "actions", label: "", render: (row) => row.status === "in_transit"
      ? <Button className="px-2 py-1 text-xs" disabled={receiving === row.id} onClick={() => void receive(row)}>{receiving === row.id ? "Receiving…" : "Receive"}</Button>
      : null },
  ];

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <header className="flex items-start justify-between gap-3 px-6 py-4">
        <div>
          <h1 className="text-lg font-bold text-[var(--text)]">Inventory transfers</h1>
          <p className="mt-1 text-xs text-[var(--text-2)]">{rows.length} transfers</p>
        </div>
        <Link to="/inventory/transfer" className="inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}>
          <Plus size={15} /> Move stock
        </Link>
      </header>
      {actionError && <p role="alert" className="mx-6 mb-3 text-sm text-[var(--error)]">{actionError}</p>}
      <div className="min-h-0 flex-1 overflow-auto">
        {loading ? <LoadingState label="Loading transfers" />
          : error ? <ErrorState body={error} />
            : <RecordTable rows={rows} columns={columns} empty="No transfers" />}
      </div>
    </div>
  );
}
