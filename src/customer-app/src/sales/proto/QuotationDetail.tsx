// @ts-nocheck
import React, { useState, useMemo } from 'react';
import { useParams, useNavigate, Link, useSearchParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle, XCircle, FileText, Send, RotateCcw, Copy, AlertTriangle, Layers } from 'lucide-react';
import { useStore } from './runtime';
import StatusChip from '../../components/shared/StatusChip';
import type { CustomerPO, CustomerQuotation, CustomerQuotationLine, POLine } from './types';
import DocumentActions from './workflow/DocumentActions';
import RelatedRecordsPanel from './workflow/RelatedRecordsPanel';
import RecordHistoryPanel from './workflow/RecordHistoryPanel';
import { useReturnNavigation } from './useReturnNavigation';

const fmt = (d: string) => d ? new Date(d).toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: 'numeric' }) : '—';
const fmtMoney = (n: number) => `₹${n.toLocaleString('en-IN')}`;

export default function QuotationDetail() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { quotations, quotationLines, rfqs, customerPos, currentUser, updateQuotation, reviseQuotation, convertQuotationToPO, updateRfq, showToast, boms } = useStore();
  const [confirming, setConfirming] = useState<'accept' | 'convert' | null>(null);
  const [reviseModal, setReviseModal] = useState(false);
  const [reviseDiscount, setReviseDiscount] = useState('0');
  const [reviseNotes, setReviseNotes] = useState('');

  const quotation = quotations.find(q => q.id === id);
  const rfq = rfqs.find(r => r.id === quotation?.rfqId);
  const { goBack, originUrl } = useReturnNavigation(rfq ? `/sales/rfq/${rfq.id}` : '/sales?tab=rfqs');

  const lines = useMemo(() => quotationLines.filter(l => l.quotationId === id), [quotationLines, id]);

  const bomStats = useMemo(() => {
    let totalBomCost = 0;
    let allLinesHaveBom = lines.length > 0;
    const lineDetails = lines.map(l => {
      const match = boms.find(b =>
        (l.productId && b.productId === l.productId) ||
        (l.drawingNumber && b.productCode && b.productCode.trim().toLowerCase() === l.drawingNumber.trim().toLowerCase()) ||
        (l.productName && b.productName && b.productName.trim().toLowerCase() === l.productName.trim().toLowerCase())
      );
      if (!match) {
        allLinesHaveBom = false;
        return { lineId: l.id, hasBom: false, unitCost: 0, lineCost: 0 };
      }
      const unitCost = match.totalMaterialCost;
      const lineCost = unitCost * (l.orderedQty || 1);
      totalBomCost += lineCost;
      return { lineId: l.id, hasBom: true, unitCost, lineCost };
    });

    const sub = quotation?.subtotal ?? lines.reduce((s, l) => s + l.value, 0);
    const disc = quotation?.discountAmount ?? 0;
    const commercialBase = Math.max(0, sub - disc);
    const estMargin = commercialBase - totalBomCost;
    const marginPct = commercialBase > 0 ? Math.round((estMargin / commercialBase) * 100) : 0;
    return {
      totalBomCost,
      estMargin,
      marginPct,
      isLow: allLinesHaveBom && marginPct < 20,
      bomAvailable: allLinesHaveBom,
      commercialBase,
      lineDetails,
    };
  }, [lines, quotation?.subtotal, quotation?.discountAmount, boms]);

  if (!quotation) return (
    <div className="p-8 text-center text-sm text-[var(--text-2)]">
      Quotation not found. <Link to="/sales" className="text-[var(--brand)] underline">Back to Sales</Link>
    </div>
  );

  const parentQuote = quotation.parentQuotationId ? quotations.find(q => q.id === quotation.parentQuotationId) : undefined;
  const childRevisions = quotations.filter(q => q.parentQuotationId === quotation.id);
  const existingPO = customerPos.find(p => p.quotationId === quotation.id || (p as any).quotationRef === quotation.quotationNumber);

  const canSubmit = quotation.status === 'draft' && ['owner', 'sales'].includes(currentUser.role);
  const canAccept = quotation.status === 'submitted' && ['owner', 'sales'].includes(currentUser.role);
  const activeRevisionExists = childRevisions.some(r => r.status !== 'draft');
  const canRevise = !['accepted', 'superseded'].includes(quotation.status) && !existingPO && !activeRevisionExists && ['owner', 'sales'].includes(currentUser.role);
  const canConvert = quotation.status === 'accepted' && !existingPO && !activeRevisionExists && ['owner', 'sales'].includes(currentUser.role);

  const handleReviseQuotation = async () => {
    const disc = Number(reviseDiscount) || 0;
    const nextQ: CustomerQuotation = { ...quotation, discountAmount: disc, notes: reviseNotes.trim() || quotation.notes, status: 'draft' };
    const saved = await reviseQuotation(quotation.id, nextQ, lines);
    setReviseModal(false);
    if (saved) navigate(`/sales/quotation/${saved.id}`, { state: { from: originUrl } });
  };

  const handleSubmit = async () => {
    await updateQuotation(quotation.id, { status: 'submitted' });
    setConfirming(null);
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
              </div>
              <h1 className="text-lg font-bold text-[var(--text)] mt-0.5">{quotation.customerName}</h1>
              <p className="text-xs text-[var(--text-2)]">
                Quoted {fmt(quotation.quotationDate)} · Valid until {fmt(quotation.validUntil)} · Rev {quotation.revision}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 flex-shrink-0">
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
            {canSubmit && (
              <button onClick={() => setConfirming('accept')} className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold border" style={{ borderColor: 'var(--brand)', color: 'var(--brand)' }}>
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
                <button type="button" onClick={() => handleDecision('rejected')} className="px-3 py-2 rounded-lg text-sm font-semibold border" style={{ borderColor: 'var(--error)', color: 'var(--error)' }}>Decline</button>
                <button type="button" onClick={() => handleDecision('expired')} className="px-3 py-2 rounded-lg text-sm font-semibold border" style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}>No Response</button>
              </>
            )}
            {canRevise && (
              <button onClick={() => { setReviseDiscount(String(quotation.discountAmount ?? 0)); setReviseNotes(quotation.notes ?? ''); setReviseModal(true); }} className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold border border-[var(--border)] text-[var(--text)] hover:bg-[var(--surface-2)]">
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

        {reviseModal && (
          <div className="fixed inset-0 bg-black/40 z-50 flex items-center justify-center p-4">
            <div className="bg-[var(--surface)] rounded-2xl border p-6 max-w-md w-full shadow-2xl space-y-4 border-[var(--border)]">
              <h3 className="text-base font-bold text-[var(--text)]">Revise {quotation.quotationNumber}</h3>
              <p className="text-xs text-[var(--text-2)]">Creates a new linked revision and marks {quotation.quotationNumber} as <span className="font-mono">superseded</span>.</p>
              <div>
                <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Revised Discount (₹)</label>
                <input type="number" min={0} className="eoss-input w-full font-mono" value={reviseDiscount} onChange={e => setReviseDiscount(e.target.value)} />
              </div>
              <div>
                <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Revision Summary / Notes</label>
                <textarea className="eoss-input w-full" rows={2} value={reviseNotes} onChange={e => setReviseNotes(e.target.value)} />
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
                  <td className="font-mono text-sm text-right">{l.orderedQty}</td>
                  <td className="text-xs text-[var(--text-2)]">{l.uom}</td>
                  <td className="font-mono text-sm text-right">{fmtMoney(l.unitPrice)}</td>
                  <td className="font-mono text-sm text-right font-semibold">{fmtMoney(l.value)}</td>
                  <td className="text-sm text-[var(--text-2)]">{fmt(l.requiredDate)}</td>
                </tr>
              ))}
            </tbody>
            <tfoot>
              <tr>
                <td colSpan={7} className="text-right text-xs font-bold text-[var(--text-2)] pr-4">Total</td>
                <td className="font-mono text-base font-bold text-[var(--text)]">{fmtMoney(quotation.totalValue)}</td>
                <td />
              </tr>
            </tfoot>
          </table>
        </div>

        {/* Commercial Costing & Estimated Material Margin Analysis */}
        <div className="mt-4 rounded-xl border p-4 space-y-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <div>
              <h4 className="text-xs font-bold uppercase tracking-wider text-[var(--text)] flex items-center gap-1.5">
                <Layers size={14} className="text-blue-500" /> Commercial Intelligence & BOM Material Margin Analysis
              </h4>
              <p className="text-[11px] text-[var(--text-3)]">
                {bomStats.bomAvailable ? 'BOM material rollup against net commercial price' : 'No engineering BOM found matching line items'}
              </p>
            </div>
            {bomStats.bomAvailable ? (
              <span className={`px-2.5 py-0.5 rounded-full text-xs font-bold border ${
                bomStats.isLow ? 'bg-amber-100 text-amber-900 border-amber-300' : 'bg-emerald-100 text-emerald-900 border-emerald-300'
              }`}>
                {bomStats.marginPct}% Material Margin
              </span>
            ) : (
              <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold border bg-slate-100 text-slate-700 border-slate-300">
                BOM Cost Not Available
              </span>
            )}
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-3 gap-3 p-3 rounded-xl bg-[var(--surface-2)] text-xs">
            <div>
              <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">Net Commercial Base</span>
              <span className="font-mono font-bold text-sm text-[var(--text)]">
                {fmtMoney(bomStats.commercialBase)}
              </span>
              {(quotation.discountAmount ?? 0) > 0 && (
                <span className="text-[10px] text-[var(--text-3)] block mt-0.5">
                  Subtotal {fmtMoney(quotation.subtotal ?? 0)} - Disc {fmtMoney(quotation.discountAmount ?? 0)}
                </span>
              )}
            </div>
            <div>
              <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">Est. BOM Material Cost</span>
              {bomStats.bomAvailable ? (
                <span className="font-mono font-bold text-sm text-slate-600">
                  {fmtMoney(bomStats.totalBomCost)}
                </span>
              ) : (
                <span className="text-xs font-semibold text-[var(--text-3)] italic">
                  BOM Cost Not Available
                </span>
              )}
            </div>
            <div>
              <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">Est. Material Margin</span>
              {bomStats.bomAvailable ? (
                <span className={`font-mono font-bold text-sm ${bomStats.isLow ? 'text-amber-600' : 'text-emerald-600'}`}>
                  {fmtMoney(bomStats.estMargin)} ({bomStats.marginPct}%)
                </span>
              ) : (
                <span className="text-xs font-semibold text-[var(--text-3)] italic">
                  Margin Not Available (BOM missing)
                </span>
              )}
            </div>
          </div>

          {bomStats.bomAvailable && bomStats.isLow && (
            <div className="p-3 rounded-lg bg-amber-50 border border-amber-200 text-amber-900 text-xs flex items-center gap-2">
              <AlertTriangle size={15} className="text-amber-600 flex-shrink-0" />
              <span>
                <strong>Low Margin Alert (&lt; 20%):</strong> Quoted price leaves narrow buffer for machining and factory overheads.
              </span>
            </div>
          )}

          <p className="text-[11px] text-[var(--text-3)] italic pt-1 border-t border-[var(--border)]">
            * Estimated material margin — excludes labour, overhead, machine cost and other unconfigured expenses.
          </p>
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
              {confirming === 'convert' ? 'Convert to Customer PO?' : 'Mark Quotation Accepted?'}
            </h3>
            <p className="text-sm text-[var(--text-2)] mb-5">
              {confirming === 'convert'
                ? `A new Customer PO will be created for ${fmtMoney(quotation.totalValue)} against ${quotation.quotationNumber}. This cannot be undone.`
                : `${quotation.quotationNumber} will be marked as accepted by ${quotation.customerName}.`}
            </p>
            <div className="flex gap-3 justify-end">
              <button onClick={() => setConfirming(null)} className="px-4 py-2 rounded-lg text-sm border" style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}>Cancel</button>
              <button
                onClick={confirming === 'convert' ? handleConvertToPO : (quotation.status === 'draft' ? handleSubmit : handleAccept)}
                className="px-4 py-2 rounded-lg text-sm font-semibold text-white"
                style={{ background: confirming === 'convert' ? 'var(--brand)' : '#16a34a' }}
              >
                {confirming === 'convert' ? 'Create PO' : (quotation.status === 'draft' ? 'Submit' : 'Accept')}
              </button>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
