import { useState } from "react";

export type CreatableOption = { id: string; label: string };

export type CreatableField = {
  name: string;
  label: string;
  required?: boolean;
  options?: CreatableOption[];
};

type Props = {
  label: string;
  ariaLabel: string;
  value: string;
  onChange: (id: string) => void;
  options: CreatableOption[];
  pinned?: CreatableOption[];
  placeholder?: string;
  loading?: boolean;
  error?: string;
  onRetry?: () => void;
  creatable?: boolean;
  fields?: CreatableField[];
  prepare?: () => Promise<CreatableField[]>;
  unique?: (values: Record<string, string>) => string | null;
  onCreate?: (values: Record<string, string>) => Promise<CreatableOption>;
  disabled?: boolean;
};

export function roleAllowed(roles: { role: string }[] | undefined, allowed: string[]) {
  return (roles ?? []).some((role) => allowed.includes(role.role));
}

export default function CreatableSelect({
  label, ariaLabel, value, onChange, options, pinned = [], placeholder, loading = false, error, onRetry,
  creatable = false, fields = [], prepare, unique, onCreate, disabled = false,
}: Props) {
  const [query, setQuery] = useState("");
  const [open, setOpen] = useState(false);
  const [activeFields, setActiveFields] = useState<CreatableField[]>(fields);
  const [draft, setDraft] = useState<Record<string, string>>({});
  const [formError, setFormError] = useState("");
  const [preparing, setPreparing] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);
  const [saving, setSaving] = useState(false);
  const term = query.trim().toLowerCase();
  const filtered = options.filter((option) => !term || option.label.toLowerCase().includes(term));
  const canAdd = creatable && !error && !loading && !!onCreate;
  const selected = [...pinned, ...options].some((option) => option.id === value);

  async function openForm() {
    setFormError("");
    setLoadFailed(false);
    setDraft({});
    setOpen(true);
    if (!prepare) {
      setActiveFields(fields);
      return;
    }
    setPreparing(true);
    try {
      setActiveFields(await prepare());
    } catch (cause) {
      setLoadFailed(true);
      setFormError(cause instanceof Error ? cause.message : "The form could not be loaded.");
    } finally {
      setPreparing(false);
    }
  }

  async function submit() {
    const values = Object.fromEntries(activeFields.map((field) => [field.name, (draft[field.name] ?? "").trim()]));
    const missing = activeFields.find((field) => field.required && !values[field.name]);
    if (missing) {
      setFormError(`${missing.label} is required.`);
      return;
    }
    const duplicate = unique?.(values);
    if (duplicate) {
      setFormError(duplicate);
      return;
    }
    if (!onCreate) return;
    setSaving(true);
    setFormError("");
    try {
      const created = await onCreate(values);
      onChange(created.id);
      setOpen(false);
      setQuery("");
    } catch (cause) {
      setFormError(cause instanceof Error ? cause.message : "The value was not saved.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="space-y-1">
      {loading ? <p className="text-xs" role="status">Loading options…</p> : error ? (
        <p role="alert" className="text-xs text-[var(--error)]">
          {error}{" "}
          {onRetry && <button type="button" className="font-semibold underline" onClick={onRetry}>Retry</button>}
        </p>
      ) : (
        <>
          <input aria-label={`${ariaLabel} search`} className="eoss-input w-full" placeholder={`Search ${label.toLowerCase()}`} value={query} disabled={disabled} onChange={(event) => setQuery(event.target.value)} />
          <select aria-label={ariaLabel} className="eoss-input mt-1 w-full" value={value} disabled={disabled} onChange={(event) => onChange(event.target.value)}>
            {placeholder !== undefined && <option value="">{placeholder}</option>}
            {pinned.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
            {!selected && value && <option value={value}>{value}</option>}
            {filtered.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
          </select>
          {options.length === 0 && !term && <p className="text-xs">{canAdd ? "No options available. Add your first option." : "No options available."}</p>}
          {term && filtered.length === 0 && <p className="text-xs">{canAdd ? "No matching option found. Add new value." : "No matching option found."}</p>}
          {options.length > 0 && !term && canAdd && <button type="button" className="text-xs font-semibold" aria-label={`Add ${label}`} onClick={() => void openForm()}>+ Add new</button>}
          {canAdd && (options.length === 0 || !!term && filtered.length === 0) && (
            <button type="button" className="text-xs font-semibold" aria-label={`Add ${label}`} onClick={() => void openForm()}>{options.length === 0 && !term ? "Add New" : "Add new value"}</button>
          )}
        </>
      )}
      {open && (
        <div className="fixed inset-0 z-[60] flex items-center justify-center bg-black/60 p-4">
          <div className="w-full max-w-md space-y-3 rounded-xl border p-4" style={{ background: "var(--surface)", borderColor: "var(--border)" }} role="dialog" aria-label={`Add ${label}`}>
            <h2 className="text-sm font-bold">Add {label}</h2>
            {preparing ? <p className="text-xs" role="status">Loading form…</p> : activeFields.map((field) => (
              <label key={field.name} className="block text-xs">{field.label}{field.required ? " *" : ""}
                {field.options ? (
                  <select aria-label={field.label} className="eoss-input mt-1 w-full" value={draft[field.name] ?? ""} onChange={(event) => setDraft((current) => ({ ...current, [field.name]: event.target.value }))}>
                    <option value="">Select</option>
                    {field.options.map((option) => <option key={option.id} value={option.id}>{option.label}</option>)}
                  </select>
                ) : (
                  <input aria-label={field.label} className="eoss-input mt-1 w-full" value={draft[field.name] ?? ""} onChange={(event) => setDraft((current) => ({ ...current, [field.name]: event.target.value }))} />
                )}
              </label>
            ))}
            {formError && (
              <p role="alert" className="text-xs text-[var(--error)]">
                {formError}{" "}
                {loadFailed && <button type="button" className="font-semibold underline" onClick={() => void openForm()}>Retry</button>}
              </p>
            )}
            <div className="flex justify-end gap-2">
              <button type="button" className="text-xs" onClick={() => setOpen(false)}>Cancel</button>
              <button type="button" disabled={saving || preparing} className="rounded-lg px-3 py-1.5 text-xs font-semibold text-white disabled:opacity-50" style={{ background: "var(--brand-fill)" }} onClick={() => void submit()}>{saving ? "Saving…" : "Save"}</button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
