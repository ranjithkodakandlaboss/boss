import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Plus } from "lucide-react";
import { api } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordSheet from "../components/shared/RecordSheet";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import { allPages, mutate, type Item, type Uom } from "./api";
import StockNav from "./stockNav";

const TYPES = ["RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD", "CONSUMABLE", "SPARE_PART", "SERVICE"];
type SpecificationDraft = { key: string; label: string; uomId: string; minValue: string; maxValue: string; defaultValue: string };

export default function ItemMaster({ embedded = false }: { embedded?: boolean }) {
  const navigate = useNavigate();
  const { user } = useCustomer();
  const canWrite = (user?.roles ?? []).some((role) => ["OWNER", "OPS_MANAGER", "STORE_KEEPER", "PURCHASE_MANAGER"].includes(role.role));
  const [items, setItems] = useState<Item[]>([]);
  const [query, setQuery] = useState("");
  const [status, setStatus] = useState<"all" | "active" | "inactive">("all");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [open, setOpen] = useState(false);
  const [editItem, setEditItem] = useState<Item | null>(null);
  const [viewItem, setViewItem] = useState<Item | null>(null);

  function reload() {
    setLoading(true);
    Promise.all([
      allPages<Item>("/api/v1/items?status=active"),
      allPages<Item>("/api/v1/items?status=inactive"),
    ]).then(([active, inactive]) => setItems([...active, ...inactive]))
      .catch((reason: unknown) => setError(reason instanceof Error ? reason.message : "Items could not be loaded."))
      .finally(() => setLoading(false));
  }

  useEffect(() => { reload(); }, []);

  const rows = useMemo(() => {
    const term = query.trim().toLowerCase();
    return items.filter((item) => {
      const text = !term || `${item.code} ${item.name} ${item.itemType ?? ""}`.toLowerCase().includes(term);
      const state = (item.status ?? "active").toLowerCase();
      return text && (status === "all" || state === status);
    });
  }, [items, query, status]);

  const columns: RecordColumn<Item>[] = [
    { key: "code", label: "Item code", value: (row) => row.code, render: (row) => <span className="font-mono text-xs font-bold text-[var(--brand)]">{row.code}</span> },
    { key: "name", label: "Item name", value: (row) => row.name, render: (row) => row.name },
    { key: "type", label: "Type", value: (row) => row.itemType ?? "", render: (row) => (row.itemType ?? "").replaceAll("_", " ").toLowerCase() },
    { key: "uom", label: "UOM", value: (row) => row.uomSymbol ?? "", render: (row) => row.uomSymbol || "—" },
    { key: "cost", label: "Std cost", value: (row) => row.standardCost != null ? `₹${row.standardCost}` : "—", render: (row) => row.standardCost != null ? `₹${Number(row.standardCost).toFixed(2)}` : "—" },
    { key: "status", label: "Status", value: (row) => row.status ?? "", render: (row) => row.status === "inactive" ? "Inactive" : "Active" },
    { key: "edit", label: "", value: () => "", render: (row) => canWrite ? <button type="button" className="text-xs font-semibold text-[var(--brand)]" onClick={(event) => { event.stopPropagation(); setEditItem(row); }}>Edit</button> : null },
  ];

  return (
    <div className={embedded ? "flex flex-col" : "flex min-h-[calc(100vh-var(--topbar-h))] flex-col"}>
      {!embedded && <StockNav />}
      <header className="flex flex-wrap items-center gap-3 px-5 py-3">
        <div>
          <h1 className="text-lg font-bold text-[var(--text)]">Item Master</h1>
          <p className="text-xs text-[var(--text-2)]">The canonical catalogue of materials and grades. Physical stock belongs in Stock on Hand.</p>
        </div>
        <div className="flex-1" />
        <input aria-label="Search items" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Search code, item, grade…" className="eoss-input w-64 text-sm" />
        <select aria-label="Item status" value={status} onChange={(event) => setStatus(event.target.value as typeof status)} className="eoss-input text-xs">
          <option value="all">All statuses</option>
          <option value="active">Active</option>
          <option value="inactive">Inactive</option>
        </select>
        {canWrite && <button type="button" onClick={() => setOpen(true)} className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}><Plus size={13} /> Add item</button>}
      </header>
      <div className="min-h-0 flex-1 overflow-auto">
        {loading ? <LoadingState label="Loading item master" /> : error ? <ErrorState body={error} /> : (
          <RecordTable
            rows={rows}
            columns={columns}
            empty="No item master records match this filter."
            onRow={(row) => {
              if (embedded) {
                setViewItem(row);
              } else {
                navigate(`/inventory/item/${row.id}`);
              }
            }}
          />
        )}
      </div>
      <p className="px-5 py-3 text-xs text-[var(--text-3)]">Item identity, code, type, UOM and lifecycle only. Use Stock on Hand for site, lot and quantity. Use Site Need for demand and shortages.</p>
      {open && <ItemForm onClose={() => setOpen(false)} onSaved={() => { setOpen(false); reload(); }} />}
      {editItem && <ItemForm item={editItem} onClose={() => setEditItem(null)} onSaved={() => { setEditItem(null); reload(); }} />}
      {viewItem && (
        <RecordSheet
          title={`${viewItem.code} · ${viewItem.name}`}
          subtitle="Master Data Configuration"
          onClose={() => setViewItem(null)}
          primary={canWrite ? { label: "Edit item", onClick: () => { const target = viewItem; setViewItem(null); setEditItem(target); } } : undefined}
          secondary={{ label: "Close", onClick: () => setViewItem(null) }}
          width="md"
        >
          <div className="space-y-4 text-sm">
            <div className="rounded-xl border p-4 space-y-3" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
              <div className="grid grid-cols-2 gap-3">
                <div><span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Item Code</span><div className="font-mono font-bold text-[var(--brand)]">{viewItem.code}</div></div>
                <div><span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Item Name</span><div className="font-semibold">{viewItem.name}</div></div>
                <div><span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Type</span><div className="capitalize">{(viewItem.itemType ?? "").replaceAll("_", " ").toLowerCase()}</div></div>
                <div><span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Unit of Measure</span><div>{viewItem.uomSymbol || "—"}</div></div>
                <div><span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Standard Cost / Price</span><div className="font-semibold">{viewItem.standardCost != null ? `₹${Number(viewItem.standardCost).toFixed(2)}` : "—"}</div></div>
                <div><span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Reorder Level</span><div>{viewItem.reorderPoint != null ? String(viewItem.reorderPoint) : "—"}</div></div>
                <div><span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Status</span><div className="capitalize">{viewItem.status || "active"}</div></div>
              </div>
            </div>

            {viewItem.itemType === "FINISHED_GOOD" && (
              <fieldset className="rounded-xl border p-4 text-sm space-y-3" style={{ borderColor: "var(--border)" }}>
                <legend className="px-1 text-sm font-semibold">Product kind &amp; specifications</legend>
                <div>
                  <span className="text-xs font-medium" style={{ color: "var(--text-3)" }}>Product Kind</span>
                  <div className="font-semibold">{viewItem.productKind || "Not specified"}</div>
                </div>
                {(viewItem.specifications ?? []).length > 0 ? (
                  <table className="w-full text-xs text-left">
                    <thead><tr className="border-b" style={{ borderColor: "var(--border)", color: "var(--text-3)" }}><th>Key</th><th>Label</th><th>Min</th><th>Default</th><th>Max</th></tr></thead>
                    <tbody>
                      {(viewItem.specifications ?? []).map((s) => (
                        <tr key={s.key} className="border-b" style={{ borderColor: "var(--border)" }}>
                          <td className="py-1 font-mono">{s.key}</td>
                          <td>{s.label}</td>
                          <td>{s.minValue != null ? String(s.minValue) : "—"}</td>
                          <td>{s.defaultValue != null ? String(s.defaultValue) : "—"}</td>
                          <td>{s.maxValue != null ? String(s.maxValue) : "—"}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                ) : (
                  <p className="text-xs" style={{ color: "var(--text-3)" }}>No product specifications configured.</p>
                )}
              </fieldset>
            )}

            <p className="text-xs" style={{ color: "var(--text-3)" }}>This view displays purely the configured master attributes for this item. Operational stock levels, lot allocations, and BOM where-used linkages are tracked in operational modules.</p>
          </div>
        </RecordSheet>
      )}
    </div>
  );
}

export function ItemForm({ item, onClose, onSaved, embedded = false }: { item?: Item; onClose: () => void; onSaved: (item: Item) => void; embedded?: boolean }) {
  const { user } = useCustomer();
  const canCreateReference = roleAllowed(user?.roles, ["OWNER", "OPS_MANAGER"]);
  const [uoms, setUoms] = useState<Uom[]>([]);
  const [code, setCode] = useState(item?.code ?? "");
  const [name, setName] = useState(item?.name ?? "");
  const [itemType, setItemType] = useState(item?.itemType ?? "RAW_MATERIAL");
  const [uomId, setUomId] = useState(item?.uomId ?? "");
  const [reorderPoint, setReorderPoint] = useState(item?.reorderPoint == null ? "" : String(item.reorderPoint));
  const [standardCost, setStandardCost] = useState(item?.standardCost == null ? "" : String(item.standardCost));
  const [productKind, setProductKind] = useState((item as { productKind?: string })?.productKind ?? "");
  const [specifications, setSpecifications] = useState<SpecificationDraft[]>(
    (item?.specifications ?? []).map((s) => ({
      key: s.key,
      label: s.label,
      uomId: s.uomId ?? "",
      minValue: s.minValue == null ? "" : String(s.minValue),
      maxValue: s.maxValue == null ? "" : String(s.maxValue),
      defaultValue: s.defaultValue == null ? "" : String(s.defaultValue),
    }))
  );
  const [error, setError] = useState("");
  const [uomError, setUomError] = useState("");
  const [saving, setSaving] = useState(false);

  function loadReferences() {
    setUomError("");
    api<Uom[] | { content?: Uom[] }>("/api/v1/uoms").then((units) => {
      const list = Array.isArray(units) ? units : units.content ?? [];
      setUoms(list);
      setUomId((current) => current || list[0]?.id || "");
    }).catch((reason: unknown) => setUomError(reason instanceof Error ? reason.message : "Units of measure could not be loaded."));
  }

  useEffect(() => { loadReferences(); }, []);

  async function save() {
    if (!name.trim() || (!item && (!code.trim() || !uomId))) {
      setError(item ? "Name is required." : "Item code *, Name * and Unit of measure * are required.");
      return;
    }
    setSaving(true);
    setError("");
    try {
      const specs = specifications.filter((s) => s.key.trim() && s.label.trim());
      const saved = item
        ? await mutate<Item>(`/api/v1/items/${item.id}`, "PATCH", {
          name: name.trim(),
          standardCost: standardCost === "" ? null : Number(standardCost),
          reorderPoint: reorderPoint === "" ? null : Number(reorderPoint),
          ...(item.itemType === "FINISHED_GOOD" ? { productKind: productKind || null, specifications: specs.map((s) => ({ key: s.key.trim(), label: s.label.trim(), uomId: s.uomId || null, minValue: s.minValue === "" ? null : Number(s.minValue), maxValue: s.maxValue === "" ? null : Number(s.maxValue), defaultValue: s.defaultValue === "" ? null : Number(s.defaultValue) })) } : {}),
        })
        : await mutate<Item>("/api/v1/items", "POST", {
          code: code.trim(),
          name: name.trim(),
          itemType,
          uomId,
          standardCost: standardCost === "" ? null : Number(standardCost),
          reorderPoint: reorderPoint === "" ? null : Number(reorderPoint),
          ...(itemType === "FINISHED_GOOD" ? { productKind: productKind || null, specifications: specs.map((s) => ({ key: s.key.trim(), label: s.label.trim(), uomId: s.uomId || null, minValue: s.minValue === "" ? null : Number(s.minValue), maxValue: s.maxValue === "" ? null : Number(s.maxValue), defaultValue: s.defaultValue === "" ? null : Number(s.defaultValue) })) } : {}),
        });
      onSaved(saved);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The item was not saved.");
    } finally {
      setSaving(false);
    }
  }

  const formBody = (
    <>
    <div className="grid gap-4 sm:grid-cols-2">
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Item code *
          <input aria-label="Item code" className="eoss-input w-full font-normal" placeholder="e.g. RM-001" value={code} disabled={!!item} onChange={(event) => setCode(event.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Item name *
          <input aria-label="Item name *" className="eoss-input w-full font-normal" placeholder="e.g. Hot Rolled Steel Sheet" value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Type
          {item ? (
            <input className="eoss-input w-full font-normal capitalize" disabled value={(item.itemType ?? "").replaceAll("_", " ").toLowerCase()} />
          ) : (
            <select aria-label="Item type" className="eoss-input w-full font-normal" value={itemType} onChange={(event) => setItemType(event.target.value)}>
              {TYPES.map((type) => <option key={type} value={type}>{type.replaceAll("_", " ")}</option>)}
            </select>
          )}
        </label>
        <div className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Unit of measure *
          <CreatableSelect label="unit of measure" ariaLabel="Unit of measure" value={uomId} onChange={setUomId} disabled={!!item} error={uomError} onRetry={loadReferences} creatable={canCreateReference && !item} options={uoms.map((uom) => ({ id: uom.id, label: `${uom.symbol} · ${uom.name}` }))} fields={[{ name: "name", label: "Name", required: true }, { name: "symbol", label: "Symbol", required: true }, { name: "uomType", label: "Type (quantity / weight / volume / length / area / time)", options: [{ id: "quantity", label: "Quantity" }, { id: "weight", label: "Weight" }, { id: "volume", label: "Volume" }, { id: "length", label: "Length" }, { id: "area", label: "Area" }, { id: "time", label: "Time" }, { id: "energy", label: "Energy" }, { id: "other", label: "Other" }], required: true }]} unique={(values) => uoms.some((uom) => uom.symbol.toLowerCase() === values.symbol.toLowerCase()) ? "This value already exists." : null} onCreate={async (values) => {
            const created = await mutate<Uom>("/api/v1/uoms", "POST", { name: values.name, symbol: values.symbol, uomType: values.uomType || "quantity" });
            setUoms((current) => [...current.filter((uom) => uom.id !== created.id), created]);
            return { id: created.id, label: `${created.symbol} · ${created.name}` };
          }} />
        </div>
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Standard cost / price (₹)
          <input className="eoss-input w-full font-normal" type="number" min="0" step="0.01" placeholder="e.g. 150.00" value={standardCost} onChange={(event) => setStandardCost(event.target.value)} />
        </label>
        <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
          Reorder level
          <input className="eoss-input w-full font-normal" type="number" min="0" placeholder="e.g. 100" value={reorderPoint} onChange={(event) => setReorderPoint(event.target.value)} />
        </label>
      </div>
      {((!item && itemType === "FINISHED_GOOD") || (item && item.itemType === "FINISHED_GOOD")) && (
        <fieldset className="mt-4 rounded-lg border p-4 text-sm" style={{ borderColor: "var(--border)" }}>
          <legend className="px-1 text-sm font-semibold">Product kind &amp; specifications</legend>
          <label className="mt-2 flex flex-col gap-1 font-medium text-[var(--text)]">
            Product kind
            <select aria-label="Product kind" className="eoss-input w-full font-normal" value={productKind} onChange={(e) => setProductKind(e.target.value)}>
              <option value="">Not specified</option>
              <option value="MANUFACTURED">Manufactured</option>
              <option value="TRADED">Traded</option>
            </select>
          </label>
          <div className="mt-3 space-y-2">
            {specifications.map((spec, i) => (
              <div key={i} className="grid grid-cols-6 gap-1 items-end">
                <input aria-label={`Specification ${i + 1} key`} className="eoss-input col-span-1" placeholder="Key" value={spec.key} onChange={(e) => setSpecifications((prev) => prev.map((s, j) => j === i ? { ...s, key: e.target.value } : s))} />
                <input aria-label={`Specification ${i + 1} label`} className="eoss-input col-span-1" placeholder="Label" value={spec.label} onChange={(e) => setSpecifications((prev) => prev.map((s, j) => j === i ? { ...s, label: e.target.value } : s))} />
                <select aria-label={`Specification ${i + 1} unit`} className="eoss-input col-span-1" value={spec.uomId} onChange={(e) => setSpecifications((prev) => prev.map((s, j) => j === i ? { ...s, uomId: e.target.value } : s))}>
                  <option value="">—</option>
                  {uoms.map((uom) => <option key={uom.id} value={uom.id}>{uom.symbol}</option>)}
                </select>
                <input aria-label={`Specification ${i + 1} minValue`} className="eoss-input col-span-1" placeholder="Min" type="number" value={spec.minValue} onChange={(e) => setSpecifications((prev) => prev.map((s, j) => j === i ? { ...s, minValue: e.target.value } : s))} />
                <input aria-label={`Specification ${i + 1} defaultValue`} className="eoss-input col-span-1" placeholder="Default" type="number" value={spec.defaultValue} onChange={(e) => setSpecifications((prev) => prev.map((s, j) => j === i ? { ...s, defaultValue: e.target.value } : s))} />
                <button type="button" className="text-[var(--error)] font-bold" onClick={() => setSpecifications((prev) => prev.filter((_, j) => j !== i))}>✕</button>
              </div>
            ))}
            <button type="button" className="text-xs font-semibold text-[var(--brand)]" onClick={() => setSpecifications((prev) => [...prev, { key: "", label: "", uomId: "", minValue: "", maxValue: "", defaultValue: "" }])}>+ Add specification</button>
          </div>
        </fieldset>
      )}
      {error && <p role="alert" className="mt-3 text-xs text-[var(--error)]">{error}</p>}
    </>
  );

  if (embedded) {
    return (
      <div className="flex flex-col h-full">
        <header className="flex items-center justify-between px-5 py-4 border-b" style={{ borderColor: "var(--border)" }}>
          <h2 className="text-sm font-semibold">{item ? "Edit item" : "Add item"}</h2>
        </header>
        <div className="flex-1 overflow-auto px-5 py-4 space-y-4">{formBody}</div>
        <footer className="flex items-center justify-end gap-2 px-5 py-3 border-t" style={{ borderColor: "var(--border)" }}>
          <button type="button" className="rounded-lg border px-4 py-2 text-sm" style={{ borderColor: "var(--border)" }} onClick={onClose}>Cancel</button>
          <button type="button" disabled={saving} className="rounded-lg px-4 py-2 text-sm font-semibold text-white disabled:opacity-40" style={{ background: "var(--brand-fill)" }} onClick={() => void save()}>{saving ? "Saving…" : item ? "Save changes" : "Create item"}</button>
        </footer>
      </div>
    );
  }

  return (
    <RecordSheet
      title={item ? "Edit item" : "Add item"}
      onClose={onClose}
      primary={{ label: saving ? "Saving…" : item ? "Save changes" : "Create item", onClick: () => void save(), disabled: saving }}
      secondary={{ label: "Cancel", onClick: onClose }}
    >
      {formBody}
    </RecordSheet>
  );
}
