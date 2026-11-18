import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../auth/api";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import { quantity, type Lot } from "./api";
import StockNav from "./stockNav";

export default function CertificateDetail() {
  const { id = "" } = useParams();
  const [lot, setLot] = useState<Lot | null>(null);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    api<Lot>(`/api/v1/inventory/lots/${id}`)
      .then(setLot)
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Certificate not found"))
      .finally(() => setLoading(false));
  }, [id]);

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <div className="mx-auto w-full max-w-3xl space-y-4 p-6">
        <h1 className="text-xl font-bold">Material certificate</h1>
        {loading ? <LoadingState label="Loading certificate trace" /> : error || !lot ? <ErrorState body={error || "Certificate not found"} /> : (
          <section className="space-y-3 rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
            <p className="text-sm text-[var(--text-2)]">BOSS keeps the supplier batch and goods-receipt link on the lot. A separate certificate document, yield strength, or tensile value is not stored.</p>
            <dl className="grid gap-3 sm:grid-cols-2 text-sm">
              <div><dt className="text-xs text-[var(--text-3)]">Lot</dt><dd className="font-mono">{lot.lotNumber || lot.id}</dd></div>
              <div><dt className="text-xs text-[var(--text-3)]">Material</dt><dd>{lot.itemCode} · {lot.itemName}</dd></div>
              <div><dt className="text-xs text-[var(--text-3)]">Heat / supplier lot</dt><dd>{lot.batchRef || "—"}</dd></div>
              <div><dt className="text-xs text-[var(--text-3)]">Quantity</dt><dd>{quantity(lot.qty, lot.uomSymbol)} · {lot.status}</dd></div>
            </dl>
            <Link to={`/inventory/lot/${lot.id}`} className="text-sm font-semibold text-[var(--brand)]">Open lot ledger</Link>
          </section>
        )}
      </div>
    </div>
  );
}
