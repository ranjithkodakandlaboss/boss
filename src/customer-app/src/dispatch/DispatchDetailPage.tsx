import { useEffect, useState } from "react";
import { useParams, useNavigate, Link } from "react-router-dom";
import {
  ArrowLeft,
  Truck,
  Package,
  CheckCircle,
  Box,
  FileText,
  Plus,
  History,
} from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import StatusChip from "../components/shared/StatusChip";
import {
  getDispatch,
  updateDispatchStatus,
  recordPoD,
  type DispatchDetail as DispatchDetailType,
} from "./api";

function Field({ label, value, mono, linkTo }: { label: string; value: string; mono?: boolean; linkTo?: string }) {
  return (
    <div>
      <dt className="text-xs text-[var(--text-3)]">{label}</dt>
      <dd className={`text-sm text-[var(--text)] mt-0.5 ${mono ? "font-mono" : ""}`}>
        {linkTo ? (
          <Link to={linkTo} className="text-[var(--brand, #7048F5)] hover:underline">
            {value}
          </Link>
        ) : (
          value
        )}
      </dd>
    </div>
  );
}

export default function DispatchDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useCustomer();
  const roles = user?.roles.map((r) => r.role.toLowerCase()) ?? [];
  const canDispatch = roles.some((r) =>
    ["owner", "ops_manager", "dispatch", "store_keeper"].includes(r)
  );

  const [dispatch, setDispatch] = useState<DispatchDetailType | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [updating, setUpdating] = useState(false);
  const [toast, setToast] = useState<{ type: "success" | "error"; title: string; body?: string } | null>(null);

  // PoD Form state
  const [deliveredDate, setDeliveredDate] = useState(new Date().toISOString().split("T")[0]);
  const [receivedByName, setReceivedByName] = useState("");
  const [condition, setCondition] = useState<"good" | "shortage" | "partial_damage">("good");
  const [podReference, setPodReference] = useState("");
  const [deliveryNotes, setDeliveryNotes] = useState("");

  const showToastMsg = (type: "success" | "error", title: string, body?: string) => {
    setToast({ type, title, body });
    setTimeout(() => setToast(null), 4000);
  };

  const loadRecord = async () => {
    if (!id) return;
    try {
      setLoading(true);
      setError("");
      const res = await getDispatch(id);
      setDispatch(res);
      if (res.actualDeliveryDate) setDeliveredDate(res.actualDeliveryDate);
      if (res.receivedByName) setReceivedByName(res.receivedByName);
      if (res.podReference) setPodReference(res.podReference);
      if (res.deliveryNotes) setDeliveryNotes(res.deliveryNotes);
      if (res.deliveryCondition && ["good", "shortage", "partial_damage"].includes(res.deliveryCondition)) {
        setCondition(res.deliveryCondition as any);
      }
    } catch (err: any) {
      setError(err?.message ?? "Dispatch record not found");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadRecord();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const handleStatusChange = async (newStatus: string) => {
    if (!dispatch) return;
    try {
      setUpdating(true);
      const updated = await updateDispatchStatus(dispatch.id, { status: newStatus });
      setDispatch(updated);
      showToastMsg("success", "Status updated", `Dispatch marked as ${newStatus}`);
    } catch (err: any) {
      showToastMsg("error", "Status update failed", err?.message ?? "Could not transition status");
    } finally {
      setUpdating(false);
    }
  };

  const handlePoDSubmit = async () => {
    if (!dispatch || !receivedByName.trim()) return;
    try {
      setUpdating(true);
      const updated = await recordPoD(dispatch.id, {
        deliveredDate,
        receivedBy: receivedByName.trim(),
        condition,
        podReference: podReference.trim() || undefined,
        notes: deliveryNotes.trim() || undefined,
      });
      setDispatch(updated);
      showToastMsg("success", "Proof of Delivery confirmed", "Delivery marked as complete.");
    } catch (err: any) {
      showToastMsg("error", "PoD submission failed", err?.message ?? "Could not save PoD");
    } finally {
      setUpdating(false);
    }
  };

  if (loading) {
    return <div className="p-12 text-center text-sm text-[var(--text-3)]">Loading dispatch details…</div>;
  }

  if (error || !dispatch) {
    return (
      <div className="max-w-3xl mx-auto p-6 space-y-4">
        <button
          onClick={() => navigate("/dispatch")}
          className="inline-flex items-center gap-1.5 text-xs text-[var(--text-2)] hover:text-[var(--text)]"
        >
          <ArrowLeft size={14} /> Back to Dispatches
        </button>
        <div className="p-6 rounded-xl border border-[var(--border)] bg-[var(--surface)] text-sm text-[var(--text-2)]">
          {error || "Dispatch not found"}
        </div>
      </div>
    );
  }

  const isQCGateBlocked = Boolean(dispatch.failedInspectionId && !dispatch.qualityReleaseId);

  return (
    <div className="max-w-3xl mx-auto p-4 md:p-6 pb-16">
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

      {/* Header */}
      <div className="flex items-center gap-3 mb-5">
        <button
          onClick={() => navigate("/dispatch")}
          className="text-[var(--text-3)] hover:text-[var(--text)] p-1 rounded-lg"
          aria-label="Back to Dispatches"
        >
          <ArrowLeft size={16} />
        </button>
        <div>
          <div className="flex items-center gap-2 flex-wrap">
            <span className="font-mono text-sm font-bold" style={{ color: "var(--brand, #7048F5)" }}>
              {dispatch.dispatchNumber}
            </span>
            <StatusChip status={dispatch.status} />
          </div>
          <p className="text-sm text-[var(--text-2)] mt-0.5 font-medium">
            <Link
              to={`/sales/customers/${dispatch.customerId}`}
              className="hover:underline text-[var(--text)]"
            >
              {dispatch.customerName}
            </Link>
          </p>
        </div>
      </div>

      <div className="grid md:grid-cols-2 gap-4">
        {/* Left Column */}
        <div className="space-y-4">
          {/* Dispatch Details Card */}
          <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl p-4 shadow-sm">
            <h3 className="font-semibold text-sm text-[var(--text)] mb-3">Dispatch Details</h3>
            <dl className="space-y-2.5 text-sm">
              {(dispatch.jobs ?? []).length > 0 && (
                <div>
                  <dt className="text-xs text-[var(--text-3)]">Jobs</dt>
                  <dd className="text-sm text-[var(--text)] mt-0.5 font-mono">
                    {dispatch.jobId ? (
                      <Link to={`/jobs/${dispatch.jobId}`} className="text-[var(--brand, #7048F5)] hover:underline">
                        {(dispatch.jobs ?? []).join(", ")}
                      </Link>
                    ) : (
                      (dispatch.jobs ?? []).join(", ")
                    )}
                  </dd>
                </div>
              )}
              {dispatch.cpoNumber && (
                <Field
                  label="Customer PO"
                  value={dispatch.cpoNumber}
                  mono
                  linkTo={dispatch.cpoId ? `/sales/po/${dispatch.cpoId}` : undefined}
                />
              )}
              {dispatch.jobNumber && (
                <Field
                  label="Job"
                  value={dispatch.jobNumber}
                  mono
                  linkTo={dispatch.jobId ? `/jobs/${dispatch.jobId}` : undefined}
                />
              )}
              {dispatch.destination && <Field label="Destination" value={dispatch.destination} />}
              {dispatch.sourceStoreName && (
                <Field
                  label="Source Location"
                  value={`${dispatch.sourceSiteName || ""} · ${dispatch.sourceStoreName}`}
                />
              )}
              {dispatch.carrier && <Field label="Carrier" value={dispatch.carrier} />}
              {dispatch.dispatchMethodLabel && (
                <Field label="Dispatch Method" value={dispatch.dispatchMethodLabel} />
              )}
              {dispatch.vehicleNumber && <Field label="Vehicle" value={dispatch.vehicleNumber} mono />}
              {dispatch.driverName && <Field label="Driver" value={dispatch.driverName} />}
              {dispatch.lrNumber && <Field label="LR / Consignment No." value={dispatch.lrNumber} mono />}
              {dispatch.dispatchDate && (
                <Field
                  label="Dispatch Date"
                  value={new Date(dispatch.dispatchDate).toLocaleDateString("en-IN")}
                />
              )}
              {dispatch.expectedDelivery && (
                <Field
                  label="Expected Delivery"
                  value={new Date(dispatch.expectedDelivery).toLocaleDateString("en-IN")}
                />
              )}
            </dl>
          </div>

          {/* Shipment Summary Card */}
          <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl p-4 shadow-sm">
            <h3 className="font-semibold text-sm text-[var(--text)] mb-3 flex items-center gap-2">
              <Package size={14} /> Shipment Summary
            </h3>
            <dl className="space-y-2 text-sm">
              {dispatch.totalQty != null && <Field label="Total Qty" value={String(dispatch.totalQty)} />}
              {dispatch.lineCount != null && <Field label="Line Count" value={String(dispatch.lineCount)} />}
              <div>
                <dt className="text-xs text-[var(--text-3)]">Quality Release</dt>
                <dd className="text-sm mt-0.5">
                  {dispatch.qualityReleaseStatus === "released" || dispatch.qualityInspectionResult === "passed" ? (
                    <Link
                      to={`/quality/inspection/${dispatch.qualityInspectionId}`}
                      className="text-[var(--success,#16A34A)] font-medium flex items-center gap-1 hover:underline"
                    >
                      <CheckCircle size={12} /> {dispatch.qualityInspectionNumber || "Inspection Passed"} — released by{" "}
                      {dispatch.qualityReleaseBy || "Quality Lead"}
                    </Link>
                  ) : (
                    <span className="text-[var(--text-3)]">No linked release</span>
                  )}
                </dd>
              </div>
              <div>
                <dt className="text-xs text-[var(--text-3)]">Proof of Delivery</dt>
                <dd className="text-sm mt-0.5">
                  {dispatch.hasPoD ? (
                    <span className="text-[var(--success,#16A34A)] font-medium flex items-center gap-1">
                      <CheckCircle size={12} /> Received
                    </span>
                  ) : (
                    <span className="text-[var(--text-3)]">Not yet received</span>
                  )}
                </dd>
              </div>
            </dl>
          </div>

          {/* Packages Card */}
          {dispatch.packages && dispatch.packages.length > 0 && (
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl p-4 shadow-sm">
              <h3 className="font-semibold text-sm text-[var(--text)] mb-3 flex items-center gap-2">
                <Box size={14} /> Packages ({dispatch.packages.length})
              </h3>
              <div className="space-y-2">
                {dispatch.packages.map((p) => (
                  <div
                    key={p.id}
                    className="p-3 rounded-lg bg-[var(--surface-2)] border border-[var(--border)] text-xs space-y-1"
                  >
                    <div className="flex items-center justify-between font-semibold text-[var(--text)]">
                      <span className="font-mono">{p.packageCode}</span>
                      <span>{p.packageType}</span>
                    </div>
                    {p.productDescription && <div className="text-[var(--text-2)]">{p.productDescription}</div>}
                    <div className="flex items-center gap-3 text-[var(--text-3)]">
                      <span>
                        Qty: {p.quantity} {p.uom || "pcs"}
                      </span>
                      {p.weightKg != null && <span>Weight: {p.weightKg} kg</span>}
                      {p.lengthMm && (
                        <span>
                          {p.lengthMm}×{p.widthMm}×{p.heightMm} mm
                        </span>
                      )}
                    </div>
                    {p.packingNotes && <div className="text-[var(--text-3)] italic">{p.packingNotes}</div>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Right Column */}
        <div className="space-y-4">
          {/* Quality Gate Blocker Alert */}
          {isQCGateBlocked && (
            <div className="p-4 rounded-xl border border-rose-300 bg-rose-50 text-rose-900 space-y-2.5 shadow-sm">
              <div className="flex items-center gap-2 font-bold text-sm text-rose-800">
                <Box size={16} className="text-rose-600" /> Dispatch Blocked — Quality Inspection Gate Active
              </div>
              <p className="text-xs leading-relaxed text-rose-700">
                Dispatch execution is locked because final inspection{" "}
                <strong>{dispatch.failedInspectionNumber}</strong> failed quality criteria.
                {dispatch.linkedNcrNumber ? ` Linked Non-Conformance: ${dispatch.linkedNcrNumber}.` : ""}
              </p>
              <div className="pt-1">
                <Link
                  to={`/quality/inspection/${dispatch.failedInspectionId}`}
                  className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg bg-rose-600 hover:bg-rose-700 text-white text-xs font-semibold shadow-sm transition-colors"
                >
                  Review Inspection & Authorize QA Concession →
                </Link>
              </div>
            </div>
          )}

          {/* Draft Actions */}
          {canDispatch && dispatch.status === "draft" && (
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl p-4 shadow-sm">
              <h3 className="font-semibold text-sm text-[var(--text)] mb-3">Dispatch Actions</h3>
              <button
                disabled={updating || isQCGateBlocked}
                onClick={() => handleStatusChange("pending_docs")}
                className="w-full py-2.5 bg-[var(--brand-fill, #7048F5)] text-white rounded-lg text-sm font-medium disabled:opacity-40 shadow-sm"
              >
                Prepare Documents
              </button>
            </div>
          )}

          {/* Pending Docs Actions */}
          {canDispatch && dispatch.status === "pending_docs" && (
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl p-4 shadow-sm">
              <h3 className="font-semibold text-sm text-[var(--text)] mb-3">Dispatch Actions</h3>
              <div className="space-y-2">
                <button
                  disabled={updating || isQCGateBlocked}
                  onClick={() => handleStatusChange("dispatched")}
                  className="w-full py-2.5 bg-[var(--brand-fill, #7048F5)] text-white rounded-lg text-sm font-medium flex items-center justify-center gap-2 disabled:opacity-40 shadow-sm"
                >
                  <Truck size={14} /> Mark Dispatched
                </button>
                <button
                  disabled={updating || isQCGateBlocked}
                  onClick={() => window.open(`/api/v1/dispatches/${dispatch.id}/challan`, "_blank")}
                  className="w-full py-2 border border-[var(--border)] text-[var(--text)] rounded-lg text-sm disabled:opacity-40 flex items-center justify-center gap-1.5 hover:bg-[var(--surface-2)] transition-colors"
                >
                  Generate E-Way Bill
                </button>
              </div>
            </div>
          )}

          {/* Dispatched Actions */}
          {canDispatch && dispatch.status === "dispatched" && (
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl p-4 shadow-sm">
              <h3 className="font-semibold text-sm text-[var(--text)] mb-3">Transit Actions</h3>
              <button
                disabled={updating}
                onClick={() => handleStatusChange("in_transit")}
                className="w-full py-2.5 border border-[var(--border)] text-[var(--text)] rounded-lg text-sm font-medium mb-2 hover:bg-[var(--surface-2)]"
              >
                Mark In Transit
              </button>
              <button
                disabled={updating}
                onClick={() => handleStatusChange("delivered")}
                className="w-full py-2.5 bg-[var(--success,#16A34A)] text-white rounded-lg text-sm font-medium shadow-sm hover:opacity-95"
              >
                Confirm Delivery
              </button>
            </div>
          )}

          {/* In Transit Actions */}
          {canDispatch && dispatch.status === "in_transit" && (
            <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl p-4 shadow-sm">
              <h3 className="font-semibold text-sm text-[var(--text)] mb-3">Transit Actions</h3>
              <button
                disabled={updating}
                onClick={() => handleStatusChange("delivered")}
                className="w-full py-2.5 bg-[var(--success,#16A34A)] text-white rounded-lg text-sm font-medium mb-2 shadow-sm hover:opacity-95"
              >
                Confirm Delivery
              </button>
              <button
                disabled={updating}
                onClick={() => handleStatusChange("delayed")}
                className="w-full py-2 border border-[var(--warning,#D97706)] text-[var(--warning,#D97706)] rounded-lg text-sm hover:bg-amber-50"
              >
                Report Delay
              </button>
            </div>
          )}

          {/* Delivery Confirmation & PoD */}
          {(dispatch.status === "delivered" ||
            dispatch.status === "delivery_confirmed" ||
            dispatch.status === "dispatched" ||
            dispatch.status === "in_transit" ||
            dispatch.hasPoD) && (
            <div className="bg-[var(--surface-2)] border border-[var(--border)] rounded-xl p-4 space-y-3 shadow-sm">
              <div className="flex items-center justify-between">
                <div className="flex items-center gap-2 text-[var(--success,#16A34A)]">
                  <Truck size={16} />
                  <span className="text-sm font-semibold">
                    {dispatch.hasPoD ? "Delivery Confirmed (PoD)" : "Delivery Confirmation & PoD"}
                  </span>
                </div>
                {dispatch.podReference && (
                  <span className="text-xs font-mono text-[var(--text-3)]">{dispatch.podReference}</span>
                )}
              </div>

              {dispatch.hasPoD ? (
                <div className="space-y-1.5 text-xs text-[var(--text-2)]">
                  <p className="text-[var(--success,#16A34A)] flex items-center gap-1 font-medium">
                    <CheckCircle size={12} /> Proof of delivery confirmed
                  </p>
                  <p>
                    Delivered Date:{" "}
                    <strong>{dispatch.actualDeliveryDate || dispatch.dispatchDate}</strong>
                  </p>
                  {dispatch.receivedByName && (
                    <p>
                      Received By: <strong>{dispatch.receivedByName}</strong>
                    </p>
                  )}
                  {dispatch.deliveryCondition && (
                    <p>
                      Condition:{" "}
                      <strong className="capitalize">{dispatch.deliveryCondition.replace("_", " ")}</strong>
                    </p>
                  )}
                  {dispatch.deliveryNotes && <p className="text-[var(--text-3)]">{dispatch.deliveryNotes}</p>}
                </div>
              ) : canDispatch ? (
                <div className="space-y-2.5 text-xs">
                  <div className="grid grid-cols-2 gap-2">
                    <div>
                      <label className="block text-[11px] text-[var(--text-3)] mb-1">Delivered Date</label>
                      <input
                        type="date"
                        value={deliveredDate}
                        onChange={(e) => setDeliveredDate(e.target.value)}
                        className="w-full px-2.5 py-1.5 bg-[var(--surface)] border border-[var(--border)] rounded-lg text-[var(--text)] outline-none"
                      />
                    </div>
                    <div>
                      <label className="block text-[11px] text-[var(--text-3)] mb-1">Condition</label>
                      <select
                        value={condition}
                        onChange={(e) => setCondition(e.target.value as any)}
                        className="w-full px-2.5 py-1.5 bg-[var(--surface)] border border-[var(--border)] rounded-lg text-[var(--text)] outline-none"
                      >
                        <option value="good">Good — Full Qty</option>
                        <option value="shortage">Shortage Reported</option>
                        <option value="partial_damage">Partial Damage</option>
                      </select>
                    </div>
                  </div>
                  <div className="grid grid-cols-2 gap-2">
                    <div>
                      <label className="block text-[11px] font-semibold text-[var(--text)] mb-1">Received By *</label>
                      <input
                        type="text"
                        placeholder="Customer receiver name (mandatory)"
                        value={receivedByName}
                        onChange={(e) => setReceivedByName(e.target.value)}
                        className="w-full px-2.5 py-1.5 bg-[var(--surface)] border border-[var(--border)] rounded-lg text-[var(--text)] outline-none"
                        required
                      />
                    </div>
                    <div>
                      <label className="block text-[11px] text-[var(--text-3)] mb-1">PoD Reference</label>
                      <input
                        type="text"
                        placeholder={`POD-${dispatch.dispatchNumber}`}
                        value={podReference}
                        onChange={(e) => setPodReference(e.target.value)}
                        className="w-full px-2.5 py-1.5 bg-[var(--surface)] border border-[var(--border)] rounded-lg text-[var(--text)] outline-none"
                      />
                    </div>
                  </div>
                  <div>
                    <label className="block text-[11px] text-[var(--text-3)] mb-1">Delivery Notes</label>
                    <input
                      type="text"
                      placeholder="Optional site unloading remarks"
                      value={deliveryNotes}
                      onChange={(e) => setDeliveryNotes(e.target.value)}
                      className="w-full px-2.5 py-1.5 bg-[var(--surface)] border border-[var(--border)] rounded-lg text-[var(--text)] outline-none"
                    />
                  </div>
                  <button
                    disabled={updating || !receivedByName.trim()}
                    onClick={handlePoDSubmit}
                    className="w-full py-2 bg-[var(--success,#16A34A)] text-white rounded-lg text-xs font-semibold hover:opacity-90 transition-opacity disabled:opacity-40 shadow-sm"
                  >
                    Confirm Delivery &amp; Record PoD
                  </button>
                </div>
              ) : null}
            </div>
          )}

          {/* Customer Invoicing */}
          <div className="bg-[var(--surface-2)] border border-[var(--border)] rounded-xl p-4 space-y-3 shadow-sm">
            <div className="flex items-center justify-between gap-2">
              <div className="flex items-center gap-2 text-[var(--text)]">
                <FileText size={15} className="text-[var(--brand, #7048F5)]" />
                <span className="text-sm font-semibold">Customer Invoicing</span>
              </div>
              {dispatch.cpoId && (
                <Link
                  to={`/finance/invoice/new?poId=${dispatch.cpoId}&dispatchId=${dispatch.id}`}
                  className="inline-flex items-center gap-1 px-2.5 py-1 bg-[var(--brand-fill, #7048F5)] text-white rounded-lg text-xs font-semibold hover:opacity-90"
                >
                  <Plus size={12} /> Create Invoice
                </Link>
              )}
            </div>
            {dispatch.linkedInvoices.length === 0 ? (
              <p className="text-xs text-[var(--text-3)]">
                {dispatch.cpoId
                  ? "Ready to create tax invoice from this dispatch."
                  : "Link a Customer PO to raise an invoice."}
              </p>
            ) : (
              <div className="space-y-1.5">
                {dispatch.linkedInvoices.map((inv) => (
                  <Link
                    key={inv.id}
                    to={`/finance/invoice/${inv.id}`}
                    className="flex items-center justify-between px-2.5 py-2 rounded-lg bg-[var(--surface)] border border-[var(--border)] hover:border-[var(--brand, #7048F5)] text-xs transition-colors"
                  >
                    <span className="font-mono font-semibold text-[var(--brand, #7048F5)]">{inv.invoiceNumber}</span>
                    <StatusChip status={inv.status} size="sm" />
                  </Link>
                ))}
              </div>
            )}
          </div>
        </div>
      </div>

      {/* Activity History Panel */}
      <div className="mt-6 bg-[var(--surface)] border border-[var(--border)] rounded-xl overflow-hidden shadow-sm">
        <div className="px-4 py-3 border-b border-[var(--border)] bg-[var(--surface-2)] flex items-center justify-between gap-3 flex-wrap">
          <div>
            <h3 className="text-sm font-semibold text-[var(--text)] flex items-center gap-2">
              <History size={15} style={{ color: "var(--brand, #7048F5)" }} />
              Dispatch &amp; Delivery Activity History
              <span className="text-xs font-mono px-1.5 py-0.5 rounded bg-[var(--surface)] border border-[var(--border)] text-[var(--text-2)]">
                {dispatch.history.length}
              </span>
            </h3>
            <p className="text-xs text-[var(--text-3)] mt-0.5">
              Dispatch creation, status updates, PoD confirmation, and customer billing events
            </p>
          </div>
        </div>

        <div className="p-4">
          {dispatch.history.length === 0 ? (
            <p className="text-xs text-[var(--text-3)]">No activity recorded yet.</p>
          ) : (
            <div className="relative pl-6 space-y-4 before:absolute before:left-2 before:top-2 before:bottom-2 before:w-0.5 before:bg-[var(--border)]">
              {dispatch.history.map((h) => (
                <div key={h.id} className="relative">
                  <div className="absolute -left-6 top-1.5 w-2.5 h-2.5 rounded-full bg-[var(--brand, #7048F5)] border-2 border-[var(--surface)]" />
                  <div className="flex items-center justify-between">
                    <div className="text-xs font-bold text-[var(--text)]">{h.title}</div>
                    <div className="text-[10px] text-[var(--text-3)]">
                      {new Date(h.timestamp).toLocaleString("en-IN", {
                        day: "2-digit",
                        month: "short",
                        hour: "2-digit",
                        minute: "2-digit",
                      })}
                    </div>
                  </div>
                  <div className="text-xs text-[var(--text-2)] mt-0.5">{h.description}</div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
