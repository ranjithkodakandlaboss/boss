import { FormEvent, useEffect, useState } from "react";
import { api, safeRandomUUID } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import { allPages, type Item, type Uom } from "./api";
import StockNav from "./stockNav";

type BomLine = {
  componentItemId: string;
  componentItemCode?: string;
  componentItemName?: string;
  qty: number | string;
  uomId?: string;
  uomSymbol?: string;
  scrapFactor?: number | string;
  quantityFormula?: string;
};

type Bom = {
  id: string;
  itemId?: string;
  itemCode?: string;
  itemName?: string;
  version?: number | string;
  status?: string;
  isDefault?: boolean;
  lines?: BomLine[];
};

export default function BomPage({ embedded = false }: { embedded?: boolean }) {
  const { user } = useCustomer();
  const canWrite = (user?.roles ?? []).some((role) => ["OWNER", "PRODUCTION_MANAGER", "OPS_MANAGER"].includes(role.role));
  const [boms, setBoms] = useState<Bom[]>([]);
  const [items, setItems] = useState<Item[]>([]);
  const [uoms, setUoms] = useState<Uom[]>([]);
  const [selected, setSelected] = useState<Bom | null>(null);
  const [creating, setCreating] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  function load() {
    setLoading(true);
    Promise.all([
      api<Bom[]>("/api/v1/boms"),
      allPages<Item>("/api/v1/items?status=active"),
      api<Uom[] | { content?: Uom[] }>("/api/v1/uoms").then((units) => Array.isArray(units) ? units : units.content ?? []),
    ])
      .then(([rows, catalogue, units]) => {
        setBoms(rows);
        setItems(catalogue.filter((item) => (item.status ?? "active") !== "inactive"));
        setUoms(units);
        setError("");
      })
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Bills of materials could not be loaded."))
      .finally(() => setLoading(false));
  }

  useEffect(() => { load(); }, []);

  return (
    <div className={embedded ? "flex flex-col" : "flex min-h-[calc(100vh-var(--topbar-h))] flex-col"}>
      {!embedded && <StockNav />}
      <header className="flex flex-wrap items-center gap-3 px-5 py-3">
        <div>
          <h1 className="text-lg font-bold">Bills of materials</h1>
          <p className="text-xs text-[var(--text-2)]">Saving a BOM approves it immediately and makes it available for production jobs.</p>
        </div>
        <div className="flex-1" />
        {canWrite && <button type="button" className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => setCreating(true)}>Create BOM</button>}
      </header>
      {loading ? <LoadingState label="Loading bills of materials" /> : error ? <ErrorState body={error} /> : (
        <div className="grid gap-4 p-5 lg:grid-cols-[1fr_1fr]">
          <table className="w-full text-left text-sm">
            <thead><tr className="text-xs text-[var(--text-3)]"><th>Finished item</th><th>Version</th><th>Status</th></tr></thead>
            <tbody>
              {boms.length === 0 && <tr><td colSpan={3} className="py-6 text-[var(--text-3)]">No bills of materials yet. Create one before opening a production job.</td></tr>}
              {boms.map((bom) => (
                <tr key={bom.id} className="cursor-pointer border-t border-[var(--border)]" onClick={() => setSelected(bom)}>
                  <td className="py-2">{bom.itemCode} {bom.itemName}</td>
                  <td>v{bom.version}</td>
                  <td>{bom.status === "approved" ? "Approved" : bom.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
          {selected && (
            <section className="rounded-xl border border-[var(--border)] p-4">
              <h2 className="font-semibold">{selected.itemCode} — {selected.itemName}</h2>
              <p className="text-xs text-[var(--text-2)]">Version {selected.version} · {selected.status === "approved" ? "Approved" : selected.status}</p>
              <table className="mt-3 w-full text-left text-sm">
                <thead><tr className="text-xs text-[var(--text-3)]"><th>Component</th><th>Qty</th><th>UOM</th><th>Scrap %</th><th>Formula</th></tr></thead>
                <tbody>
                  {(selected.lines ?? []).map((line, index) => (
                    <tr key={`${line.componentItemId}-${index}`} className="border-t border-[var(--border)]">
                      <td className="py-1">{line.componentItemCode} {line.componentItemName}</td>
                      <td>{line.qty}</td>
                      <td>{line.uomSymbol}</td>
                      <td>{line.scrapFactor ?? 0}</td>
                      <td>{line.quantityFormula || "Fixed"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
              <BomCalculator key={selected.id} bom={selected} />
            </section>
          )}
        </div>
      )}
      {creating && <BomForm items={items} uoms={uoms} onClose={() => setCreating(false)} onSaved={() => { setCreating(false); load(); }} />}
    </div>
  );
}

type Explosion = {
  bomVersion?: string;
  bomRevisions?: Array<{ itemId: string; version: string }>;
  flatMaterials?: Array<{ itemId: string; itemCode?: string; itemName?: string; qty: number; uomSymbol?: string }>;
};

function BomCalculator({ bom }: { bom: Bom }) {
  const [quantity, setQuantity] = useState("1");
  const [specifications, setSpecifications] = useState<Record<string, string>>({});
  const [explosion, setExplosion] = useState<Explosion | null>(null);
  const [error, setError] = useState("");
  const [calculating, setCalculating] = useState(false);
  const [names, setNames] = useState<string[]>([]);
  const [loadingNames, setLoadingNames] = useState(true);

  useEffect(() => {
    if (!bom.itemId) {
      setLoadingNames(false);
      return;
    }
    api<string[]>(`/api/v1/items/${bom.itemId}/bom/specifications`)
      .then(setNames)
      .catch((reason: unknown) => setError(reason instanceof Error
        ? reason.message : "BOM specification fields could not be loaded."))
      .finally(() => setLoadingNames(false));
  }, [bom.itemId]);

  async function calculate() {
    setError("");
    setExplosion(null);
    setCalculating(true);
    try {
      const values = Object.fromEntries(names.map((name) => {
        const value = Number(specifications[name]);
        if (!Number.isFinite(value) || value < 0) throw new Error(`Enter a non-negative number for ${name}.`);
        return [name, value];
      }));
      const result = await api<Explosion>(`/api/v1/items/${bom.itemId}/bom/explode`, {
        method: "POST",
        body: JSON.stringify({ quantity: Number(quantity), specifications: values }),
      });
      setExplosion(result);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The BOM calculation could not be completed.");
    } finally {
      setCalculating(false);
    }
  }

  return (
    <div className="mt-5 border-t border-[var(--border)] pt-4">
      <h3 className="font-semibold">Calculate material requirements</h3>
      <p className="mt-1 text-xs text-[var(--text-2)]">Specification values are numeric; use the unit convention agreed for the formula variables.</p>
      <div className="mt-3 grid gap-2 md:grid-cols-3">
        <label className="text-xs font-medium">Product quantity
          <input aria-label="Product quantity" className="eoss-input mt-1 w-full" type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
        </label>
        {names.map((name) => (
          <label key={name} className="text-xs font-medium">{name}
            <input aria-label={`Specification ${name}`} className="eoss-input mt-1 w-full" type="number" min="0" step="any" value={specifications[name] ?? ""} onChange={(event) => setSpecifications((current) => ({ ...current, [name]: event.target.value }))} />
          </label>
        ))}
      </div>
      <button type="button" className="mt-3 rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} disabled={calculating || loadingNames || Boolean(error) || !Number.isFinite(Number(quantity)) || Number(quantity) <= 0} onClick={() => void calculate()}>{calculating ? "Calculating…" : "Calculate"}</button>
      {error && <p role="alert" className="mt-2 text-sm text-[var(--error)]">{error}</p>}
      {explosion && (
        <div className="mt-3">
          <p className="text-xs text-[var(--text-2)]">Calculated from BOM version {explosion.bomVersion ?? "—"}. The response includes the BOM revisions and specification values used.</p>
          <table className="mt-2 w-full text-left text-sm">
            <thead><tr className="text-xs text-[var(--text-3)]"><th>Material</th><th>Required quantity</th><th>UOM</th></tr></thead>
            <tbody>{(explosion.flatMaterials ?? []).map((material, index) => (
              <tr key={`${material.itemId}-${index}`} className="border-t border-[var(--border)]">
                <td className="py-1">{material.itemCode} {material.itemName}</td>
                <td>{material.qty}</td>
                <td>{material.uomSymbol}</td>
              </tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}

function BomForm({ items, uoms, onClose, onSaved }: { items: Item[]; uoms: Uom[]; onClose: () => void; onSaved: () => void }) {
  const finished = items.filter((item) => item.itemType === "finished_goods" || item.itemType === "semi_finished");
  const parents = finished.length > 0 ? finished : items;
  const [itemId, setItemId] = useState("");
  const [lines, setLines] = useState<BomLine[]>([{ componentItemId: "", qty: "", uomId: uoms[0]?.id ?? "", scrapFactor: 0 }]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  function update(index: number, patch: Partial<BomLine>) {
    setLines((current) => current.map((line, i) => i === index ? { ...line, ...patch } : line));
  }

  async function submit(event: FormEvent) {
    event.preventDefault();
    if (saving) return;
    if (!itemId) {
      setError("Select the finished item this BOM produces.");
      return;
    }
    if (lines.some((line) => !line.componentItemId
      || ((!line.quantityFormula?.trim() || line.qty !== "") && Number(line.qty) <= 0)
      || !line.uomId)) {
      setError("Each component needs an item, a positive fixed quantity or a formula, and a unit.");
      return;
    }
    setSaving(true);
    try {
      await api(`/api/v1/items/${itemId}/bom`, {
        method: "POST",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({
          lines: lines.map((line) => ({
            componentItemId: line.componentItemId,
            qty: line.quantityFormula?.trim() ? (Number(line.qty) || 1) : Number(line.qty),
            uomId: line.uomId,
            scrapFactor: Number(line.scrapFactor || 0),
            quantityFormula: line.quantityFormula?.trim() || null,
          })),
        }),
      });
      onSaved();
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "BOM could not be saved.");
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 grid place-items-center bg-black/40 p-4">
      <form onSubmit={(event) => void submit(event)} className="max-h-[90vh] w-full max-w-2xl overflow-auto rounded-xl bg-[var(--surface)] p-5">
        <h2 className="text-lg font-bold">Create bill of materials</h2>
        <p className="mb-3 text-xs text-[var(--text-2)]">The saved BOM is approved and can be selected when a production job is created.</p>
        {error && <p className="mb-2 text-sm text-[var(--error)]">{error}</p>}
        <label className="block text-xs font-semibold">Finished item *
          <select aria-label="Finished item" className="eoss-input mt-1 w-full" value={itemId} onChange={(event) => setItemId(event.target.value)}>
            <option value="">Select finished item</option>
            {parents.map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}
          </select>
        </label>
        <div className="mt-4 space-y-2">
          {lines.map((line, index) => (
            <div key={index} className="grid gap-2 md:grid-cols-5">
              <select aria-label={`Component ${index + 1}`} className="eoss-input" value={line.componentItemId} onChange={(event) => update(index, { componentItemId: event.target.value })}>
                <option value="">Component</option>
                {items.filter((item) => item.id !== itemId && item.itemType !== "finished_goods" && item.itemType !== "service").map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}
              </select>
              <input aria-label={`Quantity ${index + 1}`} className="eoss-input text-left" inputMode="decimal" value={line.qty} onChange={(event) => update(index, { qty: event.target.value })} placeholder="Fixed qty (or default)" />
              <select aria-label={`Unit ${index + 1}`} className="eoss-input" value={line.uomId} onChange={(event) => update(index, { uomId: event.target.value })}>
                {uoms.map((uom) => <option key={uom.id} value={uom.id}>{uom.symbol}</option>)}
              </select>
              <input aria-label={`Scrap ${index + 1}`} className="eoss-input text-left" inputMode="decimal" value={line.scrapFactor} onChange={(event) => update(index, { scrapFactor: event.target.value })} placeholder="Scrap %" />
              <input aria-label={`Formula ${index + 1}`} className="eoss-input text-left" value={line.quantityFormula ?? ""} onChange={(event) => update(index, { quantityFormula: event.target.value })} placeholder="Optional: length * width" />
            </div>
          ))}
        </div>
        <button type="button" className="mt-2 text-xs font-semibold" onClick={() => setLines((current) => [...current, { componentItemId: "", qty: "", uomId: uoms[0]?.id ?? "", scrapFactor: 0 }])}>Add component</button>
        <div className="mt-4 flex justify-end gap-2">
          <button type="button" onClick={onClose}>Cancel</button>
          <button type="submit" disabled={saving} className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}>{saving ? "Saving…" : "Save and approve"}</button>
        </div>
      </form>
    </div>
  );
}
