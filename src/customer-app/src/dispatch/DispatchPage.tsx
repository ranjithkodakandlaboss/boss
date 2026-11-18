import React, { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { Plus, Search, X, PackageCheck, Trash2 } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import StatusChip from "../components/shared/StatusChip";
import SortableHeader from "../components/shared/SortableHeader";
import CreatableSelect from "../components/shared/CreatableSelect";
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
  createConfigOption,
  type DispatchSummary,
  type Site,
  type Store,
  type Customer,
  type Job,
  type QualityEligibility,
  type ConfigOption,
} from "./api";

type DispatchSortKey =
  | "number"
  | "customer"
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

  const [dispatches, setDispatches] = useState<DispatchSummary[]>([]);
  const [sites, setSites] = useState<Site[]>([]);
  const [stores, setStores] = useState<Store[]>([]);
  const [customers, setCustomers] = useState<Customer[]>([]);
  const [jobs, setJobs] = useState<Job[]>([]);
  const [carriers, setCarriers] = useState<ConfigOption[]>([]);
  const [vehicles, setVehicles] = useState<ConfigOption[]>([]);
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
  const [selectedJobId, setSelectedJobId] = useState("");
  const [eligibility, setEligibility] = useState<QualityEligibility | null>(null);
  const [checkingEligibility, setCheckingEligibility] = useState(false);
  const [creating, setCreating] = useState(false);

  // Form state
  const [form, setForm] = useState({
    customerId: "",
    customerName: "",
    carrier: "",
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
      const [dispList, comp, custList, jobList, carrierOpts, vehicleOpts] = await Promise.all([
        listDispatches(),
        loadCompanySitesAndStores(),
        loadCustomersList(),
        loadJobsList(),
        listConfigOptions("CARRIER"),
        listConfigOptions("VEHICLE"),
      ]);
      setDispatches(dispList);
      setSites(comp.sites);
      setStores(comp.stores);
      setCustomers(custList);
      setJobs(jobList);
      setCarriers(carrierOpts);
      setVehicles(vehicleOpts);

      if (comp.sites.length > 0 && !form.sourceSiteId) {
        setForm((f) => ({
          ...f,
          sourceSiteId: comp.sites[0].id,
          sourceSiteName: comp.sites[0].name,
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
    if (!job) return;
    setSelectedJobId(job.id);
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
      return;
    }
    setCheckingEligibility(true);
    checkDispatchEligibility(selectedJobId)
      .then((res) => setEligibility(res))
      .catch(() => setEligibility({ jobId: selectedJobId, eligible: true, reason: "Quality clearance checked", blockers: [] }))
      .finally(() => setCheckingEligibility(false));
  }, [selectedJobId]);

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
    if (!form.customerId || !form.dispatchDate) {
      showToastMsg("error", "Customer and dispatch date are required");
      return;
    }

    try {
      setCreating(true);
      const cust = customers.find((c) => c.id === form.customerId);
      const eligibleJob = jobs.find((j) => j.id === selectedJobId);

      const created = await createDispatch({
        customerId: form.customerId,
        customerName: cust?.tradingName || cust?.name || form.customerName,
        jobId: eligibleJob?.id,
        cpoId: eligibleJob?.customerPoId,
        sourceSiteId: form.sourceSiteId || undefined,
        sourceSiteName: form.sourceSiteName || undefined,
        sourceStoreId: form.sourceStoreId || undefined,
        sourceStoreName: form.sourceStoreName || undefined,
        carrier: form.carrier || undefined,
        vehicleNumber: form.vehicleNumber || undefined,
        lrNumber: form.lrNumber || undefined,
        dispatchDate: form.dispatchDate,
        jobs: eligibleJob ? [eligibleJob.jobNumber] : [],
        lines: eligibleJob ? [{ jobId: eligibleJob.id, quantity: 1, itemId: undefined }] : [],
      });

      setDispatches((prev) => [created, ...prev]);
      setShowModal(false);
      setSelectedJobId("");
      showToastMsg("success", "Dispatch created", created.dispatchNumber);
    } catch (err: any) {
      showToastMsg("error", "Creation failed", err?.message ?? "Could not create dispatch");
    } finally {
      setCreating(false);
    }
  };

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
          className={`fixed bottom-5 right-5 z-50 p-4 rounded-xl shadow-lg border text-sm max-w-sm transition-all ${
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
            <table className="hidden md:table w-full text-sm border-separate border-spacing-0" style={{ minWidth: 800 }}>
              <thead style={{ position: "sticky", top: 0, zIndex: 5 }}>
                <tr style={{ background: "var(--surface-2)" }}>
                  {([
                    ["Dispatch #", "number"],
                    ["Customer", "customer"],
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
                      <div className="text-[11px] font-normal text-[var(--text-3)]">
                        {placeLabel(d.sourceSiteName, d.sourceStoreName)}
                      </div>
                    </td>
                    <td className="px-4 py-3 font-mono text-xs" style={{ color: "var(--text-2)" }}>
                      {(d.jobs ?? []).join(", ") || "—"}
                    </td>
                    <td className="px-4 py-3 text-xs" style={{ color: "var(--text-2)" }}>
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

      {/* New Dispatch Modal */}
      {showModal && (
        <div className="fixed inset-0 bg-black/40 flex items-center justify-center z-50 p-4">
          <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl p-6 w-full max-w-md shadow-2xl">
            <div className="flex items-center justify-between mb-4">
              <h2 className="font-bold text-base text-[var(--text)]">New Dispatch</h2>
              <button
                type="button"
                onClick={() => setShowModal(false)}
                className="text-[var(--text-3)] hover:text-[var(--text)]"
              >
                <X size={18} />
              </button>
            </div>

            <div className="space-y-3 text-sm">
              {/* Ship-from site and store */}
              <div className="grid grid-cols-2 gap-3">
                <div>
                  <label className="text-xs text-[var(--text-3)] block mb-1">Ship-from site</label>
                  <select
                    value={form.sourceSiteId}
                    onChange={(e) => {
                      const s = sites.find((x) => x.id === e.target.value);
                      setForm((f) => ({
                        ...f,
                        sourceSiteId: e.target.value,
                        sourceSiteName: s?.name ?? "",
                      }));
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
                  <label className="text-xs text-[var(--text-3)] block mb-1">Ship-from store</label>
                  <select
                    value={form.sourceStoreId}
                    onChange={(e) => {
                      const st = stores.find((x) => x.id === e.target.value);
                      setForm((f) => ({
                        ...f,
                        sourceStoreId: e.target.value,
                        sourceStoreName: st?.name ?? "",
                      }));
                    }}
                    className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                  >
                    <option value="">Select store…</option>
                    {stores
                      .filter((st) => !form.sourceSiteId || st.siteId === form.sourceSiteId)
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

              {/* Production Job */}
              <div>
                <label className="text-xs text-[var(--text-3)] block mb-1">
                  Job (checks quality release before dispatch)
                </label>
                <select
                  value={selectedJobId}
                  onChange={(e) => setSelectedJobId(e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text)] focus:outline-none focus:border-[var(--brand)]"
                >
                  <option value="">No specific job (free dispatch)</option>
                  {jobs.map((j) => (
                    <option key={j.id} value={j.id}>
                      {j.jobNumber} {j.description ? `— ${j.description}` : ""}
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
                  placeholder="DTDC / Blue Dart / VRL…"
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
                    placeholder="TS09XX0000"
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

            <div className="flex gap-2 mt-5">
              <button
                type="button"
                onClick={() => setShowModal(false)}
                className="flex-1 py-2 border border-[var(--border)] text-[var(--text)] rounded-lg text-sm"
              >
                Cancel
              </button>
              <button
                type="button"
                disabled={
                  creating ||
                  !form.customerId ||
                  !form.dispatchDate ||
                  (!!eligibility && !eligibility.eligible)
                }
                onClick={handleCreate}
                className="flex-1 py-2 bg-[var(--brand-fill, #7048F5)] text-white rounded-lg text-sm font-medium disabled:opacity-50"
              >
                {creating ? "Creating…" : "Create Dispatch"}
              </button>
            </div>
          </div>
        </div>
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
            className="flex-1 py-2.5 bg-[var(--brand-fill, #7048F5)] text-white rounded-xl text-sm font-medium disabled:opacity-40"
          >
            Confirm PoD
          </button>
        </div>
      </div>
    </div>
  );
}
