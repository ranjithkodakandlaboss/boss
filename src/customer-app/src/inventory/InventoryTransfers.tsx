import { useCallback, useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { Plus, X } from "lucide-react";
import { api, safeRandomUUID } from "../auth/api";
import Button from "../components/shared/Button";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import StatusChip from "../components/shared/StatusChip";
import Tabs from "../components/shared/Tabs";
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

const DETAIL_TABS = [
  { id: "lines", label: "Lines" },
  { id: "history", label: "History" },
];

function TransferDetail({
  transfer,
  onClose,
  onReceive,
  receiving,
}: {
  transfer: Transfer;
  onClose: () => void;
  onReceive: (transfer: Transfer) => void;
  receiving: boolean;
}) {
  const [activeTab, setActiveTab] = useState("lines");

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 p-4">
      <div
        role="dialog"
        aria-modal="true"
        aria-label={`Transfer ${transfer.transferNumber}`}
        className="relative flex h-full max-h-[90vh] w-full max-w-3xl flex-col overflow-hidden rounded-2xl bg-[var(--bg)] shadow-2xl"
        style={{ border: "1px solid var(--border)" }}
      >
        <header
          className="flex items-center justify-between border-b px-5 py-3"
          style={{ borderColor: "var(--border)", background: "var(--surface)" }}
        >
          <div>
            <div className="text-xs font-semibold text-[var(--text-3)]">Transfer</div>
            <h2 className="text-base font-bold">{transfer.transferNumber}</h2>
          </div>
          <div className="flex items-center gap-3">
            {transfer.status === "in_transit" && (
              <Button
                className="px-3 py-1.5 text-xs"
                disabled={receiving}
                onClick={() => onReceive(transfer)}
              >
                {receiving ? "Receiving…" : "Receive"}
              </Button>
            )}
            <button
              type="button"
              onClick={onClose}
              aria-label="Close"
              className="rounded-lg p-1.5 text-[var(--text-2)] hover:bg-[var(--surface-2)]"
            >
              <X size={16} />
            </button>
          </div>
        </header>
        <div
          className="grid grid-cols-3 gap-4 border-b px-5 py-3 text-sm"
          style={{ borderColor: "var(--border)" }}
        >
          <div>
            <div className="text-xs text-[var(--text-3)]">From</div>
            <div className="font-medium">{transfer.fromStoreName}</div>
          </div>
          <div>
            <div className="text-xs text-[var(--text-3)]">To</div>
            <div className="font-medium">{transfer.toStoreName}</div>
          </div>
          <div>
            <div className="text-xs text-[var(--text-3)]">Status</div>
            <StatusChip status={transfer.status} />
          </div>
        </div>
        <Tabs tabs={DETAIL_TABS} active={activeTab} onChange={setActiveTab} />
        <div className="min-h-0 flex-1 overflow-auto">
          {activeTab === "lines" && (
            <table className="w-full text-sm">
              <thead>
                <tr style={{ borderBottom: "1px solid var(--border)", background: "var(--surface-2)" }}>
                  {["Item code", "Material", "Quantity", "UOM"].map((label) => (
                    <th key={label} className="px-4 py-2.5 text-left text-xs font-semibold text-[var(--text-3)]">
                      {label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {transfer.lines.length === 0 && (
                  <tr>
                    <td
                      colSpan={4}
                      className="px-4 py-8 text-center text-sm text-[var(--text-3)]"
                    >
                      No lines on this transfer.
                    </td>
                  </tr>
                )}
                {transfer.lines.map((line) => (
                  <tr
                    key={line.id}
                    style={{ borderBottom: "1px solid var(--border)" }}
                  >
                    <td className="px-4 py-2.5 font-mono text-xs">{line.itemCode}</td>
                    <td className="px-4 py-2.5 text-xs">{line.itemName}</td>
                    <td className="px-4 py-2.5 font-mono text-xs">{line.qty}</td>
                    <td className="px-4 py-2.5 text-xs">{line.uomSymbol}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {activeTab === "history" && (
            <div className="p-5">
              <p className="text-sm text-[var(--text-2)]">
                Transfer date:{" "}
                <span className="font-medium">
                  {transfer.transferDate
                    ? new Date(transfer.transferDate).toLocaleString("en-IN", {
                        day: "numeric",
                        month: "short",
                        year: "numeric",
                      })
                    : "—"}
                </span>
              </p>
              <p className="mt-2 text-sm text-[var(--text-2)]">
                Status: <StatusChip status={transfer.status} />
              </p>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

export default function InventoryTransfers() {
  const [rows, setRows] = useState<Transfer[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [receiving, setReceiving] = useState<string | null>(null);
  const [selected, setSelected] = useState<Transfer | null>(null);
  const [query, setQuery] = useState("");
  const [statusFilter, setStatusFilter] = useState("all");
  const [sortKey, setSortKey] = useState("transferDate");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");

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

  useEffect(() => {
    void load();
  }, [load]);

  function handleSort(key: string) {
    if (sortKey === key) {
      setSortDir((dir) => (dir === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  }

  async function receive(transfer: Transfer) {
    setReceiving(transfer.id);
    setActionError("");
    try {
      await api<Transfer>(`/api/v1/inter-site-transfers/${transfer.id}/receive`, {
        method: "POST",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({}),
      });
      setSelected(null);
      await load();
    } catch (reason) {
      setActionError(reason instanceof Error ? reason.message : "Transfer could not be received.");
    } finally {
      setReceiving(null);
    }
  }

  const statuses = [...new Set(rows.map((row) => row.status))].filter(Boolean);

  const visible = rows.filter((row) => {
    const term = query.trim().toLowerCase();
    const matchText =
      !term ||
      `${row.transferNumber} ${row.fromStoreName} ${row.toStoreName}`
        .toLowerCase()
        .includes(term);
    const matchStatus = statusFilter === "all" || row.status === statusFilter;
    return matchText && matchStatus;
  });

  const columns: RecordColumn<Transfer>[] = [
    {
      key: "transferNumber",
      label: "Transfer",
      value: (row) => row.transferNumber,
      render: (row) => <span className="font-semibold">{row.transferNumber}</span>,
    },
    {
      key: "fromStoreName",
      label: "From",
      value: (row) => row.fromStoreName,
      render: (row) => row.fromStoreName,
    },
    {
      key: "toStoreName",
      label: "To",
      value: (row) => row.toStoreName,
      render: (row) => row.toStoreName,
    },
    {
      key: "lines",
      label: "Lines",
      value: (row) => row.lines.length,
      render: (row) => (
        <span className="text-xs text-[var(--text-2)]">
          {row.lines.length} line{row.lines.length === 1 ? "" : "s"}
        </span>
      ),
    },
    {
      key: "transferDate",
      label: "Date",
      value: (row) => row.transferDate,
      render: (row) =>
        row.transferDate
          ? new Date(row.transferDate).toLocaleDateString("en-IN", {
              day: "numeric",
              month: "short",
              year: "numeric",
            })
          : "—",
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
        <div className="flex items-start justify-between gap-3">
          <div>
            <h1 className="text-lg font-bold text-[var(--text)]">Inventory Transfers</h1>
            <p className="mt-1 text-xs text-[var(--text-2)]">{rows.length} transfers</p>
          </div>
          <Link
            to="/inventory/transfer"
            className="inline-flex items-center gap-1.5 rounded-lg px-4 py-2 text-sm font-semibold text-white"
            style={{ background: "var(--brand-fill)" }}
          >
            <Plus size={15} /> Move stock
          </Link>
        </div>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input
            aria-label="Search transfers"
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            placeholder="Search transfer, from or to…"
            className="eoss-input max-w-sm flex-1 text-sm"
          />
          <select
            aria-label="Transfer status"
            className="eoss-input min-h-[36px] text-sm"
            value={statusFilter}
            onChange={(event) => setStatusFilter(event.target.value)}
          >
            <option value="all">All statuses</option>
            {statuses.map((status) => (
              <option key={status} value={status}>
                {status.replaceAll("_", " ")}
              </option>
            ))}
          </select>
        </div>
      </header>
      {actionError && (
        <p role="alert" className="mx-6 mb-3 text-sm text-[var(--error)]">
          {actionError}
        </p>
      )}
      <div className="min-h-0 flex-1 overflow-auto">
        {loading ? (
          <LoadingState label="Loading transfers" />
        ) : error ? (
          <ErrorState body={error} />
        ) : (
          <RecordTable
            rows={visible}
            columns={columns}
            sortKey={sortKey}
            sortDir={sortDir}
            onSort={handleSort}
            onRow={setSelected}
            empty="No transfers"
          />
        )}
      </div>
      {selected && (
        <TransferDetail
          transfer={selected}
          onClose={() => setSelected(null)}
          onReceive={(transfer) => void receive(transfer)}
          receiving={receiving === selected.id}
        />
      )}
    </div>
  );
}
