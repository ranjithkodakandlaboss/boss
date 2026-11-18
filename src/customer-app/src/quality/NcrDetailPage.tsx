import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import StatusChip from "../components/shared/StatusChip";
import {
  getNcr, updateNcr, createConcession,
  severityLabel, displayDate,
  type NcrDetail,
} from "./api";

export default function NcrDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useCustomer();
  const roles = user?.roles.map((r) => r.role) ?? [];
  const canWrite = roles.some((r) => ["OWNER", "OPS_MANAGER", "QUALITY_INSPECTOR"].includes(r));
  const canConcede = roles.some((r) => ["OWNER", "OPS_MANAGER"].includes(r));

  const [ncr, setNcr] = useState<NcrDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // Update NCR form
  const [showUpdate, setShowUpdate] = useState(false);
  const [updStatus, setUpdStatus] = useState("");
  const [updDisposition, setUpdDisposition] = useState("");
  const [updRootCause, setUpdRootCause] = useState("");
  const [updResolution, setUpdResolution] = useState("");
  const [updateError, setUpdateError] = useState("");
  const [saving, setSaving] = useState(false);

  // Concession form
  const [showConcession, setShowConcession] = useState(false);
  const [justification, setJustification] = useState("");
  const [concessionError, setConcessionError] = useState("");

  function load() {
    if (!id) return;
    setLoading(true);
    getNcr(id).then((data) => {
      setNcr(data);
      setUpdStatus(data.status ?? "");
      setUpdDisposition(data.disposition ?? "");
      setUpdRootCause(data.rootCause ?? "");
      setUpdResolution(data.resolution ?? "");
    }).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }

  useEffect(load, [id]);

  async function handleUpdate(e: React.FormEvent) {
    e.preventDefault();
    if (!ncr) return;
    setSaving(true);
    setUpdateError("");
    try {
      const updated = await updateNcr(ncr.id, {
        status: updStatus || undefined,
        disposition: updDisposition || undefined,
        rootCause: updRootCause || undefined,
        resolution: updResolution || undefined,
      });
      setNcr(updated);
      setShowUpdate(false);
    } catch (e: unknown) {
      setUpdateError((e as Error).message ?? "Failed to update NCR");
    } finally {
      setSaving(false);
    }
  }

  async function handleConcession(e: React.FormEvent) {
    e.preventDefault();
    if (!ncr) return;
    setSaving(true);
    setConcessionError("");
    try {
      const updated = await createConcession(ncr.id, {
        justification,
      });
      setNcr(updated);
      setShowConcession(false);
    } catch (e: unknown) {
      setConcessionError((e as Error).message ?? "Failed to grant concession");
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <p className="p-6 text-sm text-[var(--text-3)]">Loading…</p>;
  if (error) return <p className="p-6 text-sm text-red-600">{error}</p>;
  if (!ncr) return null;

  const isClosed = ncr.status === "closed" || ncr.status === "concession_granted";

  return (
    <div className="p-4 md:p-6 max-w-3xl mx-auto">
      <button
        type="button"
        onClick={() => navigate("/quality?tab=ncrs")}
        className="inline-flex items-center gap-1 text-sm text-[var(--text-2)] hover:text-[var(--text-1)] mb-4"
      >
        <ArrowLeft size={15} /> Back to NCRs
      </button>

      <div className="flex items-start justify-between mb-6">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <span className="font-mono text-xs font-bold" style={{ color: "var(--brand)" }}>{ncr.ncrNumber}</span>
            <StatusChip status={ncr.status} />
          </div>
          <h1 className="text-lg font-bold">{ncr.defectCategoryLabel ?? "Non-Conformance Report"}</h1>
          <p className="text-sm text-[var(--text-2)] mt-0.5">{ncr.defectDescription}</p>
        </div>
        <div className="flex gap-2">
          {canWrite && !isClosed && (
            <button
              type="button"
              onClick={() => setShowUpdate(true)}
              className="rounded-lg border px-3 py-1.5 text-sm font-medium"
              style={{ borderColor: "var(--border)" }}
            >
              Update
            </button>
          )}
          {canConcede && (ncr.status === "open" || ncr.status === "concession_requested") && (
            <button
              type="button"
              onClick={() => setShowConcession(true)}
              className="rounded-lg bg-[var(--brand-fill)] px-3 py-1.5 text-sm font-semibold text-white"
            >
              Grant Concession
            </button>
          )}
        </div>
      </div>

      {/* Detail fields */}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-6 rounded-xl border p-4" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <Field label="Severity">
          <span className={`font-medium ${ncr.severity === "critical" ? "text-red-600" : ncr.severity === "major" ? "text-orange-600" : "text-[var(--text)]"}`}>
            {severityLabel(ncr.severity)}
          </span>
        </Field>
        <Field label="Item" value={ncr.itemCode ? `${ncr.itemCode} — ${ncr.itemName}` : "—"} />
        <Field label="Quantity" value={ncr.quantity ?? "—"} />
        {ncr.jobNumber && <Field label="Job" value={ncr.jobNumber} />}
        {ncr.inspectionNumber && (
          <Field label="Inspection">
            <button
              type="button"
              className="text-[var(--accent)] underline text-sm"
              onClick={() => ncr.inspectionId && navigate(`/quality/inspection/${ncr.inspectionId}`)}
            >
              {ncr.inspectionNumber}
            </button>
          </Field>
        )}
        <Field label="Raised by" value={ncr.raisedByName ?? "—"} />
        <Field label="Raised" value={displayDate(ncr.createdAt)} />
        {ncr.disposition && <Field label="Disposition" value={ncr.disposition} />}
        {ncr.rootCause && <Field label="Root Cause" value={ncr.rootCause} span />}
        {ncr.resolution && <Field label="Resolution" value={ncr.resolution} span />}
      </div>

      {/* Concessions */}
      {ncr.concessions && ncr.concessions.length > 0 && (
        <section className="mb-6">
          <h2 className="text-sm font-semibold mb-3 uppercase tracking-wide text-[var(--text-3)]">Concessions</h2>
          <div className="space-y-2">
            {ncr.concessions.map((c) => (
              <div key={c.id} className="rounded-lg border p-3" style={{ borderColor: "var(--border)" }}>
                <div className="flex items-center justify-between mb-1">
                  <span className="text-sm font-medium">Concession</span>
                  <StatusChip status={c.status} />
                </div>
                {c.approvalNotes && <p className="text-xs text-[var(--text-2)]">{c.approvalNotes}</p>}
                <p className="text-xs text-[var(--text-3)] mt-1">
                  {c.approvedByName ?? "—"} · {displayDate(c.createdAt)}
                </p>
              </div>
            ))}
          </div>
        </section>
      )}

      {/* Update NCR dialog */}
      {showUpdate && (
        <div className="fixed inset-0 z-[70] bg-black/40 flex items-center justify-center p-4">
          <div
            className="bg-[var(--bg)] rounded-xl shadow-2xl w-full max-w-md p-6"
            role="dialog"
            aria-modal="true"
          >
            <h2 className="text-base font-semibold mb-4">Update NCR</h2>
            <form onSubmit={handleUpdate} className="space-y-3">
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Status</span>
                <select
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  value={updStatus}
                  onChange={(e) => setUpdStatus(e.target.value)}
                >
                  <option value="open">Open</option>
                  <option value="concession_requested">Concession Requested</option>
                  <option value="rework">Rework</option>
                  <option value="scrapped">Scrapped</option>
                  <option value="closed">Closed</option>
                </select>
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Disposition</span>
                <select
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                  style={{ borderColor: "var(--border)" }}
                  value={updDisposition}
                  onChange={(e) => setUpdDisposition(e.target.value)}
                >
                  <option value="">— Select —</option>
                  <option value="use_as_is">Use As-Is</option>
                  <option value="rework">Rework</option>
                  <option value="scrap">Scrap</option>
                  <option value="return_to_supplier">Return to Supplier</option>
                </select>
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Root Cause</span>
                <textarea
                  rows={2}
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)] resize-none"
                  style={{ borderColor: "var(--border)" }}
                  value={updRootCause}
                  onChange={(e) => setUpdRootCause(e.target.value)}
                />
              </label>
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Resolution</span>
                <textarea
                  rows={2}
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)] resize-none"
                  style={{ borderColor: "var(--border)" }}
                  value={updResolution}
                  onChange={(e) => setUpdResolution(e.target.value)}
                />
              </label>
              {updateError && <p className="text-xs text-red-600">{updateError}</p>}
              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowUpdate(false)}
                  className="rounded-lg border px-4 py-2 text-sm font-medium"
                  style={{ borderColor: "var(--border)" }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="rounded-lg bg-[var(--brand-fill)] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                >
                  {saving ? "Saving…" : "Save"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}

      {/* Concession dialog */}
      {showConcession && (
        <div className="fixed inset-0 z-[70] bg-black/40 flex items-center justify-center p-4">
          <div
            className="bg-[var(--bg)] rounded-xl shadow-2xl w-full max-w-md p-6"
            role="dialog"
            aria-modal="true"
          >
            <h2 className="text-base font-semibold mb-4">Grant Concession</h2>
            <form onSubmit={handleConcession} className="space-y-3">
              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Justification *</span>
                <textarea
                  required
                  rows={3}
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)] resize-none"
                  style={{ borderColor: "var(--border)" }}
                  placeholder="Provide justification for this concession"
                  value={justification}
                  onChange={(e) => setJustification(e.target.value)}
                />
              </label>
              {concessionError && <p className="text-xs text-red-600">{concessionError}</p>}
              <div className="flex justify-end gap-2 pt-2">
                <button
                  type="button"
                  onClick={() => setShowConcession(false)}
                  className="rounded-lg border px-4 py-2 text-sm font-medium"
                  style={{ borderColor: "var(--border)" }}
                >
                  Cancel
                </button>
                <button
                  type="submit"
                  disabled={saving}
                  className="rounded-lg bg-[var(--brand-fill)] px-4 py-2 text-sm font-semibold text-white disabled:opacity-50"
                >
                  {saving ? "Granting…" : "Grant"}
                </button>
              </div>
            </form>
          </div>
        </div>
      )}
    </div>
  );
}

function Field({
  label,
  value,
  children,
  span,
}: {
  label: string;
  value?: React.ReactNode;
  children?: React.ReactNode;
  span?: boolean;
}) {
  return (
    <div className={span ? "col-span-full" : ""}>
      <dt className="text-xs text-[var(--text-3)] font-medium mb-0.5">{label}</dt>
      <dd className="text-sm text-[var(--text)]">{children ?? value ?? "—"}</dd>
    </div>
  );
}
