import { useEffect, useRef, useState } from "react";
import { useNavigate, useParams, useSearchParams } from "react-router-dom";
import { ArrowLeft, ExternalLink } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import { ApiError } from "../auth/api";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import RecordSheet from "../components/shared/RecordSheet";
import StatusChip from "../components/shared/StatusChip";
import { createLot, lotFields } from "../inventory/inlineCreate";
import { createInspection as createQualityInspection, recordResult as recordQualityInspectionResult } from "../quality/api";
import {
  addCost, cancelJob, completeJob, concede, displayDate, featureFlags, getJob, getJobCosting, issueMaterial, money, openInspection, previewPlan, readinessLabel,
  recordInspection, releaseHold, returnMaterial, savePlan, skipStage, usableLots,
  type JobCosting, type LotOption, type ProductionJob, type StagePlanEntry,
} from "./api";

const TABS = ["Overview", "Stages", "Plan", "Materials", "Quality", "NCR / Rework", "Updates", "Blockers", "History"];

function healthColor(job: ProductionJob): "green" | "amber" | "red" {
  if (job.status === "cancelled" || job.status === "completed") return "green";
  if (job.status === "on_hold" || job.risk === "blocked") return "red";
  if (job.risk === "delayed" || (job.daysRemaining != null && job.daysRemaining < 0)) return "red";
  if (job.risk === "at_risk" || job.risk === "awaiting_material") return "amber";
  return "green";
}

function varianceClass(pct?: number) {
  if (pct == null) return "";
  if (pct > 20) return "text-[var(--error)]";
  if (pct < -20) return "text-[var(--success)]";
  return "text-[var(--text-2)]";
}

function stageVariance(stage: { actualStartAt?: string; actualEndAt?: string; estimatedDurationMin?: number }): number | undefined {
  if (!stage.estimatedDurationMin || !stage.actualStartAt) return undefined;
  const start = new Date(stage.actualStartAt).getTime();
  const end = stage.actualEndAt ? new Date(stage.actualEndAt).getTime() : Date.now();
  const actualMin = (end - start) / 60000;
  return Math.round(((actualMin - stage.estimatedDurationMin) / stage.estimatedDurationMin) * 100);
}

export default function JobWorkspacePage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user } = useCustomer();
  const roles = user?.roles.map((role) => role.role) ?? [];
  const canWrite = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR"].includes(role));
  const canQuality = roles.some((role) => ["OWNER", "OPS_MANAGER", "QUALITY_INSPECTOR"].includes(role));
  const canConcede = roles.some((role) => ["OWNER", "OPS_MANAGER"].includes(role));
  const canViewCost = roles.some((role) => ["OWNER", "OPS_MANAGER", "SALES_MANAGER", "PURCHASE_MANAGER", "ACCOUNTS_STAFF"].includes(role));
  const canEditCost = roles.some((role) => ["OWNER", "OPS_MANAGER", "ACCOUNTS_STAFF"].includes(role));
  const [job, setJob] = useState<ProductionJob | null>(null);
  const [flags, setFlags] = useState<Record<string, boolean>>({});
  const initialTab = searchParams.get("tab") ?? "Overview";
  const [tab, setTab] = useState(TABS.includes(initialTab) ? initialTab : "Overview");
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
  useEffect(() => {
    if (searchParams.get("update") === "1" && job) setTab("Stages");
    else if (searchParams.get("tab") === "Plan" && job) setTab("Plan");
  }, [searchParams, job]);

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
      {tab === "Plan" && <PlanTab job={job} canWrite={canWrite} onSaved={() => { setNotice("Production plan saved"); void load(); }} />}
      {tab === "Materials" && <Materials job={job} canIssue={canWrite && flags["production.material.issue"] !== false} canReturn={canWrite && flags["production.material.return"] !== false} onIssue={(requirementId, lotId, qty, note) => act(() => issueMaterial(job.id, requirementId, lotId, qty, note), "Material issued")} onReturn={(issueId, qty, note) => act(() => returnMaterial(job.id, issueId, qty, note), "Material returned")} />}
      {tab === "Quality" && <Quality
        job={job}
        enabled={flags["production.inspection.gate"] !== false}
        canQuality={canQuality}
        canConcede={canConcede}
        onOpen={(stageId) => act(() => openInspection(job.id, stageId), "Inspection opened")}
        onOpenFinal={() => act(() => createQualityInspection({
          inspectionType: "final",
          referenceType: "production_job",
          referenceId: job.id,
          ...(job.itemId ? { itemId: job.itemId } : {}),
          quantityInspected: Number(job.qty ?? 0),
          notes: "Final production inspection",
        }), "Final inspection opened")}
        onResult={(inspectionId, result, note) => act(() => recordInspection(job.id, inspectionId, result, note), "Inspection recorded")}
        onFinalResult={(inspectionId, result) => void finalizeInspection(inspectionId, result)}
        onConcede={(inspectionId, note) => act(() => concede(job.id, inspectionId, note), "Concession approved")}
      />}
      {tab === "NCR / Rework" && <Ncr job={job} />}
      {tab === "Updates" && <Updates job={job} />}
      {tab === "Blockers" && <Blockers job={job} />}
      {tab === "History" && <History
        job={job}
        canViewCost={canViewCost && flags["production.job.costing"] !== false}
        canEditCost={canEditCost && flags["production.job.costing"] !== false && !closed}
        onCost={(type, description, amount) => act(() => addCost(job.id, type, description, amount), "Cost recorded")}
      />}
      {cancelOpen && <CancelDialog job={job} onClose={() => setCancelOpen(false)} onDone={() => { setCancelOpen(false); void load(); }} />}
    </div>
  );

  async function act(call: () => Promise<unknown>, done: string): Promise<boolean> {
    try {
      await call();
      setNotice(done);
      await load();
      return true;
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "The change was not saved");
      return false;
    }
  }

  async function finalizeInspection(inspectionId: string, result: "pass" | "fail") {
    if (!job) return;
    const saved = await act(() => recordQualityInspectionResult(inspectionId, {
      result,
      quantityAccepted: result === "pass" ? Number(job.qty ?? 0) : 0,
      quantityRejected: result === "fail" ? Number(job.qty ?? 0) : 0,
      notes: result === "pass" ? "Final quantity accepted" : "Final inspection failed",
    }), "Final inspection recorded");
    const stagesClosed = (job.stages ?? []).length > 0
      && (job.stages ?? []).every((stage) => stage.status === "completed" || stage.status === "skipped");
    if (saved && result === "pass" && stagesClosed && job.status === "in_progress"
        && job.materialReadiness === "issued") {
      await act(() => completeJob(job.id), "Job completed");
    }
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
  const hColor = healthColor(job);
  const hStyle = { color: hColor === "green" ? "var(--success)" : hColor === "amber" ? "var(--warning)" : "var(--error)" };
  const hLabel = hColor === "green" ? "On Track" : hColor === "amber" ? "Needs Attention" : "Delayed / Blocked";
  const stages = job.stages ?? [];
  const completedStages = stages.filter((s) => s.status === "completed" || s.status === "skipped").length;
  const totalStages = stages.length;
  const progressPct = totalStages > 0 ? Math.round((completedStages / totalStages) * 100) : 0;

  // Determine health reason
  const reasons: string[] = [];
  if (job.status === "on_hold") reasons.push(job.holdReason ? `On hold: ${job.holdReason}` : "Job is on hold");
  if (job.risk === "awaiting_material") reasons.push("Material is not ready");
  if (job.daysRemaining != null && job.daysRemaining < 0) reasons.push(`${Math.abs(job.daysRemaining)} days past planned completion`);
  if (stages.some((s) => s.blockerReason)) reasons.push("Machine or asset blocked");
  if (stages.some((s) => s.inspectionStatus === "failed")) reasons.push("Failed inspection awaiting resolution");

  return (
    <div className="space-y-4">
      {/* Job Health Card */}
      <section className="rounded-xl border p-4 space-y-3" style={{ borderColor: hColor === "green" ? "var(--border)" : hColor === "amber" ? "var(--warning)" : "var(--error)" }}>
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-bold">Job Health</h2>
          <span className="text-sm font-bold" style={hStyle}>{hLabel}</span>
        </div>
        {reasons.length > 0 && (
          <ul className="text-xs space-y-0.5" style={{ color: hColor === "amber" ? "var(--warning)" : "var(--error)" }}>
            {reasons.map((r) => <li key={r}>· {r}</li>)}
          </ul>
        )}
        <div>
          <div className="flex justify-between text-xs mb-1" style={{ color: "var(--text-3)" }}>
            <span>Stage progress</span>
            <span>{completedStages} / {totalStages} stages · {progressPct}%</span>
          </div>
          <div className="h-2 rounded-full overflow-hidden" style={{ background: "var(--surface-2)" }}>
            <div className="h-full rounded-full" style={{ width: `${progressPct}%`, background: hColor === "green" ? "var(--success)" : hColor === "amber" ? "var(--warning)" : "var(--error)" }} />
          </div>
        </div>
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
          <div><span style={{ color: "var(--text-3)" }}>Total qty</span><div className="font-semibold">{job.qty} {job.uomSymbol}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Delivery date</span><div className="font-semibold" style={job.daysRemaining != null && job.daysRemaining < 0 ? { color: "var(--error)" } : {}}>{displayDate(job.plannedEnd)}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Material</span><div className="font-semibold">{readinessLabel(job.materialReadiness)}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Risk</span><div className="font-semibold">{job.risk ? job.risk.replace(/_/g, " ") : "on track"}</div></div>
        </div>
      </section>

      <div className="grid md:grid-cols-2 gap-4">
        <section className="rounded-xl border p-4 space-y-2" style={{ borderColor: "var(--border)" }}>
          <h2 className="text-sm font-bold">Job Details</h2>
          <p className="text-sm">{job.itemName} · {job.qty} {job.uomSymbol}</p>
          <p className="text-xs" style={{ color: "var(--text-2)" }}>Workflow: {job.routingTemplateName} {job.routingTemplateVersion}</p>
          {job.projectionBasis && <p className="text-xs" style={{ color: "var(--text-3)" }}>{job.projectionBasis}</p>}
        </section>
        <section className="rounded-xl border p-4" style={{ borderColor: "var(--border)" }}>
          <h2 className="text-sm font-bold mb-3">Stage Progress</h2>
          <ol className="space-y-2">
            {stages.map((stage) => {
              const v = stageVariance(stage);
              return (
                <li key={stage.id} className="flex items-center justify-between text-xs">
                  <span className="flex-1">{stage.seqNo}. {stage.stageName}</span>
                  {v != null && <span className={`mr-2 font-mono ${varianceClass(v)}`}>{v > 0 ? "+" : ""}{v}%</span>}
                  <StatusChip status={stage.status} size="sm" />
                </li>
              );
            })}
          </ol>
        </section>
      </div>
    </div>
  );
}

function Stages({ job, canWrite, onSkip }: { job: ProductionJob; canWrite: boolean; onSkip: (stageId: string, reason: string) => void }) {
  const navigate = useNavigate();
  return (
    <div className="space-y-2">
      {(job.stages ?? []).map((stage) => {
        const v = stageVariance(stage);
        const totalQty = Number(job.qty ?? 0);
        const doneQty = stage.completedQty != null ? Number(stage.completedQty) : null;
        const blockedQty = stage.blockedQty != null ? Number(stage.blockedQty) : null;
        const inProgressQty = doneQty != null ? Math.max(0, totalQty - doneQty - (blockedQty ?? 0)) : null;
        return (
          <div key={stage.id} className="rounded-xl border p-3" style={{ borderColor: "var(--border)" }}>
            <div className="flex items-start justify-between gap-3">
              <div className="flex-1 min-w-0">
                <div className="text-sm font-semibold">{stage.seqNo}. {stage.stageName}</div>
                <div className="text-xs mt-0.5 flex flex-wrap gap-x-3 gap-y-0.5" style={{ color: "var(--text-3)" }}>
                  <span>{stage.workCentre || "No work centre"}</span>
                  {stage.assignedOperator && <span>Operator: <strong>{stage.assignedOperator}</strong></span>}
                  {stage.plannedStart && <span>Planned: {displayDate(stage.plannedStart)} → {displayDate(stage.plannedEnd)}</span>}
                  {stage.estimatedDurationMin != null && <span>Est: {(stage.estimatedDurationMin / 60).toFixed(1)}h</span>}
                  {stage.actualStartAt && <span>Started: {displayDate(stage.actualStartAt)}</span>}
                  {stage.actualEndAt && <span>Ended: {displayDate(stage.actualEndAt)}</span>}
                </div>
                {/* Quantity row – only shown when captured */}
                {doneQty != null && (
                  <div className="text-xs mt-1 flex gap-3" style={{ color: "var(--text-2)" }}>
                    <span>Total: <strong>{totalQty}</strong></span>
                    <span>Done: <strong>{doneQty}</strong></span>
                    {blockedQty != null && blockedQty > 0 && <span style={{ color: "var(--warning)" }}>Blocked: <strong>{blockedQty}</strong></span>}
                    {inProgressQty != null && inProgressQty > 0 && <span>In Progress: <strong>{inProgressQty}</strong></span>}
                  </div>
                )}
                {stage.blockerReason && <div role="status" className="text-xs mt-1" style={{ color: "var(--error)" }}>Blocked: {stage.blockerReason}</div>}
              </div>
              <div className="flex items-center gap-2 flex-shrink-0">
                {v != null && <span className={`text-xs font-mono ${varianceClass(v)}`}>{v > 0 ? "+" : ""}{v}%</span>}
                <StatusChip status={stage.status} size="sm" />
                {stage.status !== "completed" && stage.status !== "skipped" && <button type="button" className="text-xs font-semibold" style={{ color: "var(--brand)" }} onClick={() => navigate(`/jobs/${job.id}/stage/${stage.id}`)}>Update</button>}
                {canWrite && stage.canSkip && stage.status === "pending" && <button type="button" className="text-xs" onClick={() => { const reason = window.prompt("Why is this optional stage skipped?"); if (reason) onSkip(stage.id, reason); }}>Skip</button>}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

type PlanRow = { stageId: string; stageName: string; workCentre?: string; estimatedDurationMin?: number; plannedStart: string; plannedEnd: string };

function buildPlanRows(job: ProductionJob, deliveryDate: string): PlanRow[] {
  const stages = job.stages ?? [];
  if (stages.length === 0) return [];
  const rows: PlanRow[] = stages.map((s) => ({
    stageId: s.id,
    stageName: s.stageName,
    workCentre: s.workCentre,
    estimatedDurationMin: s.estimatedDurationMin,
    plannedStart: s.plannedStart ?? "",
    plannedEnd: s.plannedEnd ?? "",
  }));
  // Backward schedule: work from delivery date, 8h = 1 working day
  let cursor = new Date(deliveryDate);
  for (let i = rows.length - 1; i >= 0; i--) {
    const durationDays = Math.max(1, Math.ceil((rows[i].estimatedDurationMin ?? 480) / 480));
    const end = new Date(cursor);
    cursor = new Date(cursor);
    cursor.setDate(cursor.getDate() - durationDays);
    rows[i].plannedEnd = end.toISOString().split("T")[0];
    rows[i].plannedStart = cursor.toISOString().split("T")[0];
  }
  return rows;
}

function PlanTab({ job, canWrite, onSaved }: { job: ProductionJob; canWrite: boolean; onSaved: () => void }) {
  const stages = job.stages ?? [];
  const hasPlanning = stages.some((s) => s.plannedStart);
  const [editing, setEditing] = useState(!hasPlanning);
  const [deliveryDate, setDeliveryDate] = useState(job.plannedEnd?.split("T")[0] ?? "");
  const [rows, setRows] = useState<PlanRow[]>(() =>
    hasPlanning
      ? stages.map((s) => ({ stageId: s.id, stageName: s.stageName, workCentre: s.workCentre, estimatedDurationMin: s.estimatedDurationMin, plannedStart: s.plannedStart ?? "", plannedEnd: s.plannedEnd ?? "" }))
      : []
  );
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");
  const generatedRef = useRef(false);

  // Auto-generate schedule when redirected from job creation (no plan yet)
  useEffect(() => {
    if (!hasPlanning && deliveryDate && !generatedRef.current && stages.length > 0) {
      generatedRef.current = true;
      setRows(buildPlanRows(job, deliveryDate));
    }
  }, [hasPlanning, deliveryDate, stages.length]);

  function generate() {
    if (!deliveryDate) { setErr("Enter a delivery date first"); return; }
    setErr("");
    setRows(buildPlanRows(job, deliveryDate));
  }

  function updateRow(idx: number, field: "plannedStart" | "plannedEnd", value: string) {
    setRows((prev) => prev.map((r, i) => i === idx ? { ...r, [field]: value } : r));
  }

  async function save() {
    if (rows.some((r) => !r.plannedStart || !r.plannedEnd)) { setErr("All stages need a planned start and end date"); return; }
    setSaving(true);
    setErr("");
    try {
      await savePlan(job.id, rows);
      setEditing(false);
      onSaved();
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : "Plan could not be saved");
    } finally {
      setSaving(false);
    }
  }

  // ── View mode ────────────────────────────────────────────────────────────────
  if (!editing) {
    return (
      <div className="space-y-3">
        <div className="flex items-center justify-between">
          <p className="text-xs" style={{ color: "var(--text-3)" }}>Stage-by-stage schedule · dates are working-day estimates</p>
          {canWrite && <button type="button" onClick={() => setEditing(true)} className="px-3 py-1.5 rounded-lg border text-xs font-semibold" style={{ borderColor: "var(--border)" }}>Edit Plan</button>}
        </div>
        <div className="rounded-xl border overflow-x-auto" style={{ borderColor: "var(--border)" }}>
          <table className="w-full text-xs">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr>{["Stage", "Work Centre", "Planned Start", "Planned End", "Est. Duration", "Actual Start", "Actual End", "Variance", "Status"].map((h) => (
                <th key={h} className="text-left px-3 py-2 font-semibold whitespace-nowrap" style={{ color: "var(--text-3)" }}>{h}</th>
              ))}</tr>
            </thead>
            <tbody>
              {stages.map((stage) => {
                const v = stageVariance(stage);
                return (
                  <tr key={stage.id} className="border-t" style={{ borderColor: "var(--border)" }}>
                    <td className="px-3 py-2 font-medium whitespace-nowrap">{stage.seqNo}. {stage.stageName}</td>
                    <td className="px-3 py-2" style={{ color: "var(--text-3)" }}>{stage.workCentre || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.plannedStart) || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.plannedEnd) || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{stage.estimatedDurationMin != null ? `${(stage.estimatedDurationMin / 60).toFixed(1)}h` : "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.actualStartAt) || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.actualEndAt) || "—"}</td>
                    <td className={`px-3 py-2 font-mono ${varianceClass(v)}`}>{v != null ? `${v > 0 ? "+" : ""}${v}%` : "—"}</td>
                    <td className="px-3 py-2"><StatusChip status={stage.status} size="sm" /></td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </div>
    );
  }

  // ── Edit / Create mode ────────────────────────────────────────────────────────
  return (
    <div className="space-y-4 max-w-3xl">
      {/* Prompt banner for new plan */}
      {!hasPlanning && (
        <div className="rounded-xl border-2 p-4" style={{ borderColor: "var(--brand)", background: "var(--surface-2)" }}>
          <p className="text-sm font-semibold mb-1">Set up the production plan</p>
          <p className="text-xs" style={{ color: "var(--text-3)" }}>Enter the delivery date and generate a backward-scheduled stage plan. You can adjust individual dates before saving.</p>
        </div>
      )}

      {/* Delivery date + generate */}
      <div className="flex items-end gap-3">
        <label className="text-xs font-semibold flex-1 max-w-xs">
          Delivery date
          <input type="date" className="eoss-input w-full mt-1" value={deliveryDate} onChange={(e) => setDeliveryDate(e.target.value)} />
        </label>
        <button type="button" onClick={generate} className="px-3 py-2 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}>
          Generate schedule
        </button>
      </div>

      {/* Stage rows */}
      {rows.length > 0 && (
        <div className="rounded-xl border overflow-hidden" style={{ borderColor: "var(--border)" }}>
          <table className="w-full text-xs">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr>
                {["Stage", "Work Centre", "Est. Duration", "Planned Start", "Planned End"].map((h) => (
                  <th key={h} className="text-left px-3 py-2 font-semibold" style={{ color: "var(--text-3)" }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, idx) => (
                <tr key={row.stageId} className="border-t" style={{ borderColor: "var(--border)" }}>
                  <td className="px-3 py-2 font-medium">{idx + 1}. {row.stageName}</td>
                  <td className="px-3 py-2" style={{ color: "var(--text-3)" }}>{row.workCentre || "—"}</td>
                  <td className="px-3 py-2">{row.estimatedDurationMin != null ? `${(row.estimatedDurationMin / 60).toFixed(1)}h` : "—"}</td>
                  <td className="px-2 py-1.5"><input type="date" className="eoss-input w-full" value={row.plannedStart} onChange={(e) => updateRow(idx, "plannedStart", e.target.value)} /></td>
                  <td className="px-2 py-1.5"><input type="date" className="eoss-input w-full" value={row.plannedEnd} onChange={(e) => updateRow(idx, "plannedEnd", e.target.value)} /></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {rows.length === 0 && (
        <p className="text-xs py-4 text-center" style={{ color: "var(--text-3)" }}>Enter a delivery date and click Generate schedule to build the plan.</p>
      )}

      {err && <p className="text-xs" style={{ color: "var(--error)" }}>{err}</p>}

      <div className="flex gap-3 pt-1">
        {hasPlanning && <button type="button" onClick={() => setEditing(false)} className="px-4 py-2 rounded-lg border text-xs" style={{ borderColor: "var(--border)" }}>Cancel</button>}
        <button type="button" onClick={() => void save()} disabled={saving || rows.length === 0} className="px-5 py-2 rounded-lg text-xs font-semibold text-white disabled:opacity-50" style={{ background: "var(--brand-fill)" }}>
          {saving ? "Saving…" : "Save Plan"}
        </button>
      </div>
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
  const [lotsLoading, setLotsLoading] = useState(false);
  const [lotReload, setLotReload] = useState(0);
  const [lotId, setLotId] = useState("");
  const [qty, setQty] = useState(String(Math.max(row.requiredQty - row.netIssuedQty, 0)));
  useEffect(() => {
    if (!canIssue) return;
    setLotError("");
    setLotsLoading(true);
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
      })
      .finally(() => setLotsLoading(false));
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
          {lotsLoading && <p role="status" className="text-xs">Loading usable lots…</p>}
          {!lotsLoading && !lotError && lots.length === 0 && <p className="text-xs" style={{ color: "var(--text-3)" }}>No usable lots found. Quarantined and held lots are excluded.</p>}
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

function Quality({ job, enabled, canQuality, canConcede, onOpen, onOpenFinal, onResult, onFinalResult, onConcede }: {
  job: ProductionJob; enabled: boolean; canQuality: boolean; canConcede: boolean;
  onOpen: (stageId: string) => void; onResult: (inspectionId: string, result: "pass" | "fail", note: string) => void; onConcede: (inspectionId: string, note: string) => void;
  onOpenFinal: () => void;
  onFinalResult: (inspectionId: string, result: "pass" | "fail") => void;
}) {
  const inspectionStages = enabled ? (job.stages ?? []).filter((stage) => stage.requiresInspection) : [];
  const inspections = job.inspections ?? [];
  const finalInspection = inspections.find((inspection) => !inspection.stageId
    && ["final", "dispatch_readiness"].includes((inspection.inspectionType ?? "").toLowerCase()));
  return (
    <div className="space-y-3">
      {!enabled && <p className="text-sm">In-process inspection is turned off for this company. Final inspection is still required before job completion.</p>}
      {inspectionStages.map((stage) => (
        <div key={stage.id} className="rounded-xl border p-3 text-sm" style={{ borderColor: "var(--border)" }}>
          <div className="flex justify-between"><span>{stage.stageName}</span><StatusChip status={stage.inspectionStatus} size="sm" /></div>
          {canQuality && stage.inspectionStatus === "pending" && <button type="button" className="text-xs font-semibold mt-2" onClick={() => onOpen(stage.id)}>Open inspection</button>}
        </div>
      ))}
      {inspections.map((inspection) => (
        <div key={inspection.id} className="rounded-xl border p-3 text-sm space-y-2" style={{ borderColor: "var(--border)" }}>
          <div>{inspection.stageId ? `${job.stages?.find((stage) => stage.id === inspection.stageId)?.stageName ?? "Stage"} · ` : "Job · "}{inspection.inspectionNumber} · {inspection.inspectionType} · {inspection.result || inspection.status}</div>
          {inspection.quantityInspected != null && <div className="text-xs">Inspected {inspection.quantityInspected} · Accepted {inspection.quantityAccepted ?? "—"} · Rejected {inspection.quantityRejected ?? "—"}</div>}
          {canQuality && !inspection.result && inspection.stageId && (
            <div className="flex gap-2">
              <button type="button" onClick={() => onResult(inspection.id, "pass", "Accepted")}>Pass</button>
              <button type="button" onClick={() => onResult(inspection.id, "fail", "Rejected")}>Fail</button>
            </div>
          )}
          {canQuality && !inspection.result && !inspection.stageId
            && ["final", "dispatch_readiness"].includes((inspection.inspectionType ?? "").toLowerCase()) && (
              <div className="flex gap-2">
                <button type="button" onClick={() => onFinalResult(inspection.id, "pass")}>Pass final inspection</button>
                <button type="button" onClick={() => onFinalResult(inspection.id, "fail")}>Fail final inspection</button>
              </div>
            )}
          {inspection.ncrId && <div>NCR {inspection.ncrStatus}{inspection.concessionStatus ? ` · concession ${inspection.concessionStatus}` : ""}</div>}
          {canConcede && inspection.stageId && inspection.result === "fail" && inspection.concessionStatus !== "approved" && <button type="button" onClick={() => { const note = window.prompt("Concession note"); if (note) onConcede(inspection.id, note); }}>Approve concession</button>}
        </div>
      ))}
      {!finalInspection && (
        <section className="rounded-xl border p-3 text-sm" style={{ borderColor: "var(--border)" }}>
          <div className="font-semibold">Final inspection required</div>
          <p className="text-xs mt-1" style={{ color: "var(--text-2)" }}>Inspect and accept the full job quantity before the job can be completed.</p>
          {canQuality && <button type="button" className="text-xs font-semibold mt-2" onClick={onOpenFinal}>Open final inspection</button>}
        </section>
      )}
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
  const machines = (job.stages ?? []).filter((stage) => stage.blockerReason);
  const stagesClosed = (job.stages ?? []).length > 0
    && (job.stages ?? []).every((stage) => stage.status === "completed" || stage.status === "skipped");
  const finalPassed = (job.inspections ?? []).some((inspection) =>
    !inspection.stageId
    && ["final", "dispatch_readiness"].includes((inspection.inspectionType ?? "").toLowerCase())
    && inspection.result === "pass"
    && Number(inspection.quantityAccepted ?? 0) >= Number(job.qty ?? 0));
  if (job.status !== "on_hold" && job.risk !== "blocked" && failed.length === 0
      && job.risk !== "awaiting_material" && machines.length === 0
      && !(stagesClosed && !finalPassed && job.status !== "completed")) {
    return <p className="text-sm" style={{ color: "var(--text-3)" }}>No open blocker.</p>;
  }
  return (
    <ul className="text-sm space-y-1">
      {job.status === "on_hold" && <li>The job is on hold{job.holdReason ? `: ${job.holdReason}` : "."}</li>}
      {job.risk === "awaiting_material" && <li>Material is not ready. {job.projectionBasis}</li>}
      {failed.map((stage) => <li key={stage.id}>{stage.stageName} is waiting on a failed inspection.</li>)}
      {machines.map((stage) => <li key={stage.id}>{stage.stageName} is blocked by machine downtime: {stage.blockerReason}</li>)}
      {stagesClosed && !finalPassed && job.status !== "completed" && <li>A passing final inspection covering all {job.qty} units is required before job completion.</li>}
    </ul>
  );
}

function History({ job, canViewCost, canEditCost, onCost }: {
  job: ProductionJob;
  canViewCost: boolean;
  canEditCost: boolean;
  onCost: (type: "LABOUR" | "OVERHEAD", description: string, amount: number) => Promise<boolean>;
}) {
  const [type, setType] = useState<"LABOUR" | "OVERHEAD">("LABOUR");
  const [description, setDescription] = useState("");
  const [amount, setAmount] = useState("");
  const [costing, setCosting] = useState<JobCosting | null>(null);
  const [costError, setCostError] = useState("");
  const [costLoading, setCostLoading] = useState(false);

  async function loadCosting() {
    setCostLoading(true);
    setCostError("");
    try {
      setCosting(await getJobCosting(job.id));
    } catch (cause) {
      setCostError(cause instanceof ApiError ? cause.message : "Costing could not be loaded");
    } finally {
      setCostLoading(false);
    }
  }

  useEffect(() => {
    if (canViewCost) void loadCosting();
  }, [job.id, canViewCost]);

  return (
    <div className="grid md:grid-cols-2 gap-4">
      <section className="text-sm space-y-3 md:col-span-2">
        <h2 className="font-bold">Record</h2>
        <p>Created {displayDate(job.createdAt)} · Started {displayDate(job.actualStart)} · Completed {displayDate(job.actualEnd)}</p>
        {canViewCost && (
          <section aria-label="Job profitability" className="rounded-lg border p-3 space-y-3" style={{ borderColor: "var(--border)" }}>
            <h3 className="font-bold">Order profitability</h3>
            {costLoading && <p>Loading costing…</p>}
            {costError && <p role="alert" style={{ color: "var(--error)" }}>{costError}</p>}
            {costing && (
              <>
                <p className="text-xs" style={{ color: "var(--text-2)" }}>{costing.estimatedCostBasis}</p>
                <div className="grid sm:grid-cols-2 gap-3">
                  <div>
                    <h4 className="font-semibold">Estimated</h4>
                    <p>Revenue: {money(costing.estimatedCost.sellingPrice)}</p>
                    <p>Material: {money(costing.estimatedCost.materialCost)} · {costStatus(costing.estimatedCost.materialStatus)}</p>
                    <p>Labour: {money(costing.estimatedCost.labourCost)} · {costStatus(costing.estimatedCost.labourStatus)}</p>
                    <p>Overhead: {money(costing.estimatedCost.overheadCost)} · {costStatus(costing.estimatedCost.overheadStatus)}</p>
                    <p>Total: {money(costing.estimatedCost.totalCost)} · Profit: {money(costing.estimatedProfit)} · Margin: {costing.estimatedCost.marginPct == null ? "—" : `${costing.estimatedCost.marginPct}%`}</p>
                  </div>
                  <div>
                    <h4 className="font-semibold">Actual</h4>
                    <p>Revenue (locked to Customer PO): {money(costing.actualCost.sellingPrice)}</p>
                    <p>Material: {money(costing.actualCost.materialCost)} · {costStatus(costing.actualCost.materialStatus)}</p>
                    <p>Labour: {money(costing.actualCost.labourCost)} · {costStatus(costing.actualCost.labourStatus)}</p>
                    <p>Overhead: {money(costing.actualCost.overheadCost)} · {costStatus(costing.actualCost.overheadStatus)}</p>
                    <p>Total: {money(costing.actualCost.totalCost)} · Profit: {money(costing.actualProfit)} · Margin: {costing.actualCost.marginPct == null ? "—" : `${costing.actualCost.marginPct}%`}</p>
                    <p>Variance (actual − estimated): {money(costing.variance)}</p>
                  </div>
                </div>
                <div>
                  <h4 className="font-semibold">Historical reference</h4>
                  {costing.historicalReference.completedOrderCount === 0
                    ? <p>No fully costed earlier jobs match this item and routing.</p>
                    : <p>{costing.historicalReference.completedOrderCount} completed jobs · {money(costing.historicalReference.totalActualCost)} / {costing.historicalReference.totalQuantity} units = weighted average actual cost/unit {money(costing.historicalReference.weightedAverageUnitCost)}</p>}
                  <p className="text-xs" style={{ color: "var(--text-2)" }}>{costing.historicalReference.basis}</p>
                </div>
                {costing.entries.length > 0 && (
                  <div>
                    <h4 className="font-semibold">Cost entries</h4>
                    <ul className="text-xs space-y-1">
                      {costing.entries.map((entry) => (
                        <li key={entry.id}>{entry.estimated ? "Estimate" : "Actual"} · {entry.entryType} · {entry.description} · {money(entry.amount)} · {displayDate(entry.createdAt)}</li>
                      ))}
                    </ul>
                  </div>
                )}
              </>
            )}
          </section>
        )}
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
      {canEditCost && (
        <form className="space-y-2" onSubmit={async (event) => {
          event.preventDefault();
          if (await onCost(type, description, Number(amount))) {
            await loadCosting();
            setDescription("");
            setAmount("");
          }
        }}>
          <h2 className="text-sm font-bold">Add labour or overhead</h2>
          <select className="eoss-input w-full" aria-label="Cost type" value={type} onChange={(event) => setType(event.target.value as "LABOUR" | "OVERHEAD")}><option value="LABOUR">Labour</option><option value="OVERHEAD">Overhead</option></select>
          <input className="eoss-input w-full" aria-label="Cost description / reason" value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Description or adjustment reason" />
          <input className="eoss-input w-full" aria-label="Cost amount" type="number" min="0" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="Amount (enter 0 to confirm no cost)" />
          <button type="submit" className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} disabled={!description.trim() || amount === "" || Number(amount) < 0}>Save cost</button>
        </form>
      )}
    </div>
  );
}

function costStatus(status?: string) {
  if (!status) return "Status unavailable";
  return ({
    captured: "Captured",
    complete: "Complete",
    captured_to_date: "Captured to date",
    not_recorded: "Not recorded",
    incomplete: "Incomplete",
    missing_unit_cost: "Missing inventory unit cost",
    not_applicable: "Not applicable",
  } as Record<string, string>)[status] ?? status.replace(/_/g, " ");
}
