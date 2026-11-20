import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { ArrowDown, ArrowUp, LockKeyhole, Pencil, Plus, RotateCcw } from "lucide-react";
import { ApiError } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import Button from "../components/shared/Button";
import ConfirmDialog from "../components/shared/ConfirmDialog";
import RecordSheet from "../components/shared/RecordSheet";
import {
  createConfigOption,
  listConfigCategories,
  listConfigOptions,
  reorderConfigOptions,
  setConfigOptionActive,
  updateConfigOption,
  type ConfigCategory,
  type ConfigOption,
} from "./api";

export default function ConfigOptionsAdmin() {
  const { user } = useCustomer();
  const canManage = Boolean(user?.roles.some(({ role }) => role === "OWNER"));
  const [categories, setCategories] = useState<ConfigCategory[]>([]);
  const [categoryCode, setCategoryCode] = useState("");
  const [options, setOptions] = useState<ConfigOption[]>([]);
  const [loadingCategories, setLoadingCategories] = useState(true);
  const [categoryError, setCategoryError] = useState("");
  const [loadingOptions, setLoadingOptions] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
  const [showForm, setShowForm] = useState(false);
  const [confirmOption, setConfirmOption] = useState<ConfigOption | null>(null);
  const requestGeneration = useRef(0);

  const category = useMemo(
    () => categories.find((item) => item.code === categoryCode),
    [categories, categoryCode],
  );
  const orderedOptions = useMemo(
    () => [...options].sort((left, right) => Number(right.isActive) - Number(left.isActive)
      || left.sortOrder - right.sortOrder || left.label.localeCompare(right.label)),
    [options],
  );

  async function loadOptions(codeToLoad: string) {
    const generation = ++requestGeneration.current;
    if (!codeToLoad) {
      setOptions([]);
      return;
    }
    setOptions([]);
    setLoadingOptions(true);
    try {
      const loaded = await listConfigOptions(codeToLoad);
      if (requestGeneration.current === generation) {
        setOptions(loaded);
        setError("");
      }
    } catch (reason) {
      if (requestGeneration.current === generation) setError(messageFor(reason, "Configuration values could not be loaded."));
    } finally {
      if (requestGeneration.current === generation) setLoadingOptions(false);
    }
  }

  function loadCategories() {
    setLoadingCategories(true);
    setCategoryError("");
    listConfigCategories()
      .then((items) => {
        setCategories(items);
        setCategoryCode((current) => current || items[0]?.code || "");
      })
      .catch((reason: unknown) => setCategoryError(messageFor(reason, "Configuration categories could not be loaded.")))
      .finally(() => setLoadingCategories(false));
  }

  useEffect(() => {
    loadCategories();
  }, []);

  useEffect(() => {
    void loadOptions(categoryCode);
  }, [categoryCode]);

  function resetForm() {
    setCode("");
    setLabel("");
    setEditingId(null);
    setShowForm(false);
    setError("");
  }

  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!category || !canManage) return;
    setSaving(true);
    setError("");
    try {
      if (editingId) {
        await updateConfigOption(category.code, editingId, { label });
      } else {
        await createConfigOption(category.code, {
          code,
          label,
          sortOrder: options.reduce((maximum, option) => Math.max(maximum, option.sortOrder), 0) + 10,
        });
      }
      resetForm();
      await loadOptions(category.code);
    } catch (reason) {
      setError(messageFor(reason, "The configuration value could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  function beginEdit(option: ConfigOption) {
    setEditingId(option.id);
    setCode(option.code);
    setLabel(option.label);
    setError("");
    setShowForm(true);
  }

  function openAdd() {
    setEditingId(null);
    setCode("");
    setLabel("");
    setError("");
    setShowForm(true);
  }

  async function changeActive(option: ConfigOption) {
    if (!category || !canManage || !category.tenantEditable || (option.isSystem && option.isActive)) return;
    const action = option.isActive ? "deactivate" : "reactivate";
    if (option.isActive) {
      setConfirmOption(option);
      return;
    }
    setError("");
    setSaving(true);
    try {
      await setConfigOptionActive(category.code, option.id, !option.isActive);
      await loadOptions(category.code);
    } catch (reason) {
      setError(messageFor(reason, `The value could not be ${action}d.`));
    } finally {
      setSaving(false);
    }
  }

  async function confirmDeactivate() {
    if (!category || !confirmOption) return;
    setError("");
    setSaving(true);
    try {
      await setConfigOptionActive(category.code, confirmOption.id, false);
      setConfirmOption(null);
      await loadOptions(category.code);
    } catch (reason) {
      setError(messageFor(reason, "The value could not be deactivated."));
      setConfirmOption(null);
    } finally {
      setSaving(false);
    }
  }

  async function move(option: ConfigOption, direction: -1 | 1) {
    if (!category || !canManage || !category.supportsOrdering || option.isSystem) return;
    const movable = orderedOptions.filter((item) => item.isActive && !item.isSystem);
    const index = movable.findIndex((item) => item.id === option.id);
    const nextIndex = index + direction;
    if (index < 0 || nextIndex < 0 || nextIndex >= movable.length) return;
    [movable[index], movable[nextIndex]] = [movable[nextIndex], movable[index]];
    setSaving(true);
    try {
      await reorderConfigOptions(category.code, movable.map((item) => item.id));
      await loadOptions(category.code);
    } catch (reason) {
      setError(messageFor(reason, "The configuration order could not be saved."));
    } finally {
      setSaving(false);
    }
  }

  function scopeLabel(cat: ConfigCategory, opts: ConfigOption[]) {
    if (cat.scope === "system") return "System-managed";
    if (!cat.tenantEditable) return "Read only";
    const hasSystemRows = opts.some((o) => o.isSystem);
    const allSystemRows = opts.length > 0 && opts.every((o) => o.isSystem);
    if (allSystemRows) return "System rows protected · You can add values";
    if (hasSystemRows) return "Hybrid · System rows protected";
    return "Tenant-configurable";
  }

  if (loadingCategories) return <p role="status" className="px-6 py-5 text-sm text-[var(--text-2)]">Loading configuration categories…</p>;

  return (
    <div className="space-y-5 px-6 pt-5 pb-8">
      <div>
        <h2 className="text-sm font-bold text-[var(--text)]">Master Data &amp; Business Configuration</h2>
        <p className="mt-1 text-sm text-[var(--text-2)]">
          Manage tenant reference values used by operational workflows. System-managed values stay protected; tenant-managed values can be deactivated when safe.
        </p>
      </div>

      {categoryError ? (
        <div role="alert" className="rounded-lg border border-[var(--error)] px-3 py-2 text-sm text-[var(--error)]">
          <p>Configuration categories could not be loaded: {categoryError}</p>
          <button type="button" className="mt-2 font-semibold underline" onClick={loadCategories}>Retry</button>
        </div>
      ) : (
      <>
      {error && !showForm && <p role="alert" className="rounded-lg border border-[var(--error)] px-3 py-2 text-sm text-[var(--error)]">{error}</p>}

      <div className="max-w-xl space-y-4">
        <label className="block text-sm font-medium text-[var(--text)]">
          Reference data category
          <select
            aria-label="Reference data category"
            className="eoss-input mt-1 min-h-[40px] w-full"
            value={categoryCode}
            disabled={saving || confirmOption !== null}
            onChange={(event) => { setCategoryCode(event.target.value); resetForm(); }}
          >
            {categories.map((item) => <option key={item.code} value={item.code}>{item.name}</option>)}
          </select>
        </label>
      </div>

      {!category ? (
        <p className="text-sm text-[var(--text-2)]">No configuration categories are available.</p>
      ) : (
        <>
          <div className="flex items-center justify-between gap-3">
            <div>
              <h3 className="font-semibold text-[var(--text)]">{category.name}</h3>
              <p className="text-xs text-[var(--text-2)]">{scopeLabel(category, orderedOptions)}</p>
            </div>
            <div className="flex items-center gap-2">
              {loadingOptions && <span role="status" className="text-xs text-[var(--text-2)]">Loading values…</span>}
              {canManage && category.tenantEditable && (
                <Button type="button" disabled={saving || confirmOption !== null} onClick={openAdd}>
                  <Plus size={14} /> Add value
                </Button>
              )}
            </div>
          </div>

          <div className="overflow-x-auto rounded-xl border" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b text-xs text-[var(--text-2)]" style={{ borderColor: "var(--border)" }}>
                  <th className="px-4 py-3">Code</th><th className="px-4 py-3">Value</th>
                  <th className="px-4 py-3">Status</th><th className="px-4 py-3">Actions</th>
                </tr>
              </thead>
              <tbody>
                {orderedOptions.map((option) => (
                  <tr key={option.id} className="border-b last:border-b-0" style={{ borderColor: "var(--border)" }}>
                    <td className="px-4 py-3 font-mono text-xs">{option.code}</td>
                    <td className="px-4 py-3">{option.label}</td>
                    <td className="px-4 py-3">{option.isActive ? "Active" : "Inactive"}{option.isSystem && <span className="ml-2 inline-flex items-center gap-1 text-xs text-[var(--text-2)]"><LockKeyhole size={12} /> System</span>}</td>
                    <td className="px-4 py-2">
                      {canManage && category.tenantEditable ? (
                        <div className="flex items-center gap-1">
                          {option.isSystem && <span className="mr-1 text-xs text-[var(--text-2)]">Protected</span>}
                          {!option.isSystem && category.supportsOrdering && option.isActive && (
                            <>
                              <button type="button" disabled={saving} className="btn-ghost rounded p-2" aria-label={`Move ${option.label} up`} onClick={() => void move(option, -1)}><ArrowUp size={14} /></button>
                              <button type="button" disabled={saving} className="btn-ghost rounded p-2" aria-label={`Move ${option.label} down`} onClick={() => void move(option, 1)}><ArrowDown size={14} /></button>
                            </>
                          )}
                          {!option.isSystem && option.isActive && category.code !== "ITEM_CATEGORY" && <button type="button" disabled={saving} className="btn-ghost rounded p-2" aria-label={`Edit ${option.label}`} onClick={() => beginEdit(option)}><Pencil size={14} /></button>}
                          {(!option.isSystem || !option.isActive) && <button type="button" disabled={saving} className="btn-ghost rounded p-2" aria-label={`${option.isActive ? "Deactivate" : "Reactivate"} ${option.label}`} onClick={() => void changeActive(option)}>
                            {option.isActive ? <LockKeyhole size={14} /> : <RotateCcw size={14} />}
                          </button>}
                        </div>
                      ) : option.isSystem ? <span className="text-xs text-[var(--text-2)]">Protected</span> : <span className="text-xs text-[var(--text-2)]">Read only</span>}
                    </td>
                  </tr>
                ))}
                {!loadingOptions && orderedOptions.length === 0 && (
                  <tr><td className="px-4 py-6 text-center text-sm text-[var(--text-2)]" colSpan={4}>
                    {category.scope === "system"
                      ? "Values for this category are managed by the system and cannot be edited here."
                      : "No values have been configured."}
                  </td></tr>
                )}
              </tbody>
            </table>
          </div>

          {showForm && canManage && category.tenantEditable && (
            <RecordSheet
              title={editingId ? `Edit "${code}"` : `Add value to ${category.name}`}
              onClose={resetForm}
              primary={{ label: saving ? "Saving…" : editingId ? "Save changes" : "Add value", disabled: saving || !label.trim() || (!editingId && !code.trim()), onClick: () => { void submit({ preventDefault: () => {} } as FormEvent<HTMLFormElement>); } }}
              secondary={{ label: "Cancel", onClick: resetForm }}
            >
              <form onSubmit={(event) => void submit(event)} className="space-y-4">
                {error && <p role="alert" className="rounded-lg border border-[var(--error)] px-3 py-2 text-sm text-[var(--error)]">{error}</p>}
                {!editingId && (
                  <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
                    Code
                    <input required value={code} onChange={(event) => setCode(event.target.value)} className="eoss-input min-h-[40px] w-full" maxLength={100} />
                  </label>
                )}
                {editingId && <p className="text-xs text-[var(--text-2)]">Code: {code} (codes are fixed to preserve existing references)</p>}
                <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
                  Display value
                  <input required value={label} onChange={(event) => setLabel(event.target.value)} className="eoss-input min-h-[40px] w-full" maxLength={200} />
                </label>
              </form>
            </RecordSheet>
          )}

          <ConfirmDialog
            open={confirmOption !== null}
            title="Deactivate configuration value?"
            body={confirmOption ? `Deactivate "${confirmOption.label}"? Existing records will remain unchanged, but the value will no longer be available for new selections.` : ""}
            confirmLabel="Deactivate value"
            onConfirm={() => void confirmDeactivate()}
            onCancel={() => setConfirmOption(null)}
          />
        </>
      )}
      </>
      )}
    </div>
  );
}

function messageFor(reason: unknown, fallback: string) {
  return reason instanceof ApiError ? reason.message : reason instanceof Error ? reason.message : fallback;
}
