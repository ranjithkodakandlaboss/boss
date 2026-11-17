import { useEffect, useState } from "react";
import { Building2, CheckCircle, Factory, Package, Shield, Store, Users } from "lucide-react";
import { api, ApiError } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import Button from "../components/shared/Button";
import { SelectField, TextField } from "../components/shared/Fields";
import type { CompanyRecord } from "../auth/types";

const STEPS = [
  { id: 1, label: "Welcome", icon: Building2 },
  { id: 2, label: "Company", icon: Building2 },
  { id: 3, label: "GST", icon: Shield },
  { id: 4, label: "Sites", icon: Factory },
  { id: 5, label: "Stores", icon: Store },
  { id: 6, label: "Users", icon: Users },
  { id: 7, label: "Review", icon: CheckCircle },
];

const OPERATIONS = [
  { key: "manufacturing", label: "Manufacturing", desc: "Make products from raw materials", icon: Factory },
  { key: "fabrication", label: "Industrial Fabrication", desc: "Custom fabrication and job work", icon: Package },
  { key: "trading", label: "Trading / Distribution", desc: "Buy and sell without manufacturing", icon: Store },
  { key: "mixed", label: "Mixed Operations", desc: "Manufacturing + trading combined", icon: Building2 },
];

const ENTITY_TYPES = ["PRIVATE_LIMITED", "LLP", "SOLE_PROPRIETORSHIP", "PARTNERSHIP", "PUBLIC_LIMITED", "HUF", "OTHER"];
const INVITE_ROLES = ["OPS_MANAGER", "SALES_MANAGER", "PURCHASE_MANAGER", "PRODUCTION_SUPERVISOR", "STORE_KEEPER", "ACCOUNTS_STAFF", "QUALITY_INSPECTOR", "FIELD_WORKER"];

type Draft = {
  companyName: string;
  tradingName: string;
  legalName: string;
  entityType: string;
  pan: string;
  email: string;
  phone: string;
  addressLine: string;
  city: string;
  state: string;
  gstin: string;
  gstNotApplicable: boolean;
  siteName: string;
  siteCode: string;
  siteCity: string;
  siteState: string;
  storeName: string;
  storeCode: string;
  inviteEmail: string;
  inviteRole: string;
};

const EMPTY: Draft = {
  companyName: "", tradingName: "", legalName: "", entityType: "PRIVATE_LIMITED", pan: "", email: "", phone: "",
  addressLine: "", city: "", state: "", gstin: "", gstNotApplicable: false, siteName: "", siteCode: "", siteCity: "",
  siteState: "", storeName: "", storeCode: "", inviteEmail: "", inviteRole: "SALES_MANAGER",
};

function fromCompany(company: CompanyRecord | undefined): Draft {
  const site = company?.sites?.[0];
  const store = company?.stores?.[0];
  return {
    ...EMPTY,
    companyName: company?.companyName ?? "",
    tradingName: company?.tradingName ?? "",
    legalName: company?.legalName ?? "",
    entityType: company?.entityType ?? "PRIVATE_LIMITED",
    pan: company?.pan ?? "",
    email: company?.email ?? "",
    phone: company?.phone ?? "",
    addressLine: company?.addressLine ?? "",
    city: company?.city ?? "",
    state: company?.state ?? "",
    gstin: company?.gstin ?? "",
    siteName: site?.name ?? "",
    siteCode: site?.code ?? "",
    siteCity: site?.city ?? "",
    siteState: site?.state ?? "",
    storeName: store?.name ?? "",
    storeCode: store?.code ?? "",
  };
}

function payload(draft: Draft) {
  return {
    companyName: draft.companyName,
    tradingName: draft.tradingName,
    legalName: draft.legalName,
    entityType: draft.entityType,
    pan: draft.pan,
    addressLine: draft.addressLine,
    city: draft.city,
    state: draft.state,
    email: draft.email,
    phone: draft.phone,
    gstin: draft.gstNotApplicable ? "" : draft.gstin,
    gstNotApplicable: draft.gstNotApplicable,
    sites: draft.siteName ? [{ name: draft.siteName, code: draft.siteCode, siteType: "factory", city: draft.siteCity, state: draft.siteState }] : [],
    stores: draft.storeName ? [{ siteCode: draft.siteCode, name: draft.storeName, code: draft.storeCode, storeType: "raw_material" }] : [],
  };
}

export default function OnboardingPage() {
  const customer = useCustomer();
  const [step, setStep] = useState(1);
  const [operation, setOperation] = useState("manufacturing");
  const [draft, setDraft] = useState<Draft>(EMPTY);
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    if (customer.onboarding?.company) setDraft(fromCompany(customer.onboarding.company));
  }, [customer.onboarding]);

  function update(name: keyof Draft, value: string | boolean) {
    setDraft((current) => ({ ...current, [name]: value }));
  }

  async function persist(complete = false) {
    setError("");
    try {
      await api(complete ? "/api/v1/onboarding/complete" : "/api/v1/onboarding", {
        method: complete ? "POST" : "PUT",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify(payload(draft)),
      });
      setSaved(true);
      if (complete) {
        await customer.refresh();
        window.location.assign("/");
      }
    } catch (caught) {
      setError(caught instanceof ApiError ? caught.message : "The company details were not saved");
      throw caught;
    }
  }

  async function next() {
    if (step >= 2 && step <= 5) await persist(false);
    if (step === 6 && draft.inviteEmail) {
      await api("/api/v1/invites", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ email: draft.inviteEmail, role: draft.inviteRole }),
      });
    }
    setStep((current) => Math.min(7, current + 1));
  }

  return (
    <div className="min-h-screen bg-[var(--app-bg,var(--bg))] text-[var(--text)] px-4 py-8">
      <div className="max-w-3xl mx-auto">
        <div className="text-xl font-extrabold mb-2">BOSS</div>
        {customer.onboarding?.completed && <p className="text-sm mb-4">Onboarding is complete. You can review these details in Company & Legal.</p>}
        <ol className="flex flex-wrap gap-3 mb-8">
          {STEPS.map((item) => (
            <li key={item.id}>
              <button type="button" className="text-xs font-semibold" style={{ color: step === item.id ? "var(--brand)" : "var(--text-3)" }} onClick={() => setStep(item.id)}>
                {item.label}
              </button>
            </li>
          ))}
        </ol>
        {error && <p role="alert" className="text-sm text-[var(--error)] mb-4">{error}</p>}
        {saved && <p role="status" className="text-sm mb-4">Progress saved.</p>}
        {step === 1 && (
          <section>
            <h1 className="text-2xl font-extrabold mb-1">Welcome to BOSS</h1>
            <p className="text-sm text-[var(--text-2)] mb-6">Let&apos;s configure your business workspace in a few simple steps. This takes about 5 minutes.</p>
            <p className="text-sm mb-4">Provisioned industry type: <strong>{customer.onboarding?.industryType ?? "Loading"}</strong></p>
            <h2 className="text-sm font-semibold mb-3">What best describes your operations?</h2>
            <div className="grid grid-cols-2 gap-3">
              {OPERATIONS.map((item) => (
                <button key={item.key} type="button" onClick={() => setOperation(item.key)} className="text-left p-4 rounded-xl border-2" style={{ borderColor: operation === item.key ? "var(--brand)" : "var(--border)" }}>
                  <item.icon size={20} />
                  <div className="mt-2 font-semibold text-sm">{item.label}</div>
                  <div className="text-xs text-[var(--text-3)] mt-0.5">{item.desc}</div>
                </button>
              ))}
            </div>
            <p className="text-xs text-[var(--text-3)] mt-3">The saved industry type stays the value selected when the workspace was provisioned.</p>
          </section>
        )}
        {step === 2 && (
          <section className="grid sm:grid-cols-2 gap-4">
            <h1 className="sm:col-span-2 text-xl font-bold">Company Profile</h1>
            <TextField label="Company Name *" name="companyName" value={draft.companyName} onChange={(event) => update("companyName", event.target.value)} />
            <TextField label="Trading Name" name="tradingName" value={draft.tradingName} onChange={(event) => update("tradingName", event.target.value)} />
            <TextField label="Legal Name *" name="legalName" value={draft.legalName} onChange={(event) => update("legalName", event.target.value)} />
            <SelectField label="Entity Type" name="entityType" value={draft.entityType} onChange={(event) => update("entityType", event.target.value)}>
              {ENTITY_TYPES.map((type) => <option key={type} value={type}>{type}</option>)}
            </SelectField>
            <TextField label="PAN" name="pan" value={draft.pan} maxLength={10} onChange={(event) => update("pan", event.target.value.toUpperCase())} />
            <TextField label="Email *" name="email" type="email" value={draft.email} onChange={(event) => update("email", event.target.value)} />
            <TextField label="Phone" name="phone" value={draft.phone} onChange={(event) => update("phone", event.target.value)} />
            <TextField label="Registered Address" name="addressLine" value={draft.addressLine} onChange={(event) => update("addressLine", event.target.value)} />
            <TextField label="City *" name="city" value={draft.city} onChange={(event) => update("city", event.target.value)} />
            <TextField label="State *" name="state" value={draft.state} onChange={(event) => update("state", event.target.value)} />
            <TextField label="Financial Year" name="financialYear" value="" disabled />
            <TextField label="Currency" name="currency" value="" disabled />
            <p className="sm:col-span-2 text-xs text-[var(--text-3)]">Financial year and currency have no approved company column, so they are not saved.</p>
          </section>
        )}
        {step === 3 && (
          <section className="space-y-4">
            <h1 className="text-xl font-bold mb-1">GST Registrations</h1>
            <label className="flex items-center gap-2 text-sm"><input type="checkbox" checked={draft.gstNotApplicable} onChange={(event) => update("gstNotApplicable", event.target.checked)} /> Not applicable</label>
            <TextField label="GSTIN" name="gstin" value={draft.gstin} maxLength={15} disabled={draft.gstNotApplicable} onChange={(event) => update("gstin", event.target.value.toUpperCase())} />
          </section>
        )}
        {step === 4 && (
          <section className="grid sm:grid-cols-2 gap-4">
            <h1 className="sm:col-span-2 text-xl font-bold">Sites & Locations</h1>
            <TextField label="Site Name *" name="siteName" value={draft.siteName} onChange={(event) => update("siteName", event.target.value)} />
            <TextField label="Code" name="siteCode" value={draft.siteCode} onChange={(event) => update("siteCode", event.target.value.toUpperCase())} />
            <TextField label="City" name="siteCity" value={draft.siteCity} onChange={(event) => update("siteCity", event.target.value)} />
            <TextField label="State" name="siteState" value={draft.siteState} onChange={(event) => update("siteState", event.target.value)} />
          </section>
        )}
        {step === 5 && (
          <section className="grid sm:grid-cols-2 gap-4">
            <h1 className="sm:col-span-2 text-xl font-bold">Stores & Operating Areas</h1>
            <TextField label="Store name" name="storeName" value={draft.storeName} onChange={(event) => update("storeName", event.target.value)} />
            <TextField label="Code" name="storeCode" value={draft.storeCode} onChange={(event) => update("storeCode", event.target.value.toUpperCase())} />
          </section>
        )}
        {step === 6 && (
          <section className="grid sm:grid-cols-2 gap-4">
            <h1 className="sm:col-span-2 text-xl font-bold">Users & Access</h1>
            <p className="sm:col-span-2 text-sm text-[var(--text-2)]">Add a team member. Invitation email delivery is not configured, so the invite is stored and is not sent.</p>
            <TextField label="Email" name="inviteEmail" type="email" value={draft.inviteEmail} onChange={(event) => update("inviteEmail", event.target.value)} />
            <SelectField label="Role" name="inviteRole" value={draft.inviteRole} onChange={(event) => update("inviteRole", event.target.value)}>
              {INVITE_ROLES.map((role) => <option key={role} value={role}>{role}</option>)}
            </SelectField>
          </section>
        )}
        {step === 7 && (
          <section>
            <h1 className="text-xl font-bold mb-1">Review & Activate</h1>
            <p className="text-sm">Company: {draft.companyName || "—"}</p>
            <p className="text-sm">Legal name: {draft.legalName || "—"}</p>
            <p className="text-sm">Location: {[draft.city, draft.state].filter(Boolean).join(", ") || "—"}</p>
            <p className="text-sm">Site: {draft.siteName || "—"}</p>
            <p className="text-sm">Operations card: {OPERATIONS.find((item) => item.key === operation)?.label}</p>
            <Button className="mt-4" onClick={() => { void persist(true).catch(() => undefined); }}>Activate BOSS</Button>
          </section>
        )}
        <div className="flex justify-between mt-8">
          <button type="button" className="btn-ghost" onClick={() => setStep((current) => Math.max(1, current - 1))}>Back</button>
          {step < 7 && <Button onClick={() => { void next().catch((caught) => setError(caught instanceof ApiError ? caught.message : "The step was not saved")); }}>Save & Continue</Button>}
        </div>
      </div>
    </div>
  );
}
