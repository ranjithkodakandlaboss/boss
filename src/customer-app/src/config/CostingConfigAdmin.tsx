import { useEffect, useState } from "react";
import { useCustomer } from "../auth/CustomerState";
import { salesApi, type QuotationCostingConfig } from "../sales/api";
import CostingConfigEditor from "./CostingConfigEditor";

export default function CostingConfigAdmin() {
  const { user } = useCustomer();
  const canManage = Boolean(user?.roles.some(({ role }) => role === "OWNER"));
  const [config, setConfig] = useState<QuotationCostingConfig>();
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [attempt, setAttempt] = useState(0);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError("");
    salesApi.quotationCostingConfig().then((result) => {
      if (active) setConfig(result);
    }).catch((reason: unknown) => {
      if (active) setError(reason instanceof Error ? reason.message : "Costing configuration could not be loaded.");
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => { active = false; };
  }, [attempt]);

  return (
    <section className="max-w-3xl space-y-4 px-6 py-5">
      <div>
        <h2 className="text-sm font-bold text-[var(--text)]">Costing &amp; Pricing Configuration</h2>
        <p className="mt-1 text-sm text-[var(--text-2)]">Quotation estimates use approved BOMs and weighted-average inventory costs with these tenant-wide rates and margin controls.</p>
      </div>
      {loading && <p role="status" className="text-sm text-[var(--text-2)]">Loading costing configuration…</p>}
      {error && <div role="alert" className="text-sm text-[var(--error)]">{error} <button type="button" className="underline" onClick={() => setAttempt((value) => value + 1)}>Retry</button></div>}
      {!loading && !error && config && (canManage
        ? <CostingConfigEditor initialConfig={config} />
        : <p className="text-sm text-[var(--text-2)]">Only owners can edit costing configuration.</p>)}
    </section>
  );
}
