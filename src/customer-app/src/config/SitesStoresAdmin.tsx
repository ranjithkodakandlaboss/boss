import { useEffect, useState, type FormEvent } from "react";
import { Plus, Pencil, Trash2, Eye, Building2, Warehouse } from "lucide-react";
import { api, ApiError } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import Button from "../components/shared/Button";
import ConfirmDialog from "../components/shared/ConfirmDialog";
import RecordSheet from "../components/shared/RecordSheet";

export interface SiteRecord {
  id: string;
  name: string;
  code: string;
  siteType?: string;
  city?: string;
  state?: string;
  addressLine?: string;
}

export interface StoreRecord {
  id: string;
  siteId: string;
  siteCode: string;
  name: string;
  code: string;
  storeType: string;
}

const SITE_TYPES = [
  { value: "factory", label: "Factory / Manufacturing Plant" },
  { value: "warehouse", label: "Warehouse / Distribution Centre" },
  { value: "office", label: "Office / Headquarters" },
  { value: "depot", label: "Depot / Storage" },
  { value: "other", label: "Other Site" },
];

const STORE_TYPES = [
  { value: "raw_material", label: "Raw Materials" },
  { value: "finished_goods", label: "Finished Goods" },
  { value: "work_in_progress", label: "Work In Progress (WIP)" },
  { value: "consumable", label: "Consumables & Spares" },
  { value: "tool", label: "Tools & Fixtures" },
  { value: "rejected", label: "Rejected / Defective Stock" },
  { value: "quarantine", label: "Quality Inspection / Quarantine" },
];

export default function SitesStoresAdmin() {
  const { user } = useCustomer();
  const canManage = Boolean(user?.roles.some(({ role }) => role === "OWNER"));

  const [activeTab, setActiveTab] = useState<"sites" | "stores">("sites");
  const [sites, setSites] = useState<SiteRecord[]>([]);
  const [stores, setStores] = useState<StoreRecord[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  // Site Modal state
  const [siteModalOpen, setSiteModalOpen] = useState(false);
  const [editingSite, setEditingSite] = useState<SiteRecord | null>(null);
  const [viewingSite, setViewingSite] = useState<SiteRecord | null>(null);
  const [siteForm, setSiteForm] = useState({ name: "", code: "", siteType: "factory", addressLine: "", city: "", state: "" });

  // Store Modal state
  const [storeModalOpen, setStoreModalOpen] = useState(false);
  const [editingStore, setEditingStore] = useState<StoreRecord | null>(null);
  const [storeForm, setStoreForm] = useState({ siteCode: "", name: "", code: "", storeType: "raw_material" });
  const [deleteConfirmStore, setDeleteConfirmStore] = useState<StoreRecord | null>(null);

  async function loadData() {
    setLoading(true);
    setError("");
    try {
      const company = await api<{ sites?: SiteRecord[]; stores?: StoreRecord[] }>("/api/v1/company");
      setSites(company.sites ?? []);
      setStores(company.stores ?? []);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Failed to load sites and stores.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadData();
  }, []);

  function openAddSite() {
    setEditingSite(null);
    setSiteForm({ name: "", code: "", siteType: "factory", addressLine: "", city: "", state: "" });
    setSiteModalOpen(true);
  }

  function openEditSite(site: SiteRecord) {
    setEditingSite(site);
    setSiteForm({
      name: site.name,
      code: site.code,
      siteType: site.siteType || "factory",
      addressLine: site.addressLine || "",
      city: site.city || "",
      state: site.state || "",
    });
    setSiteModalOpen(true);
  }

  async function handleSiteSubmit(event: FormEvent) {
    event.preventDefault();
    if (!canManage) return;
    setSaving(true);
    setError("");
    try {
      if (editingSite) {
        await api(`/api/v1/company/sites/${editingSite.id}`, {
          method: "PUT",
          body: JSON.stringify(siteForm),
        });
      } else {
        await api("/api/v1/company/sites", {
          method: "POST",
          body: JSON.stringify(siteForm),
        });
      }
      setSiteModalOpen(false);
      await loadData();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Could not save site.");
    } finally {
      setSaving(false);
    }
  }

  function openAddStore(defaultSiteCode?: string) {
    setEditingStore(null);
    setStoreForm({
      siteCode: defaultSiteCode || sites[0]?.code || "",
      name: "",
      code: "",
      storeType: "raw_material",
    });
    setStoreModalOpen(true);
  }

  function openEditStore(store: StoreRecord) {
    setEditingStore(store);
    setStoreForm({
      siteCode: store.siteCode,
      name: store.name,
      code: store.code,
      storeType: store.storeType || "raw_material",
    });
    setStoreModalOpen(true);
  }

  async function handleStoreSubmit(event: FormEvent) {
    event.preventDefault();
    if (!canManage) return;
    setSaving(true);
    setError("");
    try {
      if (editingStore) {
        await api(`/api/v1/company/stores/${editingStore.id}`, {
          method: "PUT",
          body: JSON.stringify(storeForm),
        });
      } else {
        await api("/api/v1/company/stores", {
          method: "POST",
          body: JSON.stringify(storeForm),
        });
      }
      setStoreModalOpen(false);
      await loadData();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Could not save store.");
    } finally {
      setSaving(false);
    }
  }

  async function handleDeleteStore() {
    if (!deleteConfirmStore || !canManage) return;
    setSaving(true);
    try {
      await api(`/api/v1/company/stores/${deleteConfirmStore.id}`, { method: "DELETE" });
      setDeleteConfirmStore(null);
      await loadData();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Could not delete store.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return <p className="px-6 py-4 text-sm text-[var(--text-2)]">Loading sites and stores…</p>;
  }

  return (
    <div className="px-6 pb-6 space-y-4">
      {error && <p role="alert" className="p-3 rounded-lg border border-[var(--error)] text-sm text-[var(--error)]">{error}</p>}

      {/* Sub-navigation */}
      <div className="flex items-center justify-between border-b" style={{ borderColor: "var(--border)" }}>
        <div className="flex gap-4">
          <button
            type="button"
            onClick={() => setActiveTab("sites")}
            className={`flex items-center gap-2 py-2 text-sm font-semibold border-b-2 transition-colors ${
              activeTab === "sites" ? "border-[var(--brand)] text-[var(--brand)]" : "border-transparent text-[var(--text-2)] hover:text-[var(--text)]"
            }`}
          >
            <Building2 size={16} /> Sites ({sites.length})
          </button>
          <button
            type="button"
            onClick={() => setActiveTab("stores")}
            className={`flex items-center gap-2 py-2 text-sm font-semibold border-b-2 transition-colors ${
              activeTab === "stores" ? "border-[var(--brand)] text-[var(--brand)]" : "border-transparent text-[var(--text-2)] hover:text-[var(--text)]"
            }`}
          >
            <Warehouse size={16} /> Stores & Locations ({stores.length})
          </button>
        </div>
        <div>
          {canManage && activeTab === "sites" && (
            <Button className="text-xs px-3 py-1.5" onClick={openAddSite}>
              <Plus size={14} className="mr-1" /> Add Site
            </Button>
          )}
          {canManage && activeTab === "stores" && (
            <Button className="text-xs px-3 py-1.5" onClick={() => openAddStore()}>
              <Plus size={14} className="mr-1" /> Add Store
            </Button>
          )}
        </div>
      </div>

      {/* Sites View */}
      {activeTab === "sites" && (
        <div className="overflow-x-auto rounded-xl border" style={{ borderColor: "var(--border)" }}>
          <table className="w-full text-left text-sm">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr>
                <th className="py-2.5 px-4 text-xs font-semibold">Code</th>
                <th className="py-2.5 px-4 text-xs font-semibold">Site Name</th>
                <th className="py-2.5 px-4 text-xs font-semibold">Type</th>
                <th className="py-2.5 px-4 text-xs font-semibold">City / State</th>
                <th className="py-2.5 px-4 text-xs font-semibold">Stores</th>
                <th className="py-2.5 px-4 text-xs font-semibold text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {sites.map((site) => {
                const siteStores = stores.filter((s) => s.siteCode === site.code);
                return (
                  <tr key={site.id} className="border-t hover:bg-[var(--surface-2)]" style={{ borderColor: "var(--border)" }}>
                    <td className="py-2.5 px-4 font-mono font-medium">{site.code}</td>
                    <td className="py-2.5 px-4 font-semibold text-[var(--text)]">{site.name}</td>
                    <td className="py-2.5 px-4 capitalize text-[var(--text-2)]">{site.siteType || "Factory"}</td>
                    <td className="py-2.5 px-4 text-[var(--text-2)]">
                      {[site.city, site.state].filter(Boolean).join(", ") || "—"}
                    </td>
                    <td className="py-2.5 px-4">
                      <span className="inline-flex px-2 py-0.5 rounded text-xs font-medium bg-[var(--surface-3)] text-[var(--text-2)]">
                        {siteStores.length} store{siteStores.length === 1 ? "" : "s"}
                      </span>
                    </td>
                    <td className="py-2.5 px-4 text-right">
                      <div className="inline-flex items-center gap-1">
                        <button
                          type="button"
                          onClick={() => setViewingSite(site)}
                          className="btn-ghost p-1.5 rounded text-[var(--text-2)] hover:text-[var(--text)]"
                          title="View Details"
                        >
                          <Eye size={15} />
                        </button>
                        {canManage && (
                          <button
                            type="button"
                            onClick={() => openEditSite(site)}
                            className="btn-ghost p-1.5 rounded text-[var(--text-2)] hover:text-[var(--text)]"
                            title="Edit Site"
                          >
                            <Pencil size={15} />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
              {sites.length === 0 && (
                <tr>
                  <td colSpan={6} className="py-6 text-center text-sm text-[var(--text-2)]">No sites configured yet.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Stores View */}
      {activeTab === "stores" && (
        <div className="overflow-x-auto rounded-xl border" style={{ borderColor: "var(--border)" }}>
          <table className="w-full text-left text-sm">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr>
                <th className="py-2.5 px-4 text-xs font-semibold">Store Code</th>
                <th className="py-2.5 px-4 text-xs font-semibold">Store Name</th>
                <th className="py-2.5 px-4 text-xs font-semibold">Store Type</th>
                <th className="py-2.5 px-4 text-xs font-semibold">Assigned Site</th>
                <th className="py-2.5 px-4 text-xs font-semibold text-right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {stores.map((store) => {
                const site = sites.find((s) => s.code === store.siteCode);
                const typeObj = STORE_TYPES.find((t) => t.value === store.storeType);
                return (
                  <tr key={store.id} className="border-t hover:bg-[var(--surface-2)]" style={{ borderColor: "var(--border)" }}>
                    <td className="py-2.5 px-4 font-mono font-medium">{store.code}</td>
                    <td className="py-2.5 px-4 font-semibold text-[var(--text)]">{store.name}</td>
                    <td className="py-2.5 px-4 text-[var(--text-2)]">{typeObj?.label || store.storeType}</td>
                    <td className="py-2.5 px-4">
                      <span className="font-medium text-[var(--text)]">{site?.name || store.siteCode}</span>
                      <span className="ml-1 text-xs text-[var(--text-3)] font-mono">({store.siteCode})</span>
                    </td>
                    <td className="py-2.5 px-4 text-right">
                      <div className="inline-flex items-center gap-1">
                        {canManage && (
                          <>
                            <button
                              type="button"
                              onClick={() => openEditStore(store)}
                              className="btn-ghost p-1.5 rounded text-[var(--text-2)] hover:text-[var(--text)]"
                              title="Edit Store"
                            >
                              <Pencil size={15} />
                            </button>
                            <button
                              type="button"
                              onClick={() => setDeleteConfirmStore(store)}
                              className="btn-ghost p-1.5 rounded text-[var(--error)] hover:opacity-80"
                              title="Delete Store"
                            >
                              <Trash2 size={15} />
                            </button>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                );
              })}
              {stores.length === 0 && (
                <tr>
                  <td colSpan={5} className="py-6 text-center text-sm text-[var(--text-2)]">No stores configured yet.</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      {/* Add / Edit Site Drawer */}
      {siteModalOpen && (
        <RecordSheet
          title={editingSite ? `Edit Site "${editingSite.name}"` : "Add New Site"}
          onClose={() => setSiteModalOpen(false)}
          primary={{
            label: saving ? "Saving…" : editingSite ? "Save Changes" : "Create Site",
            disabled: saving || !siteForm.name.trim() || !siteForm.code.trim(),
            onClick: () => { void handleSiteSubmit({ preventDefault: () => {} } as FormEvent); },
          }}
          secondary={{ label: "Cancel", onClick: () => setSiteModalOpen(false) }}
        >
          <form onSubmit={(e) => void handleSiteSubmit(e)} className="space-y-4">
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Site Code *
              <input
                required
                value={siteForm.code}
                onChange={(e) => setSiteForm((f) => ({ ...f, code: e.target.value.toUpperCase() }))}
                className="eoss-input uppercase font-mono"
                placeholder="e.g. FACTORY-01"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Site Name *
              <input
                required
                value={siteForm.name}
                onChange={(e) => setSiteForm((f) => ({ ...f, name: e.target.value }))}
                className="eoss-input"
                placeholder="e.g. Hyderabad Main Works"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Site Type *
              <select
                value={siteForm.siteType}
                onChange={(e) => setSiteForm((f) => ({ ...f, siteType: e.target.value }))}
                className="eoss-input"
              >
                {SITE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Address Line
              <input
                value={siteForm.addressLine}
                onChange={(e) => setSiteForm((f) => ({ ...f, addressLine: e.target.value }))}
                className="eoss-input"
                placeholder="Street address, Industrial area"
              />
            </label>
            <div className="grid grid-cols-2 gap-3">
              <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
                City
                <input
                  value={siteForm.city}
                  onChange={(e) => setSiteForm((f) => ({ ...f, city: e.target.value }))}
                  className="eoss-input"
                  placeholder="e.g. Hyderabad"
                />
              </label>
              <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
                State
                <input
                  value={siteForm.state}
                  onChange={(e) => setSiteForm((f) => ({ ...f, state: e.target.value }))}
                  className="eoss-input"
                  placeholder="e.g. Telangana"
                />
              </label>
            </div>
          </form>
        </RecordSheet>
      )}

      {/* View Site Drawer */}
      {viewingSite && (
        <RecordSheet
          title={`Site: ${viewingSite.name}`}
          onClose={() => setViewingSite(null)}
          secondary={{ label: "Close", onClick: () => setViewingSite(null) }}
          primary={canManage ? { label: "Edit Site", onClick: () => { const s = viewingSite; setViewingSite(null); openEditSite(s); } } : undefined}
        >
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-3 p-3 rounded-xl border bg-[var(--surface-2)]" style={{ borderColor: "var(--border)" }}>
              <div>
                <span className="text-xs text-[var(--text-3)] block uppercase">Site Code</span>
                <span className="font-mono font-semibold text-sm">{viewingSite.code}</span>
              </div>
              <div>
                <span className="text-xs text-[var(--text-3)] block uppercase">Type</span>
                <span className="capitalize font-semibold text-sm">{viewingSite.siteType || "factory"}</span>
              </div>
              <div className="col-span-2">
                <span className="text-xs text-[var(--text-3)] block uppercase">Address</span>
                <span className="text-sm">
                  {[viewingSite.addressLine, viewingSite.city, viewingSite.state].filter(Boolean).join(", ") || "No address entered"}
                </span>
              </div>
            </div>

            <div>
              <div className="flex items-center justify-between mb-2">
                <h4 className="text-xs font-bold uppercase tracking-wider text-[var(--text-2)]">Stores at this site</h4>
                {canManage && (
                  <button
                    type="button"
                    onClick={() => { const code = viewingSite.code; setViewingSite(null); openAddStore(code); }}
                    className="text-xs font-semibold text-[var(--brand)] hover:underline inline-flex items-center gap-1"
                  >
                    <Plus size={13} /> Add Store
                  </button>
                )}
              </div>
              <div className="space-y-2">
                {stores.filter((s) => s.siteCode === viewingSite.code).map((st) => (
                  <div key={st.id} className="flex items-center justify-between p-2.5 rounded-lg border bg-[var(--surface)] text-xs" style={{ borderColor: "var(--border)" }}>
                    <div>
                      <span className="font-semibold text-sm block">{st.name}</span>
                      <span className="font-mono text-[var(--text-3)]">{st.code} · {STORE_TYPES.find((t) => t.value === st.storeType)?.label || st.storeType}</span>
                    </div>
                    {canManage && (
                      <button
                        type="button"
                        onClick={() => { setViewingSite(null); openEditStore(st); }}
                        className="btn-ghost p-1 rounded text-[var(--text-2)] hover:text-[var(--text)]"
                      >
                        <Pencil size={13} />
                      </button>
                    )}
                  </div>
                ))}
                {stores.filter((s) => s.siteCode === viewingSite.code).length === 0 && (
                  <p className="text-xs text-[var(--text-3)] py-3 text-center">No stores assigned to this site.</p>
                )}
              </div>
            </div>
          </div>
        </RecordSheet>
      )}

      {/* Add / Edit Store Drawer */}
      {storeModalOpen && (
        <RecordSheet
          title={editingStore ? `Edit Store "${editingStore.name}"` : "Add New Store / Location"}
          onClose={() => setStoreModalOpen(false)}
          primary={{
            label: saving ? "Saving…" : editingStore ? "Save Changes" : "Create Store",
            disabled: saving || !storeForm.name.trim() || !storeForm.code.trim() || !storeForm.siteCode.trim(),
            onClick: () => { void handleStoreSubmit({ preventDefault: () => {} } as FormEvent); },
          }}
          secondary={{ label: "Cancel", onClick: () => setStoreModalOpen(false) }}
        >
          <form onSubmit={(e) => void handleStoreSubmit(e)} className="space-y-4">
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Assigned Site *
              <select
                disabled={Boolean(editingStore)}
                value={storeForm.siteCode}
                onChange={(e) => setStoreForm((f) => ({ ...f, siteCode: e.target.value }))}
                className="eoss-input"
              >
                {sites.map((s) => <option key={s.id} value={s.code}>{s.name} ({s.code})</option>)}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Store Code *
              <input
                required
                value={storeForm.code}
                onChange={(e) => setStoreForm((f) => ({ ...f, code: e.target.value.toUpperCase() }))}
                className="eoss-input uppercase font-mono"
                placeholder="e.g. RAW-MAT-01"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Store / Area Name *
              <input
                required
                value={storeForm.name}
                onChange={(e) => setStoreForm((f) => ({ ...f, name: e.target.value }))}
                className="eoss-input"
                placeholder="e.g. Raw Material Bay A"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Store Type *
              <select
                value={storeForm.storeType}
                onChange={(e) => setStoreForm((f) => ({ ...f, storeType: e.target.value }))}
                className="eoss-input"
              >
                {STORE_TYPES.map((t) => <option key={t.value} value={t.value}>{t.label}</option>)}
              </select>
            </label>
          </form>
        </RecordSheet>
      )}

      {/* Delete Store Confirmation */}
      <ConfirmDialog
        open={deleteConfirmStore !== null}
        title="Delete Store Location?"
        body={deleteConfirmStore ? `Are you sure you want to delete store "${deleteConfirmStore.name}" (${deleteConfirmStore.code})? Stock records associated with this store must be empty.` : ""}
        confirmLabel="Delete Store"
        onConfirm={() => void handleDeleteStore()}
        onCancel={() => setDeleteConfirmStore(null)}
      />
    </div>
  );
}
