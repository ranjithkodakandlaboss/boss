import { useEffect, useMemo, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { AlertTriangle, Plus, Search } from "lucide-react";
import RecordSheet from "../components/shared/RecordSheet";
import { useCustomer } from "../auth/CustomerState";
import SortableHeader from "../components/shared/SortableHeader";
import StatusChip from "../components/shared/StatusChip";
import { allPages, type Item } from "../inventory/api";
import {
  listInspections, listNcrs, createInspection, createNcr,
  listConfigOptions,
  severityLabel, inspectionTypeLabel, displayDate,
  type InspectionSummary, type NcrSummary, type ConfigOption,
} from "./api";

type Tab = "inspections" | "ncrs";
type InspSortKey = "number" | "type" | "job" | "inspected" | "inspector" | "sample" | "result";

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

  // Config options (CLAUDE.md Rule #10)
  const [inspectionTypes, setInspectionTypes] = useState<ConfigOption[]>([]);
  const [defectCategories, setDefectCategories] = useState<ConfigOption[]>([]);
  const [inspectionOptionsLoading, setInspectionOptionsLoading] = useState(true);
  const [defectOptionsLoading, setDefectOptionsLoading] = useState(true);
  const [inspectionOptionsError, setInspectionOptionsError] = useState("");
  const [defectOptionsError, setDefectOptionsError] = useState("");
  const [items, setItems] = useState<Item[]>([]);
  const [itemsError, setItemsError] = useState("");

  // New Inspection dialog
  const [showNewInspection, setShowNewInspection] = useState(false);
  const [newInspType, setNewInspType] = useState("");
  const [newInspWorkflowType, setNewInspWorkflowType] = useState("final");
  const [newInspRefType, setNewInspRefType] = useState("production_job");
  const [newInspRefId, setNewInspRefId] = useState("");
  const [newInspItemId, setNewInspItemId] = useState("");
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

  useEffect(() => {
    listConfigOptions("INSPECTION_TYPES").then(setInspectionTypes)
      .catch((e: Error) => setInspectionOptionsError(`Unable to load inspection categories: ${e.message}`))
      .finally(() => setInspectionOptionsLoading(false));
    listConfigOptions("DEFECT_CATEGORY").then(setDefectCategories)
      .catch((e: Error) => setDefectOptionsError(`Unable to load defect categories: ${e.message}`))
      .finally(() => setDefectOptionsLoading(false));
    allPages<Item>("/api/v1/items?status=active").then(setItems)
      .catch((e: Error) => setItemsError(`Unable to load items: ${e.message}`));
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
      type: i.inspectionTypeLabel ?? inspectionTypeLabel(i.inspectionType),
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
    return [...rows].sort((a, b) => (b.createdAt ?? "").localeCompare(a.createdAt ?? ""));
  }, [ncrs, statusFilter]);

  const openNcrCount = ncrs.filter((n) => n.status === "open").length;
  const openInspCount = inspections.filter((i) => ["pending", "in_progress"].includes(i.status)).length;

  async function handleCreateInspection(e?: React.FormEvent) {
    if (e) e.preventDefault();
    if (!newInspType || !newInspItemId || !newInspRefId.trim()) {
      setSaveError("Select an inspection category and item, and enter a reference ID.");
      return;
    }
    if (!newInspQty.trim() || !Number.isFinite(Number(newInspQty)) || Number(newInspQty) <= 0) {
      setSaveError("Enter a quantity greater than zero.");
      return;
    }
    setSaving(true);
    setSaveError("");
    try {
      const insp = await createInspection({
        inspectionType: newInspWorkflowType,
        inspectionTypeOptionId: newInspType || undefined,
        referenceType: newInspRefType,
        referenceId: newInspRefId,
        itemId: newInspItemId,
        quantityInspected: Number(newInspQty),
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
    const inspection = inspections.find((entry) => entry.id === ncrInspectionId);
    if (!inspection?.itemId || !ncrDefectCategoryId || !ncrSeverity || !ncrDefectDesc.trim()) {
      setNcrSaveError("Select an inspection with an item, a defect category and severity, and enter a description.");
      return;
    }
    if (!ncrQty.trim() || !Number.isFinite(Number(ncrQty)) || Number(ncrQty) <= 0) {
      setNcrSaveError("Enter a quantity greater than zero.");
      return;
    }
    setSaving(true);
    setNcrSaveError("");
    try {
      const ncr = await createNcr({
        inspectionId: ncrInspectionId || undefined,
        itemId: inspection.itemId,
        defectCategoryId: ncrDefectCategoryId || undefined,
        defectDescription: ncrDefectDesc,
        severity: ncrSeverity || undefined,
        quantity: Number(ncrQty),
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
        {tab === "inspections" ? (
          <select
            className="px-3 py-1.5 text-sm rounded-lg border bg-[var(--bg)] outline-none"
            style={{ borderColor: "var(--border)" }}
            value={statusFilter}
            onChange={(e) => setStatusFilter(e.target.value)}
          >
            <option value="all">All Results ({activeRows})</option>
            <option value="pending">Pending</option>
            <option value="in_progress">In Progress</option>
            <option value="completed">Completed</option>
            <option value="pass">Pass</option>
            <option value="fail">Fail</option>
          </select>
        ) : (
          <div className="flex gap-1.5 flex-wrap">
            {(["all", "open", "rework", "concession_requested", "closed"] as const).map((key) => {
              const labels: Record<string, string> = { all: `All (${filteredNcrs.length})`, open: "Open", rework: "Rework", concession_requested: "Concession", closed: "Closed" };
              return (
                <button key={key} type="button" onClick={() => setStatusFilter(key)} className="px-3 py-1 rounded-full text-xs font-semibold border whitespace-nowrap" style={{ background: statusFilter === key ? "var(--brand-fill)" : "var(--surface)", color: statusFilter === key ? "#fff" : "var(--text-2)", borderColor: statusFilter === key ? "var(--brand-fill)" : "var(--border)" }}>
                  {labels[key]}
                </button>
              );
            })}
          </div>
        )}

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
              className="inline-flex items-center gap-1 rounded-lg bg-[var(--brand-fill)] px-3 py-1.5 text-sm font-semibold text-white"
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
                ? "text-[var(--brand)] after:absolute after:bottom-0 after:inset-x-0 after:h-0.5 after:bg-[var(--brand-fill)]"
                : "text-[var(--text-3)] hover:text-[var(--text)]"
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
              <span className="text-[var(--text-2)] font-medium">Workflow type</span>
              <select className="mt-1 w-full eoss-input" value={newInspWorkflowType} onChange={(e) => setNewInspWorkflowType(e.target.value)}>
                <option value="incoming">Incoming</option>
                <option value="in_process">In-process</option>
                <option value="final">Final</option>
                <option value="customer">Customer</option>
                <option value="dispatch_readiness">Dispatch readiness</option>
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Inspection category</span>
              <select className="mt-1 w-full eoss-input" value={newInspType} onChange={(e) => setNewInspType(e.target.value)}>
                <option value="">— Select category —</option>
                {inspectionTypes.map((opt) => (
                  <option key={opt.id} value={opt.id}>{opt.label}</option>
                ))}
              </select>
              {inspectionOptionsError && <p role="alert" className="text-xs text-red-600">{inspectionOptionsError}</p>}
              {!inspectionOptionsLoading && !inspectionOptionsError && inspectionTypes.length === 0 && <p className="text-xs">No inspection categories available. Configure them in business settings.</p>}
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Item</span>
              <select className="mt-1 w-full eoss-input" value={newInspItemId} onChange={(e) => setNewInspItemId(e.target.value)}>
                <option value="">— Select item —</option>
                {items.map((item) => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}
              </select>
              {itemsError && <p role="alert" className="text-xs text-red-600">{itemsError}</p>}
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Reference type</span>
              <select className="mt-1 w-full eoss-input" value={newInspRefType} onChange={(e) => setNewInspRefType(e.target.value)}>
                <option value="production_job">Production Job</option>
                <option value="job_stage">Job Stage</option>
                <option value="grn">GRN</option>
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Reference ID</span>
              <input required className="mt-1 w-full eoss-input" placeholder="Reference UUID" value={newInspRefId} onChange={(e) => setNewInspRefId(e.target.value)} />
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
          danger
          onClose={() => { setShowNewNcr(false); setNcrInspectionId(""); }}
          primary={{ label: saving ? "Raising…" : "Raise NCR", onClick: () => { const fakeEvt = { preventDefault: () => {} } as React.FormEvent; void handleCreateNcr(fakeEvt); }, disabled: saving }}
          secondary={{ label: "Cancel", onClick: () => { setShowNewNcr(false); setNcrInspectionId(""); } }}
        >
          <form onSubmit={handleCreateNcr} className="space-y-3">
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Inspection</span>
              <select className="mt-1 w-full eoss-input" value={ncrInspectionId} onChange={(e) => setNcrInspectionId(e.target.value)}>
                <option value="">— Select inspection —</option>
                {inspections.map((inspection) => (
                  <option key={inspection.id} value={inspection.id}>{inspection.inspectionNumber} · {inspection.itemName ?? "No item"}</option>
                ))}
              </select>
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Defect category</span>
              <select required className="mt-1 w-full eoss-input" value={ncrDefectCategoryId} onChange={(e) => setNcrDefectCategoryId(e.target.value)}>
                <option value="">— Select category —</option>
                {defectCategories.map((opt) => (
                  <option key={opt.id} value={opt.id}>{opt.label}</option>
                ))}
              </select>
              {defectOptionsError && <p role="alert" className="text-xs text-red-600">{defectOptionsError}</p>}
              {!defectOptionsLoading && !defectOptionsError && defectCategories.length === 0 && <p className="text-xs">No defect categories available. Configure them in business settings.</p>}
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Description</span>
              <textarea required rows={3} className="mt-1 w-full eoss-input resize-none" value={ncrDefectDesc} onChange={(e) => setNcrDefectDesc(e.target.value)} />
            </label>
            <label className="block text-sm">
              <span className="text-[var(--text-2)] font-medium">Severity</span>
              <select className="mt-1 w-full eoss-input" value={ncrSeverity} onChange={(e) => setNcrSeverity(e.target.value)}>
                <option value="">— Select severity —</option>
                <option value="critical">Critical</option>
                <option value="major">Major</option>
                <option value="medium">Medium</option>
                <option value="minor">Minor</option>
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
              <span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{insp.inspectionNumber}</span>
              <ResultCell result={insp.result} status={insp.status} />
            </div>
            <div className="text-sm mt-1">{insp.itemName ?? insp.referenceLabel ?? "—"}</div>
            <div className="text-xs mt-0.5" style={{ color: "var(--text-3)" }}>
              {insp.inspectionTypeLabel ?? inspectionTypeLabel(insp.inspectionType)} · {displayDate(insp.inspectionDate)}
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
                  <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{insp.inspectionNumber}</td>
                  <td className="px-4 py-3 text-sm">{insp.inspectionTypeLabel ?? inspectionTypeLabel(insp.inspectionType)}</td>
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
                        className="rounded-lg bg-[var(--brand-fill)] px-2.5 py-1 text-xs font-semibold text-white whitespace-nowrap"
                      >
                        Record
                      </button>
                    )}
                    {canWrite && isFailed && !isPending && (
                      <button
                        type="button"
                        onClick={() => onRaiseNcr(insp.id)}
                        className="rounded-lg border border-[var(--error)] px-2.5 py-1 text-xs font-semibold whitespace-nowrap"
                        style={{ color: "var(--error)" }}
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
  ncrs, onSelect,
}: {
  ncrs: NcrSummary[];
  onSelect: (id: string) => void;
}) {
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
              <span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{ncr.ncrNumber}</span>
              <StatusChip status={ncr.status} size="sm" />
            </div>
            <div className="text-sm mt-1 truncate">{ncr.defectDescription ?? "—"}</div>
            <div className="text-xs mt-0.5" style={{ color: "var(--text-3)" }}>
              {ncr.itemCode ? `${ncr.itemCode} · ` : ""}{severityLabel(ncr.severity)} · {displayDate(ncr.createdAt)}
            </div>
          </button>
        ))}
      </div>

      {/* Desktop cards */}
      <div className="hidden md:block p-4 space-y-2">
        {ncrs.map((ncr) => {
          const sevColor = ncr.severity === "critical" ? "var(--error)" : ncr.severity === "major" ? "var(--warning)" : "var(--text-2)";
          return (
            <button
              key={ncr.id}
              type="button"
              onClick={() => onSelect(ncr.id)}
              className="w-full text-left rounded-xl border p-4 hover:bg-[var(--surface-2)] transition-colors"
              style={{ borderColor: "var(--border)", background: "var(--surface)" }}
            >
              <div className="flex items-start justify-between gap-3">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 flex-wrap">
                    <span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{ncr.ncrNumber}</span>
                    <StatusChip status={ncr.status} size="sm" />
                    {ncr.severity && <span className="text-xs font-semibold" style={{ color: sevColor }}>{severityLabel(ncr.severity)}</span>}
                  </div>
                  <p className="text-sm mt-1 truncate" style={{ color: "var(--text)" }}>{ncr.defectDescription ?? "—"}</p>
                  <div className="flex items-center gap-3 mt-1 text-xs" style={{ color: "var(--text-3)" }}>
                    {ncr.itemCode && <span>{ncr.itemCode}{ncr.itemName ? ` — ${ncr.itemName}` : ""}</span>}
                    {ncr.quantity && <span>Qty: {ncr.quantity}</span>}
                    <span>{displayDate(ncr.createdAt)}</span>
                  </div>
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}
