import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ArrowLeft, Plus, Trash2 } from "lucide-react";
import StockNav from "./stockNav";
import { api, mutate, type Item, type Company, type Store } from "./api";

interface StockLine {
  id: string;
  itemId: string;
  storeId: string;
  qty: string;
  unitCost: string;
  batchRef: string;
}

function newLine(): StockLine {
  return { id: `l-${Date.now()}`, itemId: "", storeId: "", qty: "", unitCost: "", batchRef: "" };
}

export default function OpeningStockPage() {
  const navigate = useNavigate();
  const [items, setItems] = useState<Item[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [lines, setLines] = useState<StockLine[]>([newLine()]);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [done, setDone] = useState(false);

  useEffect(() => {
    Promise.all([
      api<{ content?: Item[] }>("/api/v1/items?status=active&page=0&size=500"),
      api<Company>("/api/v1/company"),
    ]).then(([itemPage, company]) => {
      setItems(itemPage.content ?? []);
      setStores(company.stores ?? []);
    }).catch(() => setError("Could not load items or stores.")).finally(() => setLoading(false));
  }, []);

  const updateLine = (id: string, field: keyof StockLine, value: string) =>
    setLines(ls => ls.map(l => l.id === id ? { ...l, [field]: value } : l));

  const save = async () => {
    setError("");
    for (const l of lines) {
      if (!l.itemId) return setError("Select an item for every line.");
      if (!l.storeId) return setError("Select a store for every line.");
      if (!l.qty || Number(l.qty) <= 0) return setError("Enter a quantity greater than zero for every line.");
    }
    setSaving(true);
    try {
      for (const l of lines) {
        await mutate("/api/v1/inventory/lots", "POST", {
          itemId: l.itemId,
          storeId: l.storeId,
          qty: Number(l.qty),
          unitCost: l.unitCost ? Number(l.unitCost) : null,
          batchRef: l.batchRef || null,
        });
      }
      setDone(true);
    } catch (err: unknown) {
      setError(err instanceof Error ? err.message : "Could not save stock.");
    } finally {
      setSaving(false);
    }
  };

  if (done) {
    return (
      <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
        <StockNav />
        <div className="flex flex-col items-center justify-center flex-1 gap-4 p-8 text-center">
          <div className="text-4xl">✓</div>
          <p className="text-base font-semibold text-[var(--text)]">Opening stock recorded</p>
          <p className="text-sm text-[var(--text-2)]">{lines.length} lot{lines.length === 1 ? "" : "s"} added to inventory.</p>
          <div className="flex gap-3 mt-2">
            <button onClick={() => { setLines([newLine()]); setDone(false); }} className="px-4 py-2 rounded-lg border text-sm" style={{ borderColor: "var(--border)", color: "var(--text-2)" }}>Add more</button>
            <button onClick={() => navigate("/inventory")} className="px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: "var(--brand-fill)" }}>View stock</button>
          </div>
        </div>
      </div>
    );
  }

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
      <header className="flex items-center gap-3 border-b px-5 py-3" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <button type="button" onClick={() => navigate("/inventory")} className="p-1.5 rounded-lg hover:bg-[var(--surface-2)]">
          <ArrowLeft size={18} className="text-[var(--text-2)]" />
        </button>
        <div>
          <h1 className="text-base font-bold text-[var(--text)]">Opening Stock Entry</h1>
          <p className="text-xs text-[var(--text-2)]">Record initial stock directly — no GRN or approval required.</p>
        </div>
      </header>

      {loading && <div className="p-6 text-sm text-[var(--text-2)]">Loading items and stores…</div>}

      {!loading && (
        <div className="flex-1 overflow-auto p-5">
          <div className="max-w-4xl mx-auto space-y-3">
            {lines.map((line, idx) => (
              <div key={line.id} className="rounded-xl border p-4 relative" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
                <div className="text-xs font-semibold text-[var(--text-3)] mb-3">Line {idx + 1}</div>
                <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
                  <label className="text-xs font-medium text-[var(--text-2)]">Item *
                    <select value={line.itemId} onChange={e => updateLine(line.id, "itemId", e.target.value)} className="eoss-input w-full mt-1 text-sm">
                      <option value="">Select item…</option>
                      {items.map(item => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}
                    </select>
                  </label>
                  <label className="text-xs font-medium text-[var(--text-2)]">Store / Location *
                    <select value={line.storeId} onChange={e => updateLine(line.id, "storeId", e.target.value)} className="eoss-input w-full mt-1 text-sm">
                      <option value="">Select store…</option>
                      {stores.map(store => <option key={store.id} value={store.id}>{store.siteCode ? `${store.siteCode} · ` : ""}{store.name}</option>)}
                    </select>
                  </label>
                  <label className="text-xs font-medium text-[var(--text-2)]">Quantity *
                    <input type="number" min="0.001" step="any" value={line.qty} onChange={e => updateLine(line.id, "qty", e.target.value)} className="eoss-input w-full mt-1 text-sm" placeholder="e.g. 100" />
                  </label>
                  <label className="text-xs font-medium text-[var(--text-2)]">Unit Cost (₹)
                    <input type="number" min="0" step="any" value={line.unitCost} onChange={e => updateLine(line.id, "unitCost", e.target.value)} className="eoss-input w-full mt-1 text-sm" placeholder="e.g. 250.00" />
                  </label>
                  <label className="text-xs font-medium text-[var(--text-2)]">Batch / Lot Reference
                    <input type="text" value={line.batchRef} onChange={e => updateLine(line.id, "batchRef", e.target.value)} className="eoss-input w-full mt-1 text-sm" placeholder="e.g. BATCH-001" />
                  </label>
                </div>
                {lines.length > 1 && (
                  <button type="button" onClick={() => setLines(ls => ls.filter(l => l.id !== line.id))} className="absolute top-3 right-3 p-1 rounded hover:bg-[var(--surface-2)]">
                    <Trash2 size={14} className="text-[var(--text-3)]" />
                  </button>
                )}
              </div>
            ))}

            <button type="button" onClick={() => setLines(ls => [...ls, newLine()])} className="inline-flex items-center gap-1.5 text-sm font-medium text-[var(--brand)] hover:underline">
              <Plus size={14} /> Add another line
            </button>

            {error && <p className="text-sm text-[var(--error)]">{error}</p>}
          </div>
        </div>
      )}

      {!loading && (
        <div className="sticky bottom-0 flex justify-between items-center px-5 py-3 border-t" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          <button type="button" onClick={() => navigate("/inventory")} className="px-4 py-2 rounded-lg border text-sm" style={{ borderColor: "var(--border)", color: "var(--text-2)" }}>Cancel</button>
          <button type="button" onClick={save} disabled={saving} className="px-5 py-2 rounded-lg text-sm font-semibold text-white disabled:opacity-50" style={{ background: "var(--brand-fill)" }}>
            {saving ? "Saving…" : `Save ${lines.length} lot${lines.length === 1 ? "" : "s"}`}
          </button>
        </div>
      )}
    </div>
  );
}
