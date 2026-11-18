import { useEffect, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { api } from "../auth/api";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import { allPages, quantity, type Lot, type Movement } from "./api";
import StockNav from "./stockNav";

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

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <div className="mx-auto w-full max-w-5xl space-y-5 p-4 md:p-6">
        <div className="flex items-start gap-3">
          <button type="button" onClick={() => navigate(-1)} aria-label="Back"><ArrowLeft size={16} /></button>
          <div>
            <h1 className="font-mono text-lg font-bold">{lot.lotNumber || lot.id}</h1>
            <p className="text-sm text-[var(--text-2)]">{lot.itemName} · {lot.storeName}</p>
            <p className="mt-1 text-sm font-semibold">{quantity(lot.qty, lot.uomSymbol)} on hand · {quantity(lot.availableQty, lot.uomSymbol)} available · {lot.status}</p>
          </div>
        </div>
        <section className="grid gap-3 rounded-xl border p-4 sm:grid-cols-2" style={{ borderColor: "var(--border)" }}>
          <Field label="Heat / supplier lot" value={lot.batchRef || "—"} />
          <Field label="Expiry" value={lot.expiryDate || "—"} />
          <Field label="Reserved" value={quantity(lot.reservedQty, lot.uomSymbol)} />
          <div>
            <div className="text-xs text-[var(--text-3)]">Goods receipt</div>
            <div className="text-sm">{lot.grnLineId ? <Link to="/procurement" className="text-[var(--brand)]">Linked receipt line</Link> : "Opening balance / legacy receipt"}</div>
          </div>
        </section>
        <section className="overflow-hidden rounded-xl border" style={{ borderColor: "var(--border)" }}>
          <h2 className="border-b px-4 py-3 text-sm font-bold" style={{ borderColor: "var(--border)" }}>Stock movement ledger</h2>
          <table className="w-full text-sm">
            <thead><tr>{["Timestamp", "Movement", "Qty", "Balance", "From", "To", "Reference", "By"].map((label) => <th key={label} className="px-3 py-2 text-left text-xs">{label}</th>)}</tr></thead>
            <tbody>
              {ledger.length === 0 && <tr><td colSpan={8} className="px-3 py-6 text-center text-sm text-[var(--text-3)]">No movements recorded</td></tr>}
              {ledger.map((movement) => {
                const m = movement as typeof movement & { fromLocation?: string; toLocation?: string; performedBy?: string };
                return (
                  <tr key={movement.id}>
                    <td className="px-3 py-2 text-xs">{movement.createdAt ? new Date(movement.createdAt).toLocaleString("en-IN", { day: "numeric", month: "short", hour: "2-digit", minute: "2-digit" }) : "—"}</td>
                    <td className="px-3 py-2 text-xs">{movement.movementType.replaceAll("_", " ")}</td>
                    <td className="px-3 py-2 font-mono text-xs">{Number(movement.qty) > 0 ? "+" : ""}{quantity(movement.qty, lot.uomSymbol)}</td>
                    <td className="px-3 py-2 font-mono text-xs">{quantity(movement.balance, lot.uomSymbol)}</td>
                    <td className="px-3 py-2 text-xs">{m.fromLocation || "—"}</td>
                    <td className="px-3 py-2 text-xs">{m.toLocation || "—"}</td>
                    <td className="px-3 py-2 text-xs">{movement.referenceType ? `${movement.referenceType}` : "—"}</td>
                    <td className="px-3 py-2 text-xs">{m.performedBy || "—"}</td>
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
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return <div><div className="text-xs text-[var(--text-3)]">{label}</div><div className="text-sm">{value}</div></div>;
}
