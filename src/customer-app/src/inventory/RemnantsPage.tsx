import { useEffect, useState } from "react";
import { ArrowLeft, Scissors } from "lucide-react";
import { useNavigate } from "react-router-dom";
import ConfirmDialog from "../components/shared/ConfirmDialog";
import LoadingState from "../components/shared/LoadingState";
import { loadLots, mutate, quantity, type Lot } from "./api";

type Filter = "all" | "remnants" | "scrap";

export default function RemnantsPage() {
  const navigate = useNavigate();
  const [lots, setLots] = useState<Lot[]>([]);
  const [filter, setFilter] = useState<Filter>("all");
  const [confirmId, setConfirmId] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");

  function load() {
    setLoading(true);
    Promise.all([loadLots("status=disposed"), loadLots("status=available"), loadLots("status=reserved")])
      .then(([disposed, available, reserved]) => setLots([...disposed, ...available, ...reserved]))
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Lots could not be loaded."))
      .finally(() => setLoading(false));
  }

  useEffect(() => { load(); }, []);

  const scrap = lots.filter((lot) => lot.status === "disposed");
  const remnants = lots.filter((lot) => isRemnant(lot));
  const display = filter === "scrap" ? scrap : filter === "remnants" ? remnants : dedupe([...remnants, ...scrap]);

  const confirmLot = lots.find((lot) => lot.id === confirmId);

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

  return (
    <div className="p-4 md:p-6 max-w-5xl mx-auto space-y-5">
      <button type="button" onClick={() => navigate("/inventory")} className="flex items-center gap-1.5 text-sm text-[var(--text-2)] hover:text-[var(--brand)] mb-3">
        <ArrowLeft size={14} /> Inventory
      </button>
      <div className="flex items-center gap-2">
        <Scissors size={20} className="text-[var(--brand)]" />
        <h1 className="text-xl font-bold">Remnants & Scrap</h1>
      </div>
      <p className="text-sm text-[var(--text-2)] mt-1">Scrap is a disposed lot. A lot can be scrapped only when none of it is reserved. Off-cut remnant identity is not stored as a parent lot, so reusable off-cuts stay ordinary available lots.</p>

      <div className="grid grid-cols-3 gap-3">
        <Stat label="Remnant lots" value={remnants.length} />
        <Stat label="Scrapped lots" value={scrap.length} />
        <Stat label="Reusable remnants" value={remnants.filter((lot) => lot.status === "available").length} />
      </div>
      <div className="flex gap-2">
        {(["all", "remnants", "scrap"] as const).map((value) => (
          <button key={value} type="button" onClick={() => setFilter(value)} className="rounded-lg px-3 py-1.5 text-xs font-semibold" style={filter === value ? { background: "var(--brand-fill)", color: "#fff" } : { border: "1px solid var(--border)", color: "var(--text-2)" }}>
            {value === "all" ? "All" : value === "remnants" ? "Remnants" : "Scrap"}
          </button>
        ))}
      </div>
      {notice && <p role="status" className="text-sm text-[var(--success)]">{notice}</p>}
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      {loading ? <LoadingState label="Loading remnants and scrap" /> : display.length === 0 ? (
        <p className="py-12 text-center text-sm text-[var(--text-2)]">No items in this view.</p>
      ) : (
        <table className="w-full text-sm">
          <thead><tr>{["Lot", "Material", "Qty", "Location", "Type", "Status", "Actions"].map((label) => <th key={label} className="px-3 py-2 text-left text-xs">{label}</th>)}</tr></thead>
          <tbody>
            {display.map((lot) => (
              <tr key={lot.id}>
                <td className="px-3 py-2"><button type="button" className="text-xs font-semibold text-[var(--brand)]" onClick={() => navigate(`/inventory/lot/${lot.id}`)}>{lot.lotNumber || lot.batchRef || lot.id.slice(0, 8)}</button></td>
                <td className="px-3 py-2 text-xs">{lot.itemName}</td>
                <td className="px-3 py-2 text-xs">{quantity(lot.qty, lot.uomSymbol)}</td>
                <td className="px-3 py-2 text-xs">{lot.storeName}</td>
                <td className="px-3 py-2 text-xs">{lot.status === "disposed" ? "Scrap" : "Remnant"}</td>
                <td className="px-3 py-2 text-xs">{lot.status}</td>
                <td className="px-3 py-2">
                  {lot.status !== "disposed" && Number(lot.reservedQty) === 0 && Number(lot.qty) > 0 && (
                    <button type="button" className="text-xs font-semibold" style={{ color: "var(--error)" }} onClick={() => setConfirmId(lot.id)}>Scrap</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      )}

      {confirmLot && (
        <ConfirmDialog
          open={confirmId !== null}
          title="Scrap this lot?"
          body={`${confirmLot.lotNumber || confirmLot.itemCode} · ${quantity(confirmLot.qty, confirmLot.uomSymbol)} will be moved to scrap. This reduces stock and cannot be undone.`}
          confirmLabel="Confirm Scrap"
          cancelLabel="Cancel"
          variant="danger"
          onConfirm={() => void scrapLot(confirmLot)}
          onCancel={() => setConfirmId(null)}
        />
      )}
    </div>
  );
}

function isRemnant(lot: Lot) {
  const note = `${lot.batchRef ?? ""} ${lot.lotNumber ?? ""}`.toLowerCase();
  return lot.status !== "disposed" && (note.includes("remnant") || note.includes("offcut") || note.includes("off-cut"));
}

function dedupe(lots: Lot[]) {
  const seen = new Set<string>();
  return lots.filter((lot) => seen.has(lot.id) ? false : seen.add(lot.id));
}

function Stat({ label, value }: { label: string; value: number }) {
  return <div className="rounded-xl border p-3" style={{ borderColor: "var(--border)" }}><div className="text-xl font-bold">{value}</div><div className="text-xs text-[var(--text-2)]">{label}</div></div>;
}
