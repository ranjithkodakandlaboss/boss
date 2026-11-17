import { useState } from "react";
import { Building2, CheckCircle, Factory, Package, Shield, Store, Users } from "lucide-react";
import { TextField } from "../components/shared/Fields";
import Button from "../components/shared/Button";
import { SAVE_BLOCKED } from "../api/records";

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

export default function OnboardingPage() {
  const [step, setStep] = useState(1);
  const [operation, setOperation] = useState("manufacturing");
  const [error, setError] = useState("");

  return (
    <div className="min-h-screen bg-[var(--app-bg,var(--bg))] text-[var(--text)] px-4 py-8">
      <div className="max-w-3xl mx-auto">
        <div className="text-xl font-extrabold mb-6">BOSS</div>
        <ol className="flex flex-wrap gap-3 mb-8">
          {STEPS.map((item) => (
            <li key={item.id}>
              <button type="button" className="text-xs font-semibold" style={{ color: step === item.id ? "var(--brand)" : "var(--text-3)" }} onClick={() => setStep(item.id)}>
                {item.label}
              </button>
            </li>
          ))}
        </ol>
        {step === 1 && (
          <section>
            <h1 className="text-2xl font-extrabold mb-1">Welcome to BOSS</h1>
            <p className="text-sm text-[var(--text-2)] mb-6">Let&apos;s configure your business workspace in a few simple steps. This takes about 5 minutes.</p>
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
          </section>
        )}
        {step === 2 && (
          <section className="grid sm:grid-cols-2 gap-4">
            <h1 className="sm:col-span-2 text-xl font-bold">Company Profile</h1>
            <TextField label="Company Name *" name="companyName" />
            <TextField label="Trading Name" name="tradingName" />
            <TextField label="Legal Name *" name="legalName" placeholder="As per company registration" />
            <TextField label="PAN" name="pan" placeholder="AAAAA9999A" maxLength={10} />
            <TextField label="Email *" name="email" type="email" />
            <TextField label="Phone" name="phone" />
            <TextField label="City *" name="city" />
            <TextField label="State *" name="state" />
          </section>
        )}
        {step === 3 && (
          <section>
            <h1 className="text-xl font-bold mb-1">GST Registrations</h1>
            <p className="text-sm text-[var(--text-2)] mb-5">Add your GSTIN(s). If not registered, select Not Applicable.</p>
            <TextField label="GSTIN" name="gstin" />
          </section>
        )}
        {step === 4 && (
          <section>
            <h1 className="text-xl font-bold mb-1">Sites & Locations</h1>
            <TextField label="Site name" name="site" />
          </section>
        )}
        {step === 5 && (
          <section>
            <h1 className="text-xl font-bold mb-1">Stores & Operating Areas</h1>
            <TextField label="Store name" name="store" />
          </section>
        )}
        {step === 6 && (
          <section>
            <h1 className="text-xl font-bold mb-1">Users & Access</h1>
            <p className="text-sm text-[var(--text-2)]">Invites are sent by the identity API. No user is prefilled.</p>
            <div className="mt-4"><TextField label="Email" name="inviteEmail" type="email" /></div>
          </section>
        )}
        {step === 7 && (
          <section>
            <h1 className="text-xl font-bold mb-1">Review & Activate</h1>
            <p className="text-sm text-[var(--text-2)] mb-4">Review your configuration before activating BOSS for your business.</p>
            <p className="text-sm">Business type: {OPERATIONS.find((item) => item.key === operation)?.label}</p>
            {error && <p role="alert" className="text-sm text-[var(--error)] mt-3">{error}</p>}
            <Button className="mt-4" onClick={() => setError(SAVE_BLOCKED)}>Activate</Button>
          </section>
        )}
        <div className="flex justify-between mt-8">
          <button type="button" className="btn-ghost" onClick={() => setStep((current) => Math.max(1, current - 1))}>Back</button>
          {step < 7 && <Button onClick={() => setStep((current) => Math.min(7, current + 1))}>Continue</Button>}
        </div>
      </div>
    </div>
  );
}
