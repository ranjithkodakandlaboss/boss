import { useEffect, useState } from "react";
import { Plus, Trash2 } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import StatusChip from "../components/shared/StatusChip";
import {
  listTemplates, getTemplate, createTemplate, updateTemplate,
  type ChecklistTemplate, type ChecklistItem,
} from "./api";

type ItemDraft = Omit<ChecklistItem, "id"> & { id?: string };

export default function ChecklistTemplatesPage() {
  const { user } = useCustomer();
  const roles = user?.roles.map((r) => r.role) ?? [];
  const canManage = roles.some((r) => ["OWNER", "OPS_MANAGER"].includes(r));

  const [templates, setTemplates] = useState<ChecklistTemplate[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [selected, setSelected] = useState<ChecklistTemplate | null>(null);
  const [loadingDetail, setLoadingDetail] = useState(false);

  const [showForm, setShowForm] = useState(false);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [scope, setScope] = useState("job");
  const [inspectionType, setInspectionType] = useState("any");
  const [isActive, setIsActive] = useState(true);
  const [items, setItems] = useState<ItemDraft[]>([]);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  function loadList() {
    setLoading(true);
    listTemplates().then(setTemplates).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }

  useEffect(loadList, []);

  function openNew() {
    setEditingId(null);
    setName("");
    setDescription("");
    setScope("job");
    setInspectionType("any");
    setIsActive(true);
    setItems([]);
    setShowForm(true);
  }

  function openEdit(t: ChecklistTemplate) {
    setEditingId(t.id);
    setName(t.name);
    setDescription(t.description ?? "");
    setScope(t.scope ?? "job");
    setInspectionType(t.inspectionType ?? "any");
    setIsActive(t.isActive);
    setItems(t.items?.map((i) => ({ ...i })) ?? []);
    setShowForm(true);
  }

  function selectTemplate(t: ChecklistTemplate) {
    setLoadingDetail(true);
    setSelected(null);
    getTemplate(t.id).then(setSelected).catch(() => setSelected(t)).finally(() => setLoadingDetail(false));
  }

  function addItem() {
    setItems((prev) => [
      ...prev,
      { sortOrder: prev.length + 1, description: "", responseType: "pass_fail", isMandatory: true },
    ]);
  }

  function removeItem(index: number) {
    setItems((prev) => prev.filter((_, i) => i !== index));
  }

  function updateItem(index: number, patch: Partial<ItemDraft>) {
    setItems((prev) => prev.map((item, i) => (i === index ? { ...item, ...patch } : item)));
  }

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaveError("");
    try {
      const payload = {
        name,
        description: description || undefined,
        scope: scope || undefined,
        inspectionType: inspectionType || undefined,
        items: items.map((item, idx) => ({
          id: item.id,
          sortOrder: idx + 1,
          description: item.description,
          responseType: item.responseType,
          isMandatory: item.isMandatory,
          unit: item.unit || undefined,
          specMin: item.specMin,
          specMax: item.specMax,
        })),
      };
      if (editingId) {
        await updateTemplate(editingId, { ...payload, isActive });
      } else {
        await createTemplate(payload);
      }
      setShowForm(false);
      loadList();
    } catch (e: unknown) {
      setSaveError((e as Error).message ?? "Failed to save");
    } finally {
      setSaving(false);
    }
  }

  async function toggleActive(t: ChecklistTemplate) {
    try {
      await updateTemplate(t.id, { isActive: !t.isActive });
      loadList();
      if (selected?.id === t.id) setSelected({ ...selected, isActive: !t.isActive });
    } catch {}
  }

  return (
    <div className="p-4 md:p-6 max-w-6xl mx-auto">
      <div className="flex items-center justify-between mb-6">
        <h1 className="text-xl font-bold">Checklist Templates</h1>
        {canManage && (
          <button
            type="button"
            onClick={openNew}
            className="inline-flex items-center gap-1 rounded-lg bg-[var(--brand-fill)] px-3 py-1.5 text-sm font-semibold text-white"
          >
            <Plus size={15} /> New Template
          </button>
        )}
      </div>

      {error && <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 mb-4">{error}</div>}

      <div className="flex gap-4">
        {/* Template list */}
        <div className="w-72 shrink-0">
          {loading ? (
            <p className="text-sm text-[var(--text-3)]">Loading…</p>
          ) : templates.length === 0 ? (
            <p className="text-sm text-[var(--text-3)]">No templates yet.</p>
          ) : (
            <div className="space-y-2">
              {templates.map((t) => (
                <div
                  key={t.id}
                  className={`rounded-lg border p-3 cursor-pointer transition-colors ${
                    selected?.id === t.id ? "border-[var(--brand)] bg-[var(--surface)]" : "hover:bg-[var(--surface)]"
                  }`}
                  style={{ borderColor: selected?.id === t.id ? undefined : "var(--border)" }}
                  onClick={() => selectTemplate(t)}
                >
                  <div className="flex items-start justify-between gap-2">
                    <span className="text-sm font-medium">{t.name}</span>
                    <StatusChip status={t.isActive ? "active" : "inactive"} />
                  </div>
                  <p className="text-xs text-[var(--text-3)] mt-1 capitalize">
                    {t.scope ?? "—"} · {(t.inspectionType ?? "any").replace(/_/g, " ")}
                  </p>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* Template detail */}
        <div className="flex-1 min-w-0">
          {loadingDetail && <p className="text-sm text-[var(--text-3)]">Loading…</p>}
          {!loadingDetail && selected && (
            <div className="rounded-xl border p-5" style={{ borderColor: "var(--border)" }}>
              <div className="flex items-start justify-between mb-4">
                <div>
                  <h2 className="text-base font-bold">{selected.name}</h2>
                  {selected.description && <p className="text-sm text-[var(--text-2)] mt-0.5">{selected.description}</p>}
                  <p className="text-xs text-[var(--text-3)] mt-1 capitalize">
                    {selected.scope ?? "—"} · {(selected.inspectionType ?? "any").replace(/_/g, " ")}
                  </p>
                </div>
                {canManage && (
                  <div className="flex gap-2">
                    <button
                      type="button"
                      onClick={() => openEdit(selected)}
                      className="rounded-lg border px-3 py-1.5 text-sm font-medium"
                      style={{ borderColor: "var(--border)" }}
                    >
                      Edit
                    </button>
                    <button
                      type="button"
                      onClick={() => toggleActive(selected)}
                      className="rounded-lg border px-3 py-1.5 text-sm font-medium"
                      style={{ borderColor: "var(--border)" }}
                    >
                      {selected.isActive ? "Deactivate" : "Activate"}
                    </button>
                  </div>
                )}
              </div>

              <h3 className="text-xs font-semibold uppercase tracking-wide text-[var(--text-3)] mb-3">
                Items ({selected.items?.length ?? 0})
              </h3>
              {selected.items && selected.items.length > 0 ? (
                <div className="space-y-2">
                  {selected.items.map((item, idx) => (
                    <div key={item.id} className="rounded-lg border p-3 flex items-start gap-3" style={{ borderColor: "var(--border)" }}>
                      <span className="text-xs text-[var(--text-3)] w-5 shrink-0 mt-0.5">{idx + 1}</span>
                      <div className="flex-1 min-w-0">
                        <p className="text-sm">{item.description}</p>
                        <p className="text-xs text-[var(--text-3)] mt-0.5">
                          {item.responseType.replace(/_/g, " ")}
                          {item.isMandatory && " · Required"}
                          {item.unit && ` · ${item.unit}`}
                          {item.specMin != null && ` · Min: ${item.specMin}`}
                          {item.specMax != null && ` · Max: ${item.specMax}`}
                        </p>
                      </div>
                    </div>
                  ))}
                </div>
              ) : (
                <p className="text-sm text-[var(--text-3)]">No items defined.</p>
              )}
            </div>
          )}
        </div>
      </div>

      {/* Create / Edit form modal */}
      {showForm && (
        <div className="fixed inset-0 z-[70] bg-black/40 flex items-center justify-center p-4">
          <div
            className="bg-[var(--bg)] rounded-xl shadow-2xl w-full max-w-2xl max-h-[90vh] overflow-y-auto p-6"
            role="dialog"
            aria-modal="true"
          >
            <h2 className="text-base font-semibold mb-4">{editingId ? "Edit Template" : "New Template"}</h2>
            <form onSubmit={handleSubmit} className="space-y-4">
              <div className="grid grid-cols-2 gap-3">
                <label className="block text-sm col-span-2">
                  <span className="text-[var(--text-2)] font-medium">Name *</span>
                  <input
                    required
                    className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                    style={{ borderColor: "var(--border)" }}
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                  />
                </label>
                <label className="block text-sm">
                  <span className="text-[var(--text-2)] font-medium">Scope</span>
                  <select
                    className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                    style={{ borderColor: "var(--border)" }}
                    value={scope}
                    onChange={(e) => setScope(e.target.value)}
                  >
                    <option value="job">Job</option>
                    <option value="material">Material</option>
                  </select>
                </label>
                <label className="block text-sm">
                  <span className="text-[var(--text-2)] font-medium">Inspection type</span>
                  <select
                    className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                    style={{ borderColor: "var(--border)" }}
                    value={inspectionType}
                    onChange={(e) => setInspectionType(e.target.value)}
                  >
                    <option value="any">Any</option>
                    <option value="incoming">Incoming</option>
                    <option value="in_process">In-Process</option>
                    <option value="final">Final</option>
                    <option value="dispatch_readiness">Dispatch Readiness</option>
                  </select>
                </label>
                <label className="block text-sm col-span-2">
                  <span className="text-[var(--text-2)] font-medium">Description</span>
                  <textarea
                    rows={2}
                    className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)] resize-none"
                    style={{ borderColor: "var(--border)" }}
                    value={description}
                    onChange={(e) => setDescription(e.target.value)}
                  />
                </label>
                {editingId && (
                  <label className="flex items-center gap-2 text-sm col-span-2 cursor-pointer">
                    <input
                      type="checkbox"
                      checked={isActive}
                      onChange={(e) => setIsActive(e.target.checked)}
                      className="rounded"
                    />
                    <span className="font-medium text-[var(--text-2)]">Active</span>
                  </label>
                )}
              </div>

              {/* Items */}
              <div>
                <div className="flex items-center justify-between mb-2">
                  <span className="text-sm font-medium text-[var(--text-2)]">Checklist Items</span>
                  <button
                    type="button"
                    onClick={addItem}
                    className="inline-flex items-center gap-1 text-xs text-[var(--brand)] font-medium"
                  >
                    <Plus size={13} /> Add item
                  </button>
                </div>
                {items.length === 0 && (
                  <p className="text-xs text-[var(--text-3)] italic">No items — click "Add item" to begin.</p>
                )}
                <div className="space-y-3">
                  {items.map((item, idx) => (
                    <div key={idx} className="rounded-lg border p-3 space-y-2" style={{ borderColor: "var(--border)" }}>
                      <div className="flex items-start gap-2">
                        <span className="text-xs text-[var(--text-3)] w-5 shrink-0 mt-2">{idx + 1}</span>
                        <input
                          required
                          className="flex-1 rounded border px-2 py-1.5 text-sm bg-[var(--surface)]"
                          style={{ borderColor: "var(--border)" }}
                          placeholder="Item description *"
                          value={item.description}
                          onChange={(e) => updateItem(idx, { description: e.target.value })}
                        />
                        <button
                          type="button"
                          onClick={() => removeItem(idx)}
                          className="p-1.5 rounded text-[var(--text-3)] hover:text-red-500"
                        >
                          <Trash2 size={14} />
                        </button>
                      </div>
                      <div className="flex flex-wrap gap-2 pl-7">
                        <select
                          className="rounded border px-2 py-1 text-xs bg-[var(--surface)]"
                          style={{ borderColor: "var(--border)" }}
                          value={item.responseType}
                          onChange={(e) => updateItem(idx, { responseType: e.target.value })}
                        >
                          <option value="pass_fail">Pass/Fail</option>
                          <option value="yes_no">Yes/No</option>
                          <option value="numeric">Numeric</option>
                          <option value="text">Text</option>
                        </select>
                        <label className="flex items-center gap-1 text-xs cursor-pointer">
                          <input
                            type="checkbox"
                            checked={item.isMandatory}
                            onChange={(e) => updateItem(idx, { isMandatory: e.target.checked })}
                            className="rounded"
                          />
                          Mandatory
                        </label>
                        {item.responseType === "numeric" && (
                          <>
                            <input
                              type="number"
                              step="any"
                              className="rounded border px-2 py-1 text-xs bg-[var(--surface)] w-20"
                              style={{ borderColor: "var(--border)" }}
                              placeholder="Min"
                              value={item.specMin ?? ""}
                              onChange={(e) => updateItem(idx, { specMin: e.target.value ? parseFloat(e.target.value) : undefined })}
                            />
                            <input
                              type="number"
                              step="any"
                              className="rounded border px-2 py-1 text-xs bg-[var(--surface)] w-20"
                              style={{ borderColor: "var(--border)" }}
                              placeholder="Max"
                              value={item.specMax ?? ""}
                              onChange={(e) => updateItem(idx, { specMax: e.target.value ? parseFloat(e.target.value) : undefined })}
                            />
                            <input
                              type="text"
                              className="rounded border px-2 py-1 text-xs bg-[var(--surface)] w-20"
                              style={{ borderColor: "var(--border)" }}
                              placeholder="Unit"
                              value={item.unit ?? ""}
                              onChange={(e) => updateItem(idx, { unit: e.target.value })}
                            />
                          </>
                        )}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {saveError && <p className="text-xs text-red-600">{saveError}</p>}
              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowForm(false)}
                  className="rounded-lg border px-4 py-2 text-sm font-medium"
                  style={{ borderColor: "var(--border)" }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="rounded-lg bg-[var(--brand-fill)] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                >
                  {saving ? "Saving…" : editingId ? "Save Changes" : "Create"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}
