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
  addCost, allocateJobMaterials, cancelJob, completeJob, completeStage, concede, displayDate, featureFlags, getJob, getJobCosting, holdJob, issueMaterial, loadOperators, money, openInspection, overrideMaterialPrice, previewPlanForJob,
  progressStage, readinessLabel, recordInspection, releaseHold, releaseJobMaterials, returnMaterial, savePlan, skipStage, usableLots,
  type JobCosting, type LotOption, type OperatorOption, type ProductionJob,
} from "./api";

const TABS = ["Overview", "Stages", "Plan", "Materials", "Quality", "NCR / Rework", "Updates", "Blockers", "Costing", "History"];

function stageActualDuration(stage: { actualStartAt?: string; actualEndAt?: string }): string {
  if (!stage.actualStartAt) return "—";
  const start = new Date(stage.actualStartAt).getTime();
  const end = stage.actualEndAt ? new Date(stage.actualEndAt).getTime() : Date.now();
  const min = Math.max(0, Math.round((end - start) / 60000));
  if (min < 60) return `${min}m`;
  return `${(min / 60).toFixed(1)}h`;
}

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

function stageDateVarianceDays(stage: { plannedEnd?: string; actualEndAt?: string }): number | null {
  if (!stage.plannedEnd || !stage.actualEndAt) return null;
  const planned = new Date(stage.plannedEnd).getTime();
  const actual = new Date(stage.actualEndAt).getTime();
  return Math.round((actual - planned) / 86400000);
}

function stageHealth(stage: { status: string; plannedStart?: string; plannedEnd?: string; actualEndAt?: string }): "green" | "amber" | "red" | null {
  if (!stage.plannedEnd) return null;
  if (stage.status === "completed") {
    if (!stage.actualEndAt) return null;
    const days = (new Date(stage.actualEndAt).getTime() - new Date(stage.plannedEnd).getTime()) / 86400000;
    if (days <= 0) return "green";
    if (days <= 1) return "amber";
    return "red";
  }
  if (stage.status === "in_progress") {
    const days = (Date.now() - new Date(stage.plannedEnd).getTime()) / 86400000;
    if (days < 0) return "green";
    if (days <= 1) return "amber";
    return "red";
  }
  // pending: check if plannedStart has already passed
  if (stage.plannedStart && Date.now() > new Date(stage.plannedStart).getTime()) return "amber";
  return null;
}

const HEALTH_COLORS = { green: "var(--success)", amber: "var(--warning)", red: "var(--error)" };
function HealthDot({ color }: { color: "green" | "amber" | "red" }) {
  return <span style={{ display: "inline-block", width: 8, height: 8, borderRadius: "50%", background: HEALTH_COLORS[color], flexShrink: 0 }} />;
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
  const [updateStageId, setUpdateStageId] = useState<string | null>(null);
  const canExecute = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR", "FIELD_WORKER"].includes(role));
  const canComplete = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR", "QUALITY_INSPECTOR"].includes(role));
  const canHold = canWrite;

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
        <div className="flex items-center gap-2 flex-wrap">
          {job.status === "on_hold" && canWrite && <button type="button" className="px-4 py-2 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => act(() => releaseHold(job.id, "Hold released"), "Hold released")}>Release hold</button>}
          {!closed && canWrite && <button type="button" className="px-3 py-2 rounded-lg text-xs font-semibold border" style={{ borderColor: "var(--error)", color: "var(--error)" }} onClick={() => setCancelOpen(true)}>Cancel job</button>}
          {!closed && canWrite && (() => {
            const hasPlan = (job.stages ?? []).some((s) => s.plannedStart);
            return (
              <button type="button" className="px-3 py-2 rounded-lg text-xs font-semibold border" style={{ borderColor: hasPlan ? "var(--border)" : "var(--warning)", color: hasPlan ? "var(--text-2)" : "var(--warning)" }} onClick={() => { setTab("Plan"); }}>
                {hasPlan ? "Edit plan" : "Set up plan"}
              </button>
            );
          })()}
          {!closed && <button type="button" className="px-4 py-2 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => setUpdateStageId(currentStage(job)?.id ?? null)}>Record progress</button>}
        </div>
      </div>
      {notice && <p role="status" className="text-sm" style={{ color: "var(--success)" }}>{notice}</p>}
      {error && <p role="alert" className="text-sm" style={{ color: "var(--error)" }}>{error}</p>}
      <div className="flex gap-2 overflow-x-auto border-b" style={{ borderColor: "var(--border)" }}>
        {TABS.map((name) => <button key={name} type="button" onClick={() => setTab(name)} className="px-3 py-2 text-xs font-semibold whitespace-nowrap" style={{ color: tab === name ? "var(--brand)" : "var(--text-2)", borderBottom: tab === name ? "2px solid var(--brand)" : "2px solid transparent" }}>{name}</button>)}
      </div>
      {tab === "Overview" && <Overview job={job} onUpdate={(stageId) => setUpdateStageId(stageId)} />}
      {tab === "Stages" && <Stages job={job} canWrite={canWrite && flags["production.stage.transition"] !== false} onSkip={(stageId, reason) => act(() => skipStage(job.id, stageId, reason), "Stage skipped")} onUpdate={(stageId) => setUpdateStageId(stageId)} />}
      {tab === "Plan" && <PlanTab job={job} canWrite={canWrite} onSaved={() => { setNotice("Production plan saved"); void load(); }} />}
      {tab === "Materials" && (
        <Materials
          job={job}
          canIssue={canWrite && flags["production.material.issue"] !== false}
          canReturn={canWrite && flags["production.material.return"] !== false}
          canEditCost={canEditCost}
          onAllocate={() => act(() => allocateJobMaterials(job.id), "Stock allocated & reserved for job")}
          onRelease={() => act(() => releaseJobMaterials(job.id), "Job reservations released")}
          onIssue={(requirementId, lotId, qty, note) => act(() => issueMaterial(job.id, requirementId, lotId, qty, note), "Material issued")}
          onReturn={(issueId, qty, note) => act(() => returnMaterial(job.id, issueId, qty, note), "Material returned")}
          onPriceOverride={(issueId, unitCost, reason) => act(() => overrideMaterialPrice(job.id, issueId, unitCost, reason), "Price overridden")}
        />
      )}
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
      {tab === "Costing" && <CostingTab
        job={job}
        canViewCost={canViewCost && flags["production.job.costing"] !== false}
        canEditCost={canEditCost && flags["production.job.costing"] !== false && !closed}
        onCost={(type, description, amount) => act(() => addCost(job.id, type as "LABOUR" | "OVERHEAD" | "OTHER", description, amount), "Cost recorded")}
      />}
      {tab === "History" && <History job={job} />}
      {cancelOpen && <CancelDialog job={job} onClose={() => setCancelOpen(false)} onDone={() => { setCancelOpen(false); void load(); }} />}
      {updateStageId && <StageUpdateSheet
        job={job}
        stageId={updateStageId}
        enabled={flags["production.stage.transition"] !== false}
        canExecute={canExecute}
        canHold={canHold}
        canComplete={canComplete}
        onClose={() => setUpdateStageId(null)}
        onDone={() => { setUpdateStageId(null); void load(); }}
      />}
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

function Overview({ job, onUpdate }: { job: ProductionJob; onUpdate?: (stageId: string) => void }) {
  const hColor = healthColor(job);
  const hStyle = { color: hColor === "green" ? "var(--success)" : hColor === "amber" ? "var(--warning)" : "var(--error)" };
  const hLabel = hColor === "green" ? "On Track" : hColor === "amber" ? "Needs Attention" : "Delayed / Blocked";
  const stages = job.stages ?? [];
  const completedStages = stages.filter((s) => s.status === "completed" || s.status === "skipped").length;
  const totalStages = stages.length;
  const totalQty = Number(job.qty ?? 0);

  // Overall job progress based on actual production completion
  const lastStage = stages.length > 0 ? stages[stages.length - 1] : null;
  const overallCompletedQty = job.status === "completed"
    ? totalQty
    : lastStage != null
      ? (Number(lastStage.completedQty) || (lastStage.status === "completed" ? totalQty : 0))
      : 0;
  const overallPendingQty = Math.max(0, totalQty - overallCompletedQty);
  const overallProgressPct = totalQty > 0 ? Math.min(100, Math.round((overallCompletedQty / totalQty) * 100)) : 0;

  // Determine health reason
  const reasons: string[] = [];
  if (job.status === "on_hold") reasons.push(job.holdReason ? `On hold: ${job.holdReason}` : "Job is on hold");
  if (job.risk === "awaiting_material") reasons.push("Material is not ready");
  if (job.daysRemaining != null && job.daysRemaining < 0) reasons.push(`${Math.abs(job.daysRemaining)} days past planned completion`);
  if (stages.some((s) => s.blockerReason)) reasons.push("Machine or asset blocked");
  if (stages.some((s) => s.inspectionStatus === "failed")) reasons.push("Failed inspection awaiting resolution");

  return (
    <div className="space-y-4">
      {/* Overall Production Progress & Health Card */}
      <section className="rounded-xl border p-4 space-y-4" style={{ borderColor: hColor === "green" ? "var(--border)" : hColor === "amber" ? "var(--warning)" : "var(--error)" }}>
        <div className="flex items-center justify-between pb-2 border-b border-[var(--border)]">
          <div>
            <h2 className="text-sm font-bold">Production Execution Progress</h2>
            <p className="text-xs text-[var(--text-2)]">Overall completion based on actual finished units</p>
          </div>
          <div className="flex items-center gap-2">
            <HealthDot color={hColor} />
            <span className="text-sm font-bold" style={hStyle}>{hLabel}</span>
          </div>
        </div>

        {reasons.length > 0 && (
          <ul className="text-xs space-y-0.5" style={{ color: hColor === "amber" ? "var(--warning)" : "var(--error)" }}>
            {reasons.map((r) => <li key={r}>· {r}</li>)}
          </ul>
        )}

        {/* Quantities summary cards */}
        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
          <div className="rounded-lg border p-2.5" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
            <span style={{ color: "var(--text-3)" }}>Total Production Qty</span>
            <div className="text-base font-bold">{totalQty} {job.uomSymbol}</div>
          </div>
          <div className="rounded-lg border p-2.5" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
            <span style={{ color: "var(--text-3)" }}>Overall Completed</span>
            <div className="text-base font-bold text-[var(--success)]">{overallCompletedQty} {job.uomSymbol}</div>
          </div>
          <div className="rounded-lg border p-2.5" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
            <span style={{ color: "var(--text-3)" }}>Overall Pending</span>
            <div className="text-base font-bold text-[var(--warning)]">{overallPendingQty} {job.uomSymbol}</div>
          </div>
          <div className="rounded-lg border p-2.5" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
            <span style={{ color: "var(--text-3)" }}>Overall Completion</span>
            <div className="text-base font-bold text-[var(--brand)]">{overallProgressPct}%</div>
          </div>
        </div>

        {/* Progress bar */}
        <div>
          <div className="flex justify-between text-xs mb-1" style={{ color: "var(--text-3)" }}>
            <span>Job completion: <strong>{overallCompletedQty} / {totalQty} {job.uomSymbol}</strong></span>
            <span>{overallProgressPct}% · {completedStages}/{totalStages} stages closed</span>
          </div>
          <div className="h-2.5 rounded-full overflow-hidden" style={{ background: "var(--surface-2)" }}>
            <div className="h-full rounded-full transition-all" style={{ width: `${overallProgressPct}%`, background: "var(--brand-fill)" }} />
          </div>
        </div>

        <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs pt-1 border-t border-[var(--border)]">
          <div><span style={{ color: "var(--text-3)" }}>Delivery date</span><div className="font-semibold" style={job.daysRemaining != null && job.daysRemaining < 0 ? { color: "var(--error)" } : {}}>{displayDate(job.plannedEnd)}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Material readiness</span><div className="font-semibold">{readinessLabel(job.materialReadiness)}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Current stage</span><div className="font-semibold">{job.currentStage || "—"}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Risk status</span><div className="font-semibold capitalize">{job.risk ? job.risk.replace(/_/g, " ") : "on track"}</div></div>
        </div>
      </section>

      {/* Stage-Wise Execution Progress Table */}
      <section className="rounded-xl border overflow-hidden" style={{ borderColor: "var(--border)" }}>
        <div className="px-4 py-3 border-b flex items-center justify-between" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
          <div>
            <h3 className="text-sm font-bold">Stage-Wise Execution Progress</h3>
            <p className="text-xs text-[var(--text-2)]">Track progress, durations, quantities, and operators across all manufacturing stages</p>
          </div>
        </div>
        <div className="overflow-x-auto">
          <table className="w-full text-xs text-left">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr className="text-[var(--text-3)]">
                <th className="px-3 py-2">Stage</th>
                <th className="px-3 py-2">Planned Duration</th>
                <th className="px-3 py-2">Actual Duration</th>
                <th className="px-3 py-2">Completed Qty</th>
                <th className="px-3 py-2">Pending Qty</th>
                <th className="px-3 py-2">Progress</th>
                <th className="px-3 py-2">Assigned Operator</th>
                <th className="px-3 py-2">Status</th>
                <th className="px-3 py-2 text-right">Action</th>
              </tr>
            </thead>
            <tbody>
              {stages.map((stage) => {
                const stageDone = stage.completedQty != null ? Number(stage.completedQty) : (stage.status === "completed" ? totalQty : 0);
                const stagePending = Math.max(0, totalQty - stageDone - Number(stage.blockedQty || 0));
                const stagePct = totalQty > 0 ? Math.min(100, Math.round((stageDone / totalQty) * 100)) : 0;
                const plannedDur = stage.estimatedDurationMin != null ? `${(stage.estimatedDurationMin / 60).toFixed(1)}h` : "—";
                const actualDur = stageActualDuration(stage);
                const v = stageVariance(stage);

                return (
                  <tr key={stage.id} className="border-t hover:bg-[var(--surface-2)]" style={{ borderColor: "var(--border)" }}>
                    <td className="px-3 py-2.5 font-medium whitespace-nowrap">
                      <span className="flex items-center gap-1.5">
                        {stageHealth(stage) && <HealthDot color={stageHealth(stage)!} />}
                        {stage.seqNo}. {stage.stageName}
                      </span>
                      {stage.workCentre && <span className="text-[10px] block text-[var(--text-3)]">{stage.workCentre}</span>}
                    </td>
                    <td className="px-3 py-2.5 whitespace-nowrap">{plannedDur}</td>
                    <td className="px-3 py-2.5 whitespace-nowrap">
                      <span>{actualDur}</span>
                      {v != null && <span className={`ml-1 font-mono text-[11px] ${varianceClass(v)}`}>({v > 0 ? "+" : ""}{v}%)</span>}
                    </td>
                    <td className="px-3 py-2.5 font-semibold text-[var(--success)]">{stageDone}</td>
                    <td className="px-3 py-2.5 text-[var(--text-2)]">
                      {stagePending}
                      {stage.blockedQty != null && Number(stage.blockedQty) > 0 && (
                        <span className="ml-1 text-[var(--warning)]" title={`Blocked: ${stage.blockedQty}`}>({stage.blockedQty} blk)</span>
                      )}
                    </td>
                    <td className="px-3 py-2.5 whitespace-nowrap">
                      <div className="flex items-center gap-1.5">
                        <div className="w-12 h-1.5 rounded-full overflow-hidden bg-[var(--surface-2)]">
                          <div className="h-full rounded-full" style={{ width: `${stagePct}%`, background: stagePct === 100 ? "var(--success)" : "var(--brand)" }} />
                        </div>
                        <span className="font-mono text-[11px]">{stagePct}%</span>
                      </div>
                    </td>
                    <td className="px-3 py-2.5 whitespace-nowrap">{stage.assignedOperator || <span className="text-[var(--text-3)]">Unassigned</span>}</td>
                    <td className="px-3 py-2.5 whitespace-nowrap"><StatusChip status={stage.status} size="sm" /></td>
                    <td className="px-3 py-2.5 text-right whitespace-nowrap">
                      {stage.status !== "completed" && stage.status !== "skipped" && onUpdate && (
                        <button type="button" className="text-xs font-semibold px-2 py-0.5 rounded text-[var(--brand)] hover:underline" onClick={() => onUpdate(stage.id)}>
                          Update
                        </button>
                      )}
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>
      </section>

      {/* Job Details & CPO Traceability */}
      <div className="grid md:grid-cols-2 gap-4">
        <section className="rounded-xl border p-4 space-y-2" style={{ borderColor: "var(--border)" }}>
          <h2 className="text-sm font-bold">Job &amp; Product Specification</h2>
          <p className="text-sm font-medium">{job.itemName} · {job.qty} {job.uomSymbol}</p>
          <p className="text-xs text-[var(--text-2)]">Manufacturing Route: {job.routingTemplateName} (Version {job.routingTemplateVersion})</p>
          <p className="text-xs text-[var(--text-2)]">Target Site: {job.siteName || "—"}</p>
          {job.priority && <p className="text-xs text-[var(--text-2)]">Priority: <strong className="capitalize">{job.priority}</strong></p>}
          {job.projectionBasis && <p className="text-xs text-[var(--text-3)]">{job.projectionBasis}</p>}
        </section>
        <section className="rounded-xl border p-4 space-y-2" style={{ borderColor: "var(--border)" }}>
          <h2 className="text-sm font-bold">Traceability &amp; Commercial Reference</h2>
          <p className="text-sm font-medium">Customer: {job.customerName}</p>
          <p className="text-xs text-[var(--text-2)]">Originating Order: {job.cpoNumber || "Standalone Job"}</p>
          <p className="text-xs text-[var(--text-2)]">Selling Value: {money(job.actualCost?.sellingPrice)}</p>
          <p className="text-xs text-[var(--text-2)]">Job Number: <span className="font-mono">{job.jobNumber}</span></p>
        </section>
      </div>
    </div>
  );
}

function Stages({ job, canWrite, onSkip, onUpdate }: { job: ProductionJob; canWrite: boolean; onSkip: (stageId: string, reason: string) => void; onUpdate: (stageId: string) => void }) {
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
                <div className="text-sm font-semibold flex items-center gap-1.5">
                  {stageHealth(stage) && <HealthDot color={stageHealth(stage)!} />}
                  {stage.seqNo}. {stage.stageName}
                </div>
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
                {stage.status !== "completed" && stage.status !== "skipped" && <button type="button" className="text-xs font-semibold" style={{ color: "var(--brand)" }} onClick={() => onUpdate(stage.id)}>Update</button>}
                {canWrite && stage.canSkip && stage.status === "pending" && <button type="button" className="text-xs" onClick={() => { const reason = window.prompt("Why is this optional stage skipped?"); if (reason) onSkip(stage.id, reason); }}>Skip</button>}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

type PlanRow = {
  stageId: string;
  stageName: string;
  workCentre?: string;
  estimatedDurationMin?: number;
  plannedStart: string;
  plannedEnd: string;
  operatorId?: string;
};

function PlanTab({ job, canWrite, onSaved }: { job: ProductionJob; canWrite: boolean; onSaved: () => void }) {
  const stages = job.stages ?? [];
  const hasPlanning = stages.some((s) => s.plannedStart);
  const [editing, setEditing] = useState(!hasPlanning);
  const [deliveryDate, setDeliveryDate] = useState(job.plannedEnd?.split("T")[0] ?? "");
  const [operators, setOperators] = useState<OperatorOption[]>([]);
  const [rows, setRows] = useState<PlanRow[]>(() =>
    hasPlanning
      ? stages.map((s) => ({
          stageId: s.id,
          stageName: s.stageName,
          workCentre: s.workCentre,
          estimatedDurationMin: s.estimatedDurationMin,
          plannedStart: s.plannedStart ?? "",
          plannedEnd: s.plannedEnd ?? "",
          operatorId: s.operatorId,
        }))
      : []
  );
  const [generating, setGenerating] = useState(false);
  const [saving, setSaving] = useState(false);
  const [err, setErr] = useState("");
  const generatedRef = useRef(false);

  useEffect(() => {
    void loadOperators().then((res) => setOperators(Array.isArray(res) ? res : [])).catch(() => setOperators([]));
  }, []);

  // Auto-generate schedule when redirected from job creation (no plan yet)
  useEffect(() => {
    if (!hasPlanning && deliveryDate && !generatedRef.current && stages.length > 0) {
      generatedRef.current = true;
      void generate();
    }
  }, [hasPlanning, deliveryDate, stages.length]);

  async function generate() {
    if (!deliveryDate) { setErr("Enter a delivery date first"); return; }
    setErr("");
    setGenerating(true);
    try {
      const preview = await previewPlanForJob(job.id, deliveryDate);
      setRows(preview.map((s) => {
        const existing = stages.find((st) => st.id === s.stageId);
        return {
          stageId: s.stageId,
          stageName: s.stageName,
          workCentre: existing?.workCentre || "",
          estimatedDurationMin: s.estimatedDurationMin || existing?.estimatedDurationMin || 0,
          plannedStart: s.plannedStart,
          plannedEnd: s.plannedEnd,
          operatorId: existing?.operatorId,
        };
      }));
    } catch (cause) {
      setErr(cause instanceof Error ? cause.message : "Could not generate schedule");
    } finally {
      setGenerating(false);
    }
  }

  function updateRow<K extends keyof PlanRow>(idx: number, field: K, value: PlanRow[K]) {
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
              <tr>{["Stage", "Work Centre", "Assigned Operator", "Planned Start", "Planned End", "Est. Duration", "Actual Start", "Actual End", "Schedule", "Effort %", "Status"].map((h) => (
                <th key={h} className="text-left px-3 py-2 font-semibold whitespace-nowrap" style={{ color: "var(--text-3)" }}>{h}</th>
              ))}</tr>
            </thead>
            <tbody>
              {stages.map((stage) => {
                const v = stageVariance(stage);
                const health = stageHealth(stage);
                const dateDiff = stageDateVarianceDays(stage);
                return (
                  <tr key={stage.id} className="border-t" style={{ borderColor: "var(--border)" }}>
                    <td className="px-3 py-2 font-medium whitespace-nowrap">
                      <span className="flex items-center gap-1.5">
                        {health && <HealthDot color={health} />}
                        {stage.seqNo}. {stage.stageName}
                      </span>
                    </td>
                    <td className="px-3 py-2" style={{ color: "var(--text-3)" }}>{stage.workCentre || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{stage.assignedOperator || <span style={{ color: "var(--text-3)" }}>Unassigned</span>}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.plannedStart) || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.plannedEnd) || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{stage.estimatedDurationMin != null ? `${(stage.estimatedDurationMin / 60).toFixed(1)}h` : "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.actualStartAt) || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap">{displayDate(stage.actualEndAt) || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-mono" style={{ color: dateDiff == null ? "var(--text-3)" : dateDiff > 0 ? "var(--error)" : "var(--success)" }}>
                      {dateDiff == null ? "—" : dateDiff === 0 ? "On time" : `${dateDiff > 0 ? "+" : ""}${dateDiff}d`}
                    </td>
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
    <div className="space-y-4 max-w-4xl">
      {/* Prompt banner for new plan */}
      {!hasPlanning && (
        <div className="rounded-xl border-2 p-4" style={{ borderColor: "var(--brand)", background: "var(--surface-2)" }}>
          <p className="text-sm font-semibold mb-1">Set up the production plan</p>
          <p className="text-xs" style={{ color: "var(--text-3)" }}>Enter the delivery date and generate a backward-scheduled stage plan. You can assign operators and adjust dates before saving.</p>
        </div>
      )}

      {/* Delivery date + generate */}
      <div className="flex items-end gap-3">
        <label className="text-xs font-semibold flex-1 max-w-xs">
          Delivery date
          <input type="date" className="eoss-input w-full mt-1" value={deliveryDate} onChange={(e) => setDeliveryDate(e.target.value)} />
        </label>
        <button type="button" onClick={() => void generate()} disabled={generating} className="px-3 py-2 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)", opacity: generating ? 0.6 : 1 }}>
          {generating ? "Generating…" : "Generate schedule"}
        </button>
      </div>

      {/* Stage rows */}
      {rows.length > 0 && (
        <div className="rounded-xl border overflow-hidden" style={{ borderColor: "var(--border)" }}>
          <table className="w-full text-xs">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr>
                {["Stage", "Work Centre", "Est. Duration (h)", "Assigned Operator", "Planned Start", "Planned End"].map((h) => (
                  <th key={h} className="text-left px-3 py-2 font-semibold whitespace-nowrap" style={{ color: "var(--text-3)" }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {rows.map((row, idx) => (
                <tr key={row.stageId} className="border-t" style={{ borderColor: "var(--border)" }}>
                  <td className="px-3 py-2 font-medium whitespace-nowrap">{idx + 1}. {row.stageName}</td>
                  <td className="px-2 py-1.5 min-w-[130px]">
                    <input
                      className="eoss-input w-full text-xs"
                      value={row.workCentre || ""}
                      onChange={(e) => updateRow(idx, "workCentre", e.target.value)}
                      placeholder="Work Centre"
                      aria-label={`Work Centre for ${row.stageName}`}
                    />
                  </td>
                  <td className="px-2 py-1.5 w-24">
                    <input
                      className="eoss-input w-full text-xs"
                      type="number"
                      min="0"
                      step="0.5"
                      value={row.estimatedDurationMin != null ? Number((row.estimatedDurationMin / 60).toFixed(2)) : ""}
                      onChange={(e) => updateRow(idx, "estimatedDurationMin", e.target.value ? Math.round(Number(e.target.value) * 60) : 0)}
                      placeholder="Hours"
                      aria-label={`Duration for ${row.stageName}`}
                    />
                  </td>
                  <td className="px-2 py-1.5 min-w-[150px]">
                    <select
                      className="eoss-input w-full text-xs"
                      value={row.operatorId ?? ""}
                      onChange={(e) => updateRow(idx, "operatorId", e.target.value || undefined)}
                      aria-label={`Assigned Operator for ${row.stageName}`}
                    >
                      <option value="">-- Unassigned --</option>
                      {(Array.isArray(operators) ? operators : []).map((op) => (
                        <option key={op.id} value={op.id}>{op.name || op.email}</option>
                      ))}
                    </select>
                  </td>
                  <td className="px-2 py-1.5 min-w-[130px]"><input type="date" className="eoss-input w-full text-xs" value={row.plannedStart} onChange={(e) => updateRow(idx, "plannedStart", e.target.value)} /></td>
                  <td className="px-2 py-1.5 min-w-[130px]"><input type="date" className="eoss-input w-full text-xs" value={row.plannedEnd} onChange={(e) => updateRow(idx, "plannedEnd", e.target.value)} /></td>
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

function Materials({
  job,
  canIssue,
  canReturn,
  canEditCost,
  onAllocate,
  onRelease,
  onIssue,
  onReturn,
  onPriceOverride,
}: {
  job: ProductionJob;
  canIssue: boolean;
  canReturn: boolean;
  canEditCost: boolean;
  onAllocate?: () => void;
  onRelease?: () => void;
  onIssue: (requirementId: string, lotId: string, qty: number, note: string) => void;
  onReturn: (issueId: string, qty: number, note: string) => void;
  onPriceOverride: (issueId: string, unitCost: number, reason: string) => Promise<boolean>;
}) {
  const materials = job.materials ?? [];
  const movements = job.movements ?? [];
  const [allocating, setAllocating] = useState(false);
  const [releasing, setReleasing] = useState(false);

  const totalEstCost = materials.reduce(
    (sum, m) => sum + (m.estimatedTotalCost ?? (m.requiredQty * (m.estimatedUnitCost ?? 0))),
    0
  );
  const totalActCost = materials.reduce(
    (sum, m) => sum + (m.actualTotalCost ?? (m.netIssuedQty * (m.actualUnitCost ?? 0))),
    0
  );
  const totalCostVar = totalActCost - totalEstCost;

  return (
    <div className="space-y-4">
      {/* Material Allocation & Reservation Action Header */}
      <section className="rounded-xl border p-4 space-y-3" style={{ borderColor: "var(--border)", background: "var(--surface-2)" }}>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2">
              <h3 className="text-sm font-bold">Material Allocation &amp; Reservation</h3>
              <StatusChip status={job.materialReadiness || "pending"} label={readinessLabel(job.materialReadiness)} size="sm" />
            </div>
            <p className="text-xs mt-1" style={{ color: "var(--text-3)" }}>
              Reserve available warehouse stock lots for this job's material requirements to ensure readiness before execution.
            </p>
          </div>
          <div className="flex items-center gap-2">
            {onAllocate && (
              <button
                type="button"
                className="px-3.5 py-2 rounded-lg text-xs font-semibold text-white disabled:opacity-50"
                style={{ background: "var(--brand-fill)" }}
                disabled={allocating || job.status === "completed" || job.status === "cancelled"}
                onClick={async () => {
                  setAllocating(true);
                  try {
                    await onAllocate();
                  } finally {
                    setAllocating(false);
                  }
                }}
              >
                {allocating ? "Allocating…" : "Allocate Material (Reserve Stock)"}
              </button>
            )}
            {onRelease && (
              <button
                type="button"
                className="px-3 py-2 rounded-lg text-xs font-semibold border disabled:opacity-50"
                style={{ borderColor: "var(--border)", color: "var(--text-2)" }}
                disabled={releasing || job.status === "completed" || job.status === "cancelled"}
                onClick={async () => {
                  setReleasing(true);
                  try {
                    await onRelease();
                  } finally {
                    setReleasing(false);
                  }
                }}
              >
                {releasing ? "Releasing…" : "Release Reservations"}
              </button>
            )}
          </div>
        </div>
      </section>

      {/* Material Requirements: Estimated vs Actual Tracking */}
      <section className="space-y-2">
        <h3 className="text-sm font-bold">Material Requirements: Estimated vs Actual Tracking</h3>
        <div className="rounded-xl border overflow-x-auto" style={{ borderColor: "var(--border)" }}>
          <table className="w-full text-xs">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr>
                {["Component Item", "UOM", "Required (Est)", "Issued (Act)", "Returned", "Net Consumed", "Qty Var", "Est. Unit Cost", "Act. Unit Cost", "Est. Total Cost", "Act. Total Cost", "Cost Var", "Status"].map((h) => (
                  <th key={h} className="text-left px-3 py-2 font-semibold whitespace-nowrap" style={{ color: "var(--text-3)" }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {materials.map((m) => {
                const estTot = m.estimatedTotalCost ?? (m.requiredQty * (m.estimatedUnitCost ?? 0));
                const actTot = m.actualTotalCost ?? (m.netIssuedQty * (m.actualUnitCost ?? 0));
                const qtyVar = m.netIssuedQty - m.requiredQty;
                const costVar = actTot - estTot;
                return (
                  <tr key={m.id} className="border-t hover:bg-[var(--surface-2)]" style={{ borderColor: "var(--border)" }}>
                    <td className="px-3 py-2 font-medium whitespace-nowrap">
                      <div>{m.itemCode}</div>
                      <div className="text-[10px] text-[var(--text-3)]">{m.itemName}</div>
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap">{m.uomSymbol || "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-semibold">{m.requiredQty}</td>
                    <td className="px-3 py-2 whitespace-nowrap text-[var(--success)]">{m.issuedQty}</td>
                    <td className="px-3 py-2 whitespace-nowrap text-[var(--text-2)]">{m.returnedQty}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-semibold">{m.netIssuedQty}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-mono" style={{ color: qtyVar === 0 ? "var(--text-3)" : qtyVar > 0 ? "var(--error)" : "var(--success)" }}>
                      {qtyVar > 0 ? `+${qtyVar}` : qtyVar}
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap font-mono">{m.estimatedUnitCost != null ? money(m.estimatedUnitCost) : "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-mono">{m.actualUnitCost != null ? money(m.actualUnitCost) : "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-mono">{estTot > 0 ? money(estTot) : "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-mono">{actTot > 0 ? money(actTot) : "—"}</td>
                    <td className="px-3 py-2 whitespace-nowrap font-mono" style={{ color: costVar === 0 ? "var(--text-3)" : costVar > 0 ? "var(--error)" : "var(--success)" }}>
                      {costVar > 0 ? `+${money(costVar)}` : money(costVar)}
                    </td>
                    <td className="px-3 py-2 whitespace-nowrap">
                      <StatusChip status={m.readiness} label={readinessLabel(m.readiness)} size="sm" />
                    </td>
                  </tr>
                );
              })}
              {materials.length === 0 && (
                <tr>
                  <td colSpan={13} className="px-3 py-4 text-center text-xs" style={{ color: "var(--text-3)" }}>
                    No material requirements configured for this job.
                  </td>
                </tr>
              )}
            </tbody>
            {materials.length > 0 && (
              <tfoot style={{ background: "var(--surface-2)" }}>
                <tr className="border-t font-semibold" style={{ borderColor: "var(--border)" }}>
                  <td colSpan={9} className="px-3 py-2 text-right">Total Material Cost:</td>
                  <td className="px-3 py-2 font-mono">{money(totalEstCost)}</td>
                  <td className="px-3 py-2 font-mono">{money(totalActCost)}</td>
                  <td className="px-3 py-2 font-mono" style={{ color: totalCostVar <= 0 ? "var(--success)" : "var(--error)" }}>
                    {totalCostVar > 0 ? `+${money(totalCostVar)}` : money(totalCostVar)}
                  </td>
                  <td />
                </tr>
              </tfoot>
            )}
          </table>
        </div>
      </section>

      {/* Issue Material from Usable Lots */}
      <section className="space-y-2">
        <h3 className="text-sm font-bold">Issue Material from Usable Lots</h3>
        <div className="space-y-3">
          {materials.map((row) => (
            <MaterialRow key={row.id} job={job} row={row} canIssue={canIssue} onIssue={onIssue} />
          ))}
        </div>
      </section>

      {/* Issues and returns log */}
      <section className="space-y-2">
        <h3 className="text-sm font-bold">Issues and Returns Log</h3>
        <div className="rounded-xl border overflow-x-auto" style={{ borderColor: "var(--border)" }}>
          <table className="w-full text-xs">
            <thead style={{ background: "var(--surface-2)" }}>
              <tr>
                {["Type", "Qty", "Unit Cost", "Lot", "Actions"].map((h) => (
                  <th key={h} className="text-left px-3 py-2 font-semibold" style={{ color: "var(--text-3)" }}>{h}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {movements.map((movement) => (
                <tr key={movement.id} className="border-t" style={{ borderColor: "var(--border)" }}>
                  <td className="px-3 py-2 capitalize font-medium">{movement.issueType}</td>
                  <td className="px-3 py-2 font-semibold">{movement.quantity}</td>
                  <td className="px-3 py-2 font-mono">{movement.unitCost != null ? money(movement.unitCost) : "—"}</td>
                  <td className="px-3 py-2 font-mono">{movement.lotId?.slice(0, 8)}</td>
                  <td className="px-3 py-2">
                    <div className="flex gap-2">
                      {canReturn && movement.issueType === "issue" && <ReturnControl movementId={movement.id} max={movement.quantity} onReturn={onReturn} />}
                      {canEditCost && movement.issueType === "issue" && <PriceOverrideControl movementId={movement.id} onOverride={onPriceOverride} />}
                    </div>
                  </td>
                </tr>
              ))}
              {movements.length === 0 && (
                <tr>
                  <td colSpan={5} className="px-3 py-3 text-center text-xs" style={{ color: "var(--text-3)" }}>
                    No material issues or returns recorded yet.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

function PriceOverrideControl({ movementId, onOverride }: { movementId: string; onOverride: (issueId: string, unitCost: number, reason: string) => Promise<boolean> }) {
  const [open, setOpen] = useState(false);
  const [unitCost, setUnitCost] = useState("");
  const [reason, setReason] = useState("");
  if (!open) return <button type="button" className="text-xs" style={{ color: "var(--brand)" }} onClick={() => setOpen(true)}>Override price</button>;
  return (
    <span className="inline-flex flex-wrap gap-1 items-end">
      <input className="eoss-input w-24" aria-label="Override unit cost" type="number" min="0.001" step="0.001" value={unitCost} onChange={(event) => setUnitCost(event.target.value)} placeholder="Unit cost" />
      <input className="eoss-input w-32" aria-label="Override reason" value={reason} onChange={(event) => setReason(event.target.value)} placeholder="Reason" />
      <button type="button" className="text-xs font-semibold" style={{ color: "var(--brand)" }} disabled={!unitCost || !reason.trim() || Number(unitCost) <= 0} onClick={async () => { if (await onOverride(movementId, Number(unitCost), reason.trim())) { setOpen(false); setUnitCost(""); setReason(""); } }}>Save</button>
      <button type="button" className="text-xs" onClick={() => setOpen(false)}>Cancel</button>
    </span>
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
      {(row.estimatedUnitCost != null || row.actualUnitCost != null) && (
        <div className="mt-1 grid grid-cols-2 gap-x-4 text-xs" style={{ color: "var(--text-3)" }}>
          {row.estimatedUnitCost != null && <span>Est. unit cost: <strong style={{ color: "var(--text)" }}>{money(row.estimatedUnitCost)}</strong></span>}
          {row.estimatedTotalCost != null && <span>Est. total: <strong style={{ color: "var(--text)" }}>{money(row.estimatedTotalCost)}</strong></span>}
          {row.actualUnitCost != null && <span>Actual unit cost: <strong style={{ color: "var(--text)" }}>{money(row.actualUnitCost)}</strong></span>}
          {row.actualTotalCost != null && <span>Actual total: <strong style={{ color: "var(--text)" }}>{money(row.actualTotalCost)}</strong></span>}
        </div>
      )}
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
    ...(job.maintenanceActivity ?? []).map((activity) =>
      `${activity.action === "MACHINE_BLOCK" ? "Machine blocked by" : "Machine block cleared by"} ${activity.eventNumber} · ${displayDate(activity.at)}`),
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

function CostingTab({ job, canViewCost, canEditCost, onCost }: {
  job: ProductionJob;
  canViewCost: boolean;
  canEditCost: boolean;
  onCost: (type: "LABOUR" | "OVERHEAD" | "OTHER", description: string, amount: number) => Promise<boolean>;
}) {
  const [type, setType] = useState<"LABOUR" | "OVERHEAD" | "OTHER">("LABOUR");
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

  if (!canViewCost) return <p className="text-sm" style={{ color: "var(--text-3)" }}>You do not have permission to view costing for this job.</p>;

  const otherActual = (costing?.entries ?? [])
    .filter((e) => !e.estimated && (e.entryType === "OTHER" || e.entryType === "MISC"))
    .reduce((sum, e) => sum + Number(e.amount || 0), 0);
  const otherEstimated = (costing?.entries ?? [])
    .filter((e) => e.estimated && (e.entryType === "OTHER" || e.entryType === "MISC"))
    .reduce((sum, e) => sum + Number(e.amount || 0), 0);

  const actMat = Number(costing?.actualCost.materialCost || 0);
  const actLab = Number(costing?.actualCost.labourCost || 0);
  const actOvh = Number(costing?.actualCost.overheadCost || 0);
  const runningActualTotal = actMat + actLab + actOvh;
  const actualTotal = costing?.actualCost.totalCost ?? (runningActualTotal > 0 ? runningActualTotal : undefined);

  const sellingPrice = costing?.actualCost.sellingPrice ?? costing?.estimatedCost.sellingPrice;
  const actualProfit = costing?.actualProfit ?? (sellingPrice != null && actualTotal != null ? sellingPrice - actualTotal : undefined);
  const actualMarginPct = costing?.actualCost.marginPct ?? (sellingPrice && actualTotal != null ? Math.round(((sellingPrice - actualTotal) / sellingPrice) * 100) : undefined);

  return (
    <div className="space-y-4">
      {costLoading && <p className="text-sm" style={{ color: "var(--text-3)" }}>Loading costing…</p>}
      {costError && <p role="alert" className="text-sm" style={{ color: "var(--error)" }}>{costError}</p>}
      {costing && (
        <>
          <p className="text-xs" style={{ color: "var(--text-2)" }}>{costing.estimatedCostBasis}</p>

          {/* Component Estimated vs Actual comparison table */}
          <section className="rounded-xl border overflow-x-auto" style={{ borderColor: "var(--border)" }}>
            <table className="w-full text-xs">
              <thead style={{ background: "var(--surface-2)" }}>
                <tr>
                  {["Component", "Estimated", "Actual", "Variance"].map((h) => (
                    <th key={h} className="text-left px-3 py-2 font-semibold" style={{ color: "var(--text-3)" }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {[
                  { label: "Revenue", est: costing.estimatedCost.sellingPrice, act: costing.actualCost.sellingPrice },
                  { label: "Material", est: costing.estimatedCost.materialCost, act: costing.actualCost.materialCost, estStatus: costing.estimatedCost.materialStatus, actStatus: costing.actualCost.materialStatus },
                  { label: "Labour", est: costing.estimatedCost.labourCost, act: costing.actualCost.labourCost, estStatus: costing.estimatedCost.labourStatus, actStatus: costing.actualCost.labourStatus },
                  { label: "Overhead", est: costing.estimatedCost.overheadCost, act: costing.actualCost.overheadCost, estStatus: costing.estimatedCost.overheadStatus, actStatus: costing.actualCost.overheadStatus },
                  ...(otherActual > 0 || otherEstimated > 0 ? [
                    { label: "Miscellaneous / Other", est: otherEstimated > 0 ? otherEstimated : undefined, act: otherActual > 0 ? otherActual : undefined }
                  ] : []),
                  { label: "Total Cost", est: costing.estimatedCost.totalCost, act: actualTotal },
                  { label: "Profit", est: costing.estimatedProfit, act: actualProfit },
                  { label: "Margin", est: costing.estimatedCost.marginPct != null ? `${costing.estimatedCost.marginPct}%` : undefined, act: actualMarginPct != null ? `${actualMarginPct}%` : undefined, raw: true },
                ].map(({ label, est, act, estStatus, actStatus, raw }) => {
                  const estAmt = raw ? undefined : (est as number | undefined);
                  const actAmt = raw ? undefined : (act as number | undefined);
                  const variance = estAmt != null && actAmt != null ? actAmt - estAmt : undefined;
                  return (
                    <tr key={label} className="border-t" style={{ borderColor: "var(--border)" }}>
                      <td className="px-3 py-2 font-medium">{label}</td>
                      <td className="px-3 py-2">
                        <span>{raw ? (est ?? "—") : money(est as number | undefined)}</span>
                        {estStatus && <span className="ml-1 text-[11px]" style={{ color: "var(--text-3)" }}>({costStatus(estStatus)})</span>}
                      </td>
                      <td className="px-3 py-2">
                        <span>{raw ? (act ?? "—") : money(act as number | undefined)}</span>
                        {actStatus && <span className="ml-1 text-[11px]" style={{ color: "var(--text-3)" }}>({costStatus(actStatus)})</span>}
                      </td>
                      <td className="px-3 py-2 font-mono" style={{ color: variance == null ? "var(--text-3)" : variance > 0 ? "var(--error)" : "var(--success)" }}>
                        {variance != null ? `${variance > 0 ? "+" : ""}${money(variance)}` : "—"}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </section>

          {/* Cost entries — Displayed with Component, Estimated, Actual, Variance */}
          <section className="rounded-xl border overflow-x-auto space-y-1" style={{ borderColor: "var(--border)" }}>
            <div className="flex items-center justify-between px-3 pt-2.5 pb-1">
              <h3 className="text-xs font-bold uppercase tracking-wider text-[var(--text-2)]">Cost Entries Breakdown</h3>
              <span className="text-[11px] text-[var(--text-3)]">{costing.entries.length} recorded</span>
            </div>
            <table className="w-full text-xs">
              <thead style={{ background: "var(--surface-2)" }}>
                <tr>
                  {["Component / Description", "Category", "Estimated", "Actual", "Variance", "Date"].map((h) => (
                    <th key={h} className="text-left px-3 py-2 font-semibold" style={{ color: "var(--text-3)" }}>{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {costing.entries.map((entry) => {
                  const est = entry.estimated ? entry.amount : undefined;
                  const act = !entry.estimated ? entry.amount : undefined;
                  const variance = est != null && act != null ? act - est : act != null ? act : est != null ? -est : undefined;
                  return (
                    <tr key={entry.id} className="border-t hover:bg-[var(--surface-2)]" style={{ borderColor: "var(--border)" }}>
                      <td className="px-3 py-2 font-medium">{entry.description}</td>
                      <td className="px-3 py-2">
                        <span className="px-1.5 py-0.5 rounded text-[10px] font-semibold uppercase bg-[var(--surface-2)] text-[var(--text-2)]">
                          {entry.entryType}
                        </span>
                      </td>
                      <td className="px-3 py-2 font-mono">{est != null ? money(est) : "—"}</td>
                      <td className="px-3 py-2 font-mono font-semibold">{act != null ? money(act) : "—"}</td>
                      <td className="px-3 py-2 font-mono" style={{ color: variance == null ? "var(--text-3)" : variance > 0 ? "var(--error)" : "var(--success)" }}>
                        {variance != null ? `${variance > 0 ? "+" : ""}${money(variance)}` : "—"}
                      </td>
                      <td className="px-3 py-2 text-[var(--text-3)]">{displayDate(entry.createdAt)}</td>
                    </tr>
                  );
                })}
                {costing.entries.length === 0 && (
                  <tr>
                    <td colSpan={6} className="px-3 py-4 text-center text-xs text-[var(--text-3)]">
                      No additional actual cost entries added yet. Use the form below to record labour, overhead or miscellaneous actual costs.
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </section>
        </>
      )}

      {/* Add actual cost entry — editable form for authorised roles */}
      {canEditCost && (
        <section className="rounded-xl border p-4 space-y-3" style={{ borderColor: "var(--border)" }}>
          <h3 className="font-bold text-sm">Add actual cost entry</h3>
          <form className="space-y-2" onSubmit={async (event) => {
            event.preventDefault();
            if (await onCost(type, description, Number(amount))) {
              await loadCosting();
              setDescription("");
              setAmount("");
            }
          }}>
            <div className="grid sm:grid-cols-3 gap-2">
              <select className="eoss-input" aria-label="Cost type" value={type} onChange={(event) => setType(event.target.value as "LABOUR" | "OVERHEAD" | "OTHER")}>
                <option value="LABOUR">Labour</option>
                <option value="OVERHEAD">Overhead</option>
                <option value="OTHER">Miscellaneous / Additional</option>
              </select>
              <input className="eoss-input" aria-label="Cost description / reason" value={description} onChange={(event) => setDescription(event.target.value)} placeholder="Description or reason" />
              <input className="eoss-input" aria-label="Cost amount" type="number" min="0" step="0.01" value={amount} onChange={(event) => setAmount(event.target.value)} placeholder="Amount (₹)" />
            </div>
            <button type="submit" className="px-3 py-1.5 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }} disabled={!description.trim() || amount === "" || Number(amount) < 0}>Save cost entry</button>
          </form>
        </section>
      )}
    </div>
  );
}

function History({ job }: { job: ProductionJob }) {
  return (
    <div className="space-y-4 text-sm">
      <section>
        <h2 className="font-bold mb-2">Record</h2>
        <p>Created {displayDate(job.createdAt)} · Started {displayDate(job.actualStart)} · Completed {displayDate(job.actualEnd)}</p>
      </section>
      <div className="rounded-xl border overflow-x-auto" style={{ borderColor: "var(--border)" }}>
        <table className="w-full text-xs">
          <caption className="text-left font-semibold px-3 py-2">Stages</caption>
          <thead style={{ background: "var(--surface-2)" }}><tr><th className="text-left px-3 py-2">Stage</th><th className="text-left px-3 py-2">Work centre</th><th className="text-left px-3 py-2">Asset</th><th className="text-left px-3 py-2">Status</th></tr></thead>
          <tbody>{(job.stages ?? []).map((stage) => <tr key={stage.id} className="border-t" style={{ borderColor: "var(--border)" }}><td className="px-3 py-2">{stage.stageName}</td><td className="px-3 py-2">{stage.workCentre || "—"}</td><td className="px-3 py-2">{stage.assetCode || "—"}</td><td className="px-3 py-2">{stage.status}</td></tr>)}</tbody>
        </table>
      </div>
      <div className="rounded-xl border overflow-x-auto" style={{ borderColor: "var(--border)" }}>
        <table className="w-full text-xs">
          <caption className="text-left font-semibold px-3 py-2">Material movements</caption>
          <thead style={{ background: "var(--surface-2)" }}><tr><th className="text-left px-3 py-2">Lot / Item</th><th className="text-left px-3 py-2">Type</th><th className="text-left px-3 py-2">Qty</th><th className="text-left px-3 py-2">When</th></tr></thead>
          <tbody>{(job.movements ?? []).map((movement) => <tr key={movement.id} className="border-t" style={{ borderColor: "var(--border)" }}><td className="px-3 py-2">{movement.lotId || "—"}</td><td className="px-3 py-2">{movement.issueType}</td><td className="px-3 py-2">{movement.quantity}</td><td className="px-3 py-2">{displayDate(movement.createdAt)}</td></tr>)}</tbody>
        </table>
      </div>
      <div className="rounded-xl border overflow-x-auto" style={{ borderColor: "var(--border)" }}>
        <table className="w-full text-xs">
          <caption className="text-left font-semibold px-3 py-2">Materials</caption>
          <thead style={{ background: "var(--surface-2)" }}><tr><th className="text-left px-3 py-2">Item</th><th className="text-left px-3 py-2">Required</th><th className="text-left px-3 py-2">Issued</th><th className="text-left px-3 py-2">Returned</th></tr></thead>
          <tbody>{(job.materials ?? []).map((material) => <tr key={material.id} className="border-t" style={{ borderColor: "var(--border)" }}><td className="px-3 py-2">{material.itemCode}</td><td className="px-3 py-2">{material.requiredQty}</td><td className="px-3 py-2">{material.issuedQty}</td><td className="px-3 py-2">{material.returnedQty}</td></tr>)}</tbody>
        </table>
      </div>
    </div>
  );
}

function StageUpdateSheet({ job, stageId, enabled, canExecute, canHold, canComplete, onClose, onDone }: {
  job: ProductionJob;
  stageId: string;
  enabled: boolean;
  canExecute: boolean;
  canHold: boolean;
  canComplete: boolean;
  onClose: () => void;
  onDone: () => void;
}) {
  const stage = job.stages?.find((s) => s.id === stageId) ?? null;
  const [action, setAction] = useState<"continue" | "hold" | "complete">("continue");
  const [notes, setNotes] = useState("");
  const [operators, setOperators] = useState<OperatorOption[]>([]);
  const [operatorId, setOperatorId] = useState(stage?.operatorId ?? "");
  const [completedQty, setCompletedQty] = useState(() => {
    const totalQty = Number(job.qty ?? 0);
    return totalQty > 0 ? String(totalQty) : "";
  });
  const [blockedQty, setBlockedQty] = useState("");
  const [blockedReason, setBlockedReason] = useState("");
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    void loadOperators().then((res) => setOperators(Array.isArray(res) ? res : [])).catch(() => setOperators([]));
  }, []);

  if (!stage) return null;

  const totalQty = Number(job.qty ?? 0);
  const doneQty = Number(completedQty) || 0;
  const badQty = Number(blockedQty) || 0;
  const inProgressQty = Math.max(0, totalQty - doneQty - badQty);
  const allowed = enabled && ((action === "continue" && canExecute) || (action === "hold" && canHold) || (action === "complete" && canComplete));

  async function submit() {
    if (!enabled) return;
    if (doneQty + badQty > totalQty) {
      setError(`Completed + blocked (${doneQty + badQty}) cannot exceed the job quantity (${totalQty}).`);
      return;
    }
    if (!stage) return;
    setError("");
    setSaving(true);
    const stageId = stage.id;
    try {
      const noteText = [notes, badQty > 0 && blockedReason ? `Blockage: ${blockedReason}` : ""].filter(Boolean).join(" | ");
      if (action === "continue") await progressStage(job.id, stageId, noteText, doneQty > 0 ? doneQty : undefined, badQty > 0 ? badQty : undefined, operatorId || undefined);
      if (action === "hold") await holdJob(job.id, noteText || "Held from the stage update");
      if (action === "complete") await completeStage(job.id, stageId, noteText, doneQty > 0 ? doneQty : undefined, badQty > 0 ? badQty : undefined, operatorId || undefined);
      onDone();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "The stage was not updated");
      setSaving(false);
    }
  }

  return (
    <RecordSheet
      title="Stage Update"
      subtitle={`${job.jobNumber} · ${stage.stageName}`}
      onClose={onClose}
      width="md"
      secondary={{ label: "Cancel", onClick: onClose }}
      primary={{ label: saving ? "Saving…" : "Save Update", onClick: () => void submit(), disabled: !allowed || saving }}
    >
      <div className="space-y-4 text-sm">
        {/* Stage context */}
        <div className="rounded-xl border p-3 text-xs grid grid-cols-2 gap-2" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
          <div><span style={{ color: "var(--text-3)" }}>Work Centre</span><div>{stage.workCentre || "—"}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Job Qty</span><div>{job.qty} {job.uomSymbol}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Stage Status</span><div>{stage.status}</div></div>
          <div><span style={{ color: "var(--text-3)" }}>Inspection</span><div>{stage.inspectionStatus}</div></div>
        </div>

        {/* Action selector */}
        <div>
          <div className="text-xs font-semibold mb-2">Action</div>
          <div className="grid grid-cols-3 gap-2">
            {(["continue", "hold", "complete"] as const).map((value) => (
              <button key={value} type="button" onClick={() => setAction(value)} className="py-2 rounded-lg border text-xs font-medium capitalize" style={{ background: action === value ? "var(--brand-fill)" : "transparent", color: action === value ? "#fff" : "var(--text-2)", borderColor: action === value ? "var(--brand)" : "var(--border)" }}>
                {value === "continue" ? "Start / Continue" : value}
              </button>
            ))}
          </div>
        </div>

        {/* Operator selector */}
        <label className="block text-xs font-semibold">
          Assigned Operator / Team Member
          <select
            className="eoss-input w-full mt-1"
            value={operatorId}
            onChange={(e) => setOperatorId(e.target.value)}
          >
            <option value="">-- {stage.assignedOperator ? `${stage.assignedOperator} (keep current)` : "Unassigned"} --</option>
            {(Array.isArray(operators) ? operators : []).map((op) => (
              <option key={op.id} value={op.id}>{op.name || op.email}</option>
            ))}
          </select>
        </label>

        {/* Quantity tracking */}
        {totalQty > 0 && (
          <div className="space-y-3 rounded-xl border p-3" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
            <div className="text-xs font-semibold">{action === "complete" ? "Final Quantities" : "Progress Snapshot"}</div>
            <div className="grid grid-cols-2 gap-3">
              <label className="text-xs">Completed Qty<input className="eoss-input w-full mt-1" aria-label="Completed quantity" inputMode="decimal" value={completedQty} onChange={(e) => setCompletedQty(e.target.value)} /></label>
              <label className="text-xs">Blocked Qty<input className="eoss-input w-full mt-1" aria-label="Blocked quantity" inputMode="decimal" value={blockedQty} onChange={(e) => setBlockedQty(e.target.value)} /></label>
            </div>
            <div className="text-xs flex gap-3" style={{ color: "var(--text-3)" }}>
              <span>Total: <strong>{totalQty}</strong></span>
              <span>Done: <strong>{doneQty}</strong></span>
              {badQty > 0 && <span style={{ color: "var(--warning)" }}>Blocked: <strong>{badQty}</strong></span>}
              {inProgressQty > 0 && <span>In Progress: <strong>{inProgressQty}</strong></span>}
            </div>
            {badQty > 0 && (
              <label className="text-xs">Blockage reason<input className="eoss-input w-full mt-1" aria-label="Blockage reason" value={blockedReason} onChange={(e) => setBlockedReason(e.target.value)} placeholder="Describe what is blocking this quantity…" /></label>
            )}
          </div>
        )}

        {/* Notes */}
        <label className="block text-xs font-semibold">Notes / Observations
          <textarea rows={3} value={notes} onChange={(e) => setNotes(e.target.value)} placeholder="Any issues, deviations or notes…" className="w-full mt-1 px-3 py-2 rounded-lg border text-sm" style={{ borderColor: "var(--border)", background: "var(--surface)" }} />
        </label>

        {!enabled && <p className="text-xs" style={{ color: "var(--text-3)" }}>Stage updates are turned off for this company.</p>}
        {error && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{error}</p>}
      </div>
    </RecordSheet>
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
