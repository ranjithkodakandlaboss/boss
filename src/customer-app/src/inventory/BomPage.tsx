import { FormEvent, useEffect, useState } from "react";
import { Plus } from "lucide-react";
import { api, safeRandomUUID } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordSheet from "../components/shared/RecordSheet";
import { allPages, type Item, type Uom } from "./api";
import StockNav from "./stockNav";

type BomLine = {
  componentItemId: string;
  componentItemCode?: string;
  componentItemName?: string;
  componentItemType?: string;
  qty: number | string;
  uomId?: string;
  uomSymbol?: string;
  scrapFactor?: number | string;
  quantityFormula?: string;
  unitCost?: number | string;
  totalCost?: number | string;
};

type Bom = {
  id: string;
  itemId?: string;
  itemCode?: string;
  itemName?: string;
  version?: number | string;
  status?: string;
  isDefault?: boolean;
  labourCostPerUnit?: number | null;
  machineCostPerUnit?: number | null;
  otherCostPerUnit?: number | null;
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
        if (selected) {
          const fresh = rows.find((b) => b.id === selected.id);
          if (fresh) setSelected(fresh);
        }
      })
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Bills of materials could not be loaded."))
      .finally(() => setLoading(false));
  }

  useEffect(() => { load(); }, []);

  // Split lines into raw materials and consumables
  const lines = selected?.lines ?? [];
  const rawMaterials = lines.filter((l) => (l.componentItemType ?? "").toUpperCase() !== "CONSUMABLE");
  const consumables = lines.filter((l) => (l.componentItemType ?? "").toUpperCase() === "CONSUMABLE");

  function lineCost(l: BomLine): number {
    if (l.totalCost != null) return Number(l.totalCost);
    const q = Number(l.qty) || 0;
    const scrap = Number(l.scrapFactor) || 0;
    const u = Number(l.unitCost) || 0;
    return Math.round(q * (1 + scrap / 100) * u * 100) / 100;
  }

  const rawSubtotal = rawMaterials.reduce((sum, l) => sum + lineCost(l), 0);
  const consumableSubtotal = consumables.reduce((sum, l) => sum + lineCost(l), 0);
  const labourUnit = Number(selected?.labourCostPerUnit || 0);
  const machineUnit = Number(selected?.machineCostPerUnit || 0);
  const otherUnit = Number(selected?.otherCostPerUnit || 0);
  const totalUnitCost = Math.round((rawSubtotal + consumableSubtotal + labourUnit + machineUnit + otherUnit) * 100) / 100;

  return (
    <div className={embedded ? "flex flex-col" : "flex min-h-[calc(100vh-var(--topbar-h))] flex-col"}>
      {!embedded && <StockNav />}
      <header className="flex flex-wrap items-center gap-3 px-5 py-3">
        <div>
          <h1 className="text-lg font-bold">Bills of materials</h1>
          <p className="text-xs text-[var(--text-2)]">Defines materials, consumables, and standard manufacturing costs required to produce each product.</p>
        </div>
        <div className="flex-1" />
        {canWrite && <button type="button" className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => setCreating(true)}>Create BOM</button>}
      </header>
      {loading ? <LoadingState label="Loading bills of materials" /> : error ? <ErrorState body={error} /> : (
        <div className="grid gap-4 p-5 lg:grid-cols-[1fr_1.3fr]">
          <div className="rounded-xl border border-[var(--border)] overflow-hidden">
            <table className="w-full text-left text-sm">
              <thead style={{ background: "var(--surface-2)" }}>
                <tr className="text-xs text-[var(--text-3)]">
                  <th className="px-3 py-2">Finished item</th>
                  <th className="px-3 py-2">Version</th>
                  <th className="px-3 py-2">Status</th>
                  <th className="px-3 py-2" />
                </tr>
              </thead>
              <tbody>
                {boms.length === 0 && <tr><td colSpan={4} className="py-6 text-center text-xs text-[var(--text-3)]">No bills of materials yet. Create one before opening a production job.</td></tr>}
                {boms.map((bom) => (
                  <tr key={bom.id} className={`cursor-pointer border-t border-[var(--border)] hover:bg-[var(--surface-2)] ${selected?.id === bom.id ? "bg-[var(--surface-2)]" : ""}`} onClick={() => setSelected(bom)}>
                    <td className="px-3 py-2.5 font-medium">{bom.itemCode} {bom.itemName}</td>
                    <td className="px-3 py-2.5 text-xs">v{bom.version}</td>
                    <td className="px-3 py-2.5 text-xs">{bom.status === "approved" ? "Approved" : bom.status}</td>
                    <td className="px-3 py-2.5 text-right" onClick={(e) => e.stopPropagation()}>
                      <button type="button" className="text-xs px-2.5 py-1 rounded font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => setSelected(bom)}>View</button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {selected ? (
            <section className="rounded-xl border border-[var(--border)] p-4 space-y-4">
              <div className="flex items-center justify-between pb-3 border-b border-[var(--border)]">
                <div>
                  <h2 className="font-bold text-base">{selected.itemCode} — {selected.itemName}</h2>
                  <p className="text-xs text-[var(--text-2)]">BOM Version {selected.version} · {selected.status === "approved" ? "Approved" : selected.status}</p>
                </div>
                <div className="text-right">
                  <span className="text-xs text-[var(--text-3)]">Est. Mfg Cost / Unit</span>
                  <div className="text-base font-bold text-[var(--brand)]">₹{totalUnitCost.toFixed(2)}</div>
                </div>
              </div>

              {/* Standard Manufacturing Cost Summary */}
              <div className="rounded-lg p-3 border border-[var(--border)] text-xs grid grid-cols-2 sm:grid-cols-3 gap-3" style={{ background: "var(--surface-2)" }}>
                <div><span style={{ color: "var(--text-3)" }}>Raw Materials Cost</span><div className="font-semibold">₹{rawSubtotal.toFixed(2)}</div></div>
                <div><span style={{ color: "var(--text-3)" }}>Consumables Cost</span><div className="font-semibold">₹{consumableSubtotal.toFixed(2)}</div></div>
                <div><span style={{ color: "var(--text-3)" }}>Labour Cost / Unit</span><div className="font-semibold">{labourUnit > 0 ? `₹${labourUnit.toFixed(2)}` : "—"}</div></div>
                <div><span style={{ color: "var(--text-3)" }}>Machine Cost / Unit</span><div className="font-semibold">{machineUnit > 0 ? `₹${machineUnit.toFixed(2)}` : "—"}</div></div>
                <div><span style={{ color: "var(--text-3)" }}>Other Mfg Cost / Unit</span><div className="font-semibold">{otherUnit > 0 ? `₹${otherUnit.toFixed(2)}` : "—"}</div></div>
                <div><span style={{ color: "var(--text-3)" }}>Total Unit Cost</span><div className="font-bold text-[var(--brand)]">₹{totalUnitCost.toFixed(2)}</div></div>
              </div>

              {/* Raw Materials Section */}
              <div className="space-y-1">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-[var(--text-2)]">Raw Materials ({rawMaterials.length})</h3>
                  <span className="text-xs font-semibold text-[var(--text-3)]">Subtotal: ₹{rawSubtotal.toFixed(2)}</span>
                </div>
                {rawMaterials.length === 0 ? (
                  <p className="text-xs text-[var(--text-3)] py-1">No raw materials defined.</p>
                ) : (
                  <div className="overflow-x-auto rounded border border-[var(--border)]">
                    <table className="w-full text-left text-xs">
                      <thead style={{ background: "var(--surface-2)" }}>
                        <tr className="text-[var(--text-3)]"><th className="px-2 py-1.5">Component</th><th className="px-2 py-1.5">Qty</th><th className="px-2 py-1.5">UOM</th><th className="px-2 py-1.5">Unit Cost</th><th className="px-2 py-1.5">Scrap %</th><th className="px-2 py-1.5">Line Cost</th><th className="px-2 py-1.5">Formula</th></tr>
                      </thead>
                      <tbody>
                        {rawMaterials.map((line, idx) => (
                          <tr key={`${line.componentItemId}-${idx}`} className="border-t border-[var(--border)]">
                            <td className="px-2 py-1.5 font-medium">{line.componentItemCode} {line.componentItemName}</td>
                            <td className="px-2 py-1.5">{line.qty}</td>
                            <td className="px-2 py-1.5">{line.uomSymbol}</td>
                            <td className="px-2 py-1.5">{line.unitCost != null ? `₹${Number(line.unitCost).toFixed(2)}` : "—"}</td>
                            <td className="px-2 py-1.5">{line.scrapFactor ?? 0}%</td>
                            <td className="px-2 py-1.5 font-mono font-semibold">₹{lineCost(line).toFixed(2)}</td>
                            <td className="px-2 py-1.5 text-[var(--text-3)]">{line.quantityFormula || "Fixed"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>

              {/* Consumables Section */}
              <div className="space-y-1">
                <div className="flex items-center justify-between">
                  <h3 className="text-xs font-bold uppercase tracking-wider text-[var(--text-2)]">Consumables ({consumables.length})</h3>
                  <span className="text-xs font-semibold text-[var(--text-3)]">Subtotal: ₹{consumableSubtotal.toFixed(2)}</span>
                </div>
                {consumables.length === 0 ? (
                  <p className="text-xs text-[var(--text-3)] py-1">No consumables defined in this BOM.</p>
                ) : (
                  <div className="overflow-x-auto rounded border border-[var(--border)]">
                    <table className="w-full text-left text-xs">
                      <thead style={{ background: "var(--surface-2)" }}>
                        <tr className="text-[var(--text-3)]"><th className="px-2 py-1.5">Consumable</th><th className="px-2 py-1.5">Qty</th><th className="px-2 py-1.5">UOM</th><th className="px-2 py-1.5">Unit Cost</th><th className="px-2 py-1.5">Scrap %</th><th className="px-2 py-1.5">Line Cost</th><th className="px-2 py-1.5">Formula</th></tr>
                      </thead>
                      <tbody>
                        {consumables.map((line, idx) => (
                          <tr key={`${line.componentItemId}-${idx}`} className="border-t border-[var(--border)]">
                            <td className="px-2 py-1.5 font-medium">{line.componentItemCode} {line.componentItemName}</td>
                            <td className="px-2 py-1.5">{line.qty}</td>
                            <td className="px-2 py-1.5">{line.uomSymbol}</td>
                            <td className="px-2 py-1.5">{line.unitCost != null ? `₹${Number(line.unitCost).toFixed(2)}` : "—"}</td>
                            <td className="px-2 py-1.5">{line.scrapFactor ?? 0}%</td>
                            <td className="px-2 py-1.5 font-mono font-semibold">₹{lineCost(line).toFixed(2)}</td>
                            <td className="px-2 py-1.5 text-[var(--text-3)]">{line.quantityFormula || "Fixed"}</td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
              </div>

              {/* Manufacturing Cost Estimator Calculator */}
              <BomCalculator key={selected.id} bom={selected} items={items} />
            </section>
          ) : (
            <div className="rounded-xl border border-dashed border-[var(--border)] p-8 text-center text-xs text-[var(--text-3)]">
              Select a BOM from the list to view components, standard costs, and run production cost calculations.
            </div>
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

type FlatMaterial = { itemId: string; itemCode?: string; itemName?: string; qty: number; uomSymbol?: string };

function consolidateMaterials(materials: FlatMaterial[]): FlatMaterial[] {
  const map = new Map<string, FlatMaterial>();
  for (const m of materials) {
    const key = `${m.itemId}::${m.uomSymbol ?? ""}`;
    const existing = map.get(key);
    if (existing) {
      existing.qty += m.qty;
    } else {
      map.set(key, { ...m });
    }
  }
  return Array.from(map.values());
}

function BomCalculator({ bom, items }: { bom: Bom; items: Item[] }) {
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
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "BOM specification fields could not be loaded."))
      .finally(() => setLoadingNames(false));
  }, [bom.itemId]);

  async function calculate() {
    setError("");
    setExplosion(null);
    setCalculating(true);
    try {
      const values = Object.fromEntries(names.map((name) => {
        const value = Number(specifications[name]);
        if (!Number.isFinite(value) || value < 0) {
          setCalculating(false);
          throw new Error(`Enter a non-negative number for ${name}.`);
        }
        return [name, value];
      }));
      const result = await api<Explosion>(`/api/v1/items/${bom.itemId}/bom/explode`, {
        method: "POST",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({ quantity: Number(quantity), specifications: values }),
      });
      setExplosion(result);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The BOM calculation could not be completed.");
    } finally {
      setCalculating(false);
    }
  }

  // Cost calculation based on exploded materials and configured rates
  const runQty = Number(quantity) || 1;
  const materialsWithCost = (explosion?.flatMaterials ?? []).map((m) => {
    const bomLine = bom.lines?.find((l) => l.componentItemId === m.itemId);
    const itemMaster = items.find((i) => i.id === m.itemId);
    const unitCost = bomLine?.unitCost != null ? Number(bomLine.unitCost) : (itemMaster?.standardCost != null ? Number(itemMaster.standardCost) : 0);
    const itemType = bomLine?.componentItemType || itemMaster?.itemType || "RAW_MATERIAL";
    const totalLineCost = Math.round(m.qty * unitCost * 100) / 100;
    return { ...m, unitCost, itemType, totalLineCost };
  });

  const explodedRawTotal = materialsWithCost
    .filter((m) => (m.itemType ?? "").toUpperCase() !== "CONSUMABLE")
    .reduce((sum, m) => sum + m.totalLineCost, 0);

  const explodedConsumableTotal = materialsWithCost
    .filter((m) => (m.itemType ?? "").toUpperCase() === "CONSUMABLE")
    .reduce((sum, m) => sum + m.totalLineCost, 0);

  const labourTotal = Math.round(Number(bom.labourCostPerUnit || 0) * runQty * 100) / 100;
  const machineTotal = Math.round(Number(bom.machineCostPerUnit || 0) * runQty * 100) / 100;
  const otherTotal = Math.round(Number(bom.otherCostPerUnit || 0) * runQty * 100) / 100;
  const totalManufacturingCost = Math.round((explodedRawTotal + explodedConsumableTotal + labourTotal + machineTotal + otherTotal) * 100) / 100;
  const estimatedCostPerUnit = runQty > 0 ? Math.round((totalManufacturingCost / runQty) * 100) / 100 : 0;

  return (
    <div className="mt-4 border-t border-[var(--border)] pt-4 space-y-3">
      <div>
        <h3 className="font-semibold text-sm">Calculate Estimated Manufacturing Costs</h3>
        <p className="text-xs text-[var(--text-2)]">Calculate material requirements and total manufacturing costs based on production quantity.</p>
      </div>

      <div className="grid gap-2 md:grid-cols-3">
        <label className="text-xs font-medium">Production quantity
          <input aria-label="Product quantity" className="eoss-input mt-1 w-full" type="number" min="0.001" step="0.001" value={quantity} onChange={(event) => setQuantity(event.target.value)} />
        </label>
        {names.map((name) => (
          <label key={name} className="text-xs font-medium">{name}
            <input aria-label={`Specification ${name}`} className="eoss-input mt-1 w-full" type="number" min="0" step="any" value={specifications[name] ?? ""} onChange={(event) => setSpecifications((current) => ({ ...current, [name]: event.target.value }))} />
          </label>
        ))}
      </div>

      <button type="button" aria-label="Calculate" className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} disabled={calculating || loadingNames || !Number.isFinite(Number(quantity)) || Number(quantity) <= 0} onClick={() => void calculate()}>{calculating ? "Calculating…" : "Calculate"}</button>
      {error && <p role="alert" className="mt-2 text-sm text-[var(--error)]">{error}</p>}

      {explosion && (
        <div className="space-y-3 mt-3">
          {/* Estimated Cost Breakdown for Production Quantity */}
          <div className="rounded-lg p-3 border border-[var(--brand)] text-xs space-y-2" style={{ background: "var(--surface-2)" }}>
            <div className="flex items-center justify-between pb-1.5 border-b border-[var(--border)]">
              <span className="font-bold text-xs">Estimated Manufacturing Cost ({runQty} units)</span>
              <span className="font-bold text-sm text-[var(--brand)]">Total: ₹{totalManufacturingCost.toFixed(2)} (₹{estimatedCostPerUnit.toFixed(2)}/unit)</span>
            </div>
            <div className="grid grid-cols-2 sm:grid-cols-3 gap-2">
              <div><span style={{ color: "var(--text-3)" }}>Raw Materials Cost</span><div className="font-semibold">₹{explodedRawTotal.toFixed(2)}</div></div>
              <div><span style={{ color: "var(--text-3)" }}>Consumables Cost</span><div className="font-semibold">₹{explodedConsumableTotal.toFixed(2)}</div></div>
              <div><span style={{ color: "var(--text-3)" }}>Labour Cost</span><div className="font-semibold">₹{labourTotal.toFixed(2)}</div></div>
              <div><span style={{ color: "var(--text-3)" }}>Machine Cost</span><div className="font-semibold">₹{machineTotal.toFixed(2)}</div></div>
              <div><span style={{ color: "var(--text-3)" }}>Other Manufacturing Cost</span><div className="font-semibold">₹{otherTotal.toFixed(2)}</div></div>
              <div><span style={{ color: "var(--text-3)" }}>Est. Unit Cost</span><div className="font-bold text-[var(--brand)]">₹{estimatedCostPerUnit.toFixed(2)}</div></div>
            </div>
          </div>

          <div>
            <p className="text-xs text-[var(--text-2)] mb-1">Calculated from BOM version {explosion.bomVersion ? (explosion.bomVersion.startsWith("v") ? explosion.bomVersion : `v${explosion.bomVersion}`) : "—"}:</p>
            <div className="overflow-x-auto rounded border border-[var(--border)]">
              <table className="w-full text-left text-xs">
                <thead style={{ background: "var(--surface-2)" }}>
                  <tr className="text-[var(--text-3)]"><th className="px-2 py-1.5">Material</th><th className="px-2 py-1.5">Type</th><th className="px-2 py-1.5">Req. Qty</th><th className="px-2 py-1.5">UOM</th><th className="px-2 py-1.5">Unit Cost</th><th className="px-2 py-1.5">Total Cost</th></tr>
                </thead>
                <tbody>
                  {consolidateMaterials(materialsWithCost).map((material) => (
                    <tr key={`${material.itemId}-${material.uomSymbol}`} className="border-t border-[var(--border)]">
                      <td className="px-2 py-1.5 font-medium">{material.itemCode} {material.itemName}</td>
                      <td className="px-2 py-1.5 capitalize text-[var(--text-3)]">{(material as { itemType?: string }).itemType?.toLowerCase().replace(/_/g, " ")}</td>
                      <td className="px-2 py-1.5 font-semibold">{Math.round(material.qty * 1000) / 1000}</td>
                      <td className="px-2 py-1.5">{material.uomSymbol}</td>
                      <td className="px-2 py-1.5">{(material as { unitCost?: number }).unitCost ? `₹${(material as { unitCost?: number }).unitCost!.toFixed(2)}` : "—"}</td>
                      <td className="px-2 py-1.5 font-mono font-semibold">{(material as { totalLineCost?: number }).totalLineCost ? `₹${(material as { totalLineCost?: number }).totalLineCost!.toFixed(2)}` : "—"}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

function isBomParent(item: Item) {
  const t = (item.itemType ?? "").toLowerCase();
  const isFinishedOrSemi = t === "finished_goods" || t === "finished_good" || t === "semi_finished";
  if (!isFinishedOrSemi) return false;
  const kind = (item as { productKind?: string }).productKind;
  if (kind === "TRADED") return false;
  return true;
}

function isBomComponent(itemType?: string) {
  const t = (itemType ?? "").toLowerCase();
  return t !== "finished_goods" && t !== "finished_good" && t !== "service" && t !== "spare_part" && t !== "other";
}

function BomForm({ items, uoms, onClose, onSaved }: { items: Item[]; uoms: Uom[]; onClose: () => void; onSaved: () => void }) {
  const parents = items.filter((item) => isBomParent(item));
  const [itemId, setItemId] = useState("");
  const [labourCostPerUnit, setLabourCostPerUnit] = useState("");
  const [machineCostPerUnit, setMachineCostPerUnit] = useState("");
  const [otherCostPerUnit, setOtherCostPerUnit] = useState("");
  const [lines, setLines] = useState<BomLine[]>([{ componentItemId: "", qty: "", uomId: uoms[0]?.id ?? "", scrapFactor: 0, unitCost: "" }]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  function update(index: number, patch: Partial<BomLine>) {
    setLines((current) => current.map((line, i) => i === index ? { ...line, ...patch } : line));
  }

  function pickComponent(index: number, componentItemId: string) {
    const item = items.find((item) => item.id === componentItemId);
    const defaultUomId = item?.uomId ?? uoms[0]?.id ?? "";
    const stdCost = item?.standardCost != null ? String(item.standardCost) : "";
    const itemType = (item?.itemType ?? "").toUpperCase() === "CONSUMABLE" ? "CONSUMABLE" : "RAW_MATERIAL";
    update(index, { componentItemId, uomId: defaultUomId, unitCost: stdCost, componentItemType: itemType });
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
    if (lines.some((line) => line.scrapFactor != null && (Number(line.scrapFactor) < 0 || Number(line.scrapFactor) > 100))) {
      setError("Scrap % must be between 0 and 100.");
      return;
    }
    setSaving(true);
    try {
      await api(`/api/v1/items/${itemId}/bom`, {
        method: "POST",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({
          labourCostPerUnit: labourCostPerUnit !== "" ? Number(labourCostPerUnit) : null,
          machineCostPerUnit: machineCostPerUnit !== "" ? Number(machineCostPerUnit) : null,
          otherCostPerUnit: otherCostPerUnit !== "" ? Number(otherCostPerUnit) : null,
          lines: lines.map((line) => ({
            componentItemId: line.componentItemId,
            qty: line.quantityFormula?.trim() ? (Number(line.qty) || 1) : Number(line.qty),
            uomId: line.uomId,
            scrapFactor: Math.min(100, Math.max(0, Number(line.scrapFactor || 0))),
            quantityFormula: line.quantityFormula?.trim() || null,
            unitCost: line.unitCost !== "" && line.unitCost != null ? Number(line.unitCost) : null,
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
    <RecordSheet
      title="Create bill of materials"
      subtitle="Define required raw materials, consumables, and manufacturing costs per finished unit."
      onClose={onClose}
      primary={{ label: saving ? "Saving…" : "Save and approve", onClick: () => void submit({ preventDefault: () => {} } as FormEvent), disabled: saving }}
      secondary={{ label: "Cancel", onClick: onClose }}
      width="xl"
    >
      {error && <p role="alert" className="mb-3 text-sm text-[var(--error)]">{error}</p>}
      <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
        Finished item *
        <select aria-label="Finished item" className="eoss-input w-full font-normal" value={itemId} onChange={(event) => setItemId(event.target.value)}>
          <option value="">Select finished item</option>
          {parents.map((item) => <option key={item.id} value={item.id}>{item.code} — {item.name}</option>)}
        </select>
        {parents.length === 0 && <p className="mt-1 text-xs text-[var(--error)]">No manufactured finished goods or semi-finished items found. Add items in the Items catalogue first.</p>}
      </label>

      <div className="mt-4 grid grid-cols-3 gap-3">
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Labour cost / unit (₹)
          <input aria-label="Labour cost per unit" className="eoss-input w-full font-normal" type="number" min="0" step="0.01" value={labourCostPerUnit} onChange={(event) => setLabourCostPerUnit(event.target.value)} placeholder="0.00" />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Machine cost / unit (₹)
          <input aria-label="Machine cost per unit" className="eoss-input w-full font-normal" type="number" min="0" step="0.01" value={machineCostPerUnit} onChange={(event) => setMachineCostPerUnit(event.target.value)} placeholder="0.00" />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Other standard cost / unit (₹)
          <input aria-label="Other cost per unit" className="eoss-input w-full font-normal" type="number" min="0" step="0.01" value={otherCostPerUnit} onChange={(event) => setOtherCostPerUnit(event.target.value)} placeholder="0.00" />
        </label>
      </div>

      <div className="mt-4 space-y-2">
        <div className="flex items-center justify-between">
          <p className="text-xs font-bold text-[var(--text-2)] uppercase tracking-wider">Required Materials &amp; Consumables</p>
          <span className="text-xs text-[var(--text-3)]">Add components required to manufacture 1 finished unit</span>
        </div>

        <div className="overflow-x-auto rounded-xl border border-[var(--border)]">
          <table className="w-full text-xs text-left">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr className="text-[var(--text-3)] font-semibold border-b border-[var(--border)]">
                <th className="px-3 py-2 min-w-[300px]">Component Item *</th>
                <th className="px-2 py-2 min-w-[90px]">Qty *</th>
                <th className="px-2 py-2 min-w-[90px]">UOM *</th>
                <th className="px-2 py-2 min-w-[110px]">Unit Cost (₹)</th>
                <th className="px-2 py-2 min-w-[90px]">Scrap %</th>
                <th className="px-2 py-2 min-w-[160px]">Formula (Optional)</th>
                <th className="px-2 py-2 w-8"></th>
              </tr>
            </thead>
            <tbody>
              {lines.map((line, index) => (
                <tr key={index} className="border-t border-[var(--border)] hover:bg-[var(--surface-2)]">
                  <td className="p-2 min-w-[300px] align-top">
                    <div className="flex flex-col gap-1.5">
                      <select aria-label={`Component ${index + 1}`} className="eoss-input w-full text-xs" value={line.componentItemId} onChange={(event) => pickComponent(index, event.target.value)}>
                        <option value="">Select component</option>
                        {items.filter((item) => item.id !== itemId && isBomComponent(item.itemType)).map((item) => (
                          <option key={item.id} value={item.id}>
                            {item.code} — {item.name}
                          </option>
                        ))}
                      </select>
                      {line.componentItemType && (
                        <div>
                          <span className="inline-block text-[10px] font-semibold uppercase px-2 py-0.5 rounded" style={{ background: line.componentItemType === "CONSUMABLE" ? "var(--warning)" : "var(--brand)", color: "#fff" }}>
                            {line.componentItemType === "CONSUMABLE" ? "Consumable" : "Raw Material"}
                          </span>
                        </div>
                      )}
                    </div>
                  </td>
                  <td className="p-2 min-w-[90px] align-top">
                    <input aria-label={`Quantity ${index + 1}`} className="eoss-input text-xs w-full" inputMode="decimal" value={line.qty} onChange={(event) => update(index, { qty: event.target.value })} placeholder="1.00" />
                  </td>
                  <td className="p-2 min-w-[90px] align-top">
                    <select aria-label={`Unit ${index + 1}`} className="eoss-input text-xs w-full" value={line.uomId} onChange={(event) => update(index, { uomId: event.target.value })}>
                      {uoms.map((uom) => <option key={uom.id} value={uom.id}>{uom.symbol}</option>)}
                    </select>
                  </td>
                  <td className="p-2 min-w-[110px] align-top">
                    <input aria-label={`Unit cost ${index + 1}`} className="eoss-input text-xs w-full" inputMode="decimal" value={line.unitCost ?? ""} onChange={(event) => update(index, { unitCost: event.target.value })} placeholder="Cost ₹" />
                  </td>
                  <td className="p-2 min-w-[90px] align-top">
                    <input aria-label={`Scrap % ${index + 1}`} className="eoss-input text-xs w-full" type="number" min="0" max="100" step="0.1" inputMode="decimal" value={line.scrapFactor} onChange={(event) => update(index, { scrapFactor: event.target.value })} placeholder="0" />
                  </td>
                  <td className="p-2 min-w-[160px] align-top">
                    <input aria-label={`Formula ${index + 1}`} className="eoss-input text-xs w-full" value={line.quantityFormula ?? ""} onChange={(event) => update(index, { quantityFormula: event.target.value })} placeholder="e.g. length * width" />
                  </td>
                  <td className="p-2 text-center align-top pt-2.5">
                    <button type="button" className="text-[var(--error)] font-bold text-sm hover:opacity-80 p-1" title="Remove component" onClick={() => setLines((current) => current.filter((_, i) => i !== index))}>✕</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
        <button
          type="button"
          className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-semibold text-[var(--brand)] bg-[var(--surface)] hover:bg-[var(--surface-2)] mt-3"
          style={{ borderColor: "var(--brand)" }}
          onClick={() => setLines((current) => [...current, { componentItemId: "", qty: "", uomId: uoms[0]?.id ?? "", scrapFactor: 0, unitCost: "" }])}
        >
          <Plus size={14} /> Add Another Material / Consumable Row
        </button>
      </div>
    </RecordSheet>
  );
}
