import { useEffect, useState } from "react";
import { Plus, Scale } from "lucide-react";
import { api } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";

export interface UomItem {
  id: string;
  name: string;
  symbol: string;
  uomType?: string;
  isBase?: boolean;
}

const UOM_TYPES = [
  { id: "quantity", label: "Quantity" },
  { id: "weight", label: "Weight" },
  { id: "volume", label: "Volume" },
  { id: "length", label: "Length" },
  { id: "area", label: "Area" },
  { id: "time", label: "Time" },
  { id: "energy", label: "Energy" },
  { id: "other", label: "Other" },
];

export default function UomAdmin() {
  const { user } = useCustomer();
  const canManage = Boolean(user?.roles.some(({ role }) => role === "OWNER" || role === "ADMIN"));
  const [uoms, setUoms] = useState<UomItem[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [showAdd, setShowAdd] = useState(false);

  // Form state
  const [name, setName] = useState("");
  const [symbol, setSymbol] = useState("");
  const [uomType, setUomType] = useState("quantity");
  const [isBase, setIsBase] = useState(false);
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState("");

  const loadUoms = () => {
    setLoading(true);
    setError("");
    api<UomItem[] | { content?: UomItem[] }>("/api/v1/uoms")
      .then((res) => {
        const items = Array.isArray(res) ? res : res.content ?? [];
        setUoms(items);
      })
      .catch((err) => {
        setError(err instanceof Error ? err.message : "Failed to load units of measure.");
      })
      .finally(() => setLoading(false));
  };

  useEffect(() => {
    loadUoms();
  }, []);

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!name.trim() || !symbol.trim()) {
      setFormError("Name and symbol are required.");
      return;
    }
    setSaving(true);
    setFormError("");
    try {
      await api<UomItem>("/api/v1/uoms", {
        method: "POST",
        body: JSON.stringify({
          name: name.trim(),
          symbol: symbol.trim().toUpperCase(),
          uomType,
          isBase,
        }),
      });
      setShowAdd(false);
      setName("");
      setSymbol("");
      setUomType("quantity");
      setIsBase(false);
      loadUoms();
    } catch (err) {
      setFormError(err instanceof Error ? err.message : "Failed to create unit of measure.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3 pt-3">
        <div>
          <h2 className="text-base font-bold text-[var(--text)]">Units of Measure</h2>
          <p className="text-xs text-[var(--text-2)]">
            Centrally manage standard measurement units across inventory, purchasing, sales, and manufacturing.
          </p>
        </div>
        {canManage && (
          <button
            type="button"
            onClick={() => setShowAdd(true)}
            className="btn-primary inline-flex items-center gap-1.5 text-xs font-semibold px-3 py-1.5"
          >
            <Plus size={14} /> Add UoM
          </button>
        )}
      </div>

      {error && (
        <div className="rounded-xl border border-[var(--error)] bg-[var(--error-bg)] p-3 text-xs text-[var(--error)]">
          {error}
        </div>
      )}

      {loading ? (
        <div className="py-12 text-center text-xs text-[var(--text-3)]">Loading units of measure…</div>
      ) : uoms.length === 0 ? (
        <div className="py-12 text-center text-xs text-[var(--text-3)]">No units of measure found.</div>
      ) : (
        <div className="overflow-hidden rounded-xl border border-[var(--border)] bg-[var(--surface)]">
          <table className="w-full text-left text-xs">
            <thead>
              <tr className="border-b border-[var(--border)] bg-[var(--surface-2)] text-[var(--text-3)]">
                <th className="px-4 py-2.5 font-semibold">Symbol</th>
                <th className="px-4 py-2.5 font-semibold">Name</th>
                <th className="px-4 py-2.5 font-semibold">Category / Type</th>
                <th className="px-4 py-2.5 font-semibold text-center">Base Unit</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-[var(--border)]">
              {uoms.map((uom) => (
                <tr key={uom.id} className="hover:bg-[var(--surface-2)]">
                  <td className="px-4 py-2.5 font-mono font-bold text-[var(--brand)]">{uom.symbol}</td>
                  <td className="px-4 py-2.5 font-medium text-[var(--text)]">{uom.name}</td>
                  <td className="px-4 py-2.5 capitalize text-[var(--text-2)]">{uom.uomType || "quantity"}</td>
                  <td className="px-4 py-2.5 text-center">
                    {uom.isBase ? (
                      <span className="inline-block rounded-md bg-[var(--success-bg)] px-2 py-0.5 text-[10px] font-semibold text-[var(--success)]">
                        Base
                      </span>
                    ) : (
                      <span className="text-[var(--text-3)]">—</span>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {showAdd && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/40 backdrop-blur-sm">
          <div className="w-full max-w-md rounded-2xl border border-[var(--border)] bg-[var(--surface)] p-6 shadow-2xl space-y-4">
            <div className="flex items-center gap-2 text-[var(--brand)]">
              <Scale size={20} />
              <h3 className="text-sm font-bold text-[var(--text)]">Add Unit of Measure</h3>
            </div>
            <form onSubmit={handleCreate} className="space-y-3">
              <div>
                <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Symbol *</label>
                <input
                  type="text"
                  required
                  value={symbol}
                  onChange={(e) => setSymbol(e.target.value)}
                  placeholder="e.g. KG, PCS, MTR, NOS"
                  className="eoss-input w-full font-mono uppercase text-sm"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Name *</label>
                <input
                  type="text"
                  required
                  value={name}
                  onChange={(e) => setName(e.target.value)}
                  placeholder="e.g. Kilogram, Pieces, Meter"
                  className="eoss-input w-full text-sm"
                />
              </div>
              <div>
                <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Measurement Type</label>
                <select
                  value={uomType}
                  onChange={(e) => setUomType(e.target.value)}
                  className="eoss-input w-full text-sm"
                >
                  {UOM_TYPES.map((t) => (
                    <option key={t.id} value={t.id}>{t.label}</option>
                  ))}
                </select>
              </div>
              <div className="flex items-center gap-2 pt-1">
                <input
                  type="checkbox"
                  id="isBase"
                  checked={isBase}
                  onChange={(e) => setIsBase(e.target.checked)}
                  className="rounded border-[var(--border)]"
                />
                <label htmlFor="isBase" className="text-xs text-[var(--text-2)] cursor-pointer">
                  Base unit for this measurement type
                </label>
              </div>

              {formError && <p className="text-xs text-[var(--error)]">{formError}</p>}

              <div className="flex justify-end gap-2 pt-3">
                <button
                  type="button"
                  disabled={saving}
                  onClick={() => setShowAdd(false)}
                  className="px-4 py-2 rounded-lg border text-xs text-[var(--text-2)]"
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="btn-primary text-xs font-semibold px-4 py-2"
                >
                  {saving ? "Creating…" : "Save UoM"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
