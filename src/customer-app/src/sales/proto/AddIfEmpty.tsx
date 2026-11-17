import { useState, type ReactNode } from "react";

type Field = { key: string; label: string };

export default function AddIfEmpty({
  empty,
  title,
  hint,
  fields,
  submitLabel,
  onSubmit,
  children,
}: {
  empty: boolean;
  title: string;
  hint?: string;
  fields: Field[];
  submitLabel: string;
  onSubmit: (values: Record<string, string>) => Promise<void>;
  children: ReactNode;
}) {
  const [values, setValues] = useState<Record<string, string>>({});
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  if (!empty) return children;

  return (
    <div className="rounded-xl border p-3 space-y-2" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
      <p className="text-sm font-semibold text-[var(--text)]">{title}</p>
      {hint && <p className="text-xs text-[var(--text-2)]">{hint}</p>}
      <div className="grid sm:grid-cols-2 gap-2">
        {fields.map((field) => (
          <label key={field.key} className="text-xs font-medium text-[var(--text-2)]">
            {field.label}
            <input
              className="eoss-input w-full mt-1"
              value={values[field.key] ?? ""}
              onChange={(event) => setValues((current) => ({ ...current, [field.key]: event.target.value }))}
            />
          </label>
        ))}
      </div>
      {error && <p className="text-xs text-[var(--error,#DC2626)]">{error}</p>}
      <button
        type="button"
        disabled={busy}
        className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white disabled:opacity-60"
        style={{ background: "var(--brand)" }}
        onClick={async () => {
          setBusy(true);
          setError("");
          try {
            await onSubmit(values);
            setValues({});
          } catch (caught) {
            setError(caught instanceof Error ? caught.message : "That was not saved");
          } finally {
            setBusy(false);
          }
        }}
      >
        {busy ? "Saving…" : submitLabel}
      </button>
    </div>
  );
}
