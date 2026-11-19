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
import { allPages, categoryName, mutate, type Category, type Item, type Uom } from "./api";
import StockNav from "./stockNav";

const TYPES = ["RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD", "CONSUMABLE", "SPARE_PART", "SERVICE"];
const TRACKING_MODES = ["None", "Lot", "Heat", "Serial"] as const;
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
      const text = !term || `${item.code} ${item.name} ${item.categoryLabel ?? ""}`.toLowerCase().includes(term);
      const state = (item.status ?? "active").toLowerCase();
      return text && (status === "all" || state === status);
    });
  }, [items, query, status]);

  const columns: RecordColumn<Item>[] = [
    { key: "code", label: "Item code", value: (row) => row.code, render: (row) => <span className="font-mono text-xs font-bold text-[var(--brand)]">{row.code}</span> },
    { key: "name", label: "Item name", value: (row) => row.name, render: (row) => row.name },
    { key: "category", label: "Category", value: (row) => row.categoryLabel ?? "", render: (row) => row.categoryLabel || "—" },
    { key: "type", label: "Type", value: (row) => row.itemType ?? "", render: (row) => (row.itemType ?? "").replaceAll("_", " ").toLowerCase() },
    { key: "uom", label: "UOM", value: (row) => row.uomSymbol ?? "", render: (row) => row.uomSymbol || "—" },
    { key: "status", label: "Status", value: (row) => row.status ?? "", render: (row) => row.status === "inactive" ? "Inactive" : "Active" },
    { key: "edit", label: "", value: () => "", render: (row) => canWrite ? <button type="button" className="text-xs font-semibold text-[var(--brand)]" onClick={(event) => { event.stopPropagation(); navigate(`/inventory/item/${row.id}?edit=1`); }}>Edit</button> : null },
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
          <RecordTable rows={rows} columns={columns} empty="No item master records match this filter." onRow={(row) => navigate(`/inventory/item/${row.id}`)} />
        )}
      </div>
      <p className="px-5 py-3 text-xs text-[var(--text-3)]">Item identity, code, category, UOM and lifecycle only. Use Stock on Hand for site, lot and quantity. Use Site Need for demand and shortages.</p>
      {open && <ItemForm onClose={() => setOpen(false)} onSaved={() => { setOpen(false); reload(); }} />}
    </div>
  );
}

export function ItemForm({ item, onClose, onSaved }: { item?: Item; onClose: () => void; onSaved: (item: Item) => void }) {
  const { user } = useCustomer();
  const canCreateReference = roleAllowed(user?.roles, ["OWNER", "OPS_MANAGER"]);
  const [uoms, setUoms] = useState<Uom[]>([]);
  const [categories, setCategories] = useState<Category[]>([]);
  const [code, setCode] = useState(item?.code ?? "");
  const [name, setName] = useState(item?.name ?? "");
  const [itemType, setItemType] = useState(item?.itemType ?? "RAW_MATERIAL");
  const [categoryId, setCategoryId] = useState(item?.categoryId ?? "");
  const [uomId, setUomId] = useState(item?.uomId ?? "");
  const [reorderPoint, setReorderPoint] = useState(item?.reorderPoint == null ? "" : String(item.reorderPoint));
  const [productKind, setProductKind] = useState((item as { productKind?: string })?.productKind ?? "");
  const [specifications, setSpecifications] = useState<SpecificationDraft[]>([]);
  const [error, setError] = useState("");
  const [categoryError, setCategoryError] = useState("");
  const [uomError, setUomError] = useState("");
  const [saving, setSaving] = useState(false);

  function loadReferences() {
    setCategoryError("");
    setUomError("");
    api<Uom[] | { content?: Uom[] }>("/api/v1/uoms").then((units) => {
      const list = Array.isArray(units) ? units : units.content ?? [];
      setUoms(list);
      setUomId((current) => current || list[0]?.id || "");
    }).catch((reason: unknown) => setUomError(reason instanceof Error ? reason.message : "Units of measure could not be loaded."));
    api<Category[] | { content?: Category[] }>("/api/v1/config/options/ITEM_CATEGORY").then((cats) => {
      const list = Array.isArray(cats) ? cats : cats.content ?? [];
      setCategories(list.filter((category) => category.id));
    }).catch((reason: unknown) => setCategoryError(reason instanceof Error ? reason.message : "Categories could not be loaded."));
  }

  useEffect(() => { loadReferences(); }, []);

  async function save() {
    if (!name.trim() || (!item && (!code.trim() || !uomId))) {
      setError(item ? "Name is required." : "Item code *, Name * and Unit of measure * are required.");
      return;
    }
    setSaving(true);
    setError("");
    const category = categories.find((row) => row.id === categoryId);
    try {
      const specs = specifications.filter((s) => s.key.trim() && s.label.trim());
      const saved = item
        ? await mutate<Item>(`/api/v1/items/${item.id}`, "PATCH", {
          name: name.trim(),
          categoryId: categoryId || null,
          categoryLabel: category ? categoryName(category) : undefined,
          reorderPoint: reorderPoint === "" ? null : Number(reorderPoint),
        })
        : await mutate<Item>("/api/v1/items", "POST", {
          code: code.trim(),
          name: name.trim(),
          itemType,
          categoryId: categoryId || null,
          categoryLabel: category ? categoryName(category) : undefined,
          uomId,
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

  return (
    <RecordSheet
      title={item ? "Edit item" : "Add item"}
      onClose={onClose}
      primary={{ label: saving ? "Saving…" : item ? "Save changes" : "Create item", onClick: () => void save(), disabled: saving }}
      secondary={{ label: "Cancel", onClick: onClose }}
    >
      <div className="grid gap-3 sm:grid-cols-2">
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Item Code *</span>
          <input aria-label="Item code" className="eoss-input mt-1 w-full" placeholder="e.g. RM-001" value={code} disabled={!!item} onChange={(event) => setCode(event.target.value)} />
        </label>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Item Name *</span>
          <input aria-label="Item name *" className="eoss-input mt-1 w-full" placeholder="e.g. Hot Rolled Steel Sheet" value={name} onChange={(event) => setName(event.target.value)} />
        </label>
        {!item && (
          <label className="text-xs">
            <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Type</span>
            <select aria-label="Item type" className="eoss-input mt-1 w-full" value={itemType} onChange={(event) => setItemType(event.target.value)}>
              {TYPES.map((type) => <option key={type} value={type}>{type.replaceAll("_", " ")}</option>)}
            </select>
          </label>
        )}
        <div className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Category</span>
          <CreatableSelect label="category" ariaLabel="Category" value={categoryId} onChange={setCategoryId} placeholder="No category" error={categoryError} onRetry={loadReferences} creatable={canCreateReference} options={categories.map((category) => ({ id: category.id, label: categoryName(category) || "Unnamed category" }))} fields={[{ name: "code", label: "Code", required: true }, { name: "name", label: "Name", required: true }]} unique={(values) => categories.some((category) => (category.code ?? "").toLowerCase() === values.code.toLowerCase()) ? "This value already exists." : null} onCreate={async (values) => {
            const created = await mutate<Category>("/api/v1/item-categories", "POST", { code: values.code, name: values.name });
            setCategories((current) => [...current.filter((category) => category.id !== created.id), { ...created, label: created.name }]);
            return { id: created.id, label: created.name || values.name };
          }} />
        </div>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Grade / Specification</span>
          <input className="eoss-input mt-1 w-full" placeholder="e.g. IS 2062 Grade A" disabled title="Not stored yet" />
        </label>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Dimensions</span>
          <input className="eoss-input mt-1 w-full" placeholder="e.g. 2440 × 1220 × 3 mm" disabled title="Not stored yet" />
        </label>
        <div className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Unit of Measure *</span>
          <CreatableSelect label="unit of measure" ariaLabel="Unit of measure" value={uomId} onChange={setUomId} disabled={!!item} error={uomError} onRetry={loadReferences} creatable={canCreateReference && !item} options={uoms.map((uom) => ({ id: uom.id, label: `${uom.symbol} · ${uom.name}` }))} fields={[{ name: "name", label: "Name", required: true }, { name: "symbol", label: "Symbol", required: true }, { name: "uomType", label: "Type (quantity / weight / volume / length / area / time)", options: [{ id: "quantity", label: "Quantity" }, { id: "weight", label: "Weight" }, { id: "volume", label: "Volume" }, { id: "length", label: "Length" }, { id: "area", label: "Area" }, { id: "time", label: "Time" }, { id: "energy", label: "Energy" }, { id: "other", label: "Other" }], required: true }]} unique={(values) => uoms.some((uom) => uom.symbol.toLowerCase() === values.symbol.toLowerCase()) ? "This value already exists." : null} onCreate={async (values) => {
            const created = await mutate<Uom>("/api/v1/uoms", "POST", { name: values.name, symbol: values.symbol, uomType: values.uomType || "quantity" });
            setUoms((current) => [...current.filter((uom) => uom.id !== created.id), created]);
            return { id: created.id, label: `${created.symbol} · ${created.name}` };
          }} />
        </div>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Reorder Level</span>
          <input className="eoss-input mt-1 w-full" type="number" min="0" placeholder="e.g. 100" value={reorderPoint} onChange={(event) => setReorderPoint(event.target.value)} />
        </label>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Lead Time (days)</span>
          <input className="eoss-input mt-1 w-full" type="number" min="0" placeholder="e.g. 14" disabled title="Not stored yet" />
        </label>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Site</span>
          <input className="eoss-input mt-1 w-full" placeholder="e.g. Main Plant" disabled title="Not stored yet" />
        </label>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Store</span>
          <input className="eoss-input mt-1 w-full" placeholder="e.g. Raw Materials Store" disabled title="Not stored yet" />
        </label>
        <label className="text-xs">
          <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Tracking Mode</span>
          <select className="eoss-input mt-1 w-full" disabled title="Not stored yet">
            {TRACKING_MODES.map((mode) => <option key={mode}>{mode}</option>)}
          </select>
        </label>
      </div>
      {!item && itemType === "FINISHED_GOOD" && (
        <fieldset className="mt-3 rounded-lg border p-3 text-xs" style={{ borderColor: "var(--border)" }}>
          <legend className="px-1 font-semibold">Product kind &amp; specifications</legend>
          <label className="mt-2 block">
            <span className="block text-[11px] font-mono uppercase text-[var(--text-3)] mb-1">Product kind</span>
            <select aria-label="Product kind" className="eoss-input w-full" value={productKind} onChange={(e) => setProductKind(e.target.value)}>
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
            <button type="button" className="text-xs font-semibold text-[var(--brand)]" onClick={() => setSpecifications((prev) => [...prev, { key: "", label: "", uomId: "", minValue: "", maxValue: "", defaultValue: "" }])}>Add specification</button>
          </div>
        </fieldset>
      )}
      {error && <p role="alert" className="mt-3 text-xs text-[var(--error)]">{error}</p>}
    </RecordSheet>
  );
}
