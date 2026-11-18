import { useEffect, useMemo, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Plus } from "lucide-react";
import { api } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import RecordTable, { type RecordColumn } from "../components/shared/RecordTable";
import { allPages, categoryName, mutate, type Category, type Item, type Uom } from "./api";
import StockNav from "./stockNav";

const TYPES = ["RAW_MATERIAL", "SEMI_FINISHED", "FINISHED_GOOD", "CONSUMABLE", "SPARE_PART", "SERVICE"];

export default function ItemMaster() {
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
  ];

  return (
    <div className="flex min-h-[calc(100vh-var(--topbar-h))] flex-col">
      <StockNav />
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
        {canWrite && <button type="button" onClick={() => setOpen(true)} className="inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}><Plus size={13} /> Add material</button>}
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
  const [description, setDescription] = useState(item?.description ?? "");
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
      setError(item ? "Name is required." : "Item code, name, and unit of measure are required.");
      return;
    }
    setSaving(true);
    setError("");
    const category = categories.find((row) => row.id === categoryId);
    try {
      const saved = item
        ? await mutate<Item>(`/api/v1/items/${item.id}`, "PATCH", {
          name: name.trim(),
          description: description.trim() || null,
          categoryId: categoryId || null,
          categoryLabel: category ? categoryName(category) : undefined,
          reorderPoint: reorderPoint === "" ? null : Number(reorderPoint),
        })
        : await mutate<Item>("/api/v1/items", "POST", {
          code: code.trim(),
          name: name.trim(),
          description: description.trim() || null,
          itemType,
          categoryId: categoryId || null,
          categoryLabel: category ? categoryName(category) : undefined,
          uomId,
          reorderPoint: reorderPoint === "" ? null : Number(reorderPoint),
        });
      onSaved(saved);
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "The item was not saved.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/60 p-4">
      <div className="w-full max-w-lg space-y-3 rounded-xl border p-5" style={{ background: "var(--surface)", borderColor: "var(--border)" }}>
        <h2 className="text-sm font-bold">{item ? "Edit material" : "Add material"}</h2>
        <div className="grid gap-3 sm:grid-cols-2">
          <label className="text-xs">Item code<input className="eoss-input mt-1 w-full" value={code} disabled={!!item} onChange={(event) => setCode(event.target.value)} /></label>
          <label className="text-xs">Material name *<input className="eoss-input mt-1 w-full" value={name} onChange={(event) => setName(event.target.value)} /></label>
          {!item && (
            <label className="text-xs">Type<select aria-label="Item type" className="eoss-input mt-1 w-full" value={itemType} onChange={(event) => setItemType(event.target.value)}>{TYPES.map((type) => <option key={type} value={type}>{type.replaceAll("_", " ")}</option>)}</select></label>
          )}
          <div className="text-xs">Category
            <CreatableSelect label="category" ariaLabel="Category" value={categoryId} onChange={setCategoryId} placeholder="No category" error={categoryError} onRetry={loadReferences} creatable={canCreateReference} options={categories.map((category) => ({ id: category.id, label: categoryName(category) || "Unnamed category" }))} fields={[{ name: "code", label: "Code", required: true }, { name: "name", label: "Name", required: true }]} unique={(values) => categories.some((category) => (category.code ?? "").toLowerCase() === values.code.toLowerCase()) ? "This value already exists." : null} onCreate={async (values) => {
              const created = await mutate<Category>("/api/v1/item-categories", "POST", { code: values.code, name: values.name });
              setCategories((current) => [...current.filter((category) => category.id !== created.id), { ...created, label: created.name }]);
              return { id: created.id, label: created.name || values.name };
            }} />
          </div>
          <div className="text-xs">Unit of measure
            <CreatableSelect label="unit of measure" ariaLabel="Unit of measure" value={uomId} onChange={setUomId} disabled={!!item} error={uomError} onRetry={loadReferences} creatable={canCreateReference && !item} options={uoms.map((uom) => ({ id: uom.id, label: `${uom.symbol} · ${uom.name}` }))} fields={[{ name: "name", label: "Name", required: true }, { name: "symbol", label: "Symbol", required: true }]} unique={(values) => uoms.some((uom) => uom.symbol.toLowerCase() === values.symbol.toLowerCase()) ? "This value already exists." : null} onCreate={async (values) => {
              const created = await mutate<Uom>("/api/v1/uoms", "POST", { name: values.name, symbol: values.symbol, uomType: "quantity" });
              setUoms((current) => [...current.filter((uom) => uom.id !== created.id), created]);
              return { id: created.id, label: `${created.symbol} · ${created.name}` };
            }} />
          </div>
          <label className="text-xs">Reorder level<input className="eoss-input mt-1 w-full" type="number" min="0" value={reorderPoint} onChange={(event) => setReorderPoint(event.target.value)} /></label>
          <label className="text-xs sm:col-span-2">Description<textarea className="eoss-input mt-1 w-full" rows={2} value={description} onChange={(event) => setDescription(event.target.value)} /></label>
        </div>
        {error && <p role="alert" className="text-xs text-[var(--error)]">{error}</p>}
        <div className="flex justify-end gap-2">
          <button type="button" onClick={onClose} className="text-xs">Cancel</button>
          <button type="button" disabled={saving} onClick={() => void save()} className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50" style={{ background: "var(--brand-fill)" }}>{saving ? "Saving…" : item ? "Save changes" : "Create material"}</button>
        </div>
      </div>
    </div>
  );
}
