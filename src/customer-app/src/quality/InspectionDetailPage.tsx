import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ArrowLeft, CheckCircle, XCircle } from "lucide-react";
import { useCustomer } from "../auth/CustomerState";
import RecordSheet from "../components/shared/RecordSheet";
import StatusChip from "../components/shared/StatusChip";
import {
  getInspection, recordResult,
  inspectionTypeLabel, displayDate,
  type InspectionDetail, type ChecklistResponse,
} from "./api";

export default function InspectionDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useCustomer();
  const roles = user?.roles.map((r) => r.role) ?? [];
  const canWrite = roles.some((r) => ["OWNER", "OPS_MANAGER", "QUALITY_INSPECTOR"].includes(r));

  const [inspection, setInspection] = useState<InspectionDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  // Record result form state
  const [showResultForm, setShowResultForm] = useState(false);
  const [result, setResult] = useState<"pass" | "fail">("pass");
  const [qtyAccepted, setQtyAccepted] = useState("");
  const [qtyRejected, setQtyRejected] = useState("");
  const [resultNotes, setResultNotes] = useState("");
  const [checklistInputs, setChecklistInputs] = useState<Record<string, { responsePass?: boolean; responseValue?: string; observation?: string }>>({});
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");

  function load() {
    if (!id) return;
    setLoading(true);
    getInspection(id).then((data) => {
      setInspection(data);
      // Pre-populate checklist inputs from existing responses
      if (data.checklistResponses) {
        const inputs: typeof checklistInputs = {};
        for (const r of data.checklistResponses) {
          inputs[r.checklistItemId] = {
            responsePass: r.responsePass ?? undefined,
            responseValue: r.responseValue ?? undefined,
            observation: r.observation ?? undefined,
          };
        }
        setChecklistInputs(inputs);
      }
    }).catch((e: Error) => setError(e.message)).finally(() => setLoading(false));
  }

  useEffect(load, [id]);

  async function handleRecordResult(e: React.FormEvent) {
    e.preventDefault();
    if (!inspection) return;
    setSaving(true);
    setSaveError("");
    try {
      const checklistResponses = inspection.checklistResponses?.map((cr) => ({
        checklistItemId: cr.checklistItemId,
        responsePass: checklistInputs[cr.checklistItemId]?.responsePass,
        responseValue: checklistInputs[cr.checklistItemId]?.responseValue,
        observation: checklistInputs[cr.checklistItemId]?.observation,
      }));
      const updated = await recordResult(inspection.id, {
        result,
        quantityAccepted: parseFloat(qtyAccepted) || 0,
        quantityRejected: parseFloat(qtyRejected) || 0,
        checklistResponses,
        notes: resultNotes || undefined,
      });
      setInspection(updated);
      setShowResultForm(false);
    } catch (e: unknown) {
      setSaveError((e as Error).message ?? "Failed to record result");
    } finally {
      setSaving(false);
    }
  }

  if (loading) return <p className="p-6 text-sm text-[var(--text-3)]">Loading…</p>;
  if (error) return <p className="p-6 text-sm text-red-600">{error}</p>;
  if (!inspection) return null;

  const isComplete = inspection.status === "completed";
  const canRecord = canWrite && !isComplete;
  const hasOutOfRangeMeasurement = inspection.checklistResponses?.some((response) =>
    response.responseType === "numeric"
    && checklistInputs[response.checklistItemId]?.responseValue
    && !withinSpecification(response, checklistInputs[response.checklistItemId].responseValue!),
  ) ?? false;

  return (
    <div className="p-4 md:p-6 max-w-3xl mx-auto">
      {/* Back nav */}
      <button
        type="button"
        onClick={() => navigate("/quality")}
        className="inline-flex items-center gap-1 text-sm text-[var(--text-2)] hover:text-[var(--text)] mb-4"
      >
        <ArrowLeft size={15} /> Back to Quality
      </button>

      {/* Title */}
      <div className="flex items-start justify-between mb-6">
        <div>
          <div className="flex items-center gap-2 mb-1">
            <span className="font-mono text-xs text-[var(--text-3)]">{inspection.inspectionNumber}</span>
            <StatusChip status={inspection.status} />
            {inspection.result && (
              <StatusChip status={inspection.result} />
            )}
          </div>
          <h1 className="text-lg font-bold">
            {inspection.inspectionTypeLabel ?? `${inspectionTypeLabel(inspection.inspectionType)} Inspection`}
          </h1>
          <p className="text-sm text-[var(--text-2)]">{inspection.referenceLabel ?? inspection.referenceId}</p>
        </div>
        {canRecord && (
          <button
            type="button"
            onClick={() => setShowResultForm(true)}
            className="rounded-lg bg-[var(--brand-fill)] px-3 py-1.5 text-sm font-semibold text-white"
          >
            Record Result
          </button>
        )}
      </div>

      {/* Detail fields */}
      <div className="grid grid-cols-2 md:grid-cols-3 gap-4 mb-6 rounded-xl border p-4" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
        <Field label="Item" value={inspection.itemCode ? `${inspection.itemCode} — ${inspection.itemName}` : "—"} />
        <Field label="Inspector" value={inspection.inspectorName ?? "—"} />
        <Field label="Date" value={displayDate(inspection.inspectionDate)} />
        <Field label="Qty Inspected" value={inspection.quantityInspected ?? "—"} />
        <Field label="Qty Accepted" value={inspection.quantityAccepted ?? "—"} />
        <Field label="Qty Rejected" value={inspection.quantityRejected ?? "—"} />
        {inspection.templateName && <Field label="Checklist" value={inspection.templateName} />}
        {inspection.notes && <Field label="Notes" value={inspection.notes} span />}
      </div>

      {/* Checklist responses */}
      {inspection.checklistResponses && inspection.checklistResponses.length > 0 && (
        <section className="mb-6">
          <h2 className="text-sm font-semibold mb-3 uppercase tracking-wide text-[var(--text-3)]">Checklist</h2>
          <div className="space-y-2">
            {inspection.checklistResponses.map((cr) => (
              <ChecklistRow key={cr.id} response={cr} />
            ))}
          </div>
        </section>
      )}

      {/* Linked NCRs */}
      {inspection.linkedNcrs && inspection.linkedNcrs.length > 0 && (
        <section className="mb-6">
          <h2 className="text-sm font-semibold mb-3 uppercase tracking-wide text-[var(--text-3)]">NCRs</h2>
          <div className="space-y-2">
            {inspection.linkedNcrs.map((ncr) => (
              <button
                key={ncr.id}
                type="button"
                onClick={() => navigate(`/quality/ncr/${ncr.id}`)}
                className="w-full text-left rounded-lg border p-3 hover:bg-[var(--surface)] transition-colors"
                style={{ borderColor: "var(--border)" }}
              >
                <div className="flex items-center justify-between">
                  <span className="font-mono text-xs">{ncr.ncrNumber}</span>
                  <StatusChip status={ncr.status} />
                </div>
                <p className="text-sm text-[var(--text-2)] mt-1 truncate">{ncr.defectDescription}</p>
              </button>
            ))}
          </div>
        </section>
      )}

      {/* Record result drawer */}
      {showResultForm && (
        <RecordSheet
          title="Record Inspection Result"
          width="lg"
          onClose={() => setShowResultForm(false)}
          secondary={{ label: "Cancel", onClick: () => setShowResultForm(false) }}
          primary={{
            label: saving ? "Saving…" : "Save Result",
            onClick: () => document.querySelector<HTMLFormElement>("#inspection-result-form")?.requestSubmit(),
            disabled: saving || (result === "pass" && hasOutOfRangeMeasurement),
          }}
        >
            <form id="inspection-result-form" onSubmit={handleRecordResult} className="space-y-4">
              {/* Pass/Fail toggle */}
              <div>
                <span className="block text-sm font-medium text-[var(--text-2)] mb-2">Result</span>
                <div className="flex gap-2">
                  {(["pass", "fail"] as const).map((r) => (
                    <button
                      key={r}
                      type="button"
                      onClick={() => setResult(r)}
                      disabled={r === "pass" && hasOutOfRangeMeasurement}
                      className={`flex-1 flex items-center justify-center gap-1.5 rounded-lg border py-2 text-sm font-semibold transition-colors ${
                        result === r
                          ? r === "pass"
                            ? "bg-green-50 border-green-500 text-green-700"
                            : "bg-red-50 border-red-500 text-red-700"
                          : "border-[var(--border)] text-[var(--text-2)]"
                      }`}
                    >
                      {r === "pass" ? <CheckCircle size={15} /> : <XCircle size={15} />}
                      {r === "pass" ? "Pass" : "Fail"}
                    </button>
                  ))}
                </div>
                {hasOutOfRangeMeasurement && (
                  <p role="alert" className="mt-2 text-xs text-red-600">
                    One or more measurements are outside the specified limits. Record this inspection as a failure.
                  </p>
                )}
              </div>

              <div className="grid grid-cols-2 gap-3">
                <label className="block text-sm">
                  <span className="text-[var(--text-2)] font-medium">Qty Accepted</span>
                  <input
                    type="number"
                    min="0"
                    step="any"
                    className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                    style={{ borderColor: "var(--border)" }}
                    value={qtyAccepted}
                    onChange={(e) => setQtyAccepted(e.target.value)}
                  />
                </label>
                <label className="block text-sm">
                  <span className="text-[var(--text-2)] font-medium">Qty Rejected</span>
                  <input
                    type="number"
                    min="0"
                    step="any"
                    className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)]"
                    style={{ borderColor: "var(--border)" }}
                    value={qtyRejected}
                    onChange={(e) => setQtyRejected(e.target.value)}
                  />
                </label>
              </div>

              {/* Checklist items */}
              {inspection.checklistResponses && inspection.checklistResponses.length > 0 && (
                <div>
                  <span className="block text-sm font-medium text-[var(--text-2)] mb-2">Checklist</span>
                  <div className="space-y-3">
                    {inspection.checklistResponses.map((cr) => (
                      <ChecklistInput
                        key={cr.checklistItemId}
                        response={cr}
                        value={checklistInputs[cr.checklistItemId] ?? {}}
                        onChange={(val) =>
                          setChecklistInputs((prev) => ({ ...prev, [cr.checklistItemId]: val }))
                        }
                      />
                    ))}
                  </div>
                </div>
              )}

              <label className="block text-sm">
                <span className="text-[var(--text-2)] font-medium">Notes</span>
                <textarea
                  rows={2}
                  className="mt-1 w-full rounded-lg border px-3 py-2 text-sm bg-[var(--surface)] resize-none"
                  style={{ borderColor: "var(--border)" }}
                  value={resultNotes}
                  onChange={(e) => setResultNotes(e.target.value)}
                />
              </label>

              {saveError && <p className="text-xs text-red-600">{saveError}</p>}
            </form>
        </RecordSheet>
      )}
    </div>
  );
}

function Field({ label, value, span }: { label: string; value: React.ReactNode; span?: boolean }) {
  return (
    <div className={span ? "col-span-full" : ""}>
      <dt className="text-xs text-[var(--text-3)] font-medium mb-0.5">{label}</dt>
      <dd className="text-sm text-[var(--text)]">{value}</dd>
    </div>
  );
}

function ChecklistRow({ response }: { response: ChecklistResponse }) {
  const answered = response.answered;
  return (
    <div
      className={`rounded-lg border p-3 ${answered ? "" : "border-dashed opacity-70"}`}
      style={{ borderColor: "var(--border)" }}
    >
      <div className="flex items-start justify-between gap-2">
        <span className="text-sm">{response.itemDescription}</span>
        {response.isMandatory && <span className="text-xs text-red-500 shrink-0">Required</span>}
      </div>
      {(response.unit || response.specMin != null || response.specMax != null) && (
        <p className="mt-1 text-xs text-[var(--text-3)]">
          Specification: {response.specMin ?? "—"} to {response.specMax ?? "—"} {response.unit ?? ""}
        </p>
      )}
      {answered && (
        <div className="mt-1.5 flex flex-wrap gap-3 text-xs text-[var(--text-2)]">
          {response.responsePass != null && (
            <span className={`font-semibold ${response.responsePass ? "text-green-600" : "text-red-600"}`}>
              {response.responsePass ? "Pass" : "Fail"}
            </span>
          )}
          {response.responseValue && <span>{response.responseValue}</span>}
          {response.observation && <span className="italic">{response.observation}</span>}
        </div>
      )}
    </div>
  );
}

function ChecklistInput({
  response,
  value,
  onChange,
}: {
  response: ChecklistResponse;
  value: { responsePass?: boolean; responseValue?: string; observation?: string };
  onChange: (v: typeof value) => void;
}) {
  return (
    <div className="rounded-lg border p-3" style={{ borderColor: "var(--border)" }}>
      <div className="flex items-center justify-between mb-2">
        <span className="text-sm font-medium">{response.itemDescription}</span>
        {response.isMandatory && <span className="text-xs text-red-500">Required</span>}
      </div>
      {(response.unit || response.specMin != null || response.specMax != null) && (
        <p className="mb-2 text-xs text-[var(--text-3)]">
          Specification: {response.specMin ?? "—"} to {response.specMax ?? "—"} {response.unit ?? ""}
        </p>
      )}
      {(response.responseType === "pass_fail" || response.responseType === "yes_no") && (
        <div className="flex gap-2">
          {["pass", "fail"].map((v) => (
            <button
              key={v}
              type="button"
              onClick={() => onChange({ ...value, responsePass: v === "pass" })}
              className={`flex-1 rounded border py-1 text-xs font-semibold transition-colors ${
                value.responsePass === (v === "pass")
                  ? v === "pass"
                    ? "bg-green-50 border-green-500 text-green-700"
                    : "bg-red-50 border-red-500 text-red-700"
                  : "border-[var(--border)] text-[var(--text-2)]"
              }`}
            >
              {response.responseType === "yes_no" ? (v === "pass" ? "Yes" : "No") : v === "pass" ? "Pass" : "Fail"}
            </button>
          ))}
        </div>
      )}
      {(response.responseType === "numeric" || response.responseType === "text") && (
        <input
          type={response.responseType === "numeric" ? "number" : "text"}
          step="any"
          className="w-full rounded border px-3 py-1.5 text-sm bg-[var(--surface)]"
          style={{ borderColor: "var(--border)" }}
          placeholder="Value"
          value={value.responseValue ?? ""}
          onChange={(e) => onChange({ ...value, responseValue: e.target.value })}
        />
      )}
      <input
        type="text"
        className="mt-2 w-full rounded border px-3 py-1.5 text-xs bg-[var(--surface)]"
        style={{ borderColor: "var(--border)" }}
        placeholder="Observation (optional)"
        value={value.observation ?? ""}
        onChange={(e) => onChange({ ...value, observation: e.target.value })}
      />
    </div>
  );
}

function withinSpecification(response: ChecklistResponse, value: string) {
  const measurement = Number(value.trim());
  return Number.isFinite(measurement)
    && (response.specMin == null || measurement >= response.specMin)
    && (response.specMax == null || measurement <= response.specMax);
}
