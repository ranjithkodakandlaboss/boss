import { useEffect, useMemo, useRef, useState, type FormEvent } from "react";
import { Link } from "react-router-dom";
import { ArrowDown, ArrowUp, LockKeyhole, Pencil, Plus, RotateCcw } from "lucide-react";
import { ApiError } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import Button from "../components/shared/Button";
import ConfirmDialog from "../components/shared/ConfirmDialog";
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
  const [loadingOptions, setLoadingOptions] = useState(false);
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");
  const [code, setCode] = useState("");
  const [label, setLabel] = useState("");
  const [editingId, setEditingId] = useState<string | null>(null);
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

  useEffect(() => {
    listConfigCategories()
      .then((items) => {
        setCategories(items);
        setCategoryCode((current) => current || items[0]?.code || "");
      })
      .catch((reason: unknown) => setError(messageFor(reason, "Configuration categories could not be loaded.")))
      .finally(() => setLoadingCategories(false));
  }, []);

  useEffect(() => {
    void loadOptions(categoryCode);
  }, [categoryCode]);

  function resetForm() {
    setCode("");
    setLabel("");
    setEditingId(null);
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
  }

  async function changeActive(option: ConfigOption) {
    if (!category || !canManage || !category.tenantEditable) return;
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

  if (loadingCategories) return <p role="status" className="px-6 py-5 text-sm text-[var(--text-2)]">Loading configuration categories…</p>;

  return (
    <div className="space-y-5 px-6 pb-8">
      <div>
        <h2 className="text-sm font-bold text-[var(--text)]">Master Data &amp; Business Configuration</h2>
        <p className="mt-1 text-sm text-[var(--text-2)]">
          Manage tenant reference values used by operational workflows. System-managed codes and labels stay protected; admins can deactivate values for new selections.
        </p>
        <div className="mt-3 flex flex-wrap gap-2">
          <Link className="btn-ghost inline-flex items-center rounded-lg px-3 py-2 text-sm" to="/products">Product &amp; Material Master</Link>
          <Link className="btn-ghost inline-flex items-center rounded-lg px-3 py-2 text-sm" to="/bom">Bill of Materials</Link>
        </div>
      </div>

      {error && <p role="alert" className="rounded-lg border border-[var(--error)] px-3 py-2 text-sm text-[var(--error)]">{error}</p>}

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
              <p className="text-xs text-[var(--text-2)]">{category.scope === "system" ? "System-controlled" : "Tenant-configurable"}</p>
            </div>
            {loadingOptions && <span role="status" className="text-xs text-[var(--text-2)]">Loading values…</span>}
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
                          {!option.isSystem && option.isActive && <button type="button" disabled={saving} className="btn-ghost rounded p-2" aria-label={`Edit ${option.label}`} onClick={() => beginEdit(option)}><Pencil size={14} /></button>}
                          <button type="button" disabled={saving} className="btn-ghost rounded p-2" aria-label={`${option.isActive ? "Deactivate" : "Reactivate"} ${option.label}`} onClick={() => void changeActive(option)}>
                            {option.isActive ? <LockKeyhole size={14} /> : <RotateCcw size={14} />}
                          </button>
                        </div>
                      ) : option.isSystem ? <span className="text-xs text-[var(--text-2)]">Protected</span> : <span className="text-xs text-[var(--text-2)]">Read only</span>}
                    </td>
                  </tr>
                ))}
                {!loadingOptions && orderedOptions.length === 0 && (
                  <tr><td className="px-4 py-6 text-center text-sm text-[var(--text-2)]" colSpan={4}>No values have been configured.</td></tr>
                )}
              </tbody>
            </table>
          </div>

          {canManage && category.tenantEditable && (
            <form className="max-w-xl space-y-3 rounded-xl border p-4" style={{ borderColor: "var(--border)", background: "var(--surface)" }} onSubmit={(event) => void submit(event)}>
              <h4 className="text-sm font-semibold text-[var(--text)]">{editingId ? "Edit value" : "Add value"}</h4>
              {!editingId && (
                <label className="block text-sm text-[var(--text)]">
                  Code
                  <input required value={code} onChange={(event) => setCode(event.target.value)} className="eoss-input mt-1 min-h-[40px] w-full" maxLength={100} />
                </label>
              )}
              {editingId && <p className="text-xs text-[var(--text-2)]">Code: {code} (codes are fixed to preserve existing references)</p>}
              <label className="block text-sm text-[var(--text)]">
                Display value
                <input required value={label} onChange={(event) => setLabel(event.target.value)} className="eoss-input mt-1 min-h-[40px] w-full" maxLength={200} />
              </label>
              <div className="flex gap-2">
                <Button type="submit" disabled={saving || !label.trim() || (!editingId && !code.trim())}>
                  <Plus size={14} /> {saving ? "Saving…" : editingId ? "Save changes" : "Add value"}
                </Button>
                {editingId && <Button variant="ghost" type="button" onClick={resetForm}>Cancel</Button>}
              </div>
            </form>
          )}
          <ConfirmDialog
            open={confirmOption !== null}
            title="Deactivate configuration value?"
            body={confirmOption ? `Deactivate “${confirmOption.label}”? Existing records will remain unchanged, but the value will no longer be available for new selections.` : ""}
            confirmLabel="Deactivate value"
            onConfirm={() => void confirmDeactivate()}
            onCancel={() => setConfirmOption(null)}
          />
        </>
      )}
    </div>
  );
}

function messageFor(reason: unknown, fallback: string) {
  return reason instanceof ApiError ? reason.message : reason instanceof Error ? reason.message : fallback;
}
