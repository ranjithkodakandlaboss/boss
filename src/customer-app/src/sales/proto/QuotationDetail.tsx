// @ts-nocheck
import React, { useState, useMemo } from 'react';
import { useParams, useNavigate, Link, useSearchParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle, XCircle, FileText, Send, RotateCcw, Copy, AlertTriangle, Layers, Pencil } from 'lucide-react';
import { useStore } from './runtime';
import StatusChip from '../../components/shared/StatusChip';
import type { CustomerPO, CustomerQuotation, CustomerQuotationLine, POLine } from './types';
import DocumentActions from './workflow/DocumentActions';
import RelatedRecordsPanel from './workflow/RelatedRecordsPanel';
import RecordHistoryPanel from './workflow/RecordHistoryPanel';
import { useReturnNavigation } from './useReturnNavigation';
import LinkedDocumentsSection from './workflow/LinkedDocumentsSection';

const fmt = (d: string | null | undefined) => { if (!d) return '—'; const dt = new Date(d); return isNaN(dt.getTime()) ? '—' : dt.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }); };
const fmtMoney = (n: number) => `₹${n.toLocaleString('en-IN')}`;

export default function QuotationDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { customers, quotations, quotationLines, rfqs, customerPos, currentUser, updateQuotation, reviseQuotation, convertQuotationToPO, updateRfq, showToast } = useStore();
  const [confirming, setConfirming] = useState<'submit' | 'accept' | 'convert' | 'decline' | null>(null);
  const [reviseModal, setReviseModal] = useState(false);
  const [reviseDiscount, setReviseDiscount] = useState('0');
  const [reviseNotes, setReviseNotes] = useState('');
  const [reviseLines, setReviseLines] = useState<{ id: string; productName: string; drawingNumber: string; drawingRevision: string; requiredDate: string; uom: string; orderedQty: string; unitPrice: string }[]>([]);
  const [reviseError, setReviseError] = useState('');

  const [editDraftModal, setEditDraftModal] = useState(false);
  const [editDiscount, setEditDiscount] = useState('0');
  const [editNotes, setEditNotes] = useState('');
  const [editLines, setEditLines] = useState<{ id: string; productName: string; drawingNumber: string; drawingRevision: string; requiredDate: string; uom: string; orderedQty: string; unitPrice: string }[]>([]);
  const [editValidDays, setEditValidDays] = useState('30');
  const [editPaymentTerms, setEditPaymentTerms] = useState('Net 30 days');
  const [editDeliveryTerms, setEditDeliveryTerms] = useState('Ex-Works / By Road · 2-3 weeks');
  const [editTaxRatePct, setEditTaxRatePct] = useState(18);

  const [overrideReason, setOverrideReason] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const quotation = quotations.find(q => q.id === id);
  const rfq = rfqs.find(r => r.id === quotation?.rfqId);
  const { goBack, originUrl } = useReturnNavigation('/sales?tab=quotations');

  const lines = useMemo(() => quotationLines.filter(l => l.quotationId === id), [quotationLines, id]);

  if (!quotation) return (
    <div className="p-8 text-center text-sm text-[var(--text-2)]">
      Quotation not found. <Link to="/sales" className="text-[var(--brand)] underline">Back to Sales</Link>
    </div>
  );

  const parentQuote = quotation.parentQuotationId ? quotations.find(q => q.id === quotation.parentQuotationId) : undefined;
  const childRevisions = quotations.filter(q => q.parentQuotationId === quotation.id);
  const existingPO = customerPos.find(p => p.quotationId === quotation.id || (p as any).quotationRef === quotation.quotationNumber);

  const customerActive = customers.some(c => c.id === quotation.customerId && c.status === 'active');
  const canEditDraft = customerActive && quotation.status === 'draft' && ['owner', 'sales'].includes(currentUser.role);
  const canSubmit = customerActive && quotation.status === 'draft' && ['owner', 'sales'].includes(currentUser.role);
  const canAccept = customerActive && quotation.status === 'submitted' && ['owner', 'sales'].includes(currentUser.role);
  const activeRevisionExists = childRevisions.some(r => r.status !== 'draft');
  const canRevise = customerActive && quotation.status === 'submitted' && !existingPO && !activeRevisionExists && ['owner', 'sales'].includes(currentUser.role);
  const canConvert = customerActive && quotation.status === 'accepted' && !existingPO && !activeRevisionExists && ['owner', 'sales'].includes(currentUser.role);

  const handleReviseQuotation = async () => {
    if (!reviseNotes.trim()) {
      setReviseError('Revision reason is mandatory to issue a new revision.');
      return;
    }
    setReviseError('');
    const disc = Number(reviseDiscount) || 0;
    const taxRate = Number(editTaxRatePct) || 0;
    const revisedLines: CustomerQuotationLine[] = lines.map(l => {
      const edit = reviseLines.find(r => r.id === l.id);
      if (!edit) return l;
      const qty = Number(edit.orderedQty) || l.orderedQty;
      const price = Number(edit.unitPrice) ?? l.unitPrice;
      return { ...l, orderedQty: qty, unitPrice: price, value: qty * price, drawingNumber: edit.drawingNumber, drawingRevision: edit.drawingRevision, requiredDate: edit.requiredDate };
    });
    const subtotal = revisedLines.reduce((s, l) => s + l.value, 0);
    const taxable = Math.max(0, subtotal - disc);
    const taxAmount = Math.round(taxable * taxRate / 100);
    const totalValue = Math.round(taxable + taxAmount);
    const nextQ: CustomerQuotation = { ...quotation, discountAmount: disc, taxRatePct: taxRate, taxAmount, notes: reviseNotes.trim(), status: 'draft', totalValue, paymentTerms: editPaymentTerms, deliveryTerms: editDeliveryTerms };
    const saved = await reviseQuotation(quotation.id, nextQ, revisedLines);
    setReviseModal(false);
    if (saved) navigate(`/sales/quotation/${saved.id}`, { state: { from: originUrl } });
  };

  const handleSaveDraft = async () => {
    const disc = Number(editDiscount) || 0;
    const taxRate = Number(editTaxRatePct) || 0;
    const updatedLines: CustomerQuotationLine[] = lines.map(l => {
      const edit = editLines.find(r => r.id === l.id);
      if (!edit) return l;
      const qty = Number(edit.orderedQty) || l.orderedQty;
      const price = Number(edit.unitPrice) ?? l.unitPrice;
      return { ...l, orderedQty: qty, unitPrice: price, value: qty * price, drawingNumber: edit.drawingNumber, drawingRevision: edit.drawingRevision, requiredDate: edit.requiredDate };
    });
    const subtotal = updatedLines.reduce((s, l) => s + l.value, 0);
    const taxable = Math.max(0, subtotal - disc);
    const taxAmount = Math.round(taxable * taxRate / 100);
    const totalValue = Math.round(taxable + taxAmount);
    const today = new Date().toISOString().slice(0, 10);
    const validUntilDate = new Date(today);
    validUntilDate.setDate(validUntilDate.getDate() + (parseInt(editValidDays) || 30));
    const validUntil = validUntilDate.toISOString().slice(0, 10);
    await updateQuotation(quotation.id, {
      ...quotation,
      discountAmount: disc,
      taxRatePct: taxRate,
      taxAmount,
      totalValue,
      validUntil,
      paymentTerms: editPaymentTerms,
      deliveryTerms: editDeliveryTerms,
      notes: editNotes.trim() || quotation.notes,
      lines: updatedLines,
    } as any);
    setEditDraftModal(false);
  };

  const handleSubmit = async () => {
    if (submitting) return;
    setSubmitting(true);
    try {
      const saved = await updateQuotation(quotation.id, { status: 'submitted', notes: overrideReason });
      if (saved) setConfirming(null);
    } finally {
      setSubmitting(false);
    }
  };

  const handleAccept = async () => {
    const po = await convertQuotationToPO(quotation.id, { id: '', poNumber: '', customerId: quotation.customerId, customerName: quotation.customerName, acceptedDate: '', paymentTerms: quotation.paymentTerms ?? '', status: 'active', totalValue: quotation.totalValue, currency: 'INR', lineCount: lines.length, deliveryTerms: quotation.deliveryTerms ?? '' });
    setConfirming(null);
    if (po) navigate(`/sales/po/${po.id}`);
  };

  const handleDecision = async (status: 'rejected' | 'expired') => {
    await updateQuotation(quotation.id, { status, notes: quotation.notes });
  };

  const handleConvertToPO = async () => {
    const po = await convertQuotationToPO(quotation.id, { id: '', poNumber: '', customerId: quotation.customerId, customerName: quotation.customerName, acceptedDate: '', paymentTerms: quotation.paymentTerms ?? '', status: 'active', totalValue: quotation.totalValue, currency: 'INR', lineCount: lines.length, deliveryTerms: quotation.deliveryTerms ?? '', quotationId: quotation.id });
    setConfirming(null);
    if (po) navigate(`/sales/po/${po.id}`);
  };

  const statusColor: Record<string, string> = {
    draft: 'bg-gray-100 text-gray-600 border-gray-200',
    submitted: 'bg-blue-50 text-blue-700 border-blue-200',
    accepted: 'bg-green-50 text-green-700 border-green-200',
    rejected: 'bg-red-50 text-red-600 border-red-200',
    expired: 'bg-orange-50 text-orange-600 border-orange-200',
  };

  return (
    <div className="flex flex-col h-full overflow-auto">
      {/* Header */}
      <div className="flex-shrink-0 px-6 py-4 border-b" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
        <div className="flex items-start justify-between gap-4">
          <div className="flex items-center gap-3">
            <button onClick={() => goBack()} className="p-1.5 rounded-lg hover:bg-[var(--surface-2)]">
              <ArrowLeft size={18} className="text-[var(--text-2)]" />
            </button>
            <div>
              <div className="flex items-center gap-2">
                <span className="font-mono text-sm font-bold text-[var(--brand)]">{quotation.quotationNumber}</span>
                <StatusChip status={quotation.status} size="sm" />
                {childRevisions.length === 0 && quotation.status !== 'superseded' && (
                  <span className="text-[10px] px-1.5 py-0.5 rounded-full bg-blue-100 text-blue-700 font-semibold border border-blue-200">Latest</span>
                )}
              </div>
              <h1 className="text-lg font-bold text-[var(--text)] mt-0.5">{quotation.customerName}</h1>
              <p className="text-xs text-[var(--text-2)]">
                Quoted {fmt(quotation.quotationDate)} · Valid until {fmt(quotation.validUntil)} · Rev {quotation.revision ?? 1}
                {quotation.parentQuotationId && <span className="ml-2 text-[var(--text-3)]">↑ Revised from {parentQuote?.quotationNumber ?? 'prior version'}</span>}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 flex-wrap justify-end">
            <DocumentActions
              documentType="Customer Quotation"
              reference={quotation.quotationNumber}
              date={quotation.quotationDate}
              partyName={quotation.customerName}
              status={quotation.status}
              total={quotation.totalValue}
              revision={quotation.revision ?? quotation.version ?? 1}
              terms={[quotation.paymentTerms, quotation.deliveryTerms].filter((v): v is string => Boolean(v))}
              notes={quotation.notes}
              lines={lines.map(l => ({ description: l.productName, quantity: l.orderedQty, uom: l.uom, rate: l.unitPrice, amount: l.value }))}
              references={[
                quotation.isDirectQuotation ? 'Origin: Direct Quotation (no prior RFQ)' : quotation.rfqNumber ? `Originating RFQ: ${quotation.rfqNumber}` : '',
                quotation.parentQuotationId ? `Revised from earlier version` : '',
                `Revision: ${quotation.revision ?? quotation.version ?? 1}`,
              ].filter(Boolean)}
            />
            <p className="text-[11px] text-[var(--text-2)] max-w-[220px]">BOSS does not send email or WhatsApp. Download the PDF and send it outside BOSS.</p>
            {canEditDraft && (
              <button
                type="button"
                onClick={() => {
                  setEditDiscount(String(quotation.discountAmount ?? 0));
                  setEditNotes(quotation.notes ?? '');
                  setEditPaymentTerms(quotation.paymentTerms ?? 'Net 30 days');
                  setEditDeliveryTerms(quotation.deliveryTerms ?? 'Ex-Works / By Road · 2-3 weeks');
                  setEditTaxRatePct(quotation.taxRatePct ?? 18);
                  const validUntilDate = quotation.validUntil ? new Date(quotation.validUntil) : null;
                  const todayDate = new Date();
                  const diffDays = validUntilDate ? Math.round((validUntilDate.getTime() - todayDate.getTime()) / (1000 * 60 * 60 * 24)) : 30;
                  setEditValidDays(String(Math.max(1, diffDays)));
                  setEditLines(lines.map(l => ({ id: l.id, productName: l.productName, drawingNumber: l.drawingNumber ?? '', drawingRevision: l.drawingRevision ?? 'A', requiredDate: l.requiredDate ?? '', uom: l.uom, orderedQty: String(l.orderedQty), unitPrice: String(l.unitPrice) })));
                  setEditDraftModal(true);
                }}
                className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold border border-[var(--brand)] text-[var(--brand)] hover:bg-[var(--surface-2)]"
              >
                <Pencil size={14} /> Edit Quote
              </button>
            )}
            {canSubmit && (
              <button onClick={() => { setOverrideReason(''); setConfirming('submit'); }} className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold border" style={{ borderColor: 'var(--brand)', color: 'var(--brand)' }}>
                <Send size={14} /> Submit to Customer
              </button>
            )}
            {canAccept && (
              <button onClick={() => setConfirming('accept')} className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold text-white bg-green-600 hover:bg-green-700">
                <CheckCircle size={14} /> Mark Accepted
              </button>
            )}
            {quotation.status === 'submitted' && (
              <>
                <button type="button" onClick={() => setConfirming('decline')} className="px-3 py-2 rounded-lg text-sm font-semibold border" style={{ borderColor: 'var(--error)', color: 'var(--error)' }}>Decline</button>
                <button type="button" onClick={() => handleDecision('expired')} className="px-3 py-2 rounded-lg text-sm font-semibold border" style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}>No Response</button>
              </>
            )}
            {canRevise && (
              <button onClick={() => { setReviseDiscount(String(quotation.discountAmount ?? 0)); setReviseNotes(quotation.notes ?? ''); setEditPaymentTerms(quotation.paymentTerms ?? 'Net 30 days'); setEditDeliveryTerms(quotation.deliveryTerms ?? 'Ex-Works / By Road · 2-3 weeks'); setEditTaxRatePct(quotation.taxRatePct ?? 18); setReviseLines(lines.map(l => ({ id: l.id, productName: l.productName, drawingNumber: l.drawingNumber ?? '', drawingRevision: l.drawingRevision ?? 'A', requiredDate: l.requiredDate ?? '', uom: l.uom, orderedQty: String(l.orderedQty), unitPrice: String(l.unitPrice) }))); setReviseModal(true); }} className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold border border-[var(--border)] text-[var(--text)] hover:bg-[var(--surface-2)]">
                <Copy size={14} /> Revise Quotation
              </button>
            )}
            {canConvert && (
              <button onClick={() => setConfirming('convert')} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: 'var(--brand-fill)' }}>
                <FileText size={14} /> Convert to Customer PO
              </button>
            )}
          </div>
        </div>

        <div className="mt-3">
          <RelatedRecordsPanel
            originNote={
              quotation.isDirectQuotation
                ? 'Origin: Direct Customer Quotation (created without prior RFQ)'
                : parentQuote
                  ? `Revision ${quotation.revision ?? quotation.version ?? 2} superseding ${parentQuote.quotationNumber}`
                  : undefined
            }
            records={[
              ...(rfq ? [{ relationship: 'Upstream' as const, typeLabel: 'Customer RFQ', reference: rfq.rfqNumber, to: `/sales/rfq/${rfq.id}`, status: rfq.status, state: { from: originUrl } }] : []),
              ...(parentQuote ? [{ relationship: 'Upstream' as const, typeLabel: 'Prior Version', reference: `${parentQuote.quotationNumber} (Rev ${parentQuote.revision ?? 1})`, to: `/sales/quotation/${parentQuote.id}`, status: parentQuote.status, state: { from: originUrl } }] : []),
              ...childRevisions.map(c => ({ relationship: 'Downstream' as const, typeLabel: 'Newer Revision', reference: `${c.quotationNumber} (Rev ${c.revision ?? 2})`, to: `/sales/quotation/${c.id}`, status: c.status, state: { from: originUrl } })),
              ...(existingPO ? [{ relationship: 'Downstream' as const, typeLabel: 'Customer PO', reference: existingPO.poNumber, to: `/sales/po/${existingPO.id}`, status: existingPO.status, state: { from: originUrl } }] : []),
            ]}
          />
        </div>

        {editDraftModal && (
          <div className="fixed inset-0 bg-black/40 z-50 flex items-start justify-center p-4 overflow-y-auto">
            <div className="bg-[var(--surface)] rounded-2xl border p-6 max-w-4xl w-full shadow-2xl space-y-5 border-[var(--border)] my-6">
              <div className="flex items-center justify-between">
                <h3 className="text-base font-bold text-[var(--text)]">Edit Draft Quote — {quotation.quotationNumber}</h3>
                <span className="text-[11px] px-2 py-0.5 rounded-full bg-blue-100 text-blue-700 border border-blue-200">Draft</span>
              </div>
              {/* Quotation Details */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Valid For (Days)</label>
                  <input type="number" min={1} className="eoss-input w-full font-mono" value={editValidDays} onChange={e => setEditValidDays(e.target.value)} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Tax Rate (%)</label>
                  <input type="number" min={0} max={100} step={0.01} className="eoss-input w-full font-mono" value={editTaxRatePct} onChange={e => setEditTaxRatePct(Number(e.target.value))} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Discount (₹)</label>
                  <input type="number" min={0} className="eoss-input w-full font-mono" value={editDiscount} onChange={e => setEditDiscount(e.target.value)} />
                </div>
                <div className="flex items-end pb-0.5">
                  <div className="text-xs text-[var(--text-2)]">Net Total: <span className="font-mono font-semibold text-[var(--text)]">₹{Math.max(0, editLines.reduce((s, r) => s + (Number(r.orderedQty) || 0) * (Number(r.unitPrice) || 0), 0) - (Number(editDiscount) || 0)).toLocaleString('en-IN')}</span></div>
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Payment Terms</label>
                  <input type="text" className="eoss-input w-full" value={editPaymentTerms} onChange={e => setEditPaymentTerms(e.target.value)} placeholder="e.g. Net 30 days" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Delivery Terms</label>
                  <input type="text" className="eoss-input w-full" value={editDeliveryTerms} onChange={e => setEditDeliveryTerms(e.target.value)} placeholder="e.g. Ex-Works" />
                </div>
              </div>
              {/* Line Items */}
              <div>
                <div className="text-xs font-semibold text-[var(--text-2)] mb-2">Line Items</div>
                <div className="overflow-x-auto rounded-lg border border-[var(--border)]">
                  <table className="w-full text-xs">
                    <thead className="bg-[var(--surface-2)]">
                      <tr>
                        <th className="text-left p-2 text-[var(--text-3)]">#</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Product</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Drawing No.</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Rev</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Required By</th>
                        <th className="text-right p-2 text-[var(--text-3)]">Qty</th>
                        <th className="text-left p-2 text-[var(--text-3)]">UOM</th>
                        <th className="text-right p-2 text-[var(--text-3)]">Unit Price (₹)</th>
                        <th className="text-right p-2 text-[var(--text-3)]">Line Total</th>
                      </tr>
                    </thead>
                    <tbody>
                      {editLines.map((el, i) => {
                        const qty = Number(el.orderedQty) || 0;
                        const price = Number(el.unitPrice) || 0;
                        return (
                          <tr key={el.id} className="border-t border-[var(--border)]">
                            <td className="p-2 text-[var(--text-3)]">{i + 1}</td>
                            <td className="p-2 font-medium text-[var(--text)]">{el.productName}</td>
                            <td className="p-2">
                              <input type="text" className="eoss-input w-24 text-xs font-mono" value={el.drawingNumber} onChange={e => setEditLines(prev => prev.map(r => r.id === el.id ? { ...r, drawingNumber: e.target.value } : r))} />
                            </td>
                            <td className="p-2">
                              <input type="text" className="eoss-input w-14 text-xs font-mono" value={el.drawingRevision} onChange={e => setEditLines(prev => prev.map(r => r.id === el.id ? { ...r, drawingRevision: e.target.value } : r))} />
                            </td>
                            <td className="p-2">
                              <input type="date" className="eoss-input text-xs" value={el.requiredDate} onChange={e => setEditLines(prev => prev.map(r => r.id === el.id ? { ...r, requiredDate: e.target.value } : r))} />
                            </td>
                            <td className="p-2 text-right">
                              <input type="number" min={0.01} step={0.01} className="eoss-input w-20 text-right font-mono text-xs" value={el.orderedQty} onChange={e => setEditLines(prev => prev.map(r => r.id === el.id ? { ...r, orderedQty: e.target.value } : r))} />
                            </td>
                            <td className="p-2 text-[var(--text-2)]">{el.uom}</td>
                            <td className="p-2 text-right">
                              <input type="number" min={0} step={0.01} className="eoss-input w-28 text-right font-mono text-xs" value={el.unitPrice} onChange={e => setEditLines(prev => prev.map(r => r.id === el.id ? { ...r, unitPrice: e.target.value } : r))} />
                            </td>
                            <td className="p-2 text-right font-mono font-semibold text-[var(--text)]">₹{(qty * price).toLocaleString('en-IN')}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
              <div>
                <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Notes</label>
                <textarea className="eoss-input w-full" rows={2} value={editNotes} onChange={e => setEditNotes(e.target.value)} placeholder="Quotation terms and notes…" />
              </div>
              <div className="flex gap-2 justify-end">
                <button type="button" onClick={() => setEditDraftModal(false)} className="px-4 py-2 rounded-lg text-xs border border-[var(--border)]">Cancel</button>
                <button type="button" onClick={handleSaveDraft} className="btn-primary text-xs">Save Changes</button>
              </div>
            </div>
          </div>
        )}

        {reviseModal && (
          <div className="fixed inset-0 bg-black/40 z-50 flex items-start justify-center p-4 overflow-y-auto">
            <div className="bg-[var(--surface)] rounded-2xl border p-6 max-w-4xl w-full shadow-2xl space-y-5 border-[var(--border)] my-6">
              <div className="flex items-center justify-between">
                <h3 className="text-base font-bold text-[var(--text)]">Revise {quotation.quotationNumber}</h3>
                <span className="text-[11px] text-[var(--text-3)]">Rev {quotation.revision ?? 1} → Rev {(quotation.revision ?? 1) + 1}</span>
              </div>
              <p className="text-xs text-[var(--text-2)]">Creates a new linked revision and marks {quotation.quotationNumber} as <span className="font-mono">superseded</span>.</p>
              {/* Quotation Details */}
              <div className="grid grid-cols-2 md:grid-cols-4 gap-3">
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Tax Rate (%)</label>
                  <input type="number" min={0} max={100} step={0.01} className="eoss-input w-full font-mono" value={editTaxRatePct} onChange={e => setEditTaxRatePct(Number(e.target.value))} />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Discount (₹)</label>
                  <input type="number" min={0} className="eoss-input w-full font-mono" value={reviseDiscount} onChange={e => setReviseDiscount(e.target.value)} />
                </div>
                <div className="flex items-end pb-0.5">
                  <div className="text-xs text-[var(--text-2)]">Net Total: <span className="font-mono font-semibold text-[var(--text)]">₹{Math.max(0, reviseLines.reduce((s, r) => s + (Number(r.orderedQty) || 0) * (Number(r.unitPrice) || 0), 0) - (Number(reviseDiscount) || 0)).toLocaleString('en-IN')}</span></div>
                </div>
              </div>
              <div className="grid grid-cols-1 md:grid-cols-2 gap-3">
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Payment Terms</label>
                  <input type="text" className="eoss-input w-full" value={editPaymentTerms} onChange={e => setEditPaymentTerms(e.target.value)} placeholder="e.g. Net 30 days" />
                </div>
                <div>
                  <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Delivery Terms</label>
                  <input type="text" className="eoss-input w-full" value={editDeliveryTerms} onChange={e => setEditDeliveryTerms(e.target.value)} placeholder="e.g. Ex-Works" />
                </div>
              </div>
              {/* Line items */}
              <div>
                <div className="text-xs font-semibold text-[var(--text-2)] mb-2">Line Items</div>
                <div className="overflow-x-auto rounded-lg border border-[var(--border)]">
                  <table className="w-full text-xs">
                    <thead className="bg-[var(--surface-2)]">
                      <tr>
                        <th className="text-left p-2 text-[var(--text-3)]">#</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Product</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Drawing No.</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Rev</th>
                        <th className="text-left p-2 text-[var(--text-3)]">Required By</th>
                        <th className="text-right p-2 text-[var(--text-3)]">Qty</th>
                        <th className="text-left p-2 text-[var(--text-3)]">UOM</th>
                        <th className="text-right p-2 text-[var(--text-3)]">Unit Price (₹)</th>
                        <th className="text-right p-2 text-[var(--text-3)]">Line Total</th>
                      </tr>
                    </thead>
                    <tbody>
                      {reviseLines.map((rl, i) => {
                        const qty = Number(rl.orderedQty) || 0;
                        const price = Number(rl.unitPrice) || 0;
                        return (
                          <tr key={rl.id} className="border-t border-[var(--border)]">
                            <td className="p-2 text-[var(--text-3)]">{i + 1}</td>
                            <td className="p-2 font-medium text-[var(--text)]">{rl.productName}</td>
                            <td className="p-2">
                              <input type="text" className="eoss-input w-24 text-xs font-mono" value={rl.drawingNumber} onChange={e => setReviseLines(prev => prev.map(r => r.id === rl.id ? { ...r, drawingNumber: e.target.value } : r))} />
                            </td>
                            <td className="p-2">
                              <input type="text" className="eoss-input w-14 text-xs font-mono" value={rl.drawingRevision} onChange={e => setReviseLines(prev => prev.map(r => r.id === rl.id ? { ...r, drawingRevision: e.target.value } : r))} />
                            </td>
                            <td className="p-2">
                              <input type="date" className="eoss-input text-xs" value={rl.requiredDate} onChange={e => setReviseLines(prev => prev.map(r => r.id === rl.id ? { ...r, requiredDate: e.target.value } : r))} />
                            </td>
                            <td className="p-2 text-right">
                              <input type="number" min={0.01} step={0.01} className="eoss-input w-20 text-right font-mono text-xs" value={rl.orderedQty} onChange={e => setReviseLines(prev => prev.map(r => r.id === rl.id ? { ...r, orderedQty: e.target.value } : r))} />
                            </td>
                            <td className="p-2 text-[var(--text-2)]">{rl.uom}</td>
                            <td className="p-2 text-right">
                              <input type="number" min={0} step={0.01} className="eoss-input w-28 text-right font-mono text-xs" value={rl.unitPrice} onChange={e => setReviseLines(prev => prev.map(r => r.id === rl.id ? { ...r, unitPrice: e.target.value } : r))} />
                            </td>
                            <td className="p-2 text-right font-mono font-semibold text-[var(--text)]">₹{(qty * price).toLocaleString('en-IN')}</td>
                          </tr>
                        );
                      })}
                    </tbody>
                  </table>
                </div>
              </div>
              <div>
                <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Revision Reason * (Mandatory)</label>
                <textarea
                  required
                  className="eoss-input w-full"
                  rows={2}
                  value={reviseNotes}
                  onChange={e => { setReviseNotes(e.target.value); setReviseError(''); }}
                  placeholder="State the mandatory reason for this revision (e.g. customer negotiated price, quantity changed)…"
                />
                {reviseError && <p className="text-xs text-[var(--error)] mt-1 font-semibold">{reviseError}</p>}
              </div>
              <div className="flex gap-2 justify-end">
                <button type="button" onClick={() => setReviseModal(false)} className="px-4 py-2 rounded-lg text-xs border border-[var(--border)]">Cancel</button>
                <button type="button" onClick={handleReviseQuotation} className="btn-primary text-xs">Issue New Revision →</button>
              </div>
            </div>
          </div>
        )}
      </div>

      {/* Summary row */}
      <div className="flex-shrink-0 px-6 py-3 border-b grid grid-cols-2 md:grid-cols-4 gap-3" style={{ borderColor: 'var(--border)', background: 'var(--surface-2)' }}>
        {[
          { label: 'Total Value', value: fmtMoney(quotation.totalValue), mono: true },
          { label: 'Lines', value: `${lines.length}` },
          { label: 'Prepared By', value: quotation.owner },
          { label: 'Currency', value: quotation.currency },
        ].map((c, i) => (
          <div key={i}>
            <div className="text-[11px] text-[var(--text-3)]">{c.label}</div>
            <div className={`text-sm font-semibold text-[var(--text)] mt-0.5 ${c.mono ? 'font-mono' : ''}`}>{c.value}</div>
          </div>
        ))}
      </div>

      {/* Lines table */}
      <div className="flex-1 overflow-auto p-6">
        <div className="overflow-x-auto rounded-xl border" style={{ borderColor: 'var(--border)' }}>
          <table className="eoss-table">
            <thead>
              <tr>
                <th>#</th>
                <th>Drawing No.</th>
                <th>Rev</th>
                <th>Description</th>
                <th>Qty</th>
                <th>UOM</th>
                <th>Unit Price</th>
                <th>Line Value</th>
                <th>Required By</th>
              </tr>
            </thead>
            <tbody>
              {lines.map((l, i) => (
                <tr key={l.id}>
                  <td className="font-mono text-xs text-[var(--text-3)]">{i + 1}</td>
                  <td className="font-mono text-xs font-semibold text-[var(--brand)]">{l.drawingNumber}</td>
                  <td className="font-mono text-xs">{l.drawingRevision}</td>
                  <td className="text-sm text-[var(--text)]">{l.productName}</td>
                  <td className="font-mono text-sm text-left">{l.orderedQty}</td>
                  <td className="text-xs text-[var(--text-2)]">{l.uom}</td>
                  <td className="font-mono text-sm text-right">{fmtMoney(l.unitPrice)}</td>
                  <td className="font-mono text-sm text-right font-semibold">{fmtMoney(l.value)}</td>
                  <td className="font-mono text-xs whitespace-nowrap text-[var(--text-2)]">{fmt(l.requiredDate)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              {quotation.subtotal != null && quotation.subtotal !== quotation.totalValue && (
                <tr>
                  <td colSpan={7} className="text-right text-xs text-[var(--text-2)] pr-4">Subtotal</td>
                  <td className="font-mono text-sm text-[var(--text)]">{fmtMoney(quotation.subtotal)}</td>
                  <td />
                </tr>
              )}
              {quotation.taxAmount != null && quotation.taxAmount > 0 && (
                <tr>
                  <td colSpan={7} className="text-right text-xs text-[var(--text-2)] pr-4">GST</td>
                  <td className="font-mono text-sm text-[var(--text)]">{fmtMoney(quotation.taxAmount)}</td>
                  <td />
                </tr>
              )}
              {quotation.discountAmount != null && quotation.discountAmount > 0 && (
                <tr>
                  <td colSpan={7} className="text-right text-xs text-[var(--text-2)] pr-4">Discount</td>
                  <td className="font-mono text-sm text-red-600">−{fmtMoney(quotation.discountAmount)}</td>
                  <td />
                </tr>
              )}
              <tr>
                <td colSpan={7} className="text-right text-xs font-bold text-[var(--text-2)] pr-4">Total</td>
                <td className="font-mono text-base font-bold text-[var(--text)]">{fmtMoney(quotation.totalValue)}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>

        {/* Persisted server-side costing snapshot */}
        <div className="mt-4 rounded-xl border p-4 space-y-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h4 className="text-xs font-bold uppercase tracking-wider text-[var(--text)] flex items-center gap-1.5">
                <Layers size={14} className="text-blue-500" /> Estimated Cost and Gross Margin
              </h4>
              <p className="text-[11px] text-[var(--text-3)]">
                {quotation.costing?.available
                  ? `Saved estimate · ${quotation.costing.valuationBasis}`
                  : 'Cost estimate unavailable; an approved BOM and valued inventory are required before submission.'}
              </p>
            </div>
            {quotation.costing?.available ? (
              <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold border ${
                quotation.costing.requiresApproval ? 'bg-amber-100 text-amber-900 border-amber-300' : 'bg-emerald-100 text-emerald-900 border-emerald-300'
              }`}>
                {quotation.costing.grossMarginPct?.toFixed(2) ?? '—'}% Gross Margin
              </span>
            ) : null}
          </div>

          {quotation.costing?.available && (
            <div className="grid grid-cols-2 md:grid-cols-4 gap-3 p-3 rounded-xl bg-[var(--surface-2)] text-xs">
              {[
                ['Net Revenue', quotation.costing.netRevenue],
                ['Material', quotation.costing.materialCost],
                ['Labour', quotation.costing.labourCost],
                ['Machine', quotation.costing.machineCost],
                ['Overhead', quotation.costing.overheadCost],
                ['Consumables', quotation.costing.consumablesCost],
                ['Packaging', quotation.costing.packagingCost],
                ['Transport', quotation.costing.transportCost],
                ['Estimated Total Cost', quotation.costing.totalCost],
                ['Gross Profit', quotation.costing.grossProfit],
              ].map(([label, amount]) => (
                <div key={label as string}>
                  <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">{label}</span>
                  <span className="font-mono font-bold text-sm text-[var(--text)]">{fmtMoney(amount as number)}</span>
                </div>
              ))}
              <div>
                <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">Target Margin</span>
                <span className="font-mono font-bold text-sm text-[var(--text)]">{quotation.costing.targetMarginPct}%</span>
              </div>
            </div>
          )}

          {quotation.costing?.unavailableReasons.map((reason) => (
            <p key={reason} className="text-xs text-amber-700">{reason}</p>
          ))}
          {quotation.costing?.requiresApproval && (
            <div className="p-3 rounded-lg bg-amber-50 border border-amber-200 text-amber-900 text-xs flex items-center gap-2">
              <AlertTriangle size={15} className="text-amber-600 flex-shrink-0" />
              <span>
                Margin is below the configured approval threshold. An owner or operations manager must provide a reason to submit.
              </span>
            </div>
          )}

          {quotation.costOverrideReason && (
            <p className="text-xs text-[var(--text-2)] border-t pt-2" style={{ borderColor: 'var(--border)' }}>
              Approved override: {quotation.costOverrideReason}
            </p>
          )}
        </div>

        <div className="mt-4">
          <LinkedDocumentsSection entityType="Quotation" entityId={quotation.id} entityRef={quotation.quotationNumber} readOnly={!customerActive || quotation.status === 'cancelled' || !['owner', 'sales'].includes(currentUser.role)} />
        </div>
        {quotation.notes && (
          <div className="mt-4 rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
            <div className="text-xs font-bold text-[var(--text-3)] uppercase tracking-wider mb-2">Notes</div>
            <p className="text-sm text-[var(--text-2)]">{quotation.notes}</p>
          </div>
        )}

        <div className="mt-4 rounded-xl border p-4" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
          <div className="text-xs font-bold text-[var(--text-3)] uppercase tracking-wider mb-2">Decision and negotiation</div>
          {(quotation as CustomerQuotation & { decisionNote?: string; decidedAt?: string }).decisionNote && (
            <p className="text-sm text-[var(--text)] mb-2">{(quotation as CustomerQuotation & { decisionNote?: string }).decisionNote}</p>
          )}
          {(((quotation as CustomerQuotation & { negotiationHistory?: { id: string; round: number; partyType: string; note?: string; createdAt: string }[] }).negotiationHistory) ?? []).length === 0 ? (
            <p className="text-sm text-[var(--text-2)]">No negotiation rounds are recorded.</p>
          ) : ((quotation as CustomerQuotation & { negotiationHistory: { id: string; round: number; partyType: string; note?: string; createdAt: string }[] }).negotiationHistory).map((entry) => (
            <div key={entry.id} className="text-sm py-2 border-t" style={{ borderColor: 'var(--border)' }}>
              <span className="font-semibold">Round {entry.round}</span> · {entry.partyType} · {fmt(entry.createdAt)}
              {entry.note && <div className="text-[var(--text-2)]">{entry.note}</div>}
            </div>
          ))}
        </div>

        <div className="mt-5">
          <RecordHistoryPanel
            entityId={quotation.id}
            entityRef={quotation.quotationNumber}
            title="Quotation Activity & Audit Trail"
            subtitle="Drafting, revisions, submission, acceptance, and PO conversion"
          />
        </div>
      </div>

      {/* Confirm dialogs */}
      {confirming && (
        <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4">
          <div className="bg-[var(--surface)] rounded-2xl border p-6 max-w-sm w-full shadow-2xl" style={{ borderColor: 'var(--border)' }}>
            <h3 className="text-base font-bold text-[var(--text)] mb-2">
              {confirming === 'convert' ? 'Convert to Customer PO?' : confirming === 'submit' ? 'Submit Quotation to Customer?' : confirming === 'decline' ? 'Decline Quotation?' : 'Mark Quotation Accepted?'}
            </h3>
            <p className="text-sm text-[var(--text-2)] mb-5">
              {confirming === 'convert'
                ? `A new Customer PO will be created for ${fmtMoney(quotation.totalValue)} against ${quotation.quotationNumber}. This cannot be undone.`
                : confirming === 'submit'
                  ? `${quotation.quotationNumber} will be submitted to ${quotation.customerName}. This does not accept the quotation.`
                  : confirming === 'decline'
                    ? `${quotation.quotationNumber} will be declined. This action cannot be undone.`
                    : `${quotation.quotationNumber} will be marked as accepted by ${quotation.customerName}.`}
            </p>
            {confirming === 'submit' && quotation.costing?.requiresApproval && (
              <div className="mb-5">
                <label htmlFor="override-reason" className="block text-xs font-medium text-[var(--text-2)] mb-1">Reason for below-threshold approval <span className="text-[var(--error)]">*</span></label>
                <textarea id="override-reason" className="eoss-input w-full" rows={3} value={overrideReason}
                  onChange={(event) => setOverrideReason(event.target.value)}
                  placeholder="This quotation is below the cost threshold. Enter a justification to proceed." />
                {!overrideReason.trim() && (
                  <p className="text-xs text-[var(--error)] mt-1">Required — enter a justification to enable the Submit button.</p>
                )}
              </div>
            )}
            <div className="flex gap-3 justify-end">
              <button onClick={() => setConfirming(null)} className="px-4 py-2 rounded-lg text-sm border" style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}>Cancel</button>
              <button
                onClick={confirming === 'convert' ? handleConvertToPO : confirming === 'submit' ? handleSubmit : confirming === 'decline' ? () => { handleDecision('rejected'); setConfirming(null); } : handleAccept}
                disabled={submitting || confirming === 'submit' && quotation.costing?.requiresApproval && !overrideReason.trim()}
                className="px-4 py-2 rounded-lg text-sm font-semibold text-white"
                style={{ background: confirming === 'decline' ? 'var(--error, #DC2626)' : confirming === 'convert' ? 'var(--brand-fill)' : '#16a34a' }}
              >
                {confirming === 'convert' ? 'Create PO' : confirming === 'submit' ? 'Submit' : confirming === 'decline' ? 'Decline' : 'Accept'}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
