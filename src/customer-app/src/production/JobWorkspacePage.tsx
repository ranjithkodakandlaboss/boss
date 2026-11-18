import { useEffect, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ArrowLeft, ExternalLink } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import { ApiError } from "../auth/api";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import RecordSheet from "../components/shared/RecordSheet";
import StatusChip from "../components/shared/StatusChip";
import { createLot, lotFields } from "../inventory/inlineCreate";
import {
  addCost, cancelJob, concede, displayDate, featureFlags, getJob, issueMaterial, money, openInspection, readinessLabel,
  recordInspection, releaseHold, returnMaterial, skipStage, usableLots,
  type LotOption, type ProductionJob,
} from "./api";

const TABS = ["Overview", "Stages", "Materials", "Quality", "NCR / Rework", "Updates", "Blockers", "History"];

export default function JobWorkspacePage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user } = useCustomer();
  const roles = user?.roles.map((role) => role.role) ?? [];
  const canWrite = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR"].includes(role));
  const canQuality = roles.some((role) => ["OWNER", "OPS_MANAGER", "QUALITY_INSPECTOR"].includes(role));
  const canConcede = roles.some((role) => ["OWNER", "OPS_MANAGER"].includes(role));
  const canCost = roles.some((role) => ["OWNER", "OPS_MANAGER", "SALES_MANAGER", "PURCHASE_MANAGER", "ACCOUNTS_STAFF"].includes(role));
  const [job, setJob] = useState<ProductionJob | null>(null);
  const [flags, setFlags] = useState<Record<string, boolean>>({});
  const [tab, setTab] = useState("Overview");
  const [error, setError] = useState("");
  const [notice, setNotice] = useState("");
  const [cancelOpen, setCancelOpen] = useState(false);

  async function load() {
    try {
      const [next, nextFlags] = await Promise.all([getJob(id), featureFlags()]);
      setJob(next);
      setFlags(nextFlags);
      setError("");
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Job not found");
    }
  }

  useEffect(() => { void load(); }, [id]);
  useEffect(() => { if (searchParams.get("update") === "1" && job) setTab("Stages"); }, [searchParams, job]);

  if (error && !job) return <p role="alert" className="p-8 text-sm">{error}</p>;
  if (!job) return <p role="status" className="p-8 text-sm">Loading job</p>;

  const blocked = job.status === "on_hold" || job.risk === "blocked";
  const closed = job.status === "completed" || job.status === "cancelled";

  return (
    <div className="flex-1 overflow-auto px-5 py-5 md:px-8 md:py-6 space-y-6 max-w-7xl w-full mx-auto">
      <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-4 pb-2 border-b" style={{ borderColor: "var(--border)" }}>
        <div className="space-y-1">
          <div className="flex items-center gap-2 text-xs">
            <button type="button" onClick={() => navigate("/jobs")} className="flex items-center gap-1"><ArrowLeft size={13} /> Jobs</button>
            <span>/</span>
            <span className="font-mono font-bold">{job.jobNumber}</span>
            <StatusChip status={job.status} size="sm" />
            {blocked && <span className="px-2 py-0.5 rounded text-[11px] font-bold" style={{ color: "var(--error)" }}>Blocked</span>}
          </div>
          <h1 className="text-2xl font-bold">{job.customerName}</h1>
          <div className="text-xs flex items-center gap-3 flex-wrap" style={{ color: "var(--text-3)" }}>
            <span>Customer PO: {job.cpoId ? <button type="button" className="font-mono font-semibold inline-flex items-center gap-1" style={{ color: "var(--brand)" }} onClick={() => navigate(`/sales/po/${job.cpoId}`)}>{job.cpoNumber} <ExternalLink size={11} /></button> : job.cpoNumber}</span>
            <span>Value: <strong>{money(job.actualCost?.sellingPrice)}</strong></span>
            <span>Due Date: <strong>{displayDate(job.plannedEnd)}</strong></span>
          </div>
        </div>
        <div className="flex items-center gap-2">
          {job.status === "on_hold" && canWrite && <button type="button" className="px-4 py-2 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => act(() => releaseHold(job.id, "Hold released"), "Hold released")}>Release hold</button>}
          {!closed && canWrite && <button type="button" className="px-3 py-2 rounded-lg text-xs font-semibold border" style={{ borderColor: "var(--error)", color: "var(--error)" }} onClick={() => setCancelOpen(true)}>Cancel job</button>}
          {!closed && <button type="button" className="px-4 py-2 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => navigate(`/jobs/${job.id}/stage/${currentStage(job)?.id ?? ""}`)}>Update stage</button>}
        </div>
      </div>
      {notice && <p role="status" className="text-sm" style={{ color: "var(--success)" }}>{notice}</p>}
      {error && <p role="alert" className="text-sm" style={{ color: "var(--error)" }}>{error}</p>}
      <div className="flex gap-2 overflow-x-auto border-b" style={{ borderColor: "var(--border)" }}>
        {TABS.map((name) => <button key={name} type="button" onClick={() => setTab(name)} className="px-3 py-2 text-xs font-semibold whitespace-nowrap" style={{ color: tab === name ? "var(--brand)" : "var(--text-2)", borderBottom: tab === name ? "2px solid var(--brand)" : "2px solid transparent" }}>{name}</button>)}
      </div>
      {tab === "Overview" && <Overview job={job} />}
      {tab === "Stages" && <Stages job={job} canWrite={canWrite && flags["production.stage.transition"] !== false} onSkip={(stageId, reason) => act(() => skipStage(job.id, stageId, reason), "Stage skipped")} />}
      {tab === "Materials" && <Materials job={job} canIssue={canWrite && flags["production.material.issue"] !== false} canReturn={canWrite && flags["production.material.return"] !== false} onIssue={(requirementId, lotId, qty, note) => act(() => issueMaterial(job.id, requirementId, lotId, qty, note), "Material issued")} onReturn={(issueId, qty, note) => act(() => returnMaterial(job.id, issueId, qty, note), "Material returned")} />}
      {tab === "Quality" && <Quality job={job} enabled={flags["production.inspection.gate"] !== false} canQuality={canQuality} canConcede={canConcede} onOpen={(stageId) => act(() => openInspection(job.id, stageId), "Inspection opened")} onResult={(inspectionId, result, note) => act(() => recordInspection(job.id, inspectionId, result, note), "Inspection recorded")} onConcede={(inspectionId, note) => act(() => concede(job.id, inspectionId, note), "Concession approved")} />}
      {tab === "NCR / Rework" && <Ncr job={job} />}
      {tab === "Updates" && <Updates job={job} />}
      {tab === "Blockers" && <Blockers job={job} />}
      {tab === "History" && <History job={job} canCost={canCost && flags["production.job.costing"] !== false && !closed} onCost={(type, description, amount) => act(() => addCost(job.id, type, description, amount), "Cost recorded")} />}
      {cancelOpen && <CancelDialog job={job} onClose={() => setCancelOpen(false)} onDone={() => { setCancelOpen(false); void load(); }} />}
    </div>
  );

  function act(call: () => Promise<unknown>, done: string) {
    call().then(() => { setNotice(done); return load(); }).catch((cause) => setError(cause instanceof ApiError ? cause.message : "The change was not saved"));
  }
}

function CancelDialog({ job, onClose, onDone }: { job: ProductionJob; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  const reserved = (job.materials ?? []).reduce((sum, row) => sum + Math.max(0, Number(row.requiredQty) - Number(row.netIssuedQty ?? 0)), 0);
  const issued = (job.materials ?? []).reduce((sum, row) => sum + Number(row.netIssuedQty ?? 0), 0);
  async function confirm() {
    try {
      await cancelJob(job.id, reason.trim());
      onDone();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "The job was not cancelled");
    }
  }
  return (
    <RecordSheet title={`Cancel ${job.jobNumber}`} onClose={onClose} secondary={{ label: "Keep job", onClick: onClose }} primary={{ label: "Confirm cancellation", onClick: () => void confirm(), disabled: !reason.trim() }}>
      <div className="space-y-3">
        <p className="text-xs text-[var(--text-2)]">Cancellation preserves the job and its history. Reserved but unissued quantities will be released. Issued material is not restored automatically.</p>
        <div className="rounded-lg p-3 text-xs" style={{ background: "var(--surface-2)" }}>Status: <strong>{job.status}</strong> · Stage: <strong>{job.currentStage}</strong> · Unissued: <strong>{reserved}</strong> · Issued: <strong>{issued}</strong></div>
        <label className="block text-xs font-semibold">Cancellation reason *<textarea className="eoss-input mt-1 w-full" rows={3} value={reason} onChange={(event) => setReason(event.target.value)} /></label>
        {error && <p role="alert" className="text-xs text-[var(--error)]">{error}</p>}
      </div>
    </RecordSheet>
  );
}

function currentStage(job: ProductionJob) {
  return job.stages?.find((stage) => stage.status === "in_progress")
    ?? job.stages?.find((stage) => stage.status === "pending");
}

function Overview({ job }: { job: ProductionJob }) {
  return (
    <div className="grid md:grid-cols-2 gap-4">
      <section className="rounded-xl border p-4 space-y-2" style={{ borderColor: "var(--border)" }}>
        <h2 className="text-sm font-bold">Job</h2>
        <p className="text-sm">{job.itemName} · {job.qty} {job.uomSymbol}</p>
        <p className="text-xs" style={{ color: "var(--text-2)" }}>Workflow: {job.routingTemplateName} {job.routingTemplateVersion}</p>
        <p className="text-xs">{job.projectionBasis}</p>
        <StatusChip status={job.risk ?? "on_track"} size="sm" />
      </section>
      <section className="rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
        <h2 className="text-sm font-bold mb-3">Stages</h2>
        <ol className="space-y-2">
          {(job.stages ?? []).map((stage) => (
            <li key={stage.id} className="flex items-center justify-between text-sm">
              <span>{stage.seqNo}. {stage.stageName}</span>
              <StatusChip status={stage.status} size="sm" />
            </li>
          ))}
        </ol>
      </section>
    </div>
  );
}

function Stages({ job, canWrite, onSkip }: { job: ProductionJob; canWrite: boolean; onSkip: (stageId: string, reason: string) => void }) {
  const navigate = useNavigate();
  return (
    <div className="space-y-2">
      {(job.stages ?? []).map((stage) => (
        <div key={stage.id} className="rounded-xl border p-3 flex items-center justify-between gap-3" style={{ borderColor: "var(--border)" }}>
          <div>
            <div className="text-sm font-semibold">{stage.seqNo}. {stage.stageName}</div>
            <div className="text-xs" style={{ color: "var(--text-3)" }}>{stage.workCentre || "Work centre not set"} · Inspection {stage.inspectionStatus}</div>
          </div>
          <div className="flex items-center gap-2">
            <StatusChip status={stage.status} size="sm" />
            {stage.status !== "completed" && stage.status !== "skipped" && <button type="button" className="text-xs font-semibold" onClick={() => navigate(`/jobs/${job.id}/stage/${stage.id}`)}>Update</button>}
            {canWrite && stage.canSkip && stage.status === "pending" && <button type="button" className="text-xs" onClick={() => { const reason = window.prompt("Why is this optional stage skipped?"); if (reason) onSkip(stage.id, reason); }}>Skip</button>}
          </div>
        </div>
      ))}
    </div>
  );
}

function Materials({ job, canIssue, canReturn, onIssue, onReturn }: {
  job: ProductionJob; canIssue: boolean; canReturn: boolean;
  onIssue: (requirementId: string, lotId: string, qty: number, note: string) => void;
  onReturn: (issueId: string, qty: number, note: string) => void;
}) {
  return (
    <div className="space-y-3">
      {(job.materials ?? []).map((row) => <MaterialRow key={row.id} job={job} row={row} canIssue={canIssue} onIssue={onIssue} />)}
      <h3 className="text-sm font-bold">Issues and returns</h3>
      <table className="w-full text-xs">
        <thead><tr><th className="text-left py-1">Type</th><th className="text-left">Qty</th><th className="text-left">Lot</th><th /></tr></thead>
        <tbody>
          {(job.movements ?? []).map((movement) => (
            <tr key={movement.id}>
              <td className="py-1">{movement.issueType}</td>
              <td>{movement.quantity}</td>
              <td className="font-mono">{movement.lotId?.slice(0, 8)}</td>
              <td>{canReturn && movement.issueType === "issue" && <ReturnControl movementId={movement.id} max={movement.quantity} onReturn={onReturn} />}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function MaterialRow({ job, row, canIssue, onIssue }: { job: ProductionJob; row: NonNullable<ProductionJob["materials"]>[number]; canIssue: boolean; onIssue: (requirementId: string, lotId: string, qty: number, note: string) => void }) {
  const { user } = useCustomer();
  const canCreateLot = roleAllowed(user?.roles, ["OWNER", "OPS_MANAGER", "STORE_KEEPER"]);
  const [lots, setLots] = useState<LotOption[]>([]);
  const [lotError, setLotError] = useState("");
  const [lotReload, setLotReload] = useState(0);
  const [lotId, setLotId] = useState("");
  const [qty, setQty] = useState(String(Math.max(row.requiredQty - row.netIssuedQty, 0)));
  useEffect(() => {
    if (!canIssue) return;
    setLotError("");
    usableLots(row.itemId, job.id).then((page) => setLots((page.content ?? []).filter((lot) => {
      if (job.siteId && lot.siteId && lot.siteId !== job.siteId) return false;
      const status = (lot.status || "").toLowerCase();
      if (["quarantine", "rejected", "hold", "qc_hold"].includes(status)) return false;
      const available = Number(lot.availableQty ?? lot.qty ?? 0);
      return available > 0 || status === "reserved";
    })))
      .catch((cause: unknown) => {
        setLots([]);
        setLotError(cause instanceof Error ? cause.message : "Lots could not be loaded.");
      });
  }, [canIssue, row.itemId, job.siteId, job.id, lotReload]);
  return (
    <div className="rounded-xl border p-3" style={{ borderColor: "var(--border)" }}>
      <div className="flex justify-between text-sm"><strong>{row.itemCode} {row.itemName}</strong><StatusChip status={row.readiness} label={readinessLabel(row.readiness)} size="sm" /></div>
      <p className="text-xs mt-1" style={{ color: "var(--text-2)" }}>Required {row.requiredQty} · Issued {row.issuedQty} · Returned {row.returnedQty} {row.uomSymbol}</p>
      {canIssue && row.netIssuedQty < row.requiredQty && (
        <div className="mt-2 flex flex-wrap gap-2 items-end">
          <div className="text-xs">Lot
            <CreatableSelect label="lot" ariaLabel={`Lot for ${row.itemCode}`} value={lotId} onChange={setLotId} placeholder="Select lot" error={lotError} onRetry={() => setLotReload((current) => current + 1)} creatable={canCreateLot} options={lots.map((lot) => ({ id: lot.id, label: lotChoice(lot) }))} prepare={() => lotFields({ itemId: row.itemId, siteId: job.siteId })} onCreate={async (values) => {
              const created = await createLot(values, { itemId: row.itemId });
              const option: LotOption = { id: created.id, itemId: created.itemId, lotNumber: created.lotNumber, batchRef: created.batchRef, qty: created.qty, reservedQty: created.reservedQty, availableQty: created.availableQty, uomSymbol: created.uomSymbol, status: created.status, siteId: created.siteId, storeName: created.storeName };
              setLots((current) => [...current.filter((lot) => lot.id !== option.id), option]);
              return { id: created.id, label: lotChoice(option) };
            }} />
          </div>
          <label className="text-xs">Qty<input className="eoss-input mt-1 w-24" aria-label={`Issue quantity for ${row.itemCode}`} value={qty} onChange={(event) => setQty(event.target.value)} /></label>
          <button type="button" className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} disabled={!lotId || Number(qty) <= 0} onClick={() => onIssue(row.id, lotId, Number(qty), "Issued to job")}>Issue</button>
        </div>
      )}
      {lotError && <p role="alert" className="mt-2 text-xs text-[var(--error)]">{lotError}</p>}
    </div>
  );
}

function lotChoice(lot: LotOption) {
  const identity = lot.lotNumber || lot.batchRef || lot.id.slice(0, 8);
  const available = lot.availableQty ?? lot.qty ?? 0;
  const reserved = Number(lot.reservedQty) > 0 ? ` · ${lot.reservedQty} reserved` : "";
  const unit = lot.uomSymbol ? ` ${lot.uomSymbol}` : "";
  const store = lot.storeName ? ` · ${lot.storeName}` : "";
  return `${identity}${store} · ${available}${unit} available${reserved}`;
}

function ReturnControl({ movementId, max, onReturn }: { movementId: string; max: number; onReturn: (issueId: string, qty: number, note: string) => void }) {
  const [qty, setQty] = useState(String(max));
  return (
    <span className="inline-flex gap-1">
      <input className="eoss-input w-16" aria-label="Return quantity" value={qty} onChange={(event) => setQty(event.target.value)} />
      <button type="button" className="text-xs font-semibold" onClick={() => onReturn(movementId, Number(qty), "Returned from job")}>Return</button>
    </span>
  );
}

function Quality({ job, enabled, canQuality, canConcede, onOpen, onResult, onConcede }: {
  job: ProductionJob; enabled: boolean; canQuality: boolean; canConcede: boolean;
  onOpen: (stageId: string) => void; onResult: (inspectionId: string, result: "pass" | "fail", note: string) => void; onConcede: (inspectionId: string, note: string) => void;
}) {
  if (!enabled) return <p className="text-sm">In-process inspection is turned off for this company.</p>;
  const inspectionStages = (job.stages ?? []).filter((stage) => stage.requiresInspection);
  const inspections = job.inspections ?? [];
  if (inspectionStages.length === 0 && inspections.length === 0) {
    return <p className="text-sm" style={{ color: "var(--text-3)" }}>No quality inspections are configured for this job.</p>;
  }
  return (
    <div className="space-y-3">
      {inspectionStages.map((stage) => (
        <div key={stage.id} className="rounded-xl border p-3 text-sm" style={{ borderColor: "var(--border)" }}>
          <div className="flex justify-between"><span>{stage.stageName}</span><StatusChip status={stage.inspectionStatus} size="sm" /></div>
          {canQuality && stage.inspectionStatus === "pending" && <button type="button" className="text-xs font-semibold mt-2" onClick={() => onOpen(stage.id)}>Open inspection</button>}
        </div>
      ))}
      {inspections.map((inspection) => (
        <div key={inspection.id} className="rounded-xl border p-3 text-sm space-y-2" style={{ borderColor: "var(--border)" }}>
          <div>{inspection.inspectionNumber} · {inspection.inspectionType} · {inspection.result || inspection.status}</div>
          {canQuality && !inspection.result && (
            <div className="flex gap-2">
              <button type="button" onClick={() => onResult(inspection.id, "pass", "Accepted")}>Pass</button>
              <button type="button" onClick={() => onResult(inspection.id, "fail", "Rejected")}>Fail</button>
            </div>
          )}
          {inspection.ncrId && <div>NCR {inspection.ncrStatus}{inspection.concessionStatus ? ` · concession ${inspection.concessionStatus}` : ""}</div>}
          {canConcede && inspection.result === "fail" && inspection.concessionStatus !== "approved" && <button type="button" onClick={() => { const note = window.prompt("Concession note"); if (note) onConcede(inspection.id, note); }}>Approve concession</button>}
        </div>
      ))}
    </div>
  );
}

function Ncr({ job }: { job: ProductionJob }) {
  const rows = (job.inspections ?? []).filter((inspection) => inspection.ncrId);
  if (rows.length === 0) return <p className="text-sm" style={{ color: "var(--text-3)" }}>No non-conformance is recorded for this job.</p>;
  return <ul className="space-y-2 text-sm">{rows.map((row) => <li key={row.id}>{row.inspectionNumber}: {row.ncrStatus}{row.concessionStatus ? `, concession ${row.concessionStatus}` : ""}</li>)}</ul>;
}

function Updates({ job }: { job: ProductionJob }) {
  const rows = [
    ...(job.stages ?? []).filter((stage) => stage.actualStartAt || stage.actualEndAt).map((stage) => `${stage.stageName} ${stage.status} ${displayDate(stage.actualEndAt || stage.actualStartAt)}`),
    ...(job.movements ?? []).map((movement) => `${movement.issueType} ${movement.quantity} on ${displayDate(movement.createdAt)}`),
  ];
  if (rows.length === 0) return <p className="text-sm" style={{ color: "var(--text-3)" }}>No stage or material updates yet.</p>;
  return <ul className="space-y-1 text-sm">{rows.map((row) => <li key={row}>{row}</li>)}</ul>;
}

function Blockers({ job }: { job: ProductionJob }) {
  const failed = (job.stages ?? []).filter((stage) => stage.inspectionStatus === "failed");
  if (job.status !== "on_hold" && job.risk !== "blocked" && failed.length === 0 && job.risk !== "awaiting_material") {
    return <p className="text-sm" style={{ color: "var(--text-3)" }}>No open blocker.</p>;
  }
  return (
    <ul className="text-sm space-y-1">
      {job.status === "on_hold" && <li>The job is on hold.</li>}
      {job.risk === "awaiting_material" && <li>Material is not ready. {job.projectionBasis}</li>}
      {failed.map((stage) => <li key={stage.id}>{stage.stageName} is waiting on a failed inspection.</li>)}
    </ul>
  );
}

function History({ job, canCost, onCost }: { job: ProductionJob; canCost: boolean; onCost: (type: "LABOUR" | "OVERHEAD", description: string, amount: number) => void }) {
  const [type, setType] = useState<"LABOUR" | "OVERHEAD">("LABOUR");
  const [description, setDescription] = useState("");
  const [amount, setAmount] = useState("");
  return (
    <div className="grid md:grid-cols-2 gap-4">
      <section className="text-sm space-y-3 md:col-span-2">
        <h2 className="font-bold">Record</h2>
        <p>Created {displayDate(job.createdAt)} · Started {displayDate(job.actualStart)} · Completed {displayDate(job.actualEnd)}</p>
        <p>Estimated {money(job.estimatedCost?.totalCost)} · Actual {money(job.actualCost?.totalCost)} · Variance {money(job.variance)} · Margin {job.actualCost?.marginPct ?? "—"}%</p>
        <table className="w-full text-left text-xs">
          <caption className="text-left font-semibold mb-1">Stages</caption>
          <thead><tr><th>Stage</th><th>Work centre</th><th>Asset</th><th>Status</th></tr></thead>
          <tbody>{(job.stages ?? []).map((stage) => <tr key={stage.id}><td>{stage.stageName}</td><td>{stage.workCentre || "—"}</td><td>{stage.assetCode || "—"}</td><td>{stage.status}</td></tr>)}</tbody>
        </table>
        <table className="w-full text-left text-xs">
          <caption className="text-left font-semibold mb-1">Material movements</caption>
          <thead><tr><th>Item</th><th>Type</th><th>Qty</th><th>When</th></tr></thead>
          <tbody>{(job.movements ?? []).map((movement) => <tr key={movement.id}><td>{movement.lotId || "—"}</td><td>{movement.issueType}</td><td>{movement.quantity}</td><td>{displayDate(movement.createdAt)}</td></tr>)}</tbody>
        </table>
        <table className="w-full text-left text-xs">
          <caption className="text-left font-semibold mb-1">Materials</caption>
          <thead><tr><th>Item</th><th>Required</th><th>Issued</th><th>Returned</th></tr></thead>
          <tbody>{(job.materials ?? []).map((material) => <tr key={material.id}><td>{material.itemCode}</td><td>{material.requiredQty}</td><td>{material.issuedQty}</td><td>{material.returnedQty}</td></tr>)}</tbody>
        </table>
      </section>
      {canCost && (
        <form className="space-y-2" onSubmit={(event) => { event.preventDefault(); onCost(type, description, Number(amount)); }}>
          <h2 className="text-sm font-bold">Add labour or overhead</h2>
          <select className="eoss-input w-full" aria-label="Cost type" value={type} onChange={(event) => setType(event.target.value as "LABOUR" | "OVERHEAD")}><option value="LABOUR">Labour</option><option value="OVERHEAD">Overhead</option></select>
          <input className="eoss-input w-full" aria-label="Cost description" value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Description" />
          <input className="eoss-input w-full" aria-label="Cost amount" value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="Amount" />
          <button type="submit" className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} disabled={!description || Number(amount) < 0}>Save cost</button>
        </form>
      )}
    </div>
  );
}
