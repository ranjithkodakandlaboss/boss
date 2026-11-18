import { useCallback, useEffect, useState } from "react";
import type { FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { useCustomer } from "../auth/CustomerState";
import { ApiError } from "../auth/api";
import Modal from "../components/shared/Modal";
import StatusChip from "../components/shared/StatusChip";
import {
  MANAGE_ROLES, assetActivity, closeEvent, eventActivity, computeDue, displayDate, displayDateTime, getAsset, getEvent, listEvents, listReadings,
  logReading, markRepaired, startEvent,
  type ActivityEntry, type Asset, type MaintenanceEvent, type MeterReading,
} from "./api";
import { AssetFormModal, EventFormModal, Field, useMaintenanceOptions } from "./AssetsPage";

function useCanManage() {
  const { user } = useCustomer();
  return (user?.roles.map((r) => r.role) ?? []).some((r) => MANAGE_ROLES.includes(r));
}

function toLocalInput(d = new Date()) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function ActivityList({ items, error }: { items: ActivityEntry[]; error?: string }) {
  if (error) return <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{error}</p>;
  if (items.length === 0) return <p className="text-sm text-[var(--text-3)]">No recorded activity yet.</p>;
  return (
    <ul className="space-y-1 text-xs">
      {items.map((a, i) => <li key={`${a.at}-${i}`} className="flex gap-3"><span className="text-[var(--text-3)] w-40">{displayDateTime(a.at)}</span><span className="font-semibold">{a.action}</span><span>{a.actor ?? "System"}</span></li>)}
    </ul>
  );
}

function Info({ label, value }: { label: string; value: React.ReactNode }) {
  return <div><div className="text-[11px] text-[var(--text-3)]">{label}</div><div className="text-sm text-[var(--text)]">{value || "—"}</div></div>;
}

export function AssetDetailPage() {
  const { id = "" } = useParams();
  const canManage = useCanManage();
  const { options, lookup, ctl } = useMaintenanceOptions();
  const [asset, setAsset] = useState<Asset | null>(null);
  const [readings, setReadings] = useState<MeterReading[]>([]);
  const [events, setEvents] = useState<MaintenanceEvent[]>([]);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [tab, setTab] = useState<"info" | "hours" | "history" | "documents" | "activity">("info");
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [activityError, setActivityError] = useState("");
  const [repairOpen, setRepairOpen] = useState(false);
  const [repairForm, setRepairForm] = useState({ completionDate: toLocalInput(), rootCause: "", workDone: "", resolutionNotes: "" });
  const [editing, setEditing] = useState(false);
  const [logEvent, setLogEvent] = useState(false);
  const [readingOpen, setReadingOpen] = useState(false);
  const [readingForm, setReadingForm] = useState({ reading: "", readingType: "routine", notes: "" });

  const load = useCallback(() => {
    setError("");
    Promise.all([getAsset(id), listReadings(id), listEvents(id)])
      .then(([a, r, e]) => { setAsset(a); setReadings(r); setEvents(e); })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Could not load the asset."));
  }, [id]);
  useEffect(load, [load]);
  useEffect(() => {
    if (tab !== "activity") return;
    setActivityError("");
    assetActivity(id).then(setActivity).catch((e) => setActivityError(e instanceof ApiError ? e.message : "Could not load activity."));
  }, [tab, id, events]);

  if (error) return <div className="p-6" role="alert" style={{ color: "var(--error)" }}>{error} <button type="button" className="underline" onClick={load}>Retry</button></div>;
  if (!asset) return <div className="p-6 text-sm text-[var(--text-3)]">Loading asset…</div>;

  const due = computeDue(asset);
  const hasOpenBreakdown = events.some((e) => e.eventType === "Breakdown" && e.status !== "closed");

  async function repair(e: FormEvent) {
    e.preventDefault(); setActionError("");
    if (!repairForm.completionDate || !repairForm.rootCause.trim() || !repairForm.workDone.trim()) {
      setActionError("Completion time, root cause / diagnosis and work done are required."); return;
    }
    try {
      await markRepaired(id, {
        completionDate: new Date(repairForm.completionDate).toISOString(), rootCause: repairForm.rootCause.trim(),
        workDone: repairForm.workDone.trim(), resolutionNotes: repairForm.resolutionNotes.trim() || undefined,
      });
      setRepairOpen(false); setRepairForm({ completionDate: toLocalInput(), rootCause: "", workDone: "", resolutionNotes: "" }); load();
    } catch (err) { setActionError(err instanceof ApiError ? err.message : "Could not mark the asset repaired."); }
  }

  async function submitReading(e: FormEvent) {
    e.preventDefault(); setActionError("");
    try {
      await logReading(id, { reading: Number(readingForm.reading), readingType: readingForm.readingType, notes: readingForm.notes || undefined });
      setReadingOpen(false); setReadingForm({ reading: "", readingType: "routine", notes: "" }); load();
    } catch (err) { setActionError(err instanceof ApiError ? err.message : "Could not log the reading."); }
  }

  return (
    <div className="p-4 md:p-6 space-y-4">
      <Link to="/assets" className="text-xs underline text-[var(--text-3)]">← Maintenance</Link>
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-3">
          <h1 className="text-lg font-bold">{asset.assetCode} — {asset.name}</h1>
          <StatusChip status={asset.status} label={options.ASSET_STATUS?.find((o) => o.code === asset.status)?.label} />
          {due.status === "overdue" && <span className="text-xs font-semibold" style={{ color: "var(--error)" }}>Service overdue</span>}
        </div>
        {canManage && asset.status !== "retired" && (
          <div className="flex gap-2 text-xs">
            <button type="button" className="px-3 py-1.5 rounded-lg border border-[var(--border)]" onClick={() => setReadingOpen(true)}>Log reading</button>
            <button type="button" className="px-3 py-1.5 rounded-lg border border-[var(--border)]" onClick={() => setLogEvent(true)}>Log event</button>
            {hasOpenBreakdown && <button type="button" className="px-3 py-1.5 rounded-lg border border-[var(--border)]" onClick={() => { setActionError(""); setRepairForm((f) => ({ ...f, completionDate: toLocalInput() })); setRepairOpen(true); }}>Mark Repaired</button>}
            <button type="button" className="px-3 py-1.5 rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => setEditing(true)}>Edit Asset</button>
          </div>
        )}
      </div>
      {actionError && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{actionError}</p>}
      <p className="text-xs text-[var(--text-2)]">{due.summary}</p>

      <div role="tablist" className="flex gap-4 border-b border-[var(--border)]">
        {([["info", "Asset Info"], ["hours", "Running Hours"], ["history", "History"], ["documents", "Documents & Certificates"], ["activity", "Activity"]] as const).map(([k, label]) => (
          <button key={k} role="tab" aria-selected={tab === k} type="button" onClick={() => setTab(k)} className="pb-2 text-sm font-semibold"
            style={{ color: tab === k ? "var(--brand)" : "var(--text-3)", borderBottom: tab === k ? "2px solid var(--brand)" : "2px solid transparent" }}>{label}</button>
        ))}
      </div>

      {tab === "info" && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <Info label="Category" value={asset.category} /><Info label="Work centre" value={asset.workCentre} />
          <Info label="Site" value={asset.siteName} /><Info label="Criticality" value={asset.criticality} />
          <Info label="Manufacturer" value={asset.manufacturer} /><Info label="Model" value={asset.model} />
          <Info label="Serial number" value={asset.serialNumber} /><Info label="Install date" value={displayDate(asset.installDate)} />
          <Info label="Meter" value={asset.currentReading != null ? `${asset.currentReading} ${asset.meterType}` : null} />
          <Info label="Last service" value={displayDate(asset.lastServiceDate)} /><Info label="Next service" value={displayDate(due.nextDueDate ?? asset.nextServiceDate)} />
          <Info label="Responsible" value={asset.responsiblePerson} />
          <Info label="Supported stages" value={(asset.capabilities ?? []).map((item) => item.stageLabel).join(", ") || "None"} />
          {asset.archiveReason && <Info label="Archive reason" value={asset.archiveReason} />}
        </div>
      )}

      {tab === "hours" && (readings.length === 0 ? <p className="text-sm text-[var(--text-3)]">No readings logged yet.</p> : (
        <table className="w-full text-xs border border-[var(--border)]">
          <thead className="bg-[var(--surface-2)] text-left"><tr><th className="p-2">Date</th><th className="p-2">Reading</th><th className="p-2">Previous</th><th className="p-2">Type</th><th className="p-2">Notes</th></tr></thead>
          <tbody>{readings.map((r) => <tr key={r.id} className="border-t border-[var(--border)]"><td className="p-2">{displayDate(r.readingDate)}</td><td className="p-2">{r.reading}</td><td className="p-2">{r.previousReading ?? "—"}</td><td className="p-2">{r.readingType}</td><td className="p-2">{r.notes ?? "—"}</td></tr>)}</tbody>
        </table>
      ))}

      {tab === "history" && (events.length === 0 ? <p className="text-sm text-[var(--text-3)]">No maintenance events for this asset.</p> : (
        <table className="w-full text-xs border border-[var(--border)]">
          <thead className="bg-[var(--surface-2)] text-left"><tr><th className="p-2">Event #</th><th className="p-2">Type</th><th className="p-2">Planned</th><th className="p-2">Completed</th><th className="p-2">Downtime</th><th className="p-2">Technician</th><th className="p-2">Status</th></tr></thead>
          <tbody>{events.map((e) => (
            <tr key={e.id} className="border-t border-[var(--border)]">
              <td className="p-2"><Link className="underline" to={`/assets/me/${e.id}`}>{e.eventNumber}</Link></td>
              <td className="p-2">{e.eventType}</td><td className="p-2">{displayDate(e.plannedDate)}</td><td className="p-2">{displayDate(e.completionDate)}</td>
              <td className="p-2">{e.downtimeHours != null ? `${e.downtimeHours} h` : "—"}</td><td className="p-2">{e.technicianName || "—"}</td><td className="p-2"><StatusChip status={e.status} /></td>
            </tr>))}</tbody>
        </table>
      ))}

      {tab === "documents" && (
        <div className="border border-dashed border-[var(--border)] rounded-xl p-4 text-sm space-y-1 max-w-xl">
          <h2 className="font-semibold">Asset Documents &amp; Certificates</h2>
          <p className="text-[var(--text-3)]">No documents are attached to this asset. Uploading manuals, calibration and safety certificates is not available yet because the BOSS Documents module has no file storage; no files are stored from this screen.</p>
        </div>
      )}

      {tab === "activity" && <ActivityList items={activity} error={activityError} />}

      <Modal open={repairOpen} title="Mark Repaired" onClose={() => setRepairOpen(false)}>
        <form onSubmit={repair} className="space-y-3">
          <Field label="Completion time *"><input aria-label="Repair completion time" type="datetime-local" className="eoss-input w-full" value={repairForm.completionDate} onChange={(e) => setRepairForm({ ...repairForm, completionDate: e.target.value })} /></Field>
          <Field label="Root cause / diagnosis *"><input aria-label="Repair root cause" className="eoss-input w-full" value={repairForm.rootCause} onChange={(e) => setRepairForm({ ...repairForm, rootCause: e.target.value })} /></Field>
          <Field label="Work done *"><textarea aria-label="Repair work done" className="eoss-input w-full" rows={2} value={repairForm.workDone} onChange={(e) => setRepairForm({ ...repairForm, workDone: e.target.value })} /></Field>
          <Field label="Resolution notes"><input aria-label="Repair resolution notes" className="eoss-input w-full" value={repairForm.resolutionNotes} onChange={(e) => setRepairForm({ ...repairForm, resolutionNotes: e.target.value })} /></Field>
          {actionError && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{actionError}</p>}
          <div className="flex justify-end"><button type="submit" className="px-3 py-1.5 text-xs rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Confirm repaired</button></div>
        </form>
      </Modal>

      <AssetFormModal open={editing} asset={asset} options={options} lookup={lookup} ctl={ctl} onClose={() => setEditing(false)} onSaved={() => { setEditing(false); load(); }} />
      <EventFormModal open={logEvent} assets={[asset]} assetId={asset.id} options={options} lookup={lookup} ctl={ctl} onClose={() => setLogEvent(false)} onSaved={() => { setLogEvent(false); load(); }} />
      <Modal open={readingOpen} title="Log meter reading" onClose={() => setReadingOpen(false)}>
        <form onSubmit={submitReading} className="space-y-3">
          <Field label={`Reading (${asset.meterType})`}><input aria-label="Reading" type="number" required className="eoss-input w-full" value={readingForm.reading} onChange={(e) => setReadingForm({ ...readingForm, reading: e.target.value })} /></Field>
          <Field label="Type">
            <select aria-label="Reading type" className="eoss-input w-full" value={readingForm.readingType} onChange={(e) => setReadingForm({ ...readingForm, readingType: e.target.value })}>
              <option value="routine">Routine</option>
              <option value="shift-end">Shift end</option>
              <option value="post-maintenance">Post maintenance</option>
              <option value="correction">Correction</option>
            </select>
          </Field>
          <Field label="Notes"><input aria-label="Reading notes" className="eoss-input w-full" value={readingForm.notes} onChange={(e) => setReadingForm({ ...readingForm, notes: e.target.value })} /></Field>
          <div className="flex justify-end"><button type="submit" className="px-3 py-1.5 text-xs rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }}>Save reading</button></div>
        </form>
      </Modal>
    </div>
  );
}

export function MaintenanceEventDetailPage() {
  const { id = "" } = useParams();
  const navigate = useNavigate();
  const canManage = useCanManage();
  const [event, setEvent] = useState<MaintenanceEvent | null>(null);
  const [error, setError] = useState("");
  const [actionError, setActionError] = useState("");
  const [form, setForm] = useState({ rootCause: "", workDone: "", resolutionNotes: "", partsUsed: "", completionDate: "" });
  const [activity, setActivity] = useState<ActivityEntry[]>([]);
  const [activityError, setActivityError] = useState("");

  const load = useCallback(() => {
    setError("");
    getEvent(id).then(setEvent).catch((e) => setError(e instanceof ApiError ? e.message : "Could not load the event."));
    setActivityError("");
    eventActivity(id).then(setActivity).catch((e) => setActivityError(e instanceof ApiError ? e.message : "Could not load activity."));
  }, [id]);
  useEffect(load, [load]);

  if (error) return <div className="p-6" role="alert" style={{ color: "var(--error)" }}>{error} <button type="button" className="underline" onClick={load}>Retry</button></div>;
  if (!event) return <div className="p-6 text-sm text-[var(--text-3)]">Loading event…</div>;

  async function act(fn: () => Promise<MaintenanceEvent>) {
    setActionError("");
    try { setEvent(await fn()); eventActivity(id).then(setActivity).catch(() => undefined); } catch (e) { setActionError(e instanceof ApiError ? e.message : "Action failed."); }
  }

  return (
    <div className="p-4 md:p-6 space-y-4">
      <button type="button" className="text-xs underline text-[var(--text-3)]" onClick={() => navigate("/assets?tab=events")}>← Maintenance Events</button>
      <div className="flex items-center gap-3"><h1 className="text-lg font-bold">{event.eventNumber}</h1><StatusChip status={event.status} /></div>
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        <Info label="Asset" value={<Link className="underline" to={`/assets/${event.assetId}`}>{event.assetCode} — {event.assetName}</Link>} />
        <Info label="Type" value={event.eventType} /><Info label="Severity" value={event.severity} /><Info label="Technician" value={event.technicianName} />
        <Info label="Planned" value={displayDate(event.plannedDate)} /><Info label="Downtime start" value={displayDateTime(event.downtimeStart)} />
        <Info label="Completed" value={displayDateTime(event.completionDate)} /><Info label="Downtime" value={event.downtimeHours != null ? `${event.downtimeHours} h` : null} />
        <Info label="Affected jobs" value={event.affectedJobs.map((j) => j.jobNumber).join(", ")} />
        <Info label="Responsible owner" value={event.responsibleOwner} />
        <Info label="Description" value={event.symptom} /><Info label="Corrective action" value={event.correctiveAction} /><Info label="Notes" value={event.notes} />
        {event.status === "closed" && <><Info label="Root cause" value={event.rootCause} /><Info label="Work done" value={event.workDone} /><Info label="Resolution" value={event.resolutionNotes} /><Info label="Parts used" value={event.partsUsed} /></>}
      </div>
      {event.affectedStages.length > 0 && (
        <section className="space-y-2" aria-label="Production impact">
          <h2 className="font-semibold text-sm">Affected production stages</h2>
          <p className="text-xs text-[var(--text-3)]">
            These are the incomplete stages assigned to this asset. Machine availability is checked at stage start, so the whole job is not put on hold.
            Required-by dates are for triage only; delivery dates are not recalculated and the responsible owner is not notified automatically.
            Alternatives are operational assets at this site with the same configured stage capability; current loading is not considered.
          </p>
          <div className="space-y-2">
            {event.affectedStages.map((stage) => (
              <article key={stage.id} className="border border-[var(--border)] rounded-lg p-3 space-y-1">
                <p className="text-sm font-semibold">Job {stage.jobNumber} · {stage.sequence}. {stage.name} · {stage.status}</p>
                {(stage.customerOrderNumber || stage.requiredByDate) && (
                  <p className="text-xs text-[var(--text-2)]">
                    {stage.customerOrderNumber ? `Customer order ${stage.customerOrderNumber}` : "Customer order"}
                    {stage.requiredByDate ? ` · Required by ${displayDate(stage.requiredByDate)}` : ""}
                  </p>
                )}
                <p className="text-xs text-[var(--text-2)]">
                  {stage.alternatives.length > 0
                    ? `Other capable assets: ${stage.alternatives.map((alternative) => `${alternative.assetCode} — ${alternative.name}`).join(", ")}`
                    : "No operational alternative with this configured stage capability is available at this site."}
                </p>
              </article>
            ))}
          </div>
        </section>
      )}
      {actionError && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{actionError}</p>}
      {canManage && event.status !== "closed" && (
        <div className="border border-[var(--border)] rounded-xl p-4 space-y-3 max-w-xl">
          <h2 className="font-semibold text-sm">Resolve &amp; Close</h2>
          {event.status !== "in_progress" && <button type="button" className="px-3 py-1.5 text-xs rounded-lg border border-[var(--border)]" onClick={() => act(() => startEvent(id))}>Start work</button>}
          <Field label="Completion time"><input aria-label="Completion time" type="datetime-local" className="eoss-input w-full" value={form.completionDate} onChange={(e) => setForm({ ...form, completionDate: e.target.value })} /></Field>
          <Field label="Root cause"><input aria-label="Root cause" className="eoss-input w-full" value={form.rootCause} onChange={(e) => setForm({ ...form, rootCause: e.target.value })} /></Field>
          <Field label="Work done *"><textarea aria-label="Work done" className="eoss-input w-full" rows={2} value={form.workDone} onChange={(e) => setForm({ ...form, workDone: e.target.value })} /></Field>
          <Field label="Resolution notes"><textarea aria-label="Resolution notes" className="eoss-input w-full" rows={2} value={form.resolutionNotes} onChange={(e) => setForm({ ...form, resolutionNotes: e.target.value })} /></Field>
          <Field label="Parts used"><input aria-label="Parts used" className="eoss-input w-full" value={form.partsUsed} onChange={(e) => setForm({ ...form, partsUsed: e.target.value })} /></Field>
          <button type="button" className="px-3 py-1.5 text-xs rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }}
            onClick={() => act(() => closeEvent(id, { completionDate: form.completionDate ? new Date(form.completionDate).toISOString() : undefined, rootCause: form.rootCause || undefined, workDone: form.workDone || undefined, resolutionNotes: form.resolutionNotes || undefined, partsUsed: form.partsUsed || undefined }))}>Close event</button>
        </div>
      )}
      <section className="space-y-2"><h2 className="font-semibold text-sm">Activity</h2><ActivityList items={activity} error={activityError} /></section>
    </div>
  );
}
