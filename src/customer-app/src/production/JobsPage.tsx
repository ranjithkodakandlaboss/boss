import { useEffect, useMemo, useState } from "react";
import { useLocation, useNavigate, useSearchParams } from "react-router-dom";
import { MoreVertical, Plus, Search } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import { ApiError } from "../auth/api";
import CreatableSelect, { roleAllowed } from "../components/shared/CreatableSelect";
import RecordSheet from "../components/shared/RecordSheet";
import SortableHeader from "../components/shared/SortableHeader";
import StatusChip from "../components/shared/StatusChip";
import {
  cancelJob, createJob, createRoutingTemplate, displayDate, eligibleLines, featureFlags, listJobs, readinessLabel, routingTemplates,
  type EligibleLine, type ProductionJob, type RoutingTemplate,
} from "./api";

type SortKey = "number" | "customer" | "stage" | "status" | "plannedEnd" | "projected" | "material";

const ACTIVE = new Set(["planned", "released", "in_progress"]);
const RISK_CHIPS = [
  ["late", "Late", "error"],
  ["at_risk", "At risk", "warning"],
  ["delayed", "Delayed", "error"],
  ["blocked", "Blocked", "error"],
  ["awaiting_material", "Awaiting material", "warning"],
] as const;

function matchesStatus(job: ProductionJob, status: string) {
  if (status === "all") return true;
  if (status === "active") return ACTIVE.has(job.status);
  if (status === "hold") return job.status === "on_hold";
  if (status === "complete") return job.status === "completed";
  return job.status === status;
}

function isLate(job: ProductionJob) {
  if (job.status === "completed" || job.status === "cancelled") return false;
  return job.risk === "delayed" || (job.daysRemaining != null && job.daysRemaining < 0);
}

export default function JobsPage() {
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user } = useCustomer();
  const roles = user?.roles.map((role) => role.role) ?? [];
  const canManage = roles.some((role) => ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR"].includes(role));
  const [jobs, setJobs] = useState<ProductionJob[]>([]);
  const [flags, setFlags] = useState<Record<string, boolean>>({});
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState(searchParams.get("q") ?? "");
  const [statusFilter, setStatusFilter] = useState(searchParams.get("status") ?? "all");
  const [readinessFilter, setReadinessFilter] = useState(searchParams.get("readiness") ?? "all");
  const [riskFilter, setRiskFilter] = useState<string | null>(searchParams.get("risk"));
  const [siteFilter, setSiteFilter] = useState(searchParams.get("site") ?? "all");
  const [sortKey, setSortKey] = useState<SortKey>("number");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("asc");
  const [menuJobId, setMenuJobId] = useState<string | null>(null);
  const [cancelTarget, setCancelTarget] = useState<ProductionJob | null>(null);
  const [showNew, setShowNew] = useState(false);

  async function load() {
    setLoading(true);
    setError("");
    try {
      const [page, nextFlags] = await Promise.all([listJobs(), featureFlags()]);
      setJobs(page.content ?? []);
      setFlags(nextFlags);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "Jobs could not be loaded");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { void load(); }, []);

  useEffect(() => {
    const next = new URLSearchParams();
    if (search.trim()) next.set("q", search.trim());
    if (statusFilter !== "all") next.set("status", statusFilter);
    if (readinessFilter !== "all") next.set("readiness", readinessFilter);
    if (siteFilter !== "all") next.set("site", siteFilter);
    if (riskFilter) next.set("risk", riskFilter);
    if (next.toString() !== searchParams.toString()) setSearchParams(next, { replace: true });
  }, [search, statusFilter, readinessFilter, siteFilter, riskFilter, searchParams, setSearchParams]);

  const sites = useMemo(() => {
    const seen = new Map<string, string>();
    jobs.forEach((job) => { if (job.siteId && job.siteName) seen.set(job.siteId, job.siteName); });
    return [...seen.entries()];
  }, [jobs]);

  function match(job: ProductionJob, status: string, readiness = readinessFilter) {
    const query = search.toLowerCase();
    const text = !query || [job.jobNumber, job.customerName, job.cpoNumber, job.itemName, job.currentStage]
      .some((value) => value?.toLowerCase().includes(query));
    const risk = job.risk ?? "on_track";
    const riskOk = riskFilter === "late" ? isLate(job) : !riskFilter || risk === riskFilter;
    return text && matchesStatus(job, status) && riskOk
      && (readiness === "all" || job.materialReadiness === readiness)
      && (siteFilter === "all" || job.siteId === siteFilter);
  }

  const filtered = jobs.filter((job) => match(job, statusFilter));
  const sorted = [...filtered].sort((left, right) => {
    const value = (job: ProductionJob) => ({
      number: job.jobNumber, customer: job.customerName ?? "", stage: job.currentStage ?? "",
      status: job.status, plannedEnd: job.plannedEnd ?? "", projected: job.projectedCompletion ?? "",
      material: job.materialReadiness ?? "",
    })[sortKey];
    const compared = String(value(left)).localeCompare(String(value(right)));
    return sortDir === "asc" ? compared : -compared;
  });
  const counts = {
    all: jobs.filter((job) => match(job, "all")).length,
    active: jobs.filter((job) => match(job, "active")).length,
    hold: jobs.filter((job) => match(job, "hold")).length,
    complete: jobs.filter((job) => match(job, "complete")).length,
  };
  const readinessOptions = ["pending", "not_assessed", "available", "partial", "shortage", "awaiting_procurement", "awaiting_grn", "quality_hold", "reserved"];
  const scoped = jobs.filter((job) => match(job, "all", "all") && (siteFilter === "all" || job.siteId === siteFilter));
  const riskCounts = {
    late: scoped.filter(isLate).length,
    at_risk: scoped.filter((job) => job.risk === "at_risk").length,
    delayed: scoped.filter((job) => job.risk === "delayed").length,
    blocked: scoped.filter((job) => job.risk === "blocked" || job.status === "on_hold").length,
    awaiting_material: scoped.filter((job) => job.risk === "awaiting_material").length,
  };
  const open = (path: string) => navigate(path, { state: { from: `${location.pathname}${location.search}` } });
  const createEnabled = flags["production.job.create"] !== false && flags["production.routing.stages"] !== false;

  return (
    <div className="flex flex-col h-full">
      <div className="flex-shrink-0 flex items-center gap-3 px-5 py-3 border-b flex-wrap" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <div className="relative flex-1 min-w-[200px] max-w-sm">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: "var(--text-3)" }} />
          <input value={search} onChange={(event) => setSearch(event.target.value)} placeholder="Search job, customer, description…" aria-label="Search jobs" className="w-full pl-8 pr-3 py-1.5 text-sm rounded-lg border outline-none" style={{ borderColor: "var(--border)", background: "var(--surface)", color: "var(--text)" }} />
        </div>
        <select value={siteFilter} onChange={(event) => setSiteFilter(event.target.value)} className="eoss-input text-xs py-1.5 max-w-[11rem]" aria-label="Filter by site">
          <option value="all">All sites</option>
          {sites.map(([id, name]) => <option key={id} value={id}>{name}</option>)}
        </select>
        <div className="flex gap-1.5 overflow-x-auto">
          {([["all", "All"], ["active", "Active"], ["hold", "On Hold"], ["complete", "Complete"]] as const).map(([key, label]) => (
            <button key={key} type="button" onClick={() => setStatusFilter(key)} className="px-3 py-1.5 rounded-lg text-xs font-medium border whitespace-nowrap" style={{ background: statusFilter === key ? "var(--brand)" : "var(--surface)", color: statusFilter === key ? "#fff" : "var(--text-2)", borderColor: statusFilter === key ? "var(--brand)" : "var(--border)" }}>
              {label} ({counts[key]})
            </button>
          ))}
        </div>
        <select value={readinessFilter} onChange={(event) => setReadinessFilter(event.target.value)} className="eoss-input text-xs py-1.5 max-w-[13rem]" aria-label="Filter by material readiness">
          <option value="all">All Material Readiness</option>
          {readinessOptions.map((key) => <option key={key} value={key}>{readinessLabel(key)}</option>)}
        </select>
        {(search || statusFilter !== "all" || readinessFilter !== "all" || riskFilter || siteFilter !== "all") && (
          <button type="button" onClick={() => { setSearch(""); setStatusFilter("all"); setReadinessFilter("all"); setRiskFilter(null); setSiteFilter("all"); }} className="text-xs py-1.5 px-2.5">Clear filters</button>
        )}
        <div className="flex-1" />
        {canManage && createEnabled && <button type="button" onClick={() => setShowNew(true)} className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-white" style={{ background: "var(--brand-fill)" }}><Plus size={13} /> New Job</button>}
      </div>
      <div className="flex-shrink-0 flex gap-2 px-4 py-2.5 overflow-x-auto border-b" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        {RISK_CHIPS.map(([key, label, accent]) => {
          const value = riskCounts[key];
          const color = accent === "error" ? "var(--error)" : "var(--warning)";
          const active = riskFilter === key;
          return (
            <button key={key} type="button" onClick={() => setRiskFilter(active ? null : key)} className="flex-shrink-0 px-3 py-1.5 rounded-full border text-xs font-semibold" style={{ borderColor: active ? color : "var(--border)", color: value > 0 ? color : "var(--text-3)", background: active ? "var(--surface-2)" : "var(--surface)" }}>
              {value} {label}
            </button>
          );
        })}
      </div>
      {loading && <p role="status" className="px-5 py-4 text-sm" style={{ color: "var(--text-3)" }}>Loading jobs</p>}
      {error && <p role="alert" className="px-5 py-4 text-sm" style={{ color: "var(--error)" }}>{error}</p>}
      {!loading && !error && (
        <>
          <div className="md:hidden flex-1 overflow-auto p-3 space-y-2">
            {sorted.map((job) => (
              <button key={job.id} type="button" onClick={() => open(`/jobs/${job.id}`)} className="w-full text-left rounded-xl border p-3" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
                <div className="flex items-center justify-between"><span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{job.jobNumber}</span><span className="text-xs font-semibold">{readinessLabel(job.materialReadiness)}</span></div>
                <div className="text-sm font-semibold mt-1">{job.customerName}</div>
                <div className="text-[11px]" style={{ color: "var(--text-3)" }}>{job.siteName}</div>
                <div className="text-xs mt-0.5">{job.itemName}</div>
              </button>
            ))}
            {sorted.length === 0 && <p className="text-center py-8 text-sm" style={{ color: "var(--text-3)" }}>No jobs found</p>}
          </div>
          <div className="hidden md:block flex-1 overflow-auto">
            <table className="w-full text-sm border-separate border-spacing-0" style={{ minWidth: 900 }}>
              <thead style={{ position: "sticky", top: 0, zIndex: 5 }}>
                <tr style={{ background: "var(--surface-2)" }}>
                  {([["Job #", "number"], ["Material Readiness", "material"], ["Customer", "customer"], ["Current Stage", "stage"], ["Job Status", "status"], ["Target Completion", "plannedEnd"], ["Projected", "projected"]] as [string, SortKey][]).map(([label, key]) => (
                    <SortableHeader key={label} label={label} active={sortKey === key} direction={sortDir} onSort={() => { if (sortKey === key) setSortDir((dir) => dir === "asc" ? "desc" : "asc"); else { setSortKey(key); setSortDir("asc"); } }} className="text-left px-4 py-2.5 text-xs font-semibold" style={{ color: "var(--text-3)", borderBottom: "1px solid var(--border)" }} />
                  ))}
                  <th />
                </tr>
              </thead>
              <tbody>
                {sorted.map((job) => (
                  <tr key={job.id} className="cursor-pointer" style={{ background: "var(--surface)" }} onClick={() => open(`/jobs/${job.id}`)}>
                    <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{job.jobNumber}{isLate(job) && <span className="ml-1.5 text-[10px] font-bold px-1.5 py-0.5 rounded-full" style={{ color: "var(--error)" }}>Late</span>}</td>
                    <td className="px-4 py-3"><StatusChip status={job.materialReadiness ?? "pending"} label={readinessLabel(job.materialReadiness)} size="sm" /></td>
                    <td className="px-4 py-3">{job.customerName}<div className="text-[11px]" style={{ color: "var(--text-3)" }}>{job.siteName}</div></td>
                    <td className="px-4 py-3 text-xs">{job.currentStage}</td>
                    <td className="px-4 py-3"><StatusChip status={job.status} size="sm" /></td>
                    <td className="px-4 py-3 text-xs" style={isLate(job) ? { color: "var(--error)" } : {}}>{displayDate(job.plannedEnd)}</td>
                    <td className="px-4 py-3 text-xs" title={job.projectionBasis}>{displayDate(job.projectedCompletion)}</td>
                    <td className="px-4 py-3 relative" onClick={(event) => event.stopPropagation()}>
                      <button type="button" aria-label={`Actions for ${job.jobNumber}`} onClick={() => setMenuJobId(menuJobId === job.id ? null : job.id)} className="p-1.5 rounded-lg"><MoreVertical size={14} /></button>
                      {menuJobId === job.id && (
                        <div className="absolute right-2 top-full w-40 rounded-xl border shadow-lg py-1 z-30" style={{ background: "var(--surface)", borderColor: "var(--border)" }}>
                          <button type="button" className="w-full text-left px-3 py-1.5 text-xs" onClick={() => open(`/jobs/${job.id}`)}>Open job</button>
                          {canManage && !["completed", "cancelled"].includes(job.status) && <button type="button" className="w-full text-left px-3 py-1.5 text-xs" style={{ color: "var(--error)" }} onClick={() => { setMenuJobId(null); setCancelTarget(job); }}>Cancel job…</button>}
                        </div>
                      )}
                    </td>
                  </tr>
                ))}
                {sorted.length === 0 && <tr><td colSpan={8} className="px-4 py-10 text-center text-sm" style={{ color: "var(--text-3)" }}>No jobs found</td></tr>}
              </tbody>
            </table>
          </div>
        </>
      )}
      {cancelTarget && <CancelJob job={cancelTarget} onClose={() => setCancelTarget(null)} onDone={() => { setCancelTarget(null); void load(); }} />}
      {showNew && <NewJob onClose={() => setShowNew(false)} onCreated={(job) => navigate(`/jobs/${job.id}`)} />}
    </div>
  );
}

function CancelJob({ job, onClose, onDone }: { job: ProductionJob; onClose: () => void; onDone: () => void }) {
  const [reason, setReason] = useState("");
  const [error, setError] = useState("");
  async function confirm() {
    try {
      await cancelJob(job.id, reason.trim());
      onDone();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "The job was not cancelled");
    }
  }
  const issued = (job.materials ?? []).reduce((sum, row) => sum + Number(row.issuedQty ?? 0), 0);
  return (
    <RecordSheet title={`Cancel ${job.jobNumber}`} onClose={onClose} secondary={{ label: "Keep job", onClick: onClose }} primary={{ label: "Confirm cancellation", onClick: () => void confirm(), disabled: !reason.trim() }}>
      <div className="space-y-4">
        <p className="text-xs" style={{ color: "var(--text-2)" }}>The record and audit history will be preserved. Reserved but unissued quantities will be released. Issued material stays issued until it is returned from the job.</p>
        <div className="rounded-lg p-3 text-xs" style={{ background: "var(--surface-2)" }}>Current status: <strong>{job.status}</strong> · Current stage: <strong>{job.currentStage}</strong> · Issued: <strong>{issued}</strong></div>
        <label className="block text-xs font-semibold">Cancellation reason *<textarea value={reason} onChange={(event) => setReason(event.target.value)} rows={3} className="eoss-input w-full mt-1" /></label>
        {error && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{error}</p>}
      </div>
    </RecordSheet>
  );
}

function NewJob({ onClose, onCreated }: { onClose: () => void; onCreated: (job: ProductionJob) => void }) {
  const { user } = useCustomer();
  const canCreateTemplate = roleAllowed(user?.roles, ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR"]);
  const [lines, setLines] = useState<EligibleLine[]>([]);
  const [templates, setTemplates] = useState<RoutingTemplate[]>([]);
  const [lineId, setLineId] = useState("");
  const [templateId, setTemplateId] = useState("");
  const [plannedEnd, setPlannedEnd] = useState("");
  const [priority, setPriority] = useState("normal");
  const [quantity, setQuantity] = useState("");
  const [error, setError] = useState("");
  const [templateError, setTemplateError] = useState("");
  const line = lines.find((row) => row.cpoLineId === lineId);

  function loadLines() {
    setError("");
    eligibleLines().then(setLines).catch((cause) => setError(cause instanceof ApiError ? cause.message : "Eligible orders could not be loaded"));
  }

  function loadTemplates(itemId: string) {
    setTemplateError("");
    routingTemplates(itemId).then((rows) => { setTemplates(rows); setTemplateId((current) => current || rows[0]?.id || ""); }).catch((cause) => setTemplateError(cause instanceof ApiError ? cause.message : "Routing templates could not be loaded"));
  }

  useEffect(() => { loadLines(); }, []);
  useEffect(() => {
    if (!line) { setTemplates([]); setTemplateId(""); return; }
    setQuantity(String(line.remainingQty ?? line.qty ?? ""));
    setTemplateId("");
    loadTemplates(line.itemId);
  }, [line]);

  async function save() {
    const jobQty = Number(quantity);
    const remaining = Number(line?.remainingQty ?? line?.qty ?? 0);
    if (!line || !templateId || !plannedEnd || !(jobQty > 0)) {
      setError("Customer PO line, job quantity, production workflow and planned end are required.");
      return;
    }
    if (jobQty > remaining) {
      setError(`Job quantity cannot exceed the remaining Customer PO quantity of ${remaining}.`);
      return;
    }
    try {
      const job = await createJob({ cpoId: line.cpoId, cpoLineId: line.cpoLineId, routingTemplateId: templateId, scheduledEnd: plannedEnd, priority, quantity: jobQty });
      onCreated(job);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : "The job was not created");
    }
  }

  return (
    <RecordSheet title="New job" subtitle="Opens from an eligible Customer PO" onClose={onClose} secondary={{ label: "Cancel", onClick: onClose }} primary={{ label: "Create job", onClick: () => void save(), disabled: !line || !templateId }}>
      <div className="space-y-3">
        <label className="block text-xs font-medium">Customer PO *<select className="eoss-input w-full mt-1" aria-label="Customer PO" value={lineId} onChange={(event) => setLineId(event.target.value)}><option value="">Select an eligible order line…</option>{lines.map((row) => <option key={row.cpoLineId} value={row.cpoLineId}>{row.cpoNumber} — {row.customerName} — {row.itemName}</option>)}</select></label>
        {error && <p role="alert" className="text-xs" style={{ color: "var(--error)" }}>{error} <button type="button" className="font-semibold underline" onClick={loadLines}>Retry</button></p>}
        {line && (
          <div className="rounded-xl border p-3 text-xs space-y-1" style={{ background: "var(--surface-2)", borderColor: "var(--border)" }}>
            <div>Customer: <strong>{line.customerName}</strong></div>
            <div>Site: <strong>{line.siteName}</strong></div>
            <div>Item: <strong>{line.itemName}</strong> · Ordered <strong>{line.qty}</strong> · Remaining <strong>{line.remainingQty ?? line.qty}</strong></div>
            <div>Selling price: <strong>{line.sellingPrice ?? line.unitPrice}</strong></div>
            <p style={{ color: "var(--text-3)" }}>Selling price stays on the Customer PO. Production costing does not change it.</p>
          </div>
        )}
        <div className="block text-xs font-medium">Production workflow *
          <CreatableSelect label="workflow" ariaLabel="Production workflow" value={templateId} onChange={setTemplateId} error={templateError} onRetry={() => line && loadTemplates(line.itemId)} creatable={canCreateTemplate && !!line} options={templates.map((template) => ({ id: template.id, label: `${template.name} (${template.stageCount} stages)` }))} fields={[{ name: "name", label: "Workflow name", required: true }, { name: "stageName", label: "Stages in order, separated by commas", required: true }]} unique={(values) => templates.some((template) => template.name.toLowerCase() === values.name.toLowerCase()) ? "This value already exists." : null} onCreate={async (values) => {
            const created = await createRoutingTemplate(values.name, values.stageName);
            setTemplates((current) => [...current.filter((template) => template.id !== created.id), created]);
            return { id: created.id, label: `${created.name} (${created.stageCount} stages)` };
          }} />
          <p className="text-[11px] mt-1" style={{ color: "var(--text-3)" }}>The selected stage sequence is copied to the job and will not change if the template changes later.</p>
        </div>
        <label className="block text-xs font-medium">Job quantity *<input className="eoss-input w-full mt-1 text-left" aria-label="Job quantity" inputMode="decimal" value={quantity} onChange={(event) => setQuantity(event.target.value)} /></label>
        <div className="grid grid-cols-2 gap-3">
          <label className="text-xs font-medium">Planned End *<input type="date" className="eoss-input w-full mt-1" aria-label="Planned End" value={plannedEnd} onChange={(event) => setPlannedEnd(event.target.value)} /></label>
          <label className="text-xs font-medium">Priority<select className="eoss-input w-full mt-1" aria-label="Priority" value={priority} onChange={(event) => setPriority(event.target.value)}>{["normal", "high", "urgent", "low"].map((value) => <option key={value} value={value}>{value}</option>)}</select></label>
        </div>
      </div>
    </RecordSheet>
  );
}
