import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Plus, Search } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import StatusChip from "../components/shared/StatusChip";
import {
  listInspections, listNcrs, createInspection, createNcr,
  statusLabel, inspectionTypeLabel, severityLabel, displayDate,
  type InspectionSummary, type NcrSummary,
} from "./api";

function statusVariant(status: string): "default" | "success" | "warning" | "error" | "info" {
  switch (status) {
    case "completed": case "closed": case "concession_granted": return "success";
    case "in_progress": case "concession_requested": return "warning";
    case "pending": case "open": return "info";
    case "rework": return "warning";
    case "scrapped": return "error";
    default: return "default";
  }
}

function resultVariant(result?: string): "success" | "error" | "default" {
  if (result === "pass") return "success";
  if (result === "fail") return "error";
  return "default";
}

type Tab = "inspections" | "ncrs";

export default function QualityPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user } = useCustomer();
  const roles = user?.roles.map((r) => r.role) ?? [];
  const canWrite = roles.some((r) => ["OWNER", "OPS_MANAGER", "QUALITY_INSPECTOR"].includes(r));

  const tab: Tab = (searchParams.get("tab") as Tab) ?? "inspections";
  const [search, setSearch] = useState(searchParams.get("q") ?? "");
  const [typeFilter, setTypeFilter] = useState(searchParams.get("type") ?? "all");
  const [statusFilter, setStatusFilter] = useState(searchParams.get("status") ?? "all");

  const [inspections, setInspections] = useState<InspectionSummary[]>([]);
  const [ncrs, setNcrs] = useState<NcrSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // New Inspection dialog state
  const [showNewInspection, setShowNewInspection] = useState(false);
  const [newInspType, setNewInspType] = useState("final");
  const [newInspRefType, setNewInspRefType] = useState("job");
  const [newInspRefId, setNewInspRefId] = useState("");
  const [newInspQty, setNewInspQty] = useState("1");
  const [newInspNotes, setNewInspNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  // New NCR dialog state
  const [showNewNcr, setShowNewNcr] = useState(false);
  const [ncrDefectType, setNcrDefectType] = useState("");
  const [ncrDefectDesc, setNcrDefectDesc] = useState("");
  const [ncrSeverity, setNcrSeverity] = useState("medium");
  const [ncrQty, setNcrQty] = useState("1");
  const [ncrSaveError, setNcrSaveError] = useState("");

  function setTab(t: Tab) {
    setSearchParams((prev) => { prev.set("tab", t); return prev; }, { replace: true });
  }

  useEffect(() => {
    setLoading(true);
    setError("");
    if (tab === "inspections") {
      listInspections({
        type: typeFilter !== "all" ? typeFilter : undefined,
        status: statusFilter !== "all" ? statusFilter : undefined,
        q: search || undefined,
      }).then(setInspections).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
    } else {
      listNcrs({
        status: statusFilter !== "all" ? statusFilter : undefined,
        q: search || undefined,
      }).then(setNcrs).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
    }
  }, [tab, typeFilter, statusFilter, search]);

  const filteredInspections = useMemo(() => {
    if (!search) return inspections;
    const q = search.toLowerCase();
    return inspections.filter(
      (i) =>
        i.inspectionNumber?.toLowerCase().includes(q) ||
        i.referenceLabel?.toLowerCase().includes(q) ||
        i.itemName?.toLowerCase().includes(q) ||
        i.itemCode?.toLowerCase().includes(q),
    );
  }, [inspections, search]);

  const filteredNcrs = useMemo(() => {
    if (!search) return ncrs;
    const q = search.toLowerCase();
    return ncrs.filter(
      (n) =>
        n.ncrNumber?.toLowerCase().includes(q) ||
        n.itemName?.toLowerCase().includes(q) ||
        n.defectDescription?.toLowerCase().includes(q),
    );
  }, [ncrs, search]);

  async function handleCreateInspection(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setSaveError("");
    try {
      const insp = await createInspection({
        inspectionType: newInspType,
        referenceType: newInspRefType,
        referenceId: newInspRefId,
        quantityInspected: parseFloat(newInspQty) || 1,
        notes: newInspNotes || undefined,
      });
      setShowNewInspection(false);
      navigate(`/quality/inspection/${insp.id}`);
    } catch (e: unknown) {
      setSaveError((e as Error).message ?? "Failed to create inspection");
    } finally {
      setSaving(false);
    }
  }

  async function handleCreateNcr(e: React.FormEvent) {
    e.preventDefault();
    setSaving(true);
    setNcrSaveError("");
    try {
      const ncr = await createNcr({
        defectType: ncrDefectType,
        defectDescription: ncrDefectDesc,
        severity: ncrSeverity,
        quantity: parseFloat(ncrQty) || 1,
      });
      setShowNewNcr(false);
      navigate(`/quality/ncr/${ncr.id}`);
    } catch (e: unknown) {
      setNcrSaveError((e as Error).message ?? "Failed to create NCR");
    } finally {
      setSaving(false);
    }
  }

  return (
    <div className="p-4 md:p-6 max-w-6xl mx-auto">
      {/* Header */}
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-xl font-bold">Quality</h1>
        {canWrite && (
          <div className="flex gap-2">
            <button
              type="button"
              onClick={() => setShowNewInspection(true)}
              className="inline-flex items-center gap-1 rounded-lg bg-[var(--accent)] px-3 py-1.5 text-sm font-semibold text-white"
            >
              <Plus size={15} /> New Inspection
            </button>
            <button
              type="button"
              onClick={() => setShowNewNcr(true)}
              className="inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-sm font-semibold"
              style={{ borderColor: "var(--border)" }}
            >
              <Plus size={15} /> Raise NCR
            </button>
          </div>
        )}
      </div>

      {/* Tabs */}
      <div className="flex gap-1 mb-4 border-b" style={{ borderColor: "var(--border)" }}>
        {(["inspections", "ncrs"] as Tab[]).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={`px-4 py-2 text-sm font-medium capitalize border-b-2 -mb-px transition-colors ${
              tab === t
                ? "border-[var(--accent)] text-[var(--accent)]"
                : "border-transparent text-[var(--text-2)] hover:text-[var(--text-1)]"
            }`}
          >
            {t === "ncrs" ? "NCRs" : "Inspections"}
          </button>
        ))}
      </div>

      {/* Filters */}
      <div className="flex flex-wrap gap-2 mb-4">
        <div className="relative">
          <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--text-3)]" />
          <input
            className="pl-8 pr-3 py-1.5 text-sm rounded-lg border bg-[var(--surface)] outline-none w-52"
            style={{ borderColor: "var(--border)" }}
            placeholder={tab === "inspections" ? "Search inspections…" : "Search NCRs…"}
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>
        {tab === "inspections" && (
          <select
            className="px-3 py-1.5 text-sm rounded-lg border bg-[var(--surface)] outline-none"
            style={{ borderColor: "var(--border)" }}
            value={typeFilter}
            onChange={(e) => setTypeFilter(e.target.value)}
          >
            <option value="all">All types</option>
            <option value="incoming">Incoming</option>
            <option value="in_process">In-Process</option>
            <option value="final">Final</option>
            <option value="dispatch_readiness">Dispatch Readiness</option>
            <option value="customer">Customer</option>
          </select>
        )}
        <select
          className="px-3 py-1.5 text-sm rounded-lg border bg-[var(--surface)] outline-none"
          style={{ borderColor: "var(--border)" }}
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
        >
          <option value="all">All statuses</option>
          {tab === "inspections" ? (
            <>
              <option value="pending">Pending</option>
              <option value="in_progress">In Progress</option>
              <option value="completed">Completed</option>
            </>
          ) : (
            <>
              <option value="open">Open</option>
              <option value="concession_requested">Concession Requested</option>
              <option value="concession_granted">Concession Granted</option>
              <option value="rework">Rework</option>
              <option value="closed">Closed</option>
            </>
          )}
        </select>
      </div>

      {/* Content */}
      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700 mb-4">{error}</div>
      )}

      {loading ? (
        <p className="text-sm text-[var(--text-3)] py-8 text-center">Loading…</p>
      ) : tab === "inspections" ? (
        <InspectionsTable inspections={filteredInspections} onSelect={(id) => navigate(`/quality/inspection/${id}`)} />
      ) : (
        <NcrsTable ncrs={filteredNcrs} onSelect={(id) => navigate(`/quality/ncr/${id}`)} />
      )}

      {/* New Inspection dialog */}
      {showNewInspection && (
        <div className="fixed inset-0 z-[70] bg-black/40 flex items-center justify-center p-4">
          <div
            className="bg-[var(--bg)] rounded-xl shadow-2xl w-full max-w-md p-6"
            role="dialog"
            aria-modal="true"
            aria-label="New Inspection"
          >
            <h2 className="text-base font-semibold mb-4">New Inspection</h2>
            <form onSubmit={handleCreateInspection} className="space-y-3">
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Type</span>
                <select
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  value={newInspType}
                  onChange={(e) => setNewInspType(e.target.value)}
                >
                  <option value="in_process">In-Process</option>
                  <option value="final">Final</option>
                  <option value="dispatch_readiness">Dispatch Readiness</option>
                  <option value="customer">Customer</option>
                </select>
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Reference type</span>
                <select
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  value={newInspRefType}
                  onChange={(e) => setNewInspRefType(e.target.value)}
                >
                  <option value="job">Job</option>
                  <option value="job_stage">Job Stage</option>
                  <option value="lot">Lot</option>
                </select>
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Reference ID</span>
                <input
                  required
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  placeholder="UUID of the job / stage / lot"
                  value={newInspRefId}
                  onChange={(e) => setNewInspRefId(e.target.value)}
                />
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Quantity inspected</span>
                <input
                  type="number"
                  min="0"
                  step="any"
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  value={newInspQty}
                  onChange={(e) => setNewInspQty(e.target.value)}
                />
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Notes</span>
                <textarea
                  rows={2}
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)] resize-none"
                  style={{ borderColor: "var(--border)" }}
                  value={newInspNotes}
                  onChange={(e) => setNewInspNotes(e.target.value)}
                />
              </label>
              {saveError && <p className="text-xs text-red-600">{saveError}</p>}
              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowNewInspection(false)}
                  className="rounded-lg border px-4 py-2 text-sm font-medium"
                  style={{ borderColor: "var(--border)" }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                >
                  {saving ? "Creating…" : "Create"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* New NCR dialog */}
      {showNewNcr && (
        <div className="fixed inset-0 z-[70] bg-black/40 flex items-center justify-center p-4">
          <div
            className="bg-[var(--bg)] rounded-xl shadow-2xl w-full max-w-md p-6"
            role="dialog"
            aria-modal="true"
            aria-label="Raise NCR"
          >
            <h2 className="text-base font-semibold mb-4">Raise NCR</h2>
            <form onSubmit={handleCreateNcr} className="space-y-3">
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Defect type</span>
                <input
                  required
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  placeholder="e.g. Dimensional, Surface, Material"
                  value={ncrDefectType}
                  onChange={(e) => setNcrDefectType(e.target.value)}
                />
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Description</span>
                <textarea
                  required
                  rows={3}
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)] resize-none"
                  style={{ borderColor: "var(--border)" }}
                  value={ncrDefectDesc}
                  onChange={(e) => setNcrDefectDesc(e.target.value)}
                />
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Severity</span>
                <select
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  value={ncrSeverity}
                  onChange={(e) => setNcrSeverity(e.target.value)}
                >
                  <option value="critical">Critical</option>
                  <option value="major">Major</option>
                  <option value="medium">Medium</option>
                  <option value="minor">Minor</option>
                </select>
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Quantity</span>
                <input
                  type="number"
                  min="0"
                  step="any"
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  value={ncrQty}
                  onChange={(e) => setNcrQty(e.target.value)}
                />
              </label>
              {ncrSaveError && <p className="text-xs text-red-600">{ncrSaveError}</p>}
              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowNewNcr(false)}
                  className="rounded-lg border px-4 py-2 text-sm font-medium"
                  style={{ borderColor: "var(--border)" }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="rounded-lg bg-[var(--accent)] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                >
                  {saving ? "Raising…" : "Raise NCR"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

function InspectionsTable({ inspections, onSelect }: { inspections: InspectionSummary[]; onSelect: (id: string) => void }) {
  if (inspections.length === 0) {
    return <p className="text-sm text-[var(--text-3)] py-8 text-center">No inspections found.</p>;
  }
  return (
    <div className="overflow-x-auto rounded-xl border" style={{ borderColor: "var(--border)" }}>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs uppercase tracking-wide text-[var(--text-3)]" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
            <th className="px-4 py-3 font-semibold">Number</th>
            <th className="px-4 py-3 font-semibold">Type</th>
            <th className="px-4 py-3 font-semibold">Reference</th>
            <th className="px-4 py-3 font-semibold">Item</th>
            <th className="px-4 py-3 font-semibold">Qty</th>
            <th className="px-4 py-3 font-semibold">Result</th>
            <th className="px-4 py-3 font-semibold">Status</th>
            <th className="px-4 py-3 font-semibold">Date</th>
          </tr>
        </thead>
        <tbody>
          {inspections.map((insp) => (
            <tr
              key={insp.id}
              className="border-b cursor-pointer hover:bg-[var(--surface)] transition-colors"
              style={{ borderColor: "var(--border)" }}
              onClick={() => onSelect(insp.id)}
            >
              <td className="px-4 py-3 font-mono text-xs">{insp.inspectionNumber}</td>
              <td className="px-4 py-3">{inspectionTypeLabel(insp.inspectionType)}</td>
              <td className="px-4 py-3 text-[var(--text-2)]">{insp.referenceLabel ?? "—"}</td>
              <td className="px-4 py-3">{insp.itemCode ? `${insp.itemCode} — ${insp.itemName}` : "—"}</td>
              <td className="px-4 py-3 tabular-nums">{insp.quantityInspected ?? "—"}</td>
              <td className="px-4 py-3">
                {insp.result ? (
                  <StatusChip label={insp.result.toUpperCase()} variant={insp.result === "pass" ? "success" : insp.result === "fail" ? "error" : "default"} />
                ) : "—"}
              </td>
              <td className="px-4 py-3">
                <StatusChip label={statusLabel(insp.status)} variant={statusVariant(insp.status)} />
              </td>
              <td className="px-4 py-3 text-[var(--text-2)]">{displayDate(insp.inspectionDate)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function NcrsTable({ ncrs, onSelect }: { ncrs: NcrSummary[]; onSelect: (id: string) => void }) {
  if (ncrs.length === 0) {
    return <p className="text-sm text-[var(--text-3)] py-8 text-center">No NCRs found.</p>;
  }
  return (
    <div className="overflow-x-auto rounded-xl border" style={{ borderColor: "var(--border)" }}>
      <table className="w-full text-sm">
        <thead>
          <tr className="border-b text-left text-xs uppercase tracking-wide text-[var(--text-3)]" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
            <th className="px-4 py-3 font-semibold">Number</th>
            <th className="px-4 py-3 font-semibold">Item</th>
            <th className="px-4 py-3 font-semibold">Defect</th>
            <th className="px-4 py-3 font-semibold">Severity</th>
            <th className="px-4 py-3 font-semibold">Qty</th>
            <th className="px-4 py-3 font-semibold">Status</th>
            <th className="px-4 py-3 font-semibold">Raised</th>
          </tr>
        </thead>
        <tbody>
          {ncrs.map((ncr) => (
            <tr
              key={ncr.id}
              className="border-b cursor-pointer hover:bg-[var(--surface)] transition-colors"
              style={{ borderColor: "var(--border)" }}
              onClick={() => onSelect(ncr.id)}
            >
              <td className="px-4 py-3 font-mono text-xs">{ncr.ncrNumber}</td>
              <td className="px-4 py-3">{ncr.itemCode ? `${ncr.itemCode} — ${ncr.itemName}` : "—"}</td>
              <td className="px-4 py-3 max-w-[200px] truncate">{ncr.defectDescription ?? "—"}</td>
              <td className="px-4 py-3">
                <span className={`font-medium ${ncr.severity === "critical" ? "text-red-600" : ncr.severity === "major" ? "text-orange-600" : "text-[var(--text-1)]"}`}>
                  {severityLabel(ncr.severity)}
                </span>
              </td>
              <td className="px-4 py-3 tabular-nums">{ncr.quantity ?? "—"}</td>
              <td className="px-4 py-3">
                <StatusChip label={statusLabel(ncr.status)} variant={statusVariant(ncr.status)} />
              </td>
              <td className="px-4 py-3 text-[var(--text-2)]">{displayDate(ncr.createdAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
