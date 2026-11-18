import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { AlertTriangle, Plus, Search } from "lucide-react";
import RecordSheet from "../components/shared/RecordSheet";
import { useCustomer } from "../auth/CustomerState";
import SortableHeader from "../components/shared/SortableHeader";
import StatusChip from "../components/shared/StatusChip";
import {
  listInspections, listNcrs, createInspection, createNcr,
  listConfigOptions,
  severityLabel, inspectionTypeLabel, displayDate,
  type InspectionSummary, type NcrSummary, type ConfigOption,
} from "./api";

type Tab = "inspections" | "ncrs";
type InspSortKey = "number" | "type" | "job" | "inspected" | "inspector" | "sample" | "result";
type NcrSortKey = "number" | "item" | "severity" | "status" | "raised";

function sortRows<T>(rows: T[], key: string, dir: "asc" | "desc", getValue: (row: T, key: string) => unknown): T[] {
  return [...rows].sort((a, b) => {
    const av = getValue(a, key) ?? "";
    const bv = getValue(b, key) ?? "";
    const cmp = String(av).localeCompare(String(bv), undefined, { numeric: true });
    return dir === "asc" ? cmp : -cmp;
  });
}

export default function QualityPage() {
  const navigate = useNavigate();
  const [searchParams, setSearchParams] = useSearchParams();
  const { user } = useCustomer();
  const roles = user?.roles.map((r) => r.role) ?? [];
  const canWrite = roles.some((r) => ["OWNER", "OPS_MANAGER", "QUALITY_INSPECTOR"].includes(r));

  const tab: Tab = (searchParams.get("tab") as Tab) ?? "inspections";
  const [search, setSearch] = useState(searchParams.get("q") ?? "");
  const [statusFilter, setStatusFilter] = useState(searchParams.get("status") ?? "all");

  const [inspections, setInspections] = useState<InspectionSummary[]>([]);
  const [ncrs, setNcrs] = useState<NcrSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  const [inspSortKey, setInspSortKey] = useState<InspSortKey>("number");
  const [inspSortDir, setInspSortDir] = useState<"asc" | "desc">("desc");
  const [ncrSortKey, setNcrSortKey] = useState<NcrSortKey>("raised");
  const [ncrSortDir, setNcrSortDir] = useState<"asc" | "desc">("desc");

  // Config options (CLAUDE.md Rule #10)
  const [inspectionTypes, setInspectionTypes] = useState<ConfigOption[]>([]);
  const [defectCategories, setDefectCategories] = useState<ConfigOption[]>([]);
  const [severityOptions, setSeverityOptions] = useState<ConfigOption[]>([]);

  // New Inspection dialog
  const [showNewInspection, setShowNewInspection] = useState(false);
  const [newInspType, setNewInspType] = useState("");
  const [newInspRefType, setNewInspRefType] = useState("job");
  const [newInspRefId, setNewInspRefId] = useState("");
  const [newInspQty, setNewInspQty] = useState("1");
  const [newInspNotes, setNewInspNotes] = useState("");
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  // New NCR dialog
  const [showNewNcr, setShowNewNcr] = useState(false);
  const [ncrInspectionId, setNcrInspectionId] = useState("");
  const [ncrDefectCategoryId, setNcrDefectCategoryId] = useState("");
  const [ncrDefectDesc, setNcrDefectDesc] = useState("");
  const [ncrSeverity, setNcrSeverity] = useState("");
  const [ncrQty, setNcrQty] = useState("1");
  const [ncrSaveError, setNcrSaveError] = useState("");

  function setTab(t: Tab) {
    setStatusFilter("all");
    setSearchParams((prev) => { prev.set("tab", t); prev.delete("status"); return prev; }, { replace: true });
  }

  function handleSort(key: InspSortKey) {
    if (inspSortKey === key) setInspSortDir((d) => d === "asc" ? "desc" : "asc");
    else { setInspSortKey(key); setInspSortDir("asc"); }
  }
  function handleNcrSort(key: NcrSortKey) {
    if (ncrSortKey === key) setNcrSortDir((d) => d === "asc" ? "desc" : "asc");
    else { setNcrSortKey(key); setNcrSortDir("asc"); }
  }

  useEffect(() => {
    listConfigOptions("INSPECTION_TYPE").then((opts) => {
      setInspectionTypes(opts);
      if (opts.length > 0 && !newInspType) setNewInspType(opts[0].id);
    }).catch(() => {});
    listConfigOptions("DEFECT_CATEGORY").then((opts) => {
      setDefectCategories(opts);
      if (opts.length > 0) setNcrDefectCategoryId(opts[0].id);
    }).catch(() => {});
    listConfigOptions("DEFECT_SEVERITY").then((opts) => {
      setSeverityOptions(opts);
      if (opts.length > 0) setNcrSeverity(opts[0].id);
    }).catch(() => {});
  }, []);

  useEffect(() => {
    setLoading(true);
    setError("");
    Promise.all([
      listInspections({ q: search || undefined }),
      listNcrs({ q: search || undefined }),
    ]).then(([insps, ncrs]) => {
      setInspections(insps);
      setNcrs(ncrs);
    }).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }, [search]);

  const filteredInspections = useMemo(() => {
    let rows = inspections;
    if (statusFilter !== "all") rows = rows.filter((i) => i.status === statusFilter || i.result === statusFilter);
    return sortRows(rows, inspSortKey, inspSortDir, (i, k) => ({
      number: i.inspectionNumber,
      type: i.inspectionType,
      job: i.referenceLabel ?? "",
      inspected: i.inspectionDate ?? i.createdAt ?? "",
      inspector: i.inspectorName ?? "",
      sample: i.quantityInspected ?? 0,
      result: i.result ?? "",
    }[k] ?? ""));
  }, [inspections, statusFilter, inspSortKey, inspSortDir]);

  const filteredNcrs = useMemo(() => {
    let rows = ncrs;
    if (statusFilter !== "all") rows = rows.filter((n) => n.status === statusFilter);
    return sortRows(rows, ncrSortKey, ncrSortDir, (n, k) => ({
      number: n.ncrNumber,
      item: n.itemName ?? "",
      severity: n.severity ?? "",
      status: n.status,
      raised: n.createdAt ?? "",
    }[k] ?? ""));
  }, [ncrs, statusFilter, ncrSortKey, ncrSortDir]);

  const openNcrCount = ncrs.filter((n) => n.status === "open").length;
  const openInspCount = inspections.filter((i) => ["pending", "in_progress"].includes(i.status)).length;

  async function handleCreateInspection(e?: React.FormEvent) {
    if (e) e.preventDefault();
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

  async function handleCreateNcr(e?: React.FormEvent) {
    if (e) e.preventDefault();
    setSaving(true);
    setNcrSaveError("");
    try {
      const ncr = await createNcr({
        inspectionId: ncrInspectionId || undefined,
        defectCategoryId: ncrDefectCategoryId || undefined,
        defectDescription: ncrDefectDesc,
        severity: ncrSeverity || undefined,
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

  const activeRows = tab === "inspections" ? filteredInspections.length : filteredNcrs.length;

  return (
    <div className="flex flex-col h-full">
      {/* Sticky toolbar */}
      <div
        className="flex-shrink-0 flex items-center gap-3 px-5 py-3 border-b flex-wrap"
        style={{ borderColor: "var(--border)", background: "var(--surface)" }}
      >
        {/* Summary chips */}
        {openNcrCount > 0 && (
          <button
            type="button"
            onClick={() => { setTab("ncrs"); setStatusFilter("open"); }}
            className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold"
            style={{ background: "var(--warning-bg)", color: "var(--warning)" }}
          >
            <AlertTriangle size={11} />
            {openNcrCount} open NCR{openNcrCount !== 1 ? "s" : ""}
          </button>
        )}
        {openInspCount > 0 && (
          <button
            type="button"
            onClick={() => { setTab("inspections"); setStatusFilter("pending"); }}
            className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-xs font-semibold"
            style={{ background: "var(--info-bg)", color: "var(--info)" }}
          >
            {openInspCount} open
          </button>
        )}

        {/* Search */}
        <div className="relative flex-1 min-w-[200px] max-w-sm">
          <Search size={14} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--text-3)]" />
          <input
            className="w-full pl-8 pr-3 py-1.5 text-sm rounded-lg border bg-[var(--bg)] outline-none"
            style={{ borderColor: "var(--border)" }}
            placeholder="Search inspections, NCRs…"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
        </div>

        {/* Result count filter */}
        <select
          className="px-3 py-1.5 text-sm rounded-lg border bg-[var(--bg)] outline-none"
          style={{ borderColor: "var(--border)" }}
          value={statusFilter}
          onChange={(e) => setStatusFilter(e.target.value)}
        >
          <option value="all">All Results ({activeRows})</option>
          {tab === "inspections" ? (
            <>
              <option value="pending">Pending</option>
              <option value="in_progress">In Progress</option>
              <option value="completed">Completed</option>
              <option value="pass">Pass</option>
              <option value="fail">Fail</option>
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

        <div className="flex-1" />

        {/* Actions */}
        {canWrite && (
          <>
            <button
              type="button"
              onClick={() => setShowNewNcr(true)}
              className="inline-flex items-center gap-1 rounded-lg border px-3 py-1.5 text-sm font-semibold"
              style={{ borderColor: "var(--border)" }}
            >
              <Plus size={14} /> New NCR
            </button>
            <button
              type="button"
              onClick={() => setShowNewInspection(true)}
              className="inline-flex items-center gap-1 rounded-lg bg-[var(--accent)] px-3 py-1.5 text-sm font-semibold text-white"
            >
              <Plus size={14} /> New Inspection
            </button>
          </>
        )}
      </div>

      {/* Tabs */}
      <div
        className="flex-shrink-0 flex items-center border-b px-5 overflow-x-auto"
        style={{ borderColor: "var(--border)" }}
      >
        {(["inspections", "ncrs"] as Tab[]).map((t) => (
          <button
            key={t}
            type="button"
            onClick={() => setTab(t)}
            className={`relative px-4 py-3 text-sm font-medium transition-colors whitespace-nowrap ${
              tab === t
                ? "text-[var(--accent)] after:absolute after:bottom-0 after:inset-x-0 after:h-0.5 after:bg-[var(--accent)]"
                : "text-[var(--text-2)] hover:text-[var(--text-1)]"
            }`}
          >
            {t === "ncrs"
              ? `NCRs (${ncrs.length})`
              : `Inspections (${inspections.length})`}
          </button>
        ))}
      </div>

      {/* Body */}
      {error && (
        <div className="mx-5 mt-3 rounded-lg border border-red-200 bg-red-50 p-3 text-sm text-red-700">{error}</div>
      )}
      {loading ? (
        <p className="px-5 py-4 text-sm" style={{ color: "var(--text-3)" }}>Loading…</p>
      ) : tab === "inspections" ? (
        <InspectionsTable
          inspections={filteredInspections}
          sortKey={inspSortKey}
          sortDir={inspSortDir}
          onSort={handleSort}
          onSelect={(id) => navigate(`/quality/inspection/${id}`)}
          onRaiseNcr={(inspId) => { setNcrInspectionId(inspId); setShowNewNcr(true); }}
          canWrite={canWrite}
        />
      ) : (
        <NcrsTable
          ncrs={filteredNcrs}
          sortKey={ncrSortKey}
          sortDir={ncrSortDir}
          onSort={handleNcrSort}
          onSelect={(id) => navigate(`/quality/ncr/${id}`)}
        />
      )}

      {showNewInspection && (
        <RecordSheet
          title="New Inspection"
          onClose={() => setShowNewInspection(false)}
          primary={{ label: saving ? "Creating…" : "Create", onClick: () => { const fakeEvt = { preventDefault: () => {} } as React.FormEvent; void handleCreateInspection(fakeEvt); }, disabled: saving }}
          secondary={{ label: "Cancel", onClick: () => setShowNewInspection(false) }}
        >
          <form onSubmit={handleCreateInspection} className="space-y-3">
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Type</span>
              <select className="mt-1 w-full eoss-input" value={newInspType} onChange={(e) => setNewInspType(e.target.value)}>
                {inspectionTypes.map((opt) => (
                  <option key={opt.id} value={opt.id}>{opt.label}</option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Reference type</span>
              <select className="mt-1 w-full eoss-input" value={newInspRefType} onChange={(e) => setNewInspRefType(e.target.value)}>
                <option value="job">Job</option>
                <option value="job_stage">Job Stage</option>
                <option value="lot">Lot</option>
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Reference number</span>
              <input required className="mt-1 w-full eoss-input" placeholder="e.g. J-200 or LOT-2024-001" value={newInspRefId} onChange={(e) => setNewInspRefId(e.target.value)} />
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Quantity inspected</span>
              <input type="number" min="0" step="any" className="mt-1 w-full eoss-input" value={newInspQty} onChange={(e) => setNewInspQty(e.target.value)} />
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Notes</span>
              <textarea rows={2} className="mt-1 w-full eoss-input resize-none" value={newInspNotes} onChange={(e) => setNewInspNotes(e.target.value)} />
            </label>
            {saveError && <p className="text-xs text-red-600">{saveError}</p>}
          </form>
        </RecordSheet>
      )}

      {showNewNcr && (
        <RecordSheet
          title="Raise NCR"
          onClose={() => { setShowNewNcr(false); setNcrInspectionId(""); }}
          primary={{ label: saving ? "Raising…" : "Raise NCR", onClick: () => { const fakeEvt = { preventDefault: () => {} } as React.FormEvent; void handleCreateNcr(fakeEvt); }, disabled: saving }}
          secondary={{ label: "Cancel", onClick: () => { setShowNewNcr(false); setNcrInspectionId(""); } }}
        >
          <form onSubmit={handleCreateNcr} className="space-y-3">
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Defect category</span>
              <select required className="mt-1 w-full eoss-input" value={ncrDefectCategoryId} onChange={(e) => setNcrDefectCategoryId(e.target.value)}>
                <option value="">— Select category —</option>
                {defectCategories.map((opt) => (
                  <option key={opt.id} value={opt.id}>{opt.label}</option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Description</span>
              <textarea required rows={3} className="mt-1 w-full eoss-input resize-none" value={ncrDefectDesc} onChange={(e) => setNcrDefectDesc(e.target.value)} />
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Severity</span>
              <select className="mt-1 w-full eoss-input" value={ncrSeverity} onChange={(e) => setNcrSeverity(e.target.value)}>
                <option value="">— Select severity —</option>
                {severityOptions.map((opt) => (
                  <option key={opt.id} value={opt.id}>{opt.label}</option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Quantity</span>
              <input type="number" min="0" step="any" className="mt-1 w-full eoss-input" value={ncrQty} onChange={(e) => setNcrQty(e.target.value)} />
            </label>
            {ncrSaveError && <p className="text-xs text-red-600">{ncrSaveError}</p>}
          </form>
        </RecordSheet>
      )}
    </div>
  );
}

function InspectionsTable({
  inspections, sortKey, sortDir, onSort, onSelect, onRaiseNcr, canWrite,
}: {
  inspections: InspectionSummary[];
  sortKey: InspSortKey;
  sortDir: "asc" | "desc";
  onSort: (key: InspSortKey) => void;
  onSelect: (id: string) => void;
  onRaiseNcr: (inspId: string) => void;
  canWrite: boolean;
}) {
  const thCls = "text-left px-4 py-2.5 text-xs font-semibold";
  const thStyle = { color: "var(--text-3)", borderBottom: "1px solid var(--border)" };

  if (inspections.length === 0) {
    return <p className="px-5 py-10 text-sm text-center" style={{ color: "var(--text-3)" }}>No inspections found.</p>;
  }

  return (
    <div className="flex-1 overflow-auto">
      {/* Mobile cards */}
      <div className="md:hidden p-3 space-y-2">
        {inspections.map((insp) => (
          <button
            key={insp.id}
            type="button"
            onClick={() => onSelect(insp.id)}
            className="w-full text-left rounded-xl border p-3 hover:bg-[var(--surface)] transition-colors"
            style={{ borderColor: "var(--border)" }}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="font-mono text-xs font-bold" style={{ color: "var(--accent)" }}>{insp.inspectionNumber}</span>
              <ResultCell result={insp.result} status={insp.status} />
            </div>
            <div className="text-sm mt-1">{insp.itemName ?? insp.referenceLabel ?? "—"}</div>
            <div className="text-xs mt-0.5" style={{ color: "var(--text-3)" }}>
              {inspectionTypeLabel(insp.inspectionType)} · {displayDate(insp.inspectionDate)}
            </div>
          </button>
        ))}
      </div>

      {/* Desktop table */}
      <div className="hidden md:block">
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: "var(--surface)" }}>
              <SortableHeader label="Inspection #" active={sortKey === "number"} direction={sortDir} onSort={() => onSort("number")} className={thCls} style={thStyle} />
              <SortableHeader label="Type" active={sortKey === "type"} direction={sortDir} onSort={() => onSort("type")} className={thCls} style={thStyle} />
              <SortableHeader label="Job ID" active={sortKey === "job"} direction={sortDir} onSort={() => onSort("job")} className={thCls} style={thStyle} />
              <th className={thCls} style={thStyle}>Subject</th>
              <th className={thCls} style={thStyle}>Received</th>
              <SortableHeader label="Inspected" active={sortKey === "inspected"} direction={sortDir} onSort={() => onSort("inspected")} className={thCls} style={thStyle} />
              <SortableHeader label="Inspector" active={sortKey === "inspector"} direction={sortDir} onSort={() => onSort("inspector")} className={thCls} style={thStyle} />
              <SortableHeader label="Sample" active={sortKey === "sample"} direction={sortDir} onSort={() => onSort("sample")} className={`${thCls} text-right`} style={thStyle} />
              <th className={`${thCls} text-right`} style={thStyle}>Accept</th>
              <th className={`${thCls} text-right`} style={thStyle}>Reject</th>
              <SortableHeader label="Result" active={sortKey === "result"} direction={sortDir} onSort={() => onSort("result")} className={thCls} style={thStyle} />
              <th className={thCls} style={thStyle} />
            </tr>
          </thead>
          <tbody>
            {inspections.map((insp) => {
              const isPending = insp.status === "pending" || insp.status === "in_progress";
              const isFailed = insp.result === "fail";
              return (
                <tr
                  key={insp.id}
                  className="border-b cursor-pointer hover:bg-[var(--surface)] transition-colors"
                  style={{ borderColor: "var(--border)" }}
                  onClick={() => onSelect(insp.id)}
                >
                  <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: "var(--accent)" }}>{insp.inspectionNumber}</td>
                  <td className="px-4 py-3 text-sm">{inspectionTypeLabel(insp.inspectionType)}</td>
                  <td className="px-4 py-3">
                    {insp.referenceLabel ? (
                      <span
                        className="inline-block px-1.5 py-0.5 rounded text-xs font-mono font-semibold"
                        style={{ background: "var(--info-bg)", color: "var(--info)" }}
                      >
                        {insp.referenceLabel}
                      </span>
                    ) : "—"}
                  </td>
                  <td className="px-4 py-3 text-sm max-w-[200px] truncate" style={{ color: "var(--text-2)" }}>
                    {insp.itemName ?? insp.notes ?? "—"}
                  </td>
                  <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>{displayDate(insp.createdAt)}</td>
                  <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>{displayDate(insp.inspectionDate)}</td>
                  <td className="px-4 py-3 text-sm">{insp.inspectorName ?? "—"}</td>
                  <td className="px-4 py-3 text-right tabular-nums">{insp.quantityInspected ?? "—"}</td>
                  <td className="px-4 py-3 text-right tabular-nums font-semibold">{insp.quantityAccepted ?? "—"}</td>
                  <td
                    className="px-4 py-3 text-right tabular-nums font-semibold"
                    style={{ color: (insp.quantityRejected ?? 0) > 0 ? "var(--error)" : undefined }}
                  >
                    {insp.quantityRejected ?? "—"}
                  </td>
                  <td className="px-4 py-3">
                    <ResultCell result={insp.result} status={insp.status} />
                  </td>
                  <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                    {canWrite && isPending && (
                      <button
                        type="button"
                        onClick={() => onSelect(insp.id)}
                        className="rounded-lg bg-[var(--accent)] px-2.5 py-1 text-xs font-semibold text-white whitespace-nowrap"
                      >
                        Record
                      </button>
                    )}
                    {canWrite && isFailed && !isPending && (
                      <button
                        type="button"
                        onClick={() => onRaiseNcr(insp.id)}
                        className="rounded-lg bg-[var(--accent)] px-2.5 py-1 text-xs font-semibold text-white whitespace-nowrap"
                      >
                        Raise NCR
                      </button>
                    )}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

function ResultCell({ result, status }: { result?: string; status: string }) {
  if (result === "pass") return <span className="text-sm font-semibold" style={{ color: "var(--success)" }}>Pass</span>;
  if (result === "fail") return <span className="text-sm font-semibold" style={{ color: "var(--error)" }}>Fail</span>;
  if (status === "pending" || status === "in_progress") return <StatusChip status={status} />;
  return <span style={{ color: "var(--text-3)" }}>—</span>;
}

function NcrsTable({
  ncrs, sortKey, sortDir, onSort, onSelect,
}: {
  ncrs: NcrSummary[];
  sortKey: NcrSortKey;
  sortDir: "asc" | "desc";
  onSort: (key: NcrSortKey) => void;
  onSelect: (id: string) => void;
}) {
  const thCls = "text-left px-4 py-2.5 text-xs font-semibold";
  const thStyle = { color: "var(--text-3)", borderBottom: "1px solid var(--border)" };

  if (ncrs.length === 0) {
    return <p className="px-5 py-10 text-sm text-center" style={{ color: "var(--text-3)" }}>No NCRs found.</p>;
  }

  return (
    <div className="flex-1 overflow-auto">
      {/* Mobile cards */}
      <div className="md:hidden p-3 space-y-2">
        {ncrs.map((ncr) => (
          <button
            key={ncr.id}
            type="button"
            onClick={() => onSelect(ncr.id)}
            className="w-full text-left rounded-xl border p-3 hover:bg-[var(--surface)] transition-colors"
            style={{ borderColor: "var(--border)" }}
          >
            <div className="flex items-center justify-between gap-2">
              <span className="font-mono text-xs font-bold" style={{ color: "var(--accent)" }}>{ncr.ncrNumber}</span>
              <StatusChip status={ncr.status} size="sm" />
            </div>
            <div className="text-sm mt-1 truncate">{ncr.defectDescription ?? "—"}</div>
            <div className="text-xs mt-0.5" style={{ color: "var(--text-3)" }}>
              {ncr.itemCode} · {severityLabel(ncr.severity)} · {displayDate(ncr.createdAt)}
            </div>
          </button>
        ))}
      </div>

      {/* Desktop table */}
      <div className="hidden md:block">
        <table className="w-full text-sm">
          <thead>
            <tr style={{ background: "var(--surface)" }}>
              <SortableHeader label="NCR #" active={sortKey === "number"} direction={sortDir} onSort={() => onSort("number")} className={thCls} style={thStyle} />
              <SortableHeader label="Item" active={sortKey === "item"} direction={sortDir} onSort={() => onSort("item")} className={thCls} style={thStyle} />
              <th className={thCls} style={thStyle}>Defect</th>
              <SortableHeader label="Severity" active={sortKey === "severity"} direction={sortDir} onSort={() => onSort("severity")} className={thCls} style={thStyle} />
              <th className={`${thCls} text-right`} style={thStyle}>Qty</th>
              <SortableHeader label="Status" active={sortKey === "status"} direction={sortDir} onSort={() => onSort("status")} className={thCls} style={thStyle} />
              <SortableHeader label="Raised" active={sortKey === "raised"} direction={sortDir} onSort={() => onSort("raised")} className={thCls} style={thStyle} />
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
                <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: "var(--accent)" }}>{ncr.ncrNumber}</td>
                <td className="px-4 py-3">{ncr.itemCode ? `${ncr.itemCode} — ${ncr.itemName}` : "—"}</td>
                <td className="px-4 py-3 max-w-[220px] truncate" style={{ color: "var(--text-2)" }}>{ncr.defectDescription ?? "—"}</td>
                <td className="px-4 py-3">
                  <span className={`font-medium text-sm ${ncr.severity === "critical" ? "text-red-600" : ncr.severity === "major" ? "text-orange-600" : ""}`}>
                    {severityLabel(ncr.severity)}
                  </span>
                </td>
                <td className="px-4 py-3 text-right tabular-nums">{ncr.quantity ?? "—"}</td>
                <td className="px-4 py-3"><StatusChip status={ncr.status} /></td>
                <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>{displayDate(ncr.createdAt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}
