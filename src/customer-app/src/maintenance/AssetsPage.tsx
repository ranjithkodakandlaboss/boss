import { useCallback, useEffect, useMemo, useState } from "react";
import type { FormEvent, ReactNode } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { ChevronRight, Plus, Search } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import { ApiError } from "../auth/api";
import RecordSheet from "../components/shared/RecordSheet";
import StatusChip from "../components/shared/StatusChip";
import {
  CONFIG_ADMIN_ROLES, INLINE_ADD_CATEGORIES, MANAGE_ROLES, archiveAsset, computeDue, configOptions, createAsset,
  createConfigOption, createEvent, displayDate, listAssets, listEvents, lookups, nextServiceLabel, replaceCapabilities, updateAsset,
  type Asset, type AssetInput, type ConfigOption, type EventInput, type Lookups, type MaintenanceEvent,
} from "./api";

export type Options = Record<string, ConfigOption[]>;
const CATEGORIES = [
  "ASSET_CATEGORY", "WORK_CENTRE", "ASSET_CRITICALITY", "ASSET_STATUS", "METER_TYPE", "PRODUCTION_STAGES",
  "MAINTENANCE_EVENT_TYPE", "MAINTENANCE_SEVERITY", "MAINTENANCE_EVENT_STATUS",
];

export type OptionsCtl = { error: string; ready: boolean; canAdd: boolean; reload: () => Promise<void> };
const NO_CTL: OptionsCtl = { error: "", ready: true, canAdd: false, reload: async () => {} };

export function useMaintenanceOptions() {
  const { user } = useCustomer();
  const [options, setOptions] = useState<Options>({});
  const [lookup, setLookup] = useState<Lookups>({ sites: [], technicians: [], jobs: [] });
  const [error, setError] = useState("");
  const [ready, setReady] = useState(false);
  const reload = useCallback(async () => {
    setError("");
    try {
      const results = await Promise.all([...CATEGORIES.map((c) => configOptions(c)), lookups()]);
      const next: Options = {};
      CATEGORIES.forEach((c, i) => { next[c] = results[i] as ConfigOption[]; });
      setOptions(next);
      setLookup(results[CATEGORIES.length] as Lookups);
      setReady(true);
    } catch (e) {
      setError(e instanceof ApiError ? e.message : "Could not load dropdown options.");
    }
  }, []);
  useEffect(() => { void reload(); }, [reload]);
  const canAdd = (user?.roles.map((r) => r.role) ?? []).some((r) => CONFIG_ADMIN_ROLES.includes(r));
  const ctl: OptionsCtl = { error, ready, canAdd, reload };
  return { options, lookup, error, ctl };
}

export function Field({ label, children }: { label: string; children: ReactNode }) {
  return <label className="block text-xs font-semibold text-[var(--text-2)]">{label}<div className="mt-1 font-normal">{children}</div></label>;
}

export function Select({ value, onChange, options, label, byLabel = false, blank = "Select…" }: {
  value?: string | null; onChange: (v: string) => void; options: ConfigOption[]; label: string; byLabel?: boolean; blank?: string;
}) {
  return (
    <select aria-label={label} className="eoss-input w-full" value={value ?? ""} onChange={(e) => onChange(e.target.value)}>
      <option value="">{blank}</option>
      {options.map((o) => <option key={o.id} value={byLabel ? o.label : o.code}>{o.label}</option>)}
    </select>
  );
}

export function ConfigSelect({ category, label, value, onChange, options, byLabel = false, ctl = NO_CTL }: {
  category: string; label: string; value?: string | null; onChange: (v: string) => void; options: ConfigOption[];
  byLabel?: boolean; ctl?: OptionsCtl;
}) {
  const [query, setQuery] = useState("");
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [addError, setAddError] = useState("");
  const [busy, setBusy] = useState(false);
  if (ctl.error) {
    return <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>Could not load {label.toLowerCase()} options. <button type="button" className="underline" onClick={() => void ctl.reload()}>Retry</button></p>;
  }
  if (!ctl.ready) return <select aria-label={label} disabled className="eoss-input w-full"><option>Loading…</option></select>;

  const inlineAddable = INLINE_ADD_CATEGORIES.includes(category);
  const needle = query.trim().toLowerCase();
  const matches = needle ? options.filter((o) => o.label.toLowerCase().includes(needle)) : options;
  const showAdd = ctl.canAdd && inlineAddable && !adding && (options.length === 0 || (needle !== "" && matches.length === 0));

  async function save() {
    const text = name.trim();
    if (!text) { setAddError(`Enter a name for the new ${label.toLowerCase()}.`); return; }
    const code = text.toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
    setBusy(true); setAddError("");
    try {
      await createConfigOption(category, { code, label: text, sortOrder: options.length + 1 });
      await ctl.reload();
      onChange(byLabel ? text : code);
      setAdding(false); setName(""); setQuery("");
    } catch (err) {
      setAddError(err instanceof ApiError ? err.message : "Could not add the option.");
    } finally { setBusy(false); }
  }

  return (
    <div className="space-y-1">
      {inlineAddable && (
        <input aria-label={`Search ${label}`} placeholder={`Search ${label.toLowerCase()}…`} className="eoss-input w-full" value={query} onChange={(e) => setQuery(e.target.value)} />
      )}
      <Select label={label} byLabel={byLabel} value={value} onChange={onChange} options={matches} />
      {options.length === 0 && <p className="text-xs text-[var(--text-3)]">No {label.toLowerCase()} options yet.</p>}
      {needle !== "" && matches.length === 0 && options.length > 0 && <p className="text-xs text-[var(--text-3)]">No match for “{query.trim()}”.</p>}
      {showAdd && <button type="button" className="text-xs underline" onClick={() => { setAdding(true); setName(query.trim()); setAddError(""); }}>Add New {label}</button>}
      {adding && (
        <div className="flex gap-2 items-center">
          <input aria-label={`New ${label}`} className="eoss-input flex-1" value={name} onChange={(e) => setName(e.target.value)} />
          <button type="button" disabled={busy} className="px-2 py-1 text-xs rounded-lg font-semibold text-white" style={{ background: "var(--brand-fill)" }} onClick={() => void save()}>{busy ? "Adding…" : "Add"}</button>
          <button type="button" className="px-2 py-1 text-xs rounded-lg border border-[var(--border)]" onClick={() => setAdding(false)}>Cancel</button>
        </div>
      )}
      {addError && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{addError}</p>}
    </div>
  );
}

export function AssetFormModal({ open, asset, options, lookup, ctl, onClose, onSaved }: {
  open: boolean; asset?: Asset | null; options: Options; lookup: Lookups; ctl?: OptionsCtl; onClose: () => void; onSaved: (a: Asset) => void;
}) {
  const [form, setForm] = useState<AssetInput>({});
  const [capabilities, setCapabilities] = useState<string[]>([]);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  useEffect(() => {
    if (open) {
      setForm(asset ? { ...asset } : { meterType: "hours", criticality: "medium", status: "operational" });
      setCapabilities((asset?.capabilities ?? []).map((item) => item.stageOptionId));
      setError("");
    }
  }, [open, asset]);
  const set = (patch: AssetInput) => setForm((f) => ({ ...f, ...patch }));
  const num = (v: string) => (v === "" ? null : Number(v));

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!form.assetCode?.trim() || !form.name?.trim() || !form.siteId || !form.manufacturer?.trim()) { setError("Asset code, name, site and manufacturer are required."); return; }
    setSaving(true); setError("");
    try {
      const saved = asset ? await updateAsset(asset.id, form) : await createAsset(form);
      const withCapabilities = await replaceCapabilities(saved.id, capabilities);
      onSaved(withCapabilities);
    } catch (err) {
      setError(err instanceof ApiError ? err.message : "Could not save the asset.");
    } finally { setSaving(false); }
  }

  if (!open) return null;
  return (
    <RecordSheet
      title={asset ? "Edit Asset" : "Add Asset"}
      onClose={onClose}
      primary={{ label: saving ? "Saving…" : "Save Asset", onClick: () => { const fakeEvt = { preventDefault: () => {} } as FormEvent; void submit(fakeEvt); }, disabled: saving }}
      secondary={{ label: "Cancel", onClick: onClose }}
    >
      <form id="asset-form" onSubmit={submit} className="space-y-3">
        {asset?.status === "breakdown" && (
          <div className="rounded-lg px-3 py-2 text-xs font-semibold" style={{ background: "rgba(220,38,38,0.08)", color: "var(--error)", border: "1px solid var(--error)" }}>
            This asset is currently in breakdown. Editing will not change its status automatically.
          </div>
        )}
        <div className="grid grid-cols-2 gap-3">
          <Field label="Asset code *"><input aria-label="Asset code" className="eoss-input w-full" value={form.assetCode ?? ""} onChange={(e) => set({ assetCode: e.target.value })} /></Field>
          <Field label="Name *"><input aria-label="Asset name" className="eoss-input w-full" value={form.name ?? ""} onChange={(e) => set({ name: e.target.value })} /></Field>
          <Field label="Category"><ConfigSelect category="ASSET_CATEGORY" ctl={ctl} label="Category" byLabel value={form.category} onChange={(v) => set({ category: v })} options={options.ASSET_CATEGORY ?? []} /></Field>
          <Field label="Work centre"><ConfigSelect category="WORK_CENTRE" ctl={ctl} label="Work centre" byLabel value={form.workCentre} onChange={(v) => set({ workCentre: v })} options={options.WORK_CENTRE ?? []} /></Field>
          <Field label="Site *">
            <select aria-label="Site" className="eoss-input w-full" value={form.siteId ?? ""} onChange={(e) => set({ siteId: e.target.value })}>
              <option value="">Select…</option>
              {lookup.sites.map((s) => <option key={s.id} value={s.id}>{s.name}</option>)}
            </select>
          </Field>
          <Field label="Criticality"><ConfigSelect category="ASSET_CRITICALITY" ctl={ctl} label="Criticality" value={form.criticality} onChange={(v) => set({ criticality: v })} options={options.ASSET_CRITICALITY ?? []} /></Field>
          <Field label="Manufacturer *"><input aria-label="Manufacturer" required className="eoss-input w-full" value={form.manufacturer ?? ""} onChange={(e) => set({ manufacturer: e.target.value })} /></Field>
          <Field label="Model"><input aria-label="Model" className="eoss-input w-full" value={form.model ?? ""} onChange={(e) => set({ model: e.target.value })} /></Field>
          <Field label="Serial number"><input aria-label="Serial number" className="eoss-input w-full" value={form.serialNumber ?? ""} onChange={(e) => set({ serialNumber: e.target.value })} /></Field>
          <Field label="Status"><Select label="Status" value={form.status} onChange={(v) => set({ status: v })} options={(options.ASSET_STATUS ?? []).filter((o) => o.code !== "retired")} /></Field>
          <Field label="Meter type"><Select label="Meter type" value={form.meterType} onChange={(v) => set({ meterType: v })} options={options.METER_TYPE ?? []} /></Field>
          <Field label="Current reading"><input aria-label="Current reading" type="number" className="eoss-input w-full" value={form.currentReading ?? ""} onChange={(e) => set({ currentReading: num(e.target.value) })} /></Field>
          <Field label="PM every (meter units)"><input aria-label="PM frequency meter" type="number" className="eoss-input w-full" value={form.pmFrequencyHours ?? ""} onChange={(e) => set({ pmFrequencyHours: num(e.target.value) })} /></Field>
          <Field label="PM every (days)"><input aria-label="PM frequency days" type="number" className="eoss-input w-full" value={form.pmFrequencyDays ?? ""} onChange={(e) => set({ pmFrequencyDays: num(e.target.value) })} /></Field>
          <Field label="Last service date"><input aria-label="Last service date" type="date" className="eoss-input w-full" value={form.lastServiceDate ?? ""} onChange={(e) => set({ lastServiceDate: e.target.value || null })} /></Field>
          <Field label="Next service date"><input aria-label="Next service date" type="date" className="eoss-input w-full" value={form.nextServiceDate ?? ""} onChange={(e) => set({ nextServiceDate: e.target.value || null })} /></Field>
          <Field label="Install date"><input aria-label="Install date" type="date" className="eoss-input w-full" value={form.installDate ?? ""} onChange={(e) => set({ installDate: e.target.value || null })} /></Field>
          <Field label="Responsible person">
            <select aria-label="Responsible person" className="eoss-input w-full" value={form.responsibleUserId ?? ""} onChange={(e) => set({ responsibleUserId: e.target.value || null })}>
              <option value="">Select…</option>
              {lookup.technicians.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
            </select>
          </Field>
        </div>
        <fieldset className="text-xs">
          <legend className="font-medium mb-1">Supported production stages</legend>
          <div className="flex flex-wrap gap-2">
            {(options.PRODUCTION_STAGES ?? []).map((stage) => (
              <label key={stage.id} className="inline-flex items-center gap-1">
                <input type="checkbox" checked={capabilities.includes(stage.id)} onChange={() => setCapabilities((current) => current.includes(stage.id) ? current.filter((id) => id !== stage.id) : [...current, stage.id])} />
                {stage.label}
              </label>
            ))}
            {(options.PRODUCTION_STAGES ?? []).length === 0 && <span className="text-[var(--text-3)]">No production stages are configured.</span>}
          </div>
        </fieldset>
        {error && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{error}</p>}
      </form>
    </RecordSheet>
  );
}

export function EventFormModal({ open, assets, assetId, options, lookup, ctl, onClose, onSaved }: {
  open: boolean; assets: Asset[]; assetId?: string; options: Options; lookup: Lookups; ctl?: OptionsCtl; onClose: () => void; onSaved: (e: MaintenanceEvent) => void;
}) {
  const [form, setForm] = useState<EventInput>({ assetId: "", eventType: "", symptom: "" });
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);
  const [completed, setCompleted] = useState(false);
  useEffect(() => {
    if (open) { setForm({ assetId: assetId ?? "", eventType: "", symptom: "" }); setCompleted(false); setError(""); }
  }, [open, assetId]);
  const set = (patch: Partial<EventInput>) => setForm((f) => ({ ...f, ...patch }));
  const toggleJob = (id: string) => set({ affectedJobIds: form.affectedJobIds?.includes(id) ? form.affectedJobIds.filter((j) => j !== id) : [...(form.affectedJobIds ?? []), id] });

  async function submit(e: FormEvent) {
    e.preventDefault();
    if (!form.assetId || !form.eventType || !form.symptom.trim()) { setError("Asset, event type and description are required."); return; }
    if (form.eventType === "Breakdown" && !form.downtimeStart) { setError("Downtime start is required for a breakdown."); return; }
    if (form.eventType !== "Breakdown" && !form.plannedDate) { setError("Planned date is required."); return; }
    if (completed && !form.completionDate) { setError("Completion time is required for a completed event."); return; }
    if (completed && !form.workDone?.trim()) { setError("Work done is required for a completed event."); return; }
    setSaving(true); setError("");
    const iso = (v?: string) => (v ? new Date(v).toISOString() : undefined);
    const closure = completed
      ? { completionDate: iso(form.completionDate) }
      : { completionDate: undefined, rootCause: undefined, workDone: undefined, resolutionNotes: undefined, partsUsed: undefined };
    try { onSaved(await createEvent({ ...form, ...closure, downtimeStart: iso(form.downtimeStart) })); }
    catch (err) { setError(err instanceof ApiError ? err.message : "Could not log the event."); }
    finally { setSaving(false); }
  }

  if (!open) return null;
  return (
    <RecordSheet
      title="Log Maintenance Event"
      onClose={onClose}
      primary={{ label: saving ? "Saving…" : "Log event", onClick: () => { const fakeEvt = { preventDefault: () => {} } as FormEvent; void submit(fakeEvt); }, disabled: saving }}
      secondary={{ label: "Cancel", onClick: onClose }}
    >
      <form onSubmit={submit} className="space-y-3">
        <Field label="Asset *">
          <select aria-label="Event asset" className="eoss-input w-full" value={form.assetId} onChange={(e) => set({ assetId: e.target.value })}>
            <option value="">Select…</option>
            {assets.filter((a) => a.status !== "retired").map((a) => <option key={a.id} value={a.id}>{a.assetCode} — {a.name}</option>)}
          </select>
        </Field>
        <Field label="Event type *"><Select label="Event type" value={form.eventType} onChange={(v) => set({ eventType: v })} options={options.MAINTENANCE_EVENT_TYPE ?? []} /></Field>
        {form.eventType && <Field label="Severity"><ConfigSelect category="MAINTENANCE_SEVERITY" ctl={ctl} label="Severity" value={form.severity} onChange={(v) => set({ severity: v })} options={options.MAINTENANCE_SEVERITY ?? []} /></Field>}
        <Field label="Description *"><textarea aria-label="Description" className="eoss-input w-full" rows={2} value={form.symptom} onChange={(e) => set({ symptom: e.target.value })} /></Field>
        <Field label="Technician">
          <select aria-label="Technician" className="eoss-input w-full" value={form.technicianUserId ?? ""} onChange={(e) => set({ technicianUserId: e.target.value || undefined, technicianName: e.target.value ? undefined : form.technicianName })}>
            <option value="">Select…</option>
            {lookup.technicians.map((t) => <option key={t.id} value={t.id}>{t.name}</option>)}
          </select>
        </Field>
        {!form.technicianUserId && (
          <Field label="External technician / contractor"><input aria-label="External technician" placeholder="Name, if not a BOSS user" className="eoss-input w-full" value={form.technicianName ?? ""} onChange={(e) => set({ technicianName: e.target.value })} /></Field>
        )}
        {form.eventType === "Breakdown" ? (
          <>
            <Field label="Downtime start *"><input aria-label="Downtime start" type="datetime-local" className="eoss-input w-full" value={form.downtimeStart ?? ""} onChange={(e) => set({ downtimeStart: e.target.value })} /></Field>
            <fieldset><legend className="text-xs font-semibold text-[var(--text-2)]">Affected jobs</legend>
              <div className="max-h-28 overflow-y-auto mt-1 space-y-1">
                {lookup.jobs.length === 0 && <p className="text-xs text-[var(--text-3)]">No active jobs.</p>}
                {lookup.jobs.map((j) => (
                  <label key={j.id} className="flex items-center gap-2 text-xs"><input type="checkbox" checked={form.affectedJobIds?.includes(j.id) ?? false} onChange={() => toggleJob(j.id)} />{j.jobNumber}</label>
                ))}
              </div>
            </fieldset>
          </>
        ) : form.eventType ? (
          <>
            <Field label="Planned date *"><input aria-label="Planned date" type="date" className="eoss-input w-full" value={form.plannedDate ?? ""} onChange={(e) => set({ plannedDate: e.target.value })} /></Field>
            <Field label="Expected duration (hours)"><input aria-label="Expected duration" type="number" className="eoss-input w-full" value={form.expectedDurationHours ?? ""} onChange={(e) => set({ expectedDurationHours: e.target.value === "" ? undefined : Number(e.target.value) })} /></Field>
          </>
        ) : null}
        {form.eventType && (
          <>
            <Field label="Corrective action"><textarea aria-label="Corrective action" className="eoss-input w-full" rows={2} value={form.correctiveAction ?? ""} onChange={(e) => set({ correctiveAction: e.target.value })} /></Field>
            <Field label="Notes"><textarea aria-label="Notes" className="eoss-input w-full" rows={2} value={form.notes ?? ""} onChange={(e) => set({ notes: e.target.value })} /></Field>
            <label className="flex items-center gap-2 text-xs"><input type="checkbox" checked={completed} onChange={(e) => setCompleted(e.target.checked)} />Already completed</label>
          </>
        )}
        {completed && (
          <div className="space-y-3 border border-[var(--border)] rounded-lg p-3">
            <Field label="Completion time *"><input aria-label="Completion time" type="datetime-local" className="eoss-input w-full" value={form.completionDate ?? ""} onChange={(e) => set({ completionDate: e.target.value })} /></Field>
            <Field label="Root cause"><input aria-label="Event root cause" className="eoss-input w-full" value={form.rootCause ?? ""} onChange={(e) => set({ rootCause: e.target.value })} /></Field>
            <Field label="Work done *"><textarea aria-label="Event work done" className="eoss-input w-full" rows={2} value={form.workDone ?? ""} onChange={(e) => set({ workDone: e.target.value })} /></Field>
            <Field label="Resolution notes"><input aria-label="Event resolution notes" className="eoss-input w-full" value={form.resolutionNotes ?? ""} onChange={(e) => set({ resolutionNotes: e.target.value })} /></Field>
            <Field label="Parts used"><input aria-label="Event parts used" className="eoss-input w-full" value={form.partsUsed ?? ""} onChange={(e) => set({ partsUsed: e.target.value })} /></Field>
          </div>
        )}
        {error && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{error}</p>}
      </form>
    </RecordSheet>
  );
}

type Focus = "all" | "breakdown" | "overdue" | "due_soon";

export default function AssetsPage() {
  const navigate = useNavigate();
  const [params, setParams] = useSearchParams();
  const tab = params.get("tab") === "events" ? "events" : "assets";
  const { user } = useCustomer();
  const canManage = (user?.roles.map((r) => r.role) ?? []).some((r) => MANAGE_ROLES.includes(r));
  const { options, lookup, error: optionsError, ctl } = useMaintenanceOptions();

  const [assets, setAssets] = useState<Asset[]>([]);
  const [events, setEvents] = useState<MaintenanceEvent[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const [status, setStatus] = useState("all");
  const [eventStatus, setEventStatus] = useState("all");
  const [focus, setFocus] = useState<Focus>("all");
  const [showArchived, setShowArchived] = useState(false);
  const [sortKey, setSortKey] = useState<"assetCode" | "name" | "status">("assetCode");
  const [assetModal, setAssetModal] = useState<{ open: boolean; asset: Asset | null }>({ open: false, asset: null });
  const [eventModal, setEventModal] = useState(false);
  const [archiveTarget, setArchiveTarget] = useState<Asset | null>(null);
  const [archiveReason, setArchiveReason] = useState("");
  const [archiveError, setArchiveError] = useState("");

  const load = useCallback(() => {
    setLoading(true); setError("");
    Promise.all([listAssets(true), listEvents()])
      .then(([a, e]) => { setAssets(a); setEvents(e); })
      .catch((e) => setError(e instanceof ApiError ? e.message : "Could not load maintenance data."))
      .finally(() => setLoading(false));
  }, []);
  useEffect(load, [load]);

  const statusLabel = (code: string) => options.ASSET_STATUS?.find((o) => o.code === code)?.label ?? code;
  const counts = useMemo(() => {
    const c: Record<string, number> = {};
    assets.forEach((a) => { c[a.status] = (c[a.status] ?? 0) + 1; });
    return c;
  }, [assets]);
  const kpi = useMemo(() => ({
    breakdown: assets.filter((a) => a.status === "breakdown").length,
    overdue: assets.filter((a) => computeDue(a).status === "overdue").length,
    dueSoon: assets.filter((a) => ["due", "upcoming"].includes(computeDue(a).status)).length,
  }), [assets]);

  const visibleAssets = useMemo(() => {
    const q = search.trim().toLowerCase();
    return assets
      .filter((a) => showArchived || status === "retired" || a.status !== "retired")
      .filter((a) => status === "all" || a.status === status)
      .filter((a) => focus === "all" || (focus === "breakdown" ? a.status === "breakdown" : focus === "overdue" ? computeDue(a).status === "overdue" : ["due", "upcoming"].includes(computeDue(a).status)))
      .filter((a) => !q || [a.assetCode, a.name, a.category, a.workCentre, a.manufacturer, a.model].some((v) => v?.toLowerCase().includes(q)))
      .sort((a, b) => String(a[sortKey] ?? "").localeCompare(String(b[sortKey] ?? "")));
  }, [assets, search, status, focus, sortKey]);

  const visibleEvents = useMemo(() => {
    const q = search.trim().toLowerCase();
    return events
      .filter((e) => eventStatus === "all" || e.status === eventStatus)
      .filter((e) => !q || [e.eventNumber, e.assetCode, e.assetName, e.symptom, e.technicianName].some((v) => v?.toLowerCase().includes(q)));
  }, [events, search, eventStatus]);

  async function confirmArchive() {
    if (!archiveTarget) return;
    if (!archiveReason.trim()) { setArchiveError("A reason is required."); return; }
    try { await archiveAsset(archiveTarget.id, archiveReason); setArchiveTarget(null); setArchiveReason(""); load(); }
    catch (e) { setArchiveError(e instanceof ApiError ? e.message : "Could not archive the asset."); }
  }

  const setTab = (t: string) => setParams(t === "events" ? { tab: "events" } : {});

  return (
    <div className="p-4 md:p-6 space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-lg font-bold text-[var(--text)]">Maintenance</h1>
        {canManage && (
          <div className="flex gap-2">
            <button type="button" onClick={() => setAssetModal({ open: true, asset: null })} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}><Plus size={13} /> Add Asset</button>
            <button type="button" onClick={() => setEventModal(true)} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold border border-[var(--border)]"><Plus size={13} /> Log Event</button>
          </div>
        )}
      </div>

      <div role="tablist" className="flex gap-4 border-b border-[var(--border)]">
        {[["assets", "Assets"], ["events", "Maintenance Events"]].map(([id, label]) => (
          <button key={id} role="tab" aria-selected={tab === id} type="button" onClick={() => setTab(id)}
            className="pb-2 text-sm font-semibold" style={{ color: tab === id ? "var(--brand)" : "var(--text-3)", borderBottom: tab === id ? "2px solid var(--brand)" : "2px solid transparent" }}>{label}</button>
        ))}
      </div>

      {optionsError && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{optionsError} <button type="button" className="underline" onClick={() => void ctl.reload()}>Retry</button></p>}

      <div className="flex flex-wrap items-center gap-2">
        <div className="relative">
          <Search size={13} className="absolute left-2.5 top-2.5 text-[var(--text-3)]" />
          <input aria-label="Search" placeholder={tab === "assets" ? "Search assets" : "Search events"} className="eoss-input pl-9" value={search} onChange={(e) => setSearch(e.target.value)} />
        </div>
        {tab === "assets" ? (
          <>
            <select aria-label="Status filter" className="eoss-input" value={status} onChange={(e) => setStatus(e.target.value)}>
              <option value="all">All statuses ({assets.length})</option>
              {(options.ASSET_STATUS ?? []).map((o) => <option key={o.id} value={o.code}>{o.label} ({counts[o.code] ?? 0})</option>)}
            </select>
            {([["breakdown", `Breakdown (${kpi.breakdown})`], ["overdue", `PM overdue (${kpi.overdue})`], ["due_soon", `Due soon (${kpi.dueSoon})`]] as const).map(([id, label]) => (
              <button key={id} type="button" aria-pressed={focus === id} onClick={() => setFocus(focus === id ? "all" : id)}
                className="px-2.5 py-1 rounded-full text-xs border border-[var(--border)]" style={focus === id ? { background: "var(--brand-fill)", color: "#fff" } : undefined}>{label}</button>
            ))}
            <label className="flex items-center gap-1.5 text-xs"><input type="checkbox" checked={showArchived} onChange={(e) => setShowArchived(e.target.checked)} />Show archived</label>
          </>
        ) : (
          ["all", ...(options.MAINTENANCE_EVENT_STATUS ?? []).map((o) => o.code)].map((code) => (
            <button key={code} type="button" aria-pressed={eventStatus === code} onClick={() => setEventStatus(code)}
              className="px-2.5 py-1 rounded-full text-xs border border-[var(--border)]" style={eventStatus === code ? { background: "var(--brand-fill)", color: "#fff" } : undefined}>
              {code === "all" ? "All" : options.MAINTENANCE_EVENT_STATUS?.find((o) => o.code === code)?.label}
            </button>
          ))
        )}
      </div>

      {loading && <p className="text-sm text-[var(--text-3)]">Loading maintenance data…</p>}
      {error && (
        <div role="alert" className="text-sm" style={{ color: "var(--error)" }}>
          {error} <button type="button" className="underline" onClick={load}>Retry</button>
        </div>
      )}

      {!loading && !error && tab === "assets" && (
        visibleAssets.length === 0 ? <p className="text-sm text-[var(--text-3)]">No assets match. {canManage ? "Use Add Asset to create one." : ""}</p> : (
          <div className="overflow-x-auto border border-[var(--border)] rounded-xl">
            <table className="w-full text-xs">
              <thead className="bg-[var(--surface-2)] text-left">
                <tr>
                  <th className="p-2"><button type="button" onClick={() => setSortKey("assetCode")}>Code</button></th>
                  <th className="p-2"><button type="button" onClick={() => setSortKey("name")}>Asset</button></th>
                  <th className="p-2">Category</th><th className="p-2">Work Centre</th><th className="p-2">Criticality</th>
                  <th className="p-2">Meter</th><th className="p-2">Last Service</th><th className="p-2">Next Service</th>
                  <th className="p-2"><button type="button" onClick={() => setSortKey("status")}>Status</button></th>
                  {canManage && <th className="p-2">Actions</th>}
                </tr>
              </thead>
              <tbody>
                {visibleAssets.map((a) => {
                  const due = computeDue(a);
                  const rowStyle = a.status === "breakdown" ? { background: "rgba(220,38,38,0.07)" } : due.status === "overdue" ? { background: "rgba(217,119,6,0.07)" } : {};
                  return (
                  <tr key={a.id} className="border-t border-[var(--border)] hover:bg-[var(--surface-2)] cursor-pointer" style={rowStyle} onClick={() => navigate(`/assets/${a.id}`)}>
                    <td className="p-2 font-semibold">{a.assetCode}</td>
                    <td className="p-2">{a.name}<div className="text-[var(--text-3)]">{[a.manufacturer, a.model].filter(Boolean).join(" ")}</div></td>
                    <td className="p-2">{a.category ?? "—"}</td>
                    <td className="p-2">{a.workCentre ?? "—"}</td>
                    <td className="p-2 capitalize">{a.criticality}</td>
                    <td className="p-2">{a.currentReading != null ? `${a.currentReading} ${a.meterType}` : "—"}</td>
                    <td className="p-2">{displayDate(a.lastServiceDate)}</td>
                    <td className="p-2">{nextServiceLabel(a)}</td>
                    <td className="p-2"><StatusChip status={a.status} label={statusLabel(a.status)} /></td>
                    {canManage && (
                      <td className="p-2 whitespace-nowrap" onClick={(e) => e.stopPropagation()}>
                        {a.status !== "retired" && <>
                          <button type="button" className="mr-2 underline" onClick={() => setAssetModal({ open: true, asset: a })}>Edit</button>
                          <button type="button" className="underline" style={{ color: "var(--error)" }} onClick={() => { setArchiveTarget(a); setArchiveReason(""); setArchiveError(""); }}>Archive</button>
                        </>}
                      </td>
                    )}
                  </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )
      )}

      {!loading && !error && tab === "events" && (
        <div className="p-5 space-y-3 max-w-4xl">
          {visibleEvents.length === 0 && <p className="text-center py-10 text-sm" style={{ color: "var(--text-3)" }}>No maintenance events found.</p>}
          {visibleEvents.map((e) => (
            <div key={e.id} className="rounded-xl border p-4 cursor-pointer hover:opacity-90 transition-opacity"
              style={{ borderColor: (e.eventType === "Breakdown" && e.status !== "closed") ? "var(--error)" : "var(--border)", background: "var(--surface)" }}
              onClick={() => navigate(`/assets/me/${e.id}`)}>
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1">
                  <div className="flex items-center gap-2 flex-wrap mb-1">
                    <span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{e.eventNumber}</span>
                    <StatusChip status={e.eventType?.toLowerCase() ?? ""} label={e.eventType} size="sm" />
                    <StatusChip status={e.status} label={options.MAINTENANCE_EVENT_STATUS?.find((o) => o.code === e.status)?.label} size="sm" />
                  </div>
                  <div className="text-sm font-medium" style={{ color: "var(--text)" }}>{e.assetName} <span className="font-mono text-xs" style={{ color: "var(--text-3)" }}>({e.assetCode})</span></div>
                  {e.symptom && <p className="text-xs mt-1 line-clamp-2" style={{ color: "var(--text-2)" }}>{e.symptom}</p>}
                  <div className="flex items-center gap-3 mt-1 text-xs" style={{ color: "var(--text-3)" }}>
                    {e.technicianName && <span>{e.technicianName}</span>}
                    {e.technicianName && <span>·</span>}
                    <span>{displayDate(e.plannedDate ?? e.downtimeStart)}</span>
                    {e.downtimeHours != null && <><span>·</span><span>{e.downtimeHours} h downtime</span></>}
                  </div>
                </div>
                <ChevronRight size={14} style={{ color: "var(--text-3)", flexShrink: 0 }} />
              </div>
            </div>
          ))}
        </div>
      )}

      <AssetFormModal open={assetModal.open} asset={assetModal.asset} options={options} lookup={lookup} ctl={ctl}
        onClose={() => setAssetModal({ open: false, asset: null })} onSaved={() => { setAssetModal({ open: false, asset: null }); load(); }} />
      <EventFormModal open={eventModal} assets={assets} options={options} lookup={lookup} ctl={ctl}
        onClose={() => setEventModal(false)} onSaved={() => { setEventModal(false); load(); }} />
      {!!archiveTarget && (
        <RecordSheet
          title={`Archive ${archiveTarget.assetCode}`}
          danger
          onClose={() => setArchiveTarget(null)}
          primary={{ label: "Archive Asset", onClick: () => void confirmArchive() }}
          secondary={{ label: "Cancel", onClick: () => setArchiveTarget(null) }}
        >
          <div className="space-y-3">
            <Field label="Reason *"><textarea aria-label="Archive reason" className="eoss-input w-full" rows={2} value={archiveReason} onChange={(e) => setArchiveReason(e.target.value)} /></Field>
            {archiveError && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{archiveError}</p>}
          </div>
        </RecordSheet>
      )}
    </div>
  );
}
