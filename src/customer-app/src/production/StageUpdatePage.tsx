import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, Camera, CheckCircle } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import { ApiError } from "../auth/api";
import { completeStage, featureFlags, getJob, holdJob, startStage, type ProductionJob } from "./api";

export default function StageUpdatePage() {
  const { id = "", stageId = "" } = useParams();
  const navigate = useNavigate();
  const { user } = useCustomer();
  const roles = user?.roles.map((role) => role.role) ?? [];
  const canExecute = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR", "FIELD_WORKER"].includes(role));
  const canComplete = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR", "QUALITY_INSPECTOR"].includes(role));
  const canHold = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR"].includes(role));
  const [job, setJob] = useState<ProductionJob | null>(null);
  const [enabled, setEnabled] = useState(true);
  const [action, setAction] = useState<"continue" | "hold" | "complete" | null>(null);
  const [notes, setNotes] = useState("");
  const [error, setError] = useState("");
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    Promise.all([getJob(id), featureFlags()])
      .then(([next, flags]) => { setJob(next); setEnabled(flags["production.stage.transition"] !== false); })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : "Stage not found"));
  }, [id]);

  const stage = job?.stages?.find((row) => row.id === stageId);
  if (error && !job) return <p role="alert" className="p-6 text-sm">{error}</p>;
  if (!job || !stage) return <p role="status" className="p-6 text-sm">Loading stage</p>;
  const activeJob = job;
  const activeStage = stage;
  const selectedAction = action ?? (activeStage.status === "in_progress" ? "complete" : "continue");
  const actionOptions = [
    { value: "continue", available: activeStage.status === "pending" },
    { value: "hold", available: activeStage.status === "pending" || activeStage.status === "in_progress" },
    { value: "complete", available: activeStage.status === "in_progress" },
  ] as const;
  if (saved) {
    return (
      <div className="p-6 max-w-lg mx-auto text-center mt-20">
        <CheckCircle size={48} className="mx-auto mb-4" style={{ color: "var(--success)" }} />
        <h2 className="text-xl font-bold mb-2">Update Recorded</h2>
        <p className="text-sm mb-6">Stage update for <strong>{activeStage.stageName}</strong> has been saved.</p>
        <button type="button" onClick={() => navigate(`/jobs/${activeJob.id}`)} className="px-4 py-2 text-white rounded-lg text-sm font-medium" style={{ background: "var(--brand-fill)" }}>Back to Job</button>
      </div>
    );
  }

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!enabled || !actionOptions.find((option) => option.value === selectedAction)?.available) return;
    try {
      if (selectedAction === "continue") await startStage(activeJob.id, activeStage.id, notes);
      if (selectedAction === "hold") await holdJob(activeJob.id, notes || "Held from the stage update");
      if (selectedAction === "complete") await completeStage(activeJob.id, activeStage.id, notes);
      setSaved(true);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "The stage was not updated");
    }
  }

  const selectedAvailable = actionOptions.find((option) => option.value === selectedAction)?.available === true;
  const allowed = enabled && selectedAvailable
    && ((selectedAction === "continue" && canExecute) || (selectedAction === "hold" && canHold) || (selectedAction === "complete" && canComplete));
  return (
    <div className="max-w-lg mx-auto p-4 md:p-6">
      <div className="flex items-center gap-2 mb-5">
        <button type="button" onClick={() => navigate(`/jobs/${activeJob.id}`)} aria-label="Back to job"><ArrowLeft size={16} /></button>
        <div>
          <h1 className="font-bold">Stage Update</h1>
          <p className="text-sm" style={{ color: "var(--text-2)" }}>{activeJob.jobNumber} · {activeStage.stageName}</p>
        </div>
      </div>
      <div className="rounded-xl border p-4 mb-5 text-sm grid grid-cols-2 gap-2" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
        <div><span className="text-xs" style={{ color: "var(--text-3)" }}>Work Centre</span><div>{activeStage.workCentre || "—"}</div></div>
        <div><span className="text-xs" style={{ color: "var(--text-3)" }}>Job Qty</span><div>{activeJob.qty} {activeJob.uomSymbol}</div></div>
        <div><span className="text-xs" style={{ color: "var(--text-3)" }}>Stage</span><div>{activeStage.status}</div></div>
        <div><span className="text-xs" style={{ color: "var(--text-3)" }}>Inspection</span><div>{activeStage.inspectionStatus}</div></div>
      </div>
      <form onSubmit={(event) => void submit(event)} className="space-y-4 rounded-xl border p-4" style={{ background: "var(--surface)", borderColor: "var(--border)" }}>
        <div>
          <label className="block text-xs font-semibold mb-2">Action</label>
          <div className="grid grid-cols-3 gap-2">
            {actionOptions.map(({ value, available }) => (
              <button key={value} type="button" disabled={!available} aria-pressed={selectedAction === value} onClick={() => setAction(value)} className="py-2 rounded-lg border text-sm font-medium capitalize disabled:opacity-50" style={{ background: selectedAction === value ? "var(--brand-fill)" : "transparent", color: selectedAction === value ? "#fff" : "var(--text-2)", borderColor: selectedAction === value ? "var(--brand)" : "var(--border)" }}>{value}</button>
            ))}
          </div>
        </div>
        <label className="block text-xs font-semibold">Notes / Observations<textarea rows={3} value={notes} onChange={(event) => setNotes(event.target.value)} placeholder="Any issues, deviations or notes…" className="w-full mt-1 px-3 py-2 rounded-lg border text-sm" style={{ borderColor: "var(--border)", background: "var(--surface)" }} /></label>
        <div className="flex items-center gap-3 p-3 border border-dashed rounded-lg text-sm" style={{ borderColor: "var(--border)", color: "var(--text-3)" }}><Camera size={18} /> Photo capture is not stored with the stage record.</div>
        {error && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{error}</p>}
        {!enabled && <p className="text-xs">Stage updates are turned off for this company.</p>}
        <div className="flex gap-3 pt-2">
          <button type="button" onClick={() => navigate(`/jobs/${activeJob.id}`)} className="flex-1 py-2.5 border rounded-lg text-sm" style={{ borderColor: "var(--border)" }}>Cancel</button>
          <button type="submit" disabled={!allowed} className="flex-1 py-2.5 text-white rounded-lg text-sm font-semibold disabled:opacity-50" style={{ background: "var(--brand-fill)" }}>Save Update</button>
        </div>
      </form>
    </div>
  );
}
