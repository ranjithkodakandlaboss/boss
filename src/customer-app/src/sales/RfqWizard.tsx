import { useEffect, useState } from "react";
import { useNavigate, useParams } from "react-router-dom";
import { ApiError } from "../auth/api";
import Button from "../components/shared/Button";
import { SelectField, TextField } from "../components/shared/Fields";
import ErrorState from "../components/shared/ErrorState";
import LoadingState from "../components/shared/LoadingState";
import { ItemOption, RfqLine, UomOption, salesApi } from "./api";
import { useLoad } from "./load";
import { useCustomer } from "../auth/CustomerState";

const STEPS = ["Header & Commercial", "Line Items", "Technical & Quality", "Documents & Notes", "Review & Submit"];
const CHANNELS = ["Email", "Portal", "WhatsApp", "Phone", "In Person", "Post"];
const TERMS = ["Net 30", "Net 45", "Net 60", "Net 90", "50% Advance + 50% on Delivery", "LC at Sight", "Cash on Delivery"];

function daysFor(label: string) {
  const match = /Net\s+(\d+)/i.exec(label);
  return match ? Number(match[1]) : 0;
}

function blank(item?: ItemOption, uom?: UomOption): RfqLine {
  return { itemId: item?.id || "", uomId: item?.uomId || uom?.id || "", qty: 1, description: item?.name || "", drawingNumber: "", drawingRevision: "", material: "", notes: "" };
}

function text(value: unknown) {
  return typeof value === "string" ? value : "";
}

export function RfqWizard() {
  const { id } = useParams();
  const navigate = useNavigate();
  const { user } = useCustomer();
  const existing = useLoad(() => id ? salesApi.rfq(id) : Promise.resolve(null), [id]);
  const customers = useLoad(() => salesApi.customers(), []);
  const items = useLoad(() => salesApi.items(), []);
  const uoms = useLoad(() => salesApi.uoms(), []);
  const [step, setStep] = useState(0);
  const [customerId, setCustomerId] = useState("");
  const [receivedVia, setReceivedVia] = useState("Email");
  const [receivedDate, setReceivedDate] = useState(new Date().toISOString().slice(0, 10));
  const [requiredDate, setRequiredDate] = useState("");
  const [paymentLabel, setPaymentLabel] = useState("Net 30");
  const [deliveryTerms, setDeliveryTerms] = useState("");
  const [notes, setNotes] = useState("");
  const [customerRef, setCustomerRef] = useState("");
  const [lines, setLines] = useState<RfqLine[]>([blank()]);
  const [standards, setStandards] = useState("");
  const [inspection, setInspection] = useState("");
  const [surface, setSurface] = useState("");
  const [heat, setHeat] = useState("");
  const [packaging, setPackaging] = useState("");
  const [traceability, setTraceability] = useState(false);
  const [specialNotes, setSpecialNotes] = useState("");
  const [documentNotes, setDocumentNotes] = useState("");
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    const rfq = existing.data;
    if (!rfq) return;
    const technical = rfq.technicalRequirements ?? {};
    setCustomerId(rfq.customerId);
    setReceivedVia(rfq.receivedVia || "Email");
    setReceivedDate(rfq.receivedDate || new Date().toISOString().slice(0, 10));
    setRequiredDate(rfq.requiredDate || "");
    setPaymentLabel(text(technical.paymentTermsLabel) || (rfq.paymentTerms ? `Net ${rfq.paymentTerms}` : "Net 30"));
    setDeliveryTerms(rfq.deliveryTerms || "");
    setNotes(rfq.notes || "");
    setCustomerRef(rfq.customerRef || "");
    setLines(rfq.lines.length ? rfq.lines : [blank()]);
    setStandards(text(technical.standards));
    setInspection(text(technical.inspectionLevel));
    setSurface(text(technical.surfaceFinish));
    setHeat(text(technical.heatTreatment));
    setPackaging(text(technical.packagingRequirements));
    setTraceability(technical.traceability === true);
    setSpecialNotes(text(technical.specialNotes));
    setDocumentNotes(text(technical.documentNotes));
  }, [existing.data]);

  if (existing.loading || customers.loading || items.loading || uoms.loading) return <LoadingState label="Loading RFQ" />;
  if (existing.error) return <ErrorState body={existing.error} />;

  const customer = (customers.data ?? []).find((row) => row.id === (customerId || existing.data?.customerId));
  function patchLine(index: number, patch: Partial<RfqLine>) {
    setLines(lines.map((line, lineIndex) => lineIndex === index ? { ...line, ...patch } : line));
  }

  function next() {
    if (step === 0 && !(customerId || id)) { setError("Choose the customer."); return; }
    if (step === 1 && lines.some((line) => !line.itemId || !line.uomId || !line.drawingNumber || !line.description || line.qty <= 0)) {
      setError("Each line needs an item, drawing number, description, unit and quantity.");
      return;
    }
    setError("");
    setStep(step + 1);
  }

  async function submit() {
    setBusy(true);
    setError("");
    const technicalRequirements = {
      standards, inspectionLevel: inspection, surfaceFinish: surface, heatTreatment: heat,
      packagingRequirements: packaging, traceability, specialNotes, documentNotes, paymentTermsLabel: paymentLabel,
    };
    try {
      const saved = await salesApi.saveRfq({
        customerId: customerId || existing.data?.customerId,
        requiredDate: requiredDate || null,
        receivedDate,
        receivedVia,
        customerRef,
        paymentTerms: daysFor(paymentLabel),
        deliveryTerms,
        notes,
        technicalRequirements,
        lines: lines.map((line) => ({ ...line, qty: Number(line.qty), targetPrice: line.targetPrice ? Number(line.targetPrice) : null })),
      }, id);
      navigate(`/sales/rfq/${saved.id}`);
    } catch (caught) {
      setError(caught instanceof ApiError || caught instanceof Error ? caught.message : "The RFQ could not be saved");
      setBusy(false);
    }
  }

  return (
    <div className="p-6 max-w-5xl space-y-4">
      <button type="button" className="text-sm font-semibold text-[var(--brand)]" onClick={() => navigate("/sales?tab=rfqs")}>Customer RFQs</button>
      <h1 className="text-lg font-bold">{id ? "Edit Customer RFQ" : "New Customer RFQ"}</h1>
      <div className="flex flex-wrap gap-2">
        {STEPS.map((label, index) => <span key={label} className="text-xs font-semibold px-2 py-1 rounded-full" style={{ background: index === step ? "var(--brand-fill)" : "var(--surface-2)", color: index === step ? "#fff" : "var(--text-2)" }}>{index + 1}. {label}</span>)}
      </div>
      {step === 0 && <section className="rounded-xl border p-5 grid md:grid-cols-2 gap-4" style={{ borderColor: "var(--border)" }}>
        <SelectField label="Customer *" value={customerId} disabled={!!id} onChange={(event) => setCustomerId(event.target.value)}>
          <option value="">Select customer</option>
          {(customers.data ?? []).filter((row) => row.status !== "inactive").map((row) => <option key={row.id} value={row.id}>{row.name}</option>)}
        </SelectField>
        <SelectField label="Received via" value={receivedVia} onChange={(event) => setReceivedVia(event.target.value)}>{CHANNELS.map((channel) => <option key={channel}>{channel}</option>)}</SelectField>
        <TextField label="Received date" type="date" value={receivedDate} onChange={(event) => setReceivedDate(event.target.value)} />
        <TextField label="Required by" type="date" value={requiredDate} onChange={(event) => setRequiredDate(event.target.value)} />
        <TextField label="Customer reference" value={customerRef} onChange={(event) => setCustomerRef(event.target.value)} />
        <SelectField label="Payment terms" value={paymentLabel} onChange={(event) => setPaymentLabel(event.target.value)}>{TERMS.map((term) => <option key={term}>{term}</option>)}</SelectField>
        <TextField label="Delivery terms" value={deliveryTerms} onChange={(event) => setDeliveryTerms(event.target.value)} />
        <TextField label="Internal notes" value={notes} onChange={(event) => setNotes(event.target.value)} />
      </section>}
      {step === 1 && <section className="space-y-3">
        {lines.map((line, index) => <div key={index} className="rounded-xl border p-4 grid md:grid-cols-4 gap-3" style={{ borderColor: "var(--border)" }}>
          <SelectField label="Item *" value={line.itemId} onChange={(event) => {
            const item = (items.data ?? []).find((option) => option.id === event.target.value);
            patchLine(index, { itemId: event.target.value, uomId: item?.uomId || line.uomId, description: line.description || item?.name || "" });
          }}><option value="">Select item</option>{(items.data ?? []).map((item) => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}</SelectField>
          <TextField label="Drawing number *" value={line.drawingNumber || ""} onChange={(event) => patchLine(index, { drawingNumber: event.target.value })} />
          <TextField label="Revision" value={line.drawingRevision || ""} onChange={(event) => patchLine(index, { drawingRevision: event.target.value })} />
          <TextField label="Material" value={line.material || ""} onChange={(event) => patchLine(index, { material: event.target.value })} />
          <TextField label="Description *" value={line.description || ""} onChange={(event) => patchLine(index, { description: event.target.value })} />
          <TextField label="Quantity *" type="number" min="0.001" step="0.001" value={line.qty} onChange={(event) => patchLine(index, { qty: Number(event.target.value) })} />
          <SelectField label="UOM *" value={line.uomId} onChange={(event) => patchLine(index, { uomId: event.target.value })}>{(uoms.data ?? []).map((uom) => <option key={uom.id} value={uom.id}>{uom.symbol}</option>)}</SelectField>
          <TextField label="Target price" type="number" min="0" value={line.targetPrice ?? ""} onChange={(event) => patchLine(index, { targetPrice: event.target.value === "" ? undefined : Number(event.target.value) })} />
          <TextField label="Line notes" value={line.notes || ""} onChange={(event) => patchLine(index, { notes: event.target.value })} />
          {lines.length > 1 && <Button variant="ghost" onClick={() => setLines(lines.filter((_, lineIndex) => lineIndex !== index))}>Remove line</Button>}
        </div>)}
        <Button variant="ghost" onClick={() => setLines([...lines, blank(items.data?.[0], uoms.data?.[0])])}>Add line</Button>
      </section>}
      {step === 2 && <section className="rounded-xl border p-5 grid md:grid-cols-2 gap-4" style={{ borderColor: "var(--border)" }}>
        <TextField label="Standards" value={standards} onChange={(event) => setStandards(event.target.value)} />
        <SelectField label="Inspection level" value={inspection} onChange={(event) => setInspection(event.target.value)}>
          <option value="">Select</option>
          {["Visual", "Dimensional", "Material certificate", "Third party"].map((level) => <option key={level}>{level}</option>)}
        </SelectField>
        <TextField label="Surface finish" value={surface} onChange={(event) => setSurface(event.target.value)} />
        <TextField label="Heat treatment" value={heat} onChange={(event) => setHeat(event.target.value)} />
        <TextField label="Packaging" value={packaging} onChange={(event) => setPackaging(event.target.value)} />
        <label className="text-xs font-medium text-[var(--text-2)] flex items-center gap-2 mt-6"><input type="checkbox" checked={traceability} onChange={(event) => setTraceability(event.target.checked)} /> Traceability required</label>
        <TextField label="Special notes" value={specialNotes} onChange={(event) => setSpecialNotes(event.target.value)} />
      </section>}
      {step === 3 && <section className="rounded-xl border p-5 space-y-3" style={{ borderColor: "var(--border)" }}>
        <p className="text-sm text-[var(--text-2)]">Document files are not stored with the RFQ. Record the document note here; the drawing numbers stay on the lines.</p>
        <TextField label="Document notes" value={documentNotes} onChange={(event) => setDocumentNotes(event.target.value)} />
      </section>}
      {step === 4 && <section className="rounded-xl border p-5 space-y-2 text-sm" style={{ borderColor: "var(--border)" }}>
        <p><strong>Customer</strong> {customer?.name || existing.data?.customerName}</p>
        <p><strong>Owner</strong> {existing.data?.ownerName || user?.displayName || user?.email || "—"}</p>
        <p><strong>Received</strong> {receivedVia} · {receivedDate}</p>
        <p><strong>Required by</strong> {requiredDate || "—"} · <strong>Payment</strong> {paymentLabel} · <strong>Delivery</strong> {deliveryTerms || "—"}</p>
        <p><strong>Lines</strong> {lines.length} · <strong>Standards</strong> {standards || "—"} · <strong>Inspection</strong> {inspection || "—"}</p>
        <p><strong>Document notes</strong> {documentNotes || "—"}</p>
      </section>}
      {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
      <div className="flex justify-between">
        <Button variant="ghost" onClick={() => step === 0 ? navigate("/sales?tab=rfqs") : setStep(step - 1)}>{step === 0 ? "Cancel" : "Back"}</Button>
        {step < STEPS.length - 1 ? <Button onClick={next}>Continue</Button> : <Button onClick={submit} disabled={busy}>{busy ? "Saving" : id ? "Save RFQ" : "Submit RFQ"}</Button>}
      </div>
    </div>
  );
}
