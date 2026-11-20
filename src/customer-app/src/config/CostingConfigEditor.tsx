import { useState, type FormEvent } from "react";
import { salesApi, type CostRate, type QuotationCostingConfig } from "../sales/api";

const numericFields = [
  ["overheadPerUnit", "Overhead per unit"],
  ["consumablesPerUnit", "Consumables per unit"],
  ["packagingPerUnit", "Packaging per unit"],
  ["transportPerUnit", "Transport per unit"],
  ["targetMarginPct", "Target margin (%)"],
  ["minimumMarginPct", "Minimum margin (%)"],
  ["approvalThresholdPct", "Approval threshold (%)"],
] as const;

function formatRates(rates: CostRate[]) {
  return rates.map((rate) => `${rate.name}, ${rate.ratePerHour}`).join("\n");
}

function parseRates(text: string): CostRate[] {
  return text.split("\n").map((line) => line.trim()).filter(Boolean).map((line) => {
    const parts = line.split(",").map((part) => part.trim());
    if (parts.length !== 2 || !parts[0] || !parts[1] || !Number.isFinite(Number(parts[1])) || Number(parts[1]) < 0) {
      throw new Error("Enter rates one per line as “Activity, hourly rate”.");
    }
    return { name: parts[0], ratePerHour: Number(parts[1]) };
  });
}

export default function CostingConfigEditor({ initialConfig, onSaved }: {
  initialConfig: QuotationCostingConfig;
  onSaved?: (config: QuotationCostingConfig) => void;
}) {
  const [config, setConfig] = useState(initialConfig);
  const [labour, setLabour] = useState(() => formatRates(initialConfig.labourRates));
  const [machine, setMachine] = useState(() => formatRates(initialConfig.machineRates));
  const [message, setMessage] = useState("");
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  async function save(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    setSaving(true);
    setMessage("");
    setError("");
    try {
      const saved = await salesApi.saveQuotationCostingConfig({
        ...config,
        labourRates: parseRates(labour),
        machineRates: parseRates(machine),
      });
      setConfig(saved);
      setLabour(formatRates(saved.labourRates));
      setMachine(formatRates(saved.machineRates));
      onSaved?.(saved);
      setMessage("Costing configuration saved.");
    } catch (reason) {
      setError(reason instanceof Error ? reason.message : "Costing configuration could not be saved.");
    } finally {
      setSaving(false);
    }
  }

  return (
    <form onSubmit={(event) => void save(event)}>
      <div className="grid gap-3 md:grid-cols-2">
        <div>
          <label className="text-xs">Labour rates (one per line: activity, rate/hour)
            <textarea value={labour} onChange={(event) => setLabour(event.target.value)} className="eoss-input mt-1 w-full font-mono" rows={4} />
          </label>
          <p className="mt-1 text-xs" style={{ color: "var(--text-3)" }}>Rate names must match stage names in your routing. E.g. a rate named <strong>Welding</strong> is applied to stages named <strong>Welding</strong> for labour cost estimates and cost intelligence.</p>
        </div>
        <div>
          <label className="text-xs">Machine rates (one per line: stage, rate/hour)
            <textarea value={machine} onChange={(event) => setMachine(event.target.value)} className="eoss-input mt-1 w-full font-mono" rows={4} />
          </label>
          <p className="mt-1 text-xs" style={{ color: "var(--text-3)" }}>Rate names must match stage names in your routing. E.g. a rate named <strong>CNC Milling</strong> is applied to stages named <strong>CNC Milling</strong> for machine cost estimates.</p>
        </div>
        {numericFields.map(([field, label]) => (
          <label key={field} className="text-xs">{label}
            <input type="number" min={0} max={field.endsWith("Pct") ? 100 : undefined} step="0.01" required value={config[field]} className="eoss-input mt-1 w-full"
              onChange={(event) => setConfig({ ...config, [field]: event.target.valueAsNumber })} />
          </label>
        ))}
      </div>
      {error && <p role="alert" className="mt-2 text-xs text-[var(--error)]">{error}</p>}
      {message && <p role="status" className="mt-2 text-xs text-[var(--text-2)]">{message}</p>}
      <button type="submit" disabled={saving} className="mt-3 btn-primary text-xs disabled:opacity-50">
        {saving ? "Saving…" : "Save costing controls"}
      </button>
    </form>
  );
}
