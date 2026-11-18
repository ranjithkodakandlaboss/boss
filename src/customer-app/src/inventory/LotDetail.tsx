import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { api } from "../auth/api";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import StatusChip from "../components/shared/StatusChip";
import { allPages, quantity, type Lot, type Movement } from "./api";

export default function LotDetail() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [lot, setLot] = useState<Lot | null>(null);
  const [movements, setMovements] = useState<Movement[]>([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let active = true;
    Promise.all([
      api<Lot>(`/api/v1/inventory/lots/${id}`),
      allPages<Movement>(`/api/v1/inventory/lots/${id}/movements`),
    ]).then(([next, ledger]) => {
      if (!active) return;
      setLot(next);
      setMovements(ledger);
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "Lot not found");
    }).finally(() => { if (active) setLoading(false); });
    return () => { active = false; };
  }, [id]);

  if (loading) return <LoadingState label="Loading material lot" />;
  if (error || !lot) return <ErrorState body={error || "Lot not found"} />;

  let balance = 0;
  const ledger = [...movements].sort((left, right) => String(left.createdAt).localeCompare(String(right.createdAt))).map((movement) => {
    balance += Number(movement.qty);
    return { ...movement, balance };
  });

  const isRemnant = (() => {
    const note = `${lot.batchRef ?? ""} ${lot.lotNumber ?? ""}`.toLowerCase();
    return note.includes("remnant") || note.includes("offcut") || note.includes("off-cut");
  })();

  return (
    <div className="p-4 md:p-6 max-w-5xl mx-auto space-y-5">
      <div className="flex items-center gap-3 mb-5">
        <button type="button" onClick={() => navigate(-1)} aria-label="Back" className="text-[var(--text-3)] hover:text-[var(--text)]"><ArrowLeft size={16} /></button>
        <div className="flex-1 min-w-0">
          <div className="flex flex-wrap items-center gap-2">
            <h1 className="font-bold font-mono text-lg">{lot.lotNumber || lot.id.slice(0, 8)}</h1>
            <StatusChip status={lot.status} size="sm" />
            {isRemnant && <span className="rounded-full px-2 py-0.5 text-[11px] font-medium" style={{ background: "var(--surface-2)", color: "var(--text-2)" }}>Remnant</span>}
          </div>
          <p className="text-sm text-[var(--text-2)]">{lot.itemName} · {lot.storeName}</p>
        </div>
      </div>

      <div className="grid gap-3 rounded-xl border p-4 sm:grid-cols-2" style={{ borderColor: "var(--border)" }}>
        <Field label="Heat / Supplier lot" value={lot.batchRef || "—"} />
        <Field label="Grade" value={(lot as { grade?: string }).grade || "—"} />
        <Field label="Received" value={lot.qty != null ? quantity(lot.qty, lot.uomSymbol) : "—"} />
        <div>
          <div className="text-xs text-[var(--text-3)]">GRN</div>
          <div className="text-sm">{lot.grnLineId ? <Link to="/procurement" className="text-[var(--brand)]">Linked receipt line</Link> : "Opening balance / legacy receipt"}</div>
        </div>
        <Field label="Reserved" value={quantity(lot.reservedQty, lot.uomSymbol)} />
        <Field label="Expiry" value={lot.expiryDate || "—"} />
      </div>

      <section className="overflow-hidden rounded-xl border" style={{ borderColor: "var(--border)" }}>
        <h2 className="border-b px-4 py-3 text-sm font-bold" style={{ borderColor: "var(--border)" }}>Stock movement ledger</h2>
        <table className="w-full text-sm">
          <thead><tr>{["Timestamp", "Movement", "Qty", "Balance", "From", "To", "Reference", "By"].map((label) => <th key={label} className="px-3 py-2 text-left text-xs">{label}</th>)}</tr></thead>
          <tbody>
            {ledger.length === 0 && <tr><td colSpan={8} className="px-3 py-6 text-center text-sm text-[var(--text-3)]">No movements recorded</td></tr>}
            {ledger.map((movement) => {
              const m = movement as typeof movement & { fromLocation?: string; toLocation?: string; performedBy?: string; performedByName?: string };
              const byDisplay = m.performedByName || (m.performedBy && isUuid(m.performedBy) ? m.performedBy.slice(0, 8) : m.performedBy) || "—";
              return (
                <tr key={movement.id}>
                  <td className="px-3 py-2 text-xs">{movement.createdAt ? new Date(movement.createdAt).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—"}</td>
                  <td className="px-3 py-2 text-xs">{movement.movementType.replaceAll("_", " ")}</td>
                  <td className="px-3 py-2 font-mono text-xs">{Number(movement.qty) > 0 ? "+" : ""}{quantity(movement.qty, lot.uomSymbol)}</td>
                  <td className="px-3 py-2 font-mono text-xs">{quantity(movement.balance, lot.uomSymbol)}</td>
                  <td className="px-3 py-2 text-xs">{m.fromLocation || "—"}</td>
                  <td className="px-3 py-2 text-xs">{m.toLocation || "—"}</td>
                  <td className="px-3 py-2 text-xs">{movement.referenceType ? `${movement.referenceType}` : "—"}</td>
                  <td className="px-3 py-2 text-xs">{byDisplay}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
        {ledger.length > 0 && (
          <p className="border-t px-4 py-2 text-xs" style={{ borderColor: "var(--border)", color: Math.abs(balance - Number(lot.qty)) > 0.001 ? "var(--error)" : "var(--success)" }}>
            Closing balance from ledger: {quantity(balance, lot.uomSymbol)}. Lot shows {quantity(lot.qty, lot.uomSymbol)}.
          </p>
        )}
      </section>
      <p className="text-xs text-[var(--text-3)]">Material certificates are not a separate ledger. Trace this lot through its batch reference{lot.grnLineId ? " and goods receipt" : ""}.</p>
      <Link to={`/inventory/cert/${lot.id}`} className="text-xs font-semibold text-[var(--brand)]">Open certificate trace</Link>
    </div>
  );
}

function isUuid(value: string) {
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i.test(value);
}

function Field({ label, value }: { label: string; value: string }) {
  return <div><div className="text-xs text-[var(--text-3)]">{label}</div><div className="text-sm">{value}</div></div>;
}
