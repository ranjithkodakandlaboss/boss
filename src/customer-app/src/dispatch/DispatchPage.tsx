import React, { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Plus, Search, X, PackageCheck, Trash2 } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import StatusChip from "../components/shared/StatusChip";
import RecordSheet from "../components/shared/RecordSheet";
import SortableHeader from "../components/shared/SortableHeader";
import CreatableSelect from "../components/shared/CreatableSelect";
import { loadLots, type Lot } from "../inventory/api";
import { createClearanceCertificate } from "../quality/api";
import {
  listDispatches,
  createDispatch,
  recordPoD,
  deleteDispatch,
  checkDispatchEligibility,
  loadCompanySitesAndStores,
  loadCustomersList,
  loadJobsList,
  listConfigOptions,
  listDispatchMethods,
  createConfigOption,
  loadPoLineAvailability,
  type DispatchSummary,
  type Site,
  type Store,
  type Customer,
  type Job,
  type QualityEligibility,
  type PoLineAvailability,
  type ConfigOption,
} from "./api";

type DispatchSortKey =
  | "number"
  | "customer"
  | "sourceStore"
  | "jobs"
  | "carrier"
  | "dispatchDate"
  | "expectedDelivery"
  | "qty"
  | "status";

const READY_TO_SHIP = new Set(["draft", "ready", "pending", "pending_docs"]);

function placeLabel(siteName?: string, storeName?: string) {
  if (siteName && storeName) return `${siteName} · ${storeName}`;
  return siteName || storeName || "Main Facility";
}

function displayDate(iso?: string) {
  if (!iso) return "—";
  try {
    const d = new Date(iso);
    if (Number.isNaN(d.getTime())) return iso;
    return d.toLocaleDateString("en-IN", { day: "numeric", month: "short" });
  } catch {
    return iso;
  }
}

export default function DispatchPage() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { user } = useCustomer();
  const roles = user?.roles.map((r) => r.role.toLowerCase()) ?? [];
  const canManage = roles.some((r) =>
    ["owner", "ops_manager", "dispatch", "store_keeper"].includes(r)
  );
  const canIssueQualityRelease = roles.some((r) =>
    ["owner", "ops_manager", "quality_inspector"].includes(r)
  );

  const [dispatches, setDispatches] = useState<DispatchSummary[]>([]);
  const [sites, setSites] = useState<Site[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [carriers, setCarriers] = useState<ConfigOption[]>([]);
  const [vehicles, setVehicles] = useState<ConfigOption[]>([]);
  const [dispatchMethods, setDispatchMethods] = useState<ConfigOption[]>([]);
  const [loading, setLoading] = useState(true);
  const [toast, setToast] = useState<{ type: "success" | "error"; title: string; body?: string } | null>(null);

  const [search, setSearch] = useState("");
  const [siteFilter, setSiteFilter] = useState("all");
  const [statusFocus, setStatusFocus] = useState<string | null>(searchParams.get("status"));
  const [sortKey, setSortKey] = useState<DispatchSortKey>("number");
  const [sortDir, setSortDir] = useState<"asc" | "desc">("desc");

  // Modal state
  const [showModal, setShowModal] = useState(false);
  const [podDispatchId, setPodDispatchId] = useState<string | null>(null);
  const [selectedCpoId, setSelectedCpoId] = useState("");
  const [selectedJobId, setSelectedJobId] = useState("");
  const [selectedLotId, setSelectedLotId] = useState("");
  const [dispatchQuantity, setDispatchQuantity] = useState("");
  const [lots, setLots] = useState<Lot[]>([]);
  const [loadingLots, setLoadingLots] = useState(false);
  const [lotsError, setLotsError] = useState("");
  const [eligibility, setEligibility] = useState<QualityEligibility | null>(null);
  const [checkingEligibility, setCheckingEligibility] = useState(false);
  const [issuingQualityRelease, setIssuingQualityRelease] = useState(false);
  const [poLineAvailability, setPoLineAvailability] = useState<PoLineAvailability | null>(null);
  const [checkingPoLineAvailability, setCheckingPoLineAvailability] = useState(false);
  const [poLineAvailabilityError, setPoLineAvailabilityError] = useState("");
  const [creating, setCreating] = useState(false);

  // Form state
  const [form, setForm] = useState({
    customerId: "",
    customerName: "",
    carrier: "",
    dispatchMethodOptionId: "",
    vehicleNumber: "",
    lrNumber: "",
    dispatchDate: new Date().toISOString().split("T")[0],
    sourceSiteId: "",
    sourceSiteName: "",
    sourceStoreId: "",
    sourceStoreName: "",
  });

  const showToastMsg = (type: "success" | "error", title: string, body?: string) => {
    setToast({ type, title, body });
    setTimeout(() => setToast(null), 4000);
  };

  const loadData = async () => {
    try {
      setLoading(true);
      const [dispList, comp, custList, jobList, carrierOpts, vehicleOpts, dispatchMethodOpts] = await Promise.all([
        listDispatches(),
        loadCompanySitesAndStores(),
        loadCustomersList(),
        loadJobsList(),
        listConfigOptions("CARRIER"),
        listConfigOptions("VEHICLE"),
        listDispatchMethods(),
      ]);
      setDispatches(dispList);
      setSites(comp.sites);
      setStores(comp.stores);
      setCustomers(custList);
      setJobs(jobList);
      setCarriers(carrierOpts);
      setVehicles(vehicleOpts);
      setDispatchMethods(dispatchMethodOpts);

      if (comp.sites.length > 0 && !form.sourceSiteId) {
        const firstStore = comp.stores.find((store) =>
          store.siteId === comp.sites[0].id && store.storeType === "finished_goods"
        );
        setForm((f) => ({
          ...f,
          sourceSiteId: comp.sites[0].id,
          sourceSiteName: comp.sites[0].name,
          sourceStoreId: firstStore?.id ?? "",
          sourceStoreName: firstStore?.name ?? "",
        }));
      }
    } catch {
      showToastMsg("error", "Error loading dispatch records");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadData();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Pre-fill from query params (e.g., ?plan=JOB_ID)
  useEffect(() => {
    const plan = searchParams.get("plan");
    if (!plan || jobs.length === 0) return;
    const job = jobs.find((j) => j.id === plan || j.jobNumber === plan);
    if (!job || !job.cpoId) return;
    setSelectedJobId(job.id);
    setSelectedCpoId(job.cpoId);
    setForm((f) => ({
      ...f,
      customerId: job.customerId ?? f.customerId,
      customerName: job.customerName ?? f.customerName,
    }));
    setShowModal(true);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [jobs, searchParams]);

  // When job selection changes in modal, check quality eligibility
  useEffect(() => {
    if (!selectedJobId) {
      setEligibility(null);
      setPoLineAvailability(null);
      return;
    }
    setCheckingEligibility(true);
    checkDispatchEligibility(selectedJobId)
      .then((res) => setEligibility(res))
      .catch((cause) => setEligibility({
        jobId: selectedJobId,
        eligible: false,
        reason: cause instanceof Error ? cause.message : "Quality release could not be verified",
        blockers: ["Quality release could not be verified"],
      }))
      .finally(() => setCheckingEligibility(false));
  }, [selectedJobId]);

  useEffect(() => {
    const job = jobs.find((candidate) => candidate.id === selectedJobId);
    if (!job?.cpoLineId) {
      setPoLineAvailability(null);
      setCheckingPoLineAvailability(false);
      setPoLineAvailabilityError("");
      return;
    }

    let active = true;
    setCheckingPoLineAvailability(true);
    setPoLineAvailability(null);
    setPoLineAvailabilityError("");
    loadPoLineAvailability(job.cpoLineId)
      .then((availability) => {
        if (active) setPoLineAvailability(availability);
      })
      .catch((cause) => {
        if (active) {
          setPoLineAvailability(null);
          setPoLineAvailabilityError(cause instanceof Error
            ? cause.message
            : "Remaining PO quantity could not be verified");
        }
      })
      .finally(() => {
        if (active) setCheckingPoLineAvailability(false);
      });
    return () => { active = false; };
  }, [jobs, selectedJobId]);

  useEffect(() => {
    const job = jobs.find((candidate) => candidate.id === selectedJobId);
    if (!job?.itemId || !form.sourceStoreId) {
      setLots([]);
      setSelectedLotId("");
      setDispatchQuantity("");
      setLotsError("");
      return;
    }

    let active = true;
    setLoadingLots(true);
    setLotsError("");
    setLots([]);
    setSelectedLotId("");
    setDispatchQuantity("");
    loadLots(`itemId=${encodeURIComponent(job.itemId)}&storeId=${encodeURIComponent(form.sourceStoreId)}&status=available`)
      .then((rows) => {
        if (active) {
          setLots(rows.filter((lot) =>
            lot.status === "available" && lot.availableQty > 0
          ));
        }
      })
      .catch((cause) => {
        if (active) {
          setLotsError(cause instanceof Error ? cause.message : "Finished-goods lots could not be loaded");
        }
      })
      .finally(() => {
        if (active) setLoadingLots(false);
      });
    return () => { active = false; };
  }, [jobs, selectedJobId, form.sourceStoreId]);

  const handleDeleteDispatch = async (id: string, e: React.MouseEvent) => {
    e.stopPropagation();
    try {
      await deleteDispatch(id);
      setDispatches((prev) => prev.filter((d) => d.id !== id));
      showToastMsg("success", "Dispatch removed");
    } catch (err: any) {
      showToastMsg("error", "Cannot delete", err?.message ?? "Dispatch removal failed");
    }
  };

  const handleCreate = async () => {
    if (eligibility && !eligibility.eligible) {
      showToastMsg("error", "Not dispatch-eligible", eligibility.reason);
      return;
    }
    const job = jobs.find((candidate) => candidate.id === selectedJobId);
    const lot = lots.find((candidate) => candidate.id === selectedLotId);
    const quantity = Number(dispatchQuantity);
    if (!form.customerId || !form.dispatchDate || !job?.cpoId || !job.cpoLineId || !lot
      || !Number.isFinite(quantity) || quantity <= 0 || !eligibility?.eligible
      || !poLineAvailability || quantity > maxDispatchQuantity) {
      showToastMsg("error", "Select an eligible PO line, finished-goods lot, and quantity");
      return;
    }

    try {
      setCreating(true);
      const cust = customers.find((c) => c.id === form.customerId);

      const created = await createDispatch({
        customerId: form.customerId,
        customerName: cust?.tradingName || cust?.name || form.customerName,
        jobId: job.id,
        cpoId: job.cpoId,
        sourceSiteId: form.sourceSiteId || undefined,
        sourceSiteName: form.sourceSiteName || undefined,
        sourceStoreId: lot.storeId,
        sourceStoreName: lot.storeName,
        carrier: form.carrier || undefined,
        dispatchMethodOptionId: form.dispatchMethodOptionId || undefined,
        vehicleNumber: form.vehicleNumber || undefined,
        lrNumber: form.lrNumber || undefined,
        dispatchDate: form.dispatchDate,
        jobs: [job.jobNumber],
        lines: [{
          cpoLineId: job.cpoLineId,
          jobId: job.id,
          itemId: job.itemId,
          quantity,
          lotId: lot.id,
        }],
      });

      setDispatches((prev) => [created, ...prev]);
      setShowModal(false);
      setSelectedJobId("");
      setSelectedCpoId("");
      setSelectedLotId("");
      setDispatchQuantity("");
      showToastMsg("success", "Dispatch created", created.dispatchNumber);
    } catch (err: any) {
      showToastMsg("error", "Creation failed", err?.message ?? "Could not create dispatch");
    } finally {
      setCreating(false);
    }
  };

  const handleIssueQualityRelease = async () => {
    const job = jobs.find((candidate) => candidate.id === selectedJobId);
    if (!job) return;
    try {
      setIssuingQualityRelease(true);
      await createClearanceCertificate({ jobId: job.id });
      setEligibility(await checkDispatchEligibility(job.id));
      showToastMsg("success", "Quality release issued", job.jobNumber);
    } catch (cause) {
      showToastMsg("error", "Quality release failed",
        cause instanceof Error ? cause.message : "The quality release could not be issued");
    } finally {
      setIssuingQualityRelease(false);
    }
  };

  const purchaseOrders = Array.from(
    new Map(
      jobs
        .filter((job) => job.customerId === form.customerId && job.cpoId && job.cpoNumber)
        .map((job) => [job.cpoId!, { id: job.cpoId!, number: job.cpoNumber! }])
    ).values()
  );
  const poLines = jobs.filter((job) =>
    job.customerId === form.customerId
      && job.cpoId === selectedCpoId
      && job.cpoLineId
      && job.itemId
      && job.status === "completed"
  );
  const selectedJob = jobs.find((job) => job.id === selectedJobId);
  const selectedLot = lots.find((lot) => lot.id === selectedLotId);
  const maxDispatchQuantity = selectedJob && selectedLot
    ? Math.min(
      selectedJob.qty ?? selectedLot.availableQty,
      selectedLot.availableQty,
      poLineAvailability?.remainingQty ?? 0
    )
    : 0;

  useEffect(() => {
    const quantity = Number(dispatchQuantity);
    if (dispatchQuantity && Number.isFinite(quantity) && quantity > maxDispatchQuantity && maxDispatchQuantity > 0) {
      setDispatchQuantity(String(maxDispatchQuantity));
    }
  }, [dispatchQuantity, maxDispatchQuantity]);

  // Base and KPI counts
  const base = dispatches.filter(
    (d) =>
      (siteFilter === "all" || d.sourceSiteId === siteFilter) &&
      (!search ||
        d.dispatchNumber.toLowerCase().includes(search.toLowerCase()) ||
        d.customerName.toLowerCase().includes(search.toLowerCase()))
  );

  const pendingDocs = base.filter((d) => d.status === "pending_docs").length;
  const dispatched = base.filter((d) => d.status === "dispatched" || d.status === "in_transit").length;
  const readyCount = base.filter((d) => READY_TO_SHIP.has(d.status)).length;

  const passesStatus = (status: string, focus: string | null) => {
    if (!focus) return true;
    if (focus === "ready") return READY_TO_SHIP.has(status);
    if (focus === "dispatched") return status === "dispatched" || status === "in_transit";
    return status === focus;
  };

  const filteredBase = base.filter((d) => passesStatus(d.status, statusFocus));

  const sorted = [...filteredBase].sort((a, b) => {
    const valA =
      sortKey === "number"
        ? a.dispatchNumber
        : sortKey === "customer"
        ? a.customerName
        : sortKey === "sourceStore"
        ? placeLabel(a.sourceSiteName, a.sourceStoreName)
        : sortKey === "jobs"
        ? (a.jobs ?? []).join(", ")
        : sortKey === "carrier"
        ? a.carrier ?? a.vehicleNumber ?? ""
        : sortKey === "dispatchDate"
        ? a.dispatchDate
        : sortKey === "expectedDelivery"
        ? a.expectedDelivery ?? ""
        : sortKey === "qty"
        ? a.totalQty ?? 0
        : a.status;

    const valB =
      sortKey === "number"
        ? b.dispatchNumber
        : sortKey === "customer"
        ? b.customerName
        : sortKey === "sourceStore"
        ? placeLabel(b.sourceSiteName, b.sourceStoreName)
        : sortKey === "jobs"
        ? (b.jobs ?? []).join(", ")
        : sortKey === "carrier"
        ? b.carrier ?? b.vehicleNumber ?? ""
        : sortKey === "dispatchDate"
        ? b.dispatchDate
        : sortKey === "expectedDelivery"
        ? b.expectedDelivery ?? ""
        : sortKey === "qty"
        ? b.totalQty ?? 0
        : b.status;

    const cmp = String(valA).localeCompare(String(valB), undefined, { numeric: true });
    return sortDir === "asc" ? cmp : -cmp;
  });

  const handleSort = (key: DispatchSortKey) => {
    if (sortKey === key) {
      setSortDir((d) => (d === "asc" ? "desc" : "asc"));
    } else {
      setSortKey(key);
      setSortDir("asc");
    }
  };

  return (
    <div className="flex flex-col h-full min-h-[calc(100vh-var(--topbar-h))]">
      {/* Toast */}
      {toast && (
        <div
          className={`fixed bottom-5 right-5 z-[9999] p-4 rounded-xl shadow-lg border text-sm max-w-sm transition-all ${
            toast.type === "success"
              ? "bg-[var(--surface)] border-green-500 text-green-700"
              : "bg-[var(--surface)] border-red-500 text-red-700"
          }`}
        >
          <div className="font-bold">{toast.title}</div>
          {toast.body && <div className="text-xs mt-0.5 text-[var(--text-2)]">{toast.body}</div>}
        </div>
      )}

      {/* Toolbar */}
      <div
        className="flex-shrink-0 flex items-center gap-3 px-5 py-3 border-b flex-wrap"
        style={{ borderColor: "var(--border)", background: "var(--surface)" }}
      >
        <div className="relative flex-1 min-w-[180px] max-w-xs">
          <Search
            size={14}
            className="absolute left-3 top-1/2 -translate-y-1/2"
            style={{ color: "var(--text-3)" }}
          />
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder="Search dispatches…"
            className="w-full pl-8 pr-3 py-1.5 text-sm rounded-lg border outline-none"
            style={{ borderColor: "var(--border)", background: "var(--surface)", color: "var(--text)" }}
          />
        </div>

        <select
          value={siteFilter}
          onChange={(e) => setSiteFilter(e.target.value)}
          className="eoss-input text-xs py-1.5 max-w-[11rem]"
          aria-label="Ship-from site"
        >
          <option value="all">All sites</option>
          {sites.map((s) => (
            <option key={s.id} value={s.id}>
              {s.name}
            </option>
          ))}
        </select>

        <div className="flex-1" />

        {readyCount > 0 && (
          <button
            type="button"
            onClick={() => setStatusFocus(statusFocus === "ready" ? null : "ready")}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${
              statusFocus === "ready" ? "ring-2 ring-[var(--success,#16A34A)]" : ""
            }`}
            style={{ background: "var(--success-bg,#F0FDF4)", color: "var(--success,#16A34A)" }}
          >
            {readyCount} ready
          </button>
        )}

        {pendingDocs > 0 && (
          <button
            type="button"
            onClick={() => setStatusFocus(statusFocus === "pending_docs" ? null : "pending_docs")}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${
              statusFocus === "pending_docs" ? "ring-2 ring-[var(--warning,#D97706)]" : ""
            }`}
            style={{ background: "var(--warning-bg,#FFFBEB)", color: "var(--warning,#D97706)" }}
          >
            {pendingDocs} pending docs
          </button>
        )}

        {dispatched > 0 && (
          <button
            type="button"
            onClick={() => setStatusFocus(statusFocus === "dispatched" ? null : "dispatched")}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${
              statusFocus === "dispatched" ? "ring-2 ring-[var(--info,#2563EB)]" : ""
            }`}
            style={{ background: "var(--info-bg,#EFF6FF)", color: "var(--info,#2563EB)" }}
          >
            {dispatched} in transit
          </button>
        )}

        {canManage && (
          <button
            onClick={() => setShowModal(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-white shadow-sm hover:opacity-95"
            style={{ background: "var(--brand-fill, #7048F5)" }}
          >
            <Plus size={13} /> New Dispatch
          </button>
        )}
      </div>

      {/* Table */}
      <div className="flex-1 overflow-auto">
        {loading ? (
          <div className="p-12 text-center text-sm text-[var(--text-3)]">Loading dispatches…</div>
        ) : (
          <>
            {/* Mobile View */}
            <div className="md:hidden p-3 space-y-2">
              {sorted.map((d) => (
                <button
                  key={d.id}
                  type="button"
                  onClick={() => navigate(`/dispatch/${d.id}`)}
                  className="w-full text-left rounded-xl border p-3 hover:bg-[var(--surface-2)] transition-colors"
                  style={{ borderColor: "var(--border)", background: "var(--surface)" }}
                >
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs font-bold" style={{ color: "var(--brand, #7048F5)" }}>
                      {d.dispatchNumber}
                    </span>
                    <StatusChip status={d.status} size="sm" />
                  </div>
                  <div className="text-sm font-semibold text-[var(--text)] mt-1">{d.customerName}</div>
                  <div className="text-xs text-[var(--text-2)] mt-1">
                    {placeLabel(d.sourceSiteName, d.sourceStoreName)} · {(d.jobs ?? []).join(", ") || "No job"}
                  </div>
                </button>
              ))}
            </div>

            {/* Desktop Table */}
            <table className="hidden md:table w-full text-sm border-separate border-spacing-0" style={{ minWidth: 900 }}>
              <thead style={{ position: "sticky", top: 0, zIndex: 5 }}>
                <tr style={{ background: "var(--surface-2)" }}>
                  {([
                    ["Dispatch #", "number"],
                    ["Customer", "customer"],
                    ["Ship from", "sourceStore"],
                    ["Jobs", "jobs"],
                    ["Carrier / Vehicle", "carrier"],
                    ["Dispatch Date", "dispatchDate"],
                    ["Expected Delivery", "expectedDelivery"],
                    ["Qty", "qty"],
                    ["Status", "status"],
                  ] as [string, DispatchSortKey][]).map(([label, key]) => (
                    <SortableHeader
                      key={label}
                      label={label}
                      active={sortKey === key}
                      direction={sortDir}
                      onSort={() => handleSort(key)}
                      className="text-left px-4 py-2.5 text-xs font-semibold"
                      style={{ color: "var(--text-3)", borderBottom: "1px solid var(--border)" }}
                    />
                  ))}
                  <th style={{ borderBottom: "1px solid var(--border)" }} />
                </tr>
              </thead>
              <tbody>
                {sorted.map((d) => (
                  <tr
                    key={d.id}
                    className="cursor-pointer transition-colors"
                    style={{ borderBottom: "1px solid var(--border)", background: "var(--surface)" }}
                    onMouseEnter={(e) => {
                      (e.currentTarget as HTMLElement).style.background = "var(--surface-2)";
                    }}
                    onMouseLeave={(e) => {
                      (e.currentTarget as HTMLElement).style.background = "var(--surface)";
                    }}
                    onClick={() => navigate(`/dispatch/${d.id}`)}
                  >
                    <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: "var(--brand, #7048F5)" }}>
                      {d.dispatchNumber}
                    </td>
                    <td className="px-4 py-3 text-sm font-medium" style={{ color: "var(--text)" }}>
                      {d.customerName}
                    </td>
                    <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>
                      {placeLabel(d.sourceSiteName, d.sourceStoreName)}
                    </td>
                    <td className="px-4 py-3 font-mono text-xs" style={{ color: "var(--text-2)" }}>
                      {(d.jobs ?? []).join(", ") || "—"}
                    </td>
                    <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>
                      {d.dispatchMethodLabel && <div>{d.dispatchMethodLabel}</div>}
                      {d.carrier ?? d.vehicleNumber ?? "—"}
                    </td>
                    <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>
                      {displayDate(d.dispatchDate)}
                    </td>
                    <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>
                      {displayDate(d.expectedDelivery)}
                    </td>
                    <td className="px-4 py-3 text-xs text-center" style={{ color: "var(--text-2)" }}>
                      {d.totalQty ?? "—"}
                    </td>
                    <td className="px-4 py-3">
                      <div className="flex items-center gap-1 flex-wrap">
                        <StatusChip status={d.status} size="sm" />
                        {d.status === "pending_docs" && (!d.totalQty || d.totalQty === 0) && (
                          <span className="text-[9px] font-bold px-1.5 py-0.5 rounded-full bg-green-100 text-green-700">
                            Ready Stock
                          </span>
                        )}
                      </div>
                    </td>
                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                      <div className="flex items-center gap-1">
                        {(d.status === "dispatched" || d.status === "in_transit") && !d.hasPoD && (
                          <button
                            onClick={(e) => {
                              e.stopPropagation();
                              setPodDispatchId(d.id);
                            }}
                            className="flex items-center gap-1 text-xs px-2 py-1 rounded-lg border font-medium transition-colors"
                            style={{ borderColor: "var(--brand, #7048F5)", color: "var(--brand, #7048F5)" }}
                            title="Record Proof of Delivery"
                          >
                            <PackageCheck size={11} /> PoD
                          </button>
                        )}
                        {canManage && d.status !== "delivered" && (
                          <button
                            onClick={(e) => handleDeleteDispatch(d.id, e)}
                            className="p-1 rounded hover:bg-[var(--surface-2)]"
                            title="Remove dispatch"
                          >
                            <Trash2 size={12} style={{ color: "var(--error,#DC2626)" }} />
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
                {sorted.length === 0 && (
                  <tr>
                    <td colSpan={9} className="px-4 py-12 text-center text-sm" style={{ color: "var(--text-3)" }}>
                      No dispatches found
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </>
        )}
      </div>

      {/* New Dispatch drawer */}
      {showModal && (
        <RecordSheet
          title="New Dispatch"
          subtitle="Select an eligible Customer PO line and finished-goods lot"
          width="lg"
          pinnedFooter
          onClose={() => setShowModal(false)}
          secondary={{ label: "Cancel", onClick: () => setShowModal(false) }}
          primary={{
            label: creating ? "Creating…" : "Create Dispatch",
            onClick: handleCreate,
            disabled: creating || !form.customerId || !form.dispatchDate || !selectedJobId
              || !selectedLotId || !dispatchQuantity || checkingEligibility || !eligibility?.eligible,
          }}
        >
            <div className="space-y-3 text-sm">
              {/* Ship-from site and store */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label htmlFor="dispatch-site" className="text-xs text-[var(--text-3)] block mb-1">Ship-from site</label>
                  <select
                    id="dispatch-site"
                    value={form.sourceSiteId}
                    onChange={(e) => {
                      const s = sites.find((x) => x.id === e.target.value);
                      const store = stores.find((candidate) =>
                        candidate.siteId === e.target.value && candidate.storeType === "finished_goods"
                      );
                      setForm((f) => ({
                        ...f,
                        sourceSiteId: e.target.value,
                        sourceSiteName: s?.name ?? "",
                        sourceStoreId: store?.id ?? "",
                        sourceStoreName: store?.name ?? "",
                      }));
                      setSelectedLotId("");
                      setDispatchQuantity("");
                    }}
                    className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                  >
                    {sites.map((s) => (
                      <option key={s.id} value={s.id}>
                        {s.name}
                      </option>
                    ))}
                  </select>
                </div>
                <div>
                  <label htmlFor="dispatch-store" className="text-xs text-[var(--text-3)] block mb-1">Finished-goods store *</label>
                  <select
                    id="dispatch-store"
                    value={form.sourceStoreId}
                    onChange={(e) => {
                      const st = stores.find((x) => x.id === e.target.value);
                      setForm((f) => ({
                        ...f,
                        sourceStoreId: e.target.value,
                        sourceStoreName: st?.name ?? "",
                      }));
                      setSelectedLotId("");
                      setDispatchQuantity("");
                    }}
                    className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                  >
                    {stores
                      .filter((st) =>
                        st.storeType === "finished_goods"
                        && (!form.sourceSiteId || st.siteId === form.sourceSiteId)
                      )
                      .map((st) => (
                        <option key={st.id} value={st.id}>
                          {st.name}
                        </option>
                      ))}
                  </select>
                </div>
              </div>

              {/* Customer */}
              <div>
                <label htmlFor="new-dispatch-customer" className="text-xs text-[var(--text-3)] block mb-1">Customer *</label>
                <select
                  id="new-dispatch-customer"
                  aria-label="Customer"
                  value={form.customerId}
                  onChange={(e) => {
                    const c = customers.find((x) => x.id === e.target.value);
                    setSelectedCpoId("");
                    setSelectedJobId("");
                    setSelectedLotId("");
                    setDispatchQuantity("");
                    setForm((f) => ({
                      ...f,
                      customerId: e.target.value,
                      customerName: c?.tradingName || c?.name || "",
                    }));
                  }}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                >
                  <option value="">Select customer…</option>
                  {customers.map((c) => (
                    <option key={c.id} value={c.id}>
                      {c.tradingName || c.name}
                    </option>
                  ))}
                </select>
              </div>

              {/* Customer PO and line */}
              <div>
                <label htmlFor="dispatch-cpo" className="text-xs text-[var(--text-3)] block mb-1">Customer PO *</label>
                <select
                  id="dispatch-cpo"
                  aria-label="Customer PO"
                  value={selectedCpoId}
                  disabled={!form.customerId}
                  onChange={(e) => {
                    setSelectedCpoId(e.target.value);
                    setSelectedJobId("");
                    setSelectedLotId("");
                    setDispatchQuantity("");
                  }}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                >
                  <option value="">Select Customer PO…</option>
                  {purchaseOrders.map((po) => (
                    <option key={po.id} value={po.id}>
                      {po.number}
                    </option>
                  ))}
                </select>
                <label htmlFor="dispatch-po-line" className="text-xs text-[var(--text-3)] block mt-3 mb-1">PO line *</label>
                <select
                  id="dispatch-po-line"
                  aria-label="PO line"
                  value={selectedJobId}
                  disabled={!selectedCpoId}
                  onChange={(e) => {
                    const job = poLines.find((candidate) => candidate.id === e.target.value);
                    setSelectedJobId(e.target.value);
                    setSelectedLotId("");
                    setDispatchQuantity("");
                    if (job?.siteId) {
                      const site = sites.find((candidate) => candidate.id === job.siteId);
                      const store = stores.find((candidate) =>
                        candidate.siteId === job.siteId && candidate.storeType === "finished_goods"
                      );
                      setForm((current) => ({
                        ...current,
                        sourceSiteId: job.siteId!,
                        sourceSiteName: site?.name ?? current.sourceSiteName,
                        sourceStoreId: store?.id ?? "",
                        sourceStoreName: store?.name ?? "",
                      }));
                    }
                  }}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                >
                  <option value="">Select PO line…</option>
                  {poLines.map((job) => (
                    <option key={job.id} value={job.id}>
                      {job.itemCode ?? job.itemName ?? "Item"} · {job.jobNumber} · {job.qty ?? "—"} {job.uomSymbol ?? ""}
                    </option>
                  ))}
                </select>
                {checkingEligibility && (
                  <p className="text-xs mt-1 text-[var(--text-3)]">Checking quality eligibility…</p>
                )}
                {eligibility && (
                  <p
                    className={`text-xs mt-1.5 font-medium ${
                      eligibility.eligible ? "text-[var(--success,#16A34A)]" : "text-[var(--error,#DC2626)]"
                    }`}
                  >
                    {eligibility.eligible
                      ? `✓ Dispatch-eligible — ${eligibility.reason}`
                      : `✗ Not eligible — ${eligibility.reason}`}
                  </p>
                )}
                {eligibility && !eligibility.eligible
                  && ["passed", "concession_granted"].includes(eligibility.inspectionStatus ?? "")
                  && eligibility.blockers.some((blocker) => blocker.toLowerCase().includes("clearance certificate"))
                  && canIssueQualityRelease && (
                    <button
                      type="button"
                      onClick={handleIssueQualityRelease}
                      disabled={issuingQualityRelease}
                      className="mt-2 px-3 py-1.5 rounded-lg text-xs font-semibold border border-[var(--border)] disabled:opacity-50"
                    >
                      {issuingQualityRelease ? "Issuing quality release…" : "Issue quality release"}
                    </button>
                  )}
              </div>

              <div>
                <label htmlFor="dispatch-lot" className="text-xs text-[var(--text-3)] block mb-1">Finished-goods lot *</label>
                <select
                  id="dispatch-lot"
                  aria-label="Finished-goods lot"
                  value={selectedLotId}
                  disabled={!selectedJobId || !form.sourceStoreId || loadingLots || lots.length === 0}
                  onChange={(e) => {
                    setSelectedLotId(e.target.value);
                    const lot = lots.find((candidate) => candidate.id === e.target.value);
                    const job = jobs.find((candidate) => candidate.id === selectedJobId);
                    setDispatchQuantity(lot && job
                      ? String(Math.min(job.qty ?? lot.availableQty, lot.availableQty))
                      : "");
                  }}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                >
                  <option value="">{loadingLots ? "Loading lots…" : "Select finished-goods lot…"}</option>
                  {lots.map((lot) => (
                    <option key={lot.id} value={lot.id}>
                      {lot.lotNumber || lot.batchRef || lot.id.slice(0, 8)} · {lot.availableQty} {lot.uomSymbol} available
                    </option>
                  ))}
                </select>
                {lotsError && <p role="alert" className="text-xs mt-1 text-[var(--error)]">{lotsError}</p>}
                {!loadingLots && !lotsError && selectedJobId && lots.length === 0 && (
                  <p className="text-xs mt-1 text-[var(--text-3)]">No available finished-goods stock for this PO line and store.</p>
                )}
                {checkingPoLineAvailability && (
                  <p className="text-xs mt-1 text-[var(--text-3)]">Checking remaining Customer PO quantity…</p>
                )}
                {poLineAvailability && (
                  <p className="text-xs mt-1 text-[var(--text-3)]">
                    Remaining Customer PO quantity: {poLineAvailability.remainingQty} {selectedJob?.uomSymbol ?? ""}
                  </p>
                )}
                {poLineAvailabilityError && (
                  <p role="alert" className="text-xs mt-1 text-[var(--error)]">{poLineAvailabilityError}</p>
                )}
              </div>

              <div>
                <label htmlFor="dispatch-quantity" className="text-xs text-[var(--text-3)] block mb-1">Quantity *</label>
                <input
                  id="dispatch-quantity"
                  aria-label="Quantity"
                  type="number"
                  min="0.001"
                  step="0.001"
                  max={maxDispatchQuantity || undefined}
                  value={dispatchQuantity}
                  disabled={!selectedLotId}
                  onChange={(e) => setDispatchQuantity(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                />
                {selectedLot && (
                  <p className="text-xs mt-1 text-[var(--text-3)]">
                    Maximum dispatchable: {maxDispatchQuantity} {selectedLot.uomSymbol}
                  </p>
                )}
              </div>

              <div>
                <label htmlFor="dispatch-method" className="text-xs text-[var(--text-3)] block mb-1">
                  Dispatch Method (optional)
                </label>
                <select
                  id="dispatch-method"
                  value={form.dispatchMethodOptionId}
                  onChange={(e) => setForm((f) => ({ ...f, dispatchMethodOptionId: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                >
                  <option value="">Select dispatch method</option>
                  {dispatchMethods.map((method) => (
                    <option key={method.id} value={method.id}>{method.label}</option>
                  ))}
                </select>
              </div>

              {/* Carrier with CreatableSelect inline Add New */}
              <div>
                <label className="text-xs text-[var(--text-3)] block mb-1">Carrier</label>
                <CreatableSelect
                  label="carrier"
                  ariaLabel="Carrier / Transporter"
                  value={form.carrier}
                  onChange={(val) => {
                    const matched = carriers.find((c) => c.id === val);
                    setForm((f) => ({ ...f, carrier: matched?.label ?? val }));
                  }}
                  placeholder="Select carrier…"
                  creatable={canManage}
                  options={carriers.map((c) => ({ id: c.id, label: c.label }))}
                  fields={[{ name: "label", label: "Carrier name", required: true }]}
                  onCreate={async (values) => {
                    const created = await createConfigOption("CARRIER", values.label, values.label);
                    setCarriers((prev) => [...prev, created]);
                    return { id: created.id, label: created.label };
                  }}
                />
              </div>

              {/* Vehicle & LR Number with CreatableSelect inline Add New */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-[var(--text-3)] block mb-1">Vehicle No.</label>
                  <CreatableSelect
                    label="vehicle"
                    ariaLabel="Vehicle Number"
                    value={form.vehicleNumber}
                    onChange={(val) => {
                      const matched = vehicles.find((v) => v.id === val);
                      setForm((f) => ({ ...f, vehicleNumber: matched?.label ?? val }));
                    }}
                    placeholder="Select vehicle…"
                    creatable={canManage}
                    options={vehicles.map((v) => ({ id: v.id, label: v.label }))}
                    fields={[{ name: "label", label: "Vehicle registration #", required: true }]}
                    onCreate={async (values) => {
                      const created = await createConfigOption("VEHICLE", values.label, values.label);
                      setVehicles((prev) => [...prev, created]);
                      return { id: created.id, label: created.label };
                    }}
                  />
                </div>
                <div>
                  <label className="text-xs text-[var(--text-3)] block mb-1">LR Number</label>
                  <input
                    value={form.lrNumber}
                    onChange={(e) => setForm((f) => ({ ...f, lrNumber: e.target.value }))}
                    placeholder="LR-XXXX"
                    className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                  />
                </div>
              </div>

              {/* Dispatch Date */}
              <div>
                <label className="text-xs text-[var(--text-3)] block mb-1">Dispatch Date *</label>
                <input
                  type="date"
                  value={form.dispatchDate}
                  onChange={(e) => setForm((f) => ({ ...f, dispatchDate: e.target.value }))}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                />
              </div>
            </div>

        </RecordSheet>
      )}

      {/* PoD Modal */}
      {podDispatchId && (
        <PodModal
          onClose={() => setPodDispatchId(null)}
          onSave={async (podData) => {
            try {
              const updated = await recordPoD(podDispatchId, podData);
              setDispatches((prev) =>
                prev.map((d) => (d.id === podDispatchId ? { ...d, status: updated.status, hasPoD: true } : d))
              );
              showToastMsg("success", "Proof of Delivery recorded", "Dispatch marked as delivered.");
              setPodDispatchId(null);
            } catch (err: any) {
              showToastMsg("error", "PoD failed", err?.message ?? "Could not save PoD");
            }
          }}
        />
      )}
    </div>
  );
}

function PodModal({
  onClose,
  onSave,
}: {
  onClose: () => void;
  onSave: (data: { receivedBy: string; deliveredDate: string; condition: string; notes?: string }) => void;
}) {
  const [form, setForm] = useState({
    receivedBy: "",
    receivedDate: new Date().toISOString().split("T")[0],
    condition: "Good",
    notes: "",
  });

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl w-full max-w-sm p-6 shadow-2xl">
        <div className="flex justify-between items-center mb-4">
          <h3 className="font-bold text-[var(--text)] text-sm">Record Proof of Delivery</h3>
          <button type="button" onClick={onClose}>
            <X size={16} className="text-[var(--text-3)]" />
          </button>
        </div>
        <div className="space-y-3 text-sm">
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Received By *</label>
            <input
              className="eoss-input w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)]"
              value={form.receivedBy}
              onChange={(e) => setForm((f) => ({ ...f, receivedBy: e.target.value }))}
              placeholder="Name of person who received"
              required
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Received Date</label>
            <input
              type="date"
              className="eoss-input w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)]"
              value={form.receivedDate}
              onChange={(e) => setForm((f) => ({ ...f, receivedDate: e.target.value }))}
            />
          </div>
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Condition</label>
            <select
              className="eoss-input w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)]"
              value={form.condition}
              onChange={(e) => setForm((f) => ({ ...f, condition: e.target.value }))}
            >
              {["Good", "Damaged", "Partial"].map((c) => (
                <option key={c}>{c}</option>
              ))}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Notes</label>
            <input
              className="eoss-input w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)]"
              value={form.notes}
              onChange={(e) => setForm((f) => ({ ...f, notes: e.target.value }))}
              placeholder="Optional notes"
            />
          </div>
        </div>
        <div className="flex gap-3 mt-5">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 py-2.5 border border-[var(--border)] rounded-xl text-sm text-[var(--text-2)]"
          >
            Cancel
          </button>
          <button
            type="button"
            disabled={!form.receivedBy.trim()}
            onClick={() => {
              if (!form.receivedBy.trim()) return;
              onSave({
                receivedBy: form.receivedBy.trim(),
                deliveredDate: form.receivedDate,
                condition: form.condition.toLowerCase(),
                notes: form.notes.trim() || undefined,
              });
            }}
            className="flex-1 py-2.5 bg-[var(--brand-fill)] text-white rounded-xl text-sm font-medium disabled:opacity-40"
          >
            Confirm PoD
          </button>
        </div>
      </div>
    </div>
  );
}
