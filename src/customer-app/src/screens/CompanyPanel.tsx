import { useEffect, useState } from "react";
import { api, ApiError, safeRandomUUID } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import type { CompanyRecord } from "../auth/types";
import Button from "../components/shared/Button";
import { TextField } from "../components/shared/Fields";

const FIELDS = [
  ["companyName", "Company Name"],
  ["tradingName", "Trading Name"],
  ["legalName", "Legal Name"],
  ["entityType", "Entity Type"],
  ["pan", "PAN"],
  ["gstin", "GSTIN"],
  ["city", "City"],
  ["state", "State"],
  ["phone", "Phone"],
  ["email", "Email"],
] as const;

export default function CompanyPanel() {
  const customer = useCustomer();
  const [editing, setEditing] = useState(false);
  const [form, setForm] = useState<CompanyRecord | null>(null);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState("");
  const company = customer.onboarding?.company;
  const owner = customer.user?.roles.some((role) => role.role === "OWNER") ?? false;

  useEffect(() => { if (company) setForm(company); }, [company]);

  async function save() {
    if (!form) return;
    setError("");
    setSaved("");
    try {
      const next = await api<CompanyRecord>("/api/v1/company", {
        method: "PUT",
        headers: { "Idempotency-Key": safeRandomUUID() },
        body: JSON.stringify({ ...form, gstNotApplicable: !form.gstin, sites: form.sites, stores: form.stores }),
      });
      setForm(next);
      setEditing(false);
      setSaved("Company profile updated");
      await customer.refresh();
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "Company details were not saved");
    }
  }

  if (!form) return <p role="status" className="px-6 py-4 text-sm">Loading company details</p>;

  return (
    <section className="px-6 pb-8 max-w-3xl">
      <div className="flex items-center justify-between mb-4">
        <h2 className="text-base font-semibold">Company & Legal Profile</h2>
        {owner && <Button variant="ghost" onClick={() => setEditing((current) => !current)}>{editing ? "Cancel" : "Edit"}</Button>}
      </div>
      {editing ? (
        <div className="grid sm:grid-cols-2 gap-4 rounded-2xl border p-5" style={{ borderColor: "var(--border)" }}>
          {FIELDS.map(([key, label]) => (
            <TextField key={key} label={label} name={key} value={form[key] ?? ""} onChange={(event) => setForm({ ...form, [key]: event.target.value })} />
          ))}
          <TextField label="Financial Year" name="financialYear" value="" disabled />
          <TextField label="Currency" name="currency" value="" disabled />
          <div className="sm:col-span-2 flex gap-3">
            <Button onClick={() => { void save(); }}>Save Changes</Button>
          </div>
        </div>
      ) : (
        <dl className="grid sm:grid-cols-2 gap-4 rounded-2xl border p-5" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          {FIELDS.map(([key, label]) => (
            <div key={key}>
              <dt className="text-xs text-[var(--text-3)]">{label}</dt>
              <dd className="text-sm font-medium mt-0.5">{form[key] || "—"}</dd>
            </div>
          ))}
          <div>
            <dt className="text-xs text-[var(--text-3)]">Financial Year</dt>
            <dd className="text-sm font-medium mt-0.5">—</dd>
          </div>
          <div>
            <dt className="text-xs text-[var(--text-3)]">Default Currency</dt>
            <dd className="text-sm font-medium mt-0.5">—</dd>
          </div>
        </dl>
      )}
      {saved && <p role="status" className="text-sm mt-3">{saved}</p>}
      {error && <p role="alert" className="text-sm text-[var(--error)] mt-3">{error}</p>}
      <p className="text-xs text-[var(--text-3)] mt-4">Financial year and currency have no approved company column, so they are shown empty.</p>
    </section>
  );
}
