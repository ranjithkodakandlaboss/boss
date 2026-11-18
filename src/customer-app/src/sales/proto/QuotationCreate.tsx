// @ts-nocheck
import React, { useState, useMemo } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { ArrowLeft, Plus, Trash2, Check, AlertTriangle, Layers } from 'lucide-react';
import clsx from 'clsx';
import { useStore } from './runtime';
import AddIfEmpty from './AddIfEmpty';
import { newCustomer, saveCatalogItem, saveUnit } from './quickMasters';
import type { CustomerQuotation, CustomerQuotationLine } from './types';

const DEMO_NOW = new Date().toISOString().slice(0, 10);
const addDays = (d: string, n: number) => {
  const dt = new Date(d);
  dt.setDate(dt.getDate() + n);
  return dt.toISOString().slice(0, 10);
};

interface QLine {
  id: string;
  itemId: string;
  drawingNumber: string;
  drawingRevision: string;
  productName: string;
  orderedQty: number;
  uom: string;
  unitPrice: number;
  requiredDate: string;
  gstRate?: number;
}

function newLine(n: number, rfqLine?: any): QLine {
  return {
    id: `ql-${Date.now()}-${n}`,
    itemId: rfqLine?.itemId ?? rfqLine?.productId ?? '',
    drawingNumber: rfqLine?.drawingNumber ?? '',
    drawingRevision: rfqLine?.drawingRevision ?? 'A',
    productName: rfqLine?.description ?? rfqLine?.productName ?? rfqLine?.partName ?? '',
    orderedQty: rfqLine?.quantity ?? rfqLine?.qty ?? 1,
    uom: rfqLine?.uom ?? 'Nos',
    unitPrice: rfqLine?.targetPrice ?? 0,
    requiredDate: rfqLine?.requiredBy ?? '',
  };
}

export default function QuotationCreate() {
  const { rfqId } = useParams<{ rfqId: string }>();
  const navigate = useNavigate();
  const { rfqs, customers, addCustomer, addQuotation, createDirectQuotation, currentUser, boms, catalog, uoms, reload } = useStore();

  const rfq = rfqs.find(r => r.id === rfqId) as (typeof rfqs[0] & Record<string, any>) | undefined;
  const rfqLines: any[] = rfq?.lines ?? [];
  const isDirect = !rfqId;

  const [customerId, setCustomerId] = useState('');
  const [validDays, setValidDays] = useState('30');
  const [paymentTerms, setPaymentTerms] = useState('Net 30 days');
  const [deliveryTerms, setDeliveryTerms] = useState('Ex-Works / By Road · 2-3 weeks');
  const [discountAmount, setDiscountAmount] = useState(0);
  const [taxRatePct, setTaxRatePct] = useState(18);
  const [notes, setNotes] = useState('');
  const [lines, setLines] = useState<QLine[]>(
    rfqLines.length > 0 ? rfqLines.map((l, i) => newLine(i + 1, l)) : [newLine(1)]
  );

  if (rfqId && !rfq) return <div className="p-8 text-sm text-[var(--text-2)]">RFQ not found.</div>;

  const directCustomer = customers.find(c => c.id === customerId);
  const activeCustomerId = rfq ? rfq.customerId : (directCustomer?.id ?? '');
  const activeCustomerName = rfq ? rfq.customerName : (directCustomer?.tradingName || directCustomer?.legalName || 'Customer');

  const addLine = () => setLines(ls => [...ls, newLine(ls.length + 1)]);
  const removeLine = (id: string) => setLines(ls => ls.filter(l => l.id !== id));
  const updateLine = (id: string, field: keyof QLine, value: any) =>
    setLines(ls => ls.map(l => l.id === id ? { ...l, [field]: value } : l));

  const subtotal = lines.reduce((s, l) => s + l.orderedQty * l.unitPrice, 0);
  const taxable = Math.max(0, subtotal - (discountAmount || 0));
  const taxAmount = Math.round((taxable * (taxRatePct || 0)) / 100);
  const totalValue = Math.round(taxable + taxAmount);
  const fmtMoney = (n: number) => `₹${n.toLocaleString('en-IN')}`;
  const validUntil = addDays(DEMO_NOW, parseInt(validDays) || 30);

  const bomCostDetails = useMemo(() => {
    let totalBomCost = 0;
    let allLinesHaveBom = lines.length > 0;
    const lineBreakdowns = lines.map(l => {
      const matchingBom = boms.find(b =>
        (b.productId && l.drawingNumber && b.productId === l.drawingNumber) ||
        (l.drawingNumber && b.productCode && b.productCode.trim().toLowerCase() === l.drawingNumber.trim().toLowerCase()) ||
        (l.productName && b.productName && b.productName.trim().toLowerCase() === l.productName.trim().toLowerCase())
      );
      if (!matchingBom) {
        allLinesHaveBom = false;
        return { lineId: l.id, hasBom: false, unitBomCost: 0, lineCost: 0 };
      }
      const unitBomCost = matchingBom.totalMaterialCost;
      const lineCost = unitBomCost * (l.orderedQty || 1);
      totalBomCost += lineCost;
      return { lineId: l.id, hasBom: true, matchingBom, unitBomCost, lineCost };
    });
    const commercialBase = Math.max(0, subtotal - (discountAmount || 0));
    const estMargin = commercialBase - totalBomCost;
    const marginPct = commercialBase > 0 ? Math.round((estMargin / commercialBase) * 100) : 0;
    return {
      totalBomCost,
      lineBreakdowns,
      estMargin,
      marginPct,
      commercialBase,
      bomAvailable: allLinesHaveBom,
      isLow: allLinesHaveBom && marginPct < 20,
    };
  }, [lines, subtotal, discountAmount, boms]);

  const handleSubmit = async () => {
    const quotation: CustomerQuotation = {
      id: '',
      quotationNumber: '',
      rfqId: rfq?.id,
      rfqNumber: rfq?.rfqNumber,
      isDirectQuotation: isDirect,
      customerId: activeCustomerId,
      customerName: activeCustomerName,
      quotationDate: DEMO_NOW,
      validUntil,
      currency: 'INR',
      status: 'draft',
      subtotal,
      discountAmount: discountAmount || 0,
      taxRatePct: taxRatePct || 0,
      taxAmount,
      totalValue,
      paymentTerms,
      deliveryTerms,
      revision: 1,
      owner: currentUser.name,
      notes,
    };

    const qLines: CustomerQuotationLine[] = lines.map((l, i) => ({
      id: `${quotation.id}-l${i + 1}`,
      quotationId: quotation.id,
      productId: l.itemId,
      productName: l.productName,
      gstRate: l.gstRate ?? taxRatePct,
      drawingNumber: l.drawingNumber,
      drawingRevision: l.drawingRevision,
      orderedQty: l.orderedQty,
      uom: l.uom,
      unitPrice: l.unitPrice,
      value: l.orderedQty * l.unitPrice,
      requiredDate: l.requiredDate,
    }));

    const saved = isDirect ? await createDirectQuotation(quotation, qLines) : await addQuotation(quotation, qLines);
    if (saved) navigate(`/sales/quotation/${saved.id}`, { state: { from: rfq ? `/sales/rfq/${rfq.id}` : '/sales?tab=rfqs' } });
  };

  const backTarget = rfqId ? `/sales/rfq/${rfqId}` : '/sales?tab=rfqs';

  return (
    <div className="flex flex-col h-full overflow-auto">
      <div className="flex-shrink-0 px-6 py-4 border-b flex items-center gap-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
        <button onClick={() => navigate(backTarget)} className="p-1.5 rounded-lg hover:bg-[var(--surface-2)]">
          <ArrowLeft size={18} className="text-[var(--text-2)]" />
        </button>
        <div>
          <h1 className="text-lg font-bold text-[var(--text)]">
            {isDirect ? 'New Direct Quotation' : 'New Quotation'}
          </h1>
          <p className="text-xs text-[var(--text-2)]">
            {rfq ? `Against ${rfq.rfqNumber} · ${rfq.customerName}` : 'Direct Quotation (standalone, no prior RFQ required)'}
          </p>
        </div>
      </div>

      <div className="flex-1 overflow-auto p-6">
        <div className="max-w-3xl mx-auto space-y-5">

          {/* Header */}
          <Card title="Quotation Details">
            <div className="grid md:grid-cols-3 gap-4">
              {isDirect && (
                <Field label="Customer *" className="md:col-span-2">
                  <AddIfEmpty
                    empty={customers.length === 0}
                    title="No customers yet"
                    hint="Add the business customer here. It is the same list used on RFQs and customer POs."
                    fields={[{ key: 'legalName', label: 'Legal name' }, { key: 'city', label: 'City' }]}
                    submitLabel="Save customer"
                    onSubmit={async (values) => {
                      const name = (values.legalName || '').trim();
                      const city = (values.city || '').trim();
                      if (!name || !city) throw new Error('Enter a legal name and city.');
                      const saved = await addCustomer(newCustomer(name, city));
                      if (!saved?.id) throw new Error('Customer was not saved.');
                      setCustomerId(saved.id);
                    }}
                  >
                    <select value={customerId} onChange={e => setCustomerId(e.target.value)} className="eoss-input">
                      <option value="">Select Customer</option>
                      {customers.map(c => (
                        <option key={c.id} value={c.id}>{c.tradingName || c.legalName || 'Customer'}</option>
                      ))}
                    </select>
                  </AddIfEmpty>
                </Field>
              )}
              <Field label="Quotation Date">
                <input type="text" value={DEMO_NOW} disabled className="eoss-input opacity-60 cursor-not-allowed" />
              </Field>
              <Field label="Valid For (days)">
                <select value={validDays} onChange={e => setValidDays(e.target.value)} className="eoss-input">
                  {['15', '30', '45', '60', '90'].map(d => <option key={d} value={d}>{d} days (until {addDays(DEMO_NOW, parseInt(d))})</option>)}
                </select>
              </Field>
              <Field label="Payment Terms">
                <input value={paymentTerms} onChange={e => setPaymentTerms(e.target.value)} className="eoss-input" />
              </Field>
              <Field label="Delivery Commitment">
                <input value={deliveryTerms} onChange={e => setDeliveryTerms(e.target.value)} className="eoss-input" />
              </Field>
              <Field label="Tax Rate (GST %)">
                <input type="number" min={0} max={28} value={taxRatePct} onChange={e => setTaxRatePct(Number(e.target.value) || 0)} className="eoss-input font-mono" />
              </Field>
              <Field label="Discount (₹)">
                <input type="number" min={0} value={discountAmount} onChange={e => setDiscountAmount(Number(e.target.value) || 0)} className="eoss-input font-mono" />
              </Field>
              <Field label="Prepared By">
                <input value={currentUser.name} disabled className="eoss-input opacity-60 cursor-not-allowed" />
              </Field>
            </div>
          </Card>

          {/* Lines */}
          {catalog.length === 0 && (
            <AddIfEmpty
              empty
              title="The item list is empty"
              hint="A quotation line needs an item from the item master. Add the first one here."
              fields={[{ key: 'code', label: 'Code' }, { key: 'name', label: 'Name' }]}
              submitLabel="Save item and use it on line 1"
              onSubmit={async (values) => {
                const item = await saveCatalogItem({ code: values.code || '', name: values.name || '' }, uoms);
                await reload();
                const first = lines[0];
                if (first) {
                  updateLine(first.id, 'itemId', item.id);
                  if (!first.productName) updateLine(first.id, 'productName', item.name);
                  if (item.uomSymbol) updateLine(first.id, 'uom', item.uomSymbol);
                }
              }}
            >
              {null}
            </AddIfEmpty>
          )}
          {uoms.length === 0 && (
            <AddIfEmpty
              empty
              title="No units yet"
              hint="Add a unit such as NOS or KG before pricing a line."
              fields={[{ key: 'name', label: 'Unit name' }, { key: 'symbol', label: 'Symbol' }]}
              submitLabel="Save unit"
              onSubmit={async (values) => {
                const unit = await saveUnit(values.name || '', values.symbol || '');
                await reload();
                const first = lines[0];
                if (first) updateLine(first.id, 'uom', unit.symbol);
              }}
            >
              {null}
            </AddIfEmpty>
          )}
          <Card title="Quotation Lines" trailing={
            <button onClick={addLine} className="flex items-center gap-1 px-2.5 py-1 rounded-lg text-xs font-semibold text-white" style={{ background: 'var(--brand-fill)' }}>
              <Plus size={12} /> Add Line
            </button>
          }>
            {lines.map((l, idx) => (
              <div key={l.id} className={`${idx > 0 ? 'mt-4 pt-4 border-t' : ''}`} style={idx > 0 ? { borderColor: 'var(--border)' } : {}}>
                <div className="flex items-center justify-between mb-3">
                  <span className="text-xs font-semibold text-[var(--text-2)]">Line {idx + 1}</span>
                  {lines.length > 1 && (
                    <button onClick={() => removeLine(l.id)} className="p-1 rounded hover:bg-red-50 text-red-500">
                      <Trash2 size={13} />
                    </button>
                  )}
                </div>
                <div className="grid md:grid-cols-3 gap-3">
                  <Field label="Item *" className="md:col-span-3">
                    <select value={l.itemId} onChange={e => {
                      const item = catalog.find(entry => entry.id === e.target.value);
                      updateLine(l.id, 'itemId', e.target.value);
                      if (item) {
                        if (!l.productName) updateLine(l.id, 'productName', item.name);
                        if (item.uomSymbol) updateLine(l.id, 'uom', item.uomSymbol);
                        if (item.gstRate != null) updateLine(l.id, 'gstRate', Number(item.gstRate));
                      }
                    }} className="eoss-input">
                      <option value="">{catalog.length ? 'Select an item…' : 'No items in the item master'}</option>
                      {catalog.map(item => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}
                    </select>
                  </Field>
                  <Field label="Drawing No. *">
                    <input value={l.drawingNumber} onChange={e => updateLine(l.id, 'drawingNumber', e.target.value)} className="eoss-input" />
                  </Field>
                  <Field label="Rev">
                    <input value={l.drawingRevision} onChange={e => updateLine(l.id, 'drawingRevision', e.target.value)} className="eoss-input" />
                  </Field>
                  <Field label="Required By">
                    <input type="date" value={l.requiredDate} onChange={e => updateLine(l.id, 'requiredDate', e.target.value)} className="eoss-input" />
                  </Field>
                  <Field label="Description" className="md:col-span-2">
                    <input value={l.productName} onChange={e => updateLine(l.id, 'productName', e.target.value)} className="eoss-input" />
                  </Field>
                  <Field label="UOM">
                    <select value={l.uom} onChange={e => updateLine(l.id, 'uom', e.target.value)} className="eoss-input">
                      <option value="">{uoms.length ? 'Select a unit…' : 'Units are not loaded'}</option>
                      {uoms.map(unit => <option key={unit.id} value={unit.symbol}>{unit.symbol}</option>)}
                    </select>
                  </Field>
                  <Field label="Qty">
                    <input type="number" min={1} value={l.orderedQty} onChange={e => updateLine(l.id, 'orderedQty', parseInt(e.target.value) || 1)} className="eoss-input" />
                  </Field>
                  <Field label="Unit Price (₹)">
                    <input type="number" min={0} value={l.unitPrice} onChange={e => updateLine(l.id, 'unitPrice', parseFloat(e.target.value) || 0)} className="eoss-input" />
                  </Field>
                  <Field label="Line Value">
                    <input value={fmtMoney(l.orderedQty * l.unitPrice)} disabled className="eoss-input opacity-60 cursor-not-allowed font-mono" />
                  </Field>
                </div>
              </div>
            ))}

            <div className="mt-4 pt-3 border-t flex justify-end" style={{ borderColor: 'var(--border)' }}>
              <div className="text-right">
                <div className="text-xs text-[var(--text-3)]">Total Quotation Value</div>
                <div className="text-xl font-bold text-[var(--text)] font-mono">{fmtMoney(totalValue)}</div>
              </div>
            </div>
          </Card>

          {/* BOM Material Costing & Margin Analysis */}
          <Card title="Commercial Intelligence & Material Margin Analysis">
            <div className="space-y-3">
              <div className="flex items-center justify-between">
                <p className="text-xs text-[var(--text-3)]">
                  {bomCostDetails.bomAvailable
                    ? 'BOM material cost rollup from engineering master specs'
                    : 'No matching engineering BOM found for line items'}
                </p>
                {bomCostDetails.bomAvailable ? (
                  <span className={clsx(
                    'px-2.5 py-0.5 rounded-full text-xs font-bold border',
                    bomCostDetails.isLow ? 'bg-amber-100 text-amber-900 border-amber-300' : 'bg-emerald-100 text-emerald-900 border-emerald-300'
                  )}>
                    {bomCostDetails.marginPct}% Material Margin
                  </span>
                ) : (
                  <span className="px-2.5 py-0.5 rounded-full text-xs font-semibold border bg-slate-100 text-slate-700 border-slate-300">
                    BOM Cost Not Available
                  </span>
                )}
              </div>

              <div className="grid grid-cols-3 gap-3 p-3 rounded-xl bg-[var(--surface-2)] text-xs">
                <div>
                  <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">Net Commercial Base</span>
                  <span className="font-mono font-bold text-sm text-[var(--text)]">{fmtMoney(bomCostDetails.commercialBase)}</span>
                </div>
                <div>
                  <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">Est. BOM Material Cost</span>
                  {bomCostDetails.bomAvailable ? (
                    <span className="font-mono font-bold text-sm text-slate-600">{fmtMoney(bomCostDetails.totalBomCost)}</span>
                  ) : (
                    <span className="text-xs font-semibold text-[var(--text-3)] italic">BOM Cost Not Available</span>
                  )}
                </div>
                <div>
                  <span className="text-[10px] text-[var(--text-3)] uppercase tracking-wider block">Est. Material Margin</span>
                  {bomCostDetails.bomAvailable ? (
                    <span className={clsx('font-mono font-bold text-sm', bomCostDetails.isLow ? 'text-amber-600' : 'text-emerald-600')}>
                      {fmtMoney(bomCostDetails.estMargin)} ({bomCostDetails.marginPct}%)
                    </span>
                  ) : (
                    <span className="text-xs font-semibold text-[var(--text-3)] italic">Margin Not Available (BOM missing)</span>
                  )}
                </div>
              </div>

              {bomCostDetails.bomAvailable && bomCostDetails.isLow && (
                <div className="p-3 rounded-lg bg-amber-50 border border-amber-200 text-amber-900 text-xs flex items-center gap-2">
                  <AlertTriangle size={15} className="text-amber-600 flex-shrink-0" />
                  <span>
                    <strong>Low Margin Alert (&lt; 20%):</strong> Quoted price leaves limited headroom for machining and conversion overheads. Consider revising unit rate or optimizing raw material grade.
                  </span>
                </div>
              )}

              <p className="text-[11px] text-[var(--text-3)] italic pt-1 border-t border-[var(--border)]">
                * Estimated material margin — excludes labour, overhead, machine cost and other unconfigured expenses.
              </p>
            </div>
          </Card>

          {/* Notes */}
          <Card title="Notes to Customer">
            <textarea value={notes} onChange={e => setNotes(e.target.value)} className="eoss-input w-full" rows={3} placeholder="Validity, delivery lead time, exclusions, special terms…" />
          </Card>
        </div>
      </div>

      <div className="flex-shrink-0 px-6 py-4 border-t flex items-center justify-between" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
        <button onClick={() => navigate(backTarget)} className="px-4 py-2 rounded-lg text-sm border" style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}>
          Cancel
        </button>
        <button
          onClick={handleSubmit}
          disabled={lines.some(l => !l.itemId || !l.drawingNumber || !l.productName) || (isDirect && !directCustomer)}
          className="flex items-center gap-1.5 px-5 py-2 rounded-lg text-sm font-semibold text-white disabled:opacity-40"
          style={{ background: 'var(--brand-fill)' }}
        >
          <Check size={15} /> Save Quotation
        </button>
      </div>
    </div>
  );
}

function Card({ title, children, trailing }: { title: string; children: React.ReactNode; trailing?: React.ReactNode }) {
  return (
    <div className="rounded-xl border p-5" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
      <div className="flex items-center justify-between mb-4">
        <h3 className="text-sm font-bold text-[var(--text)]">{title}</h3>
        {trailing}
      </div>
      {children}
    </div>
  );
}

function Field({ label, children, className }: { label: string; children: React.ReactNode; className?: string }) {
  return (
    <div className={className}>
      <label className="block text-xs font-medium text-[var(--text-2)] mb-1">{label}</label>
      {children}
    </div>
  );
}
