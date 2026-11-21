// @ts-nocheck
import React, { useEffect, useState } from 'react';
import { useNavigate, useParams, useSearchParams } from 'react-router-dom';
import { ArrowLeft, Plus, Trash2, Check } from 'lucide-react';
import { useStore } from './runtime';
import { uploadLinkedDocument } from '../../documents/api';
import AddIfEmpty from './AddIfEmpty';
import { newCustomer, saveCatalogItem, saveUnit } from './quickMasters';
import type { CustomerQuotation, CustomerQuotationLine } from './types';
import { salesApi, type QuotationCostingConfig } from '../api';
import { api } from '../../auth/api';
import CostingConfigEditor from '../../config/CostingConfigEditor';

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
  unitPrice: number | undefined;
  requiredDate: string;
  gstRate?: number;
  labourHours: Record<string, number>;
  machineHours: Record<string, number>;
  rfqLineId?: string;
  specificationValues: Record<string, number>;
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
    unitPrice: rfqLine?.targetPrice ?? undefined,
    requiredDate: rfqLine?.requiredBy ?? '',
    labourHours: {},
    machineHours: {},
    rfqLineId: rfqLine?.rfqLineId ?? rfqLine?.id,
    specificationValues: rfqLine?.specificationValues ?? {},
  };
}

export default function QuotationCreate() {
  const { rfqId } = useParams<{ rfqId: string }>();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { rfqs, quotations, quotationLines, customers, addCustomer, addQuotation, createDirectQuotation, updateQuotation, reviseQuotation, currentUser, catalog, uoms, reload, showToast } = useStore();

  const editId = searchParams.get('edit');
  const reviseId = searchParams.get('revise');
  const targetQuoteId = editId || reviseId;
  const isEditing = Boolean(editId);
  const isRevising = Boolean(reviseId);
  const targetQuote = targetQuoteId ? quotations.find(q => q.id === targetQuoteId) : undefined;

  const rfq = rfqs.find(r => r.id === (rfqId || targetQuote?.rfqId)) as (typeof rfqs[0] & Record<string, any>) | undefined;
  const rfqLines: any[] = rfq?.lines ?? [];
  const isDirect = !rfqId && !targetQuote?.rfqId;

  const [customerId, setCustomerId] = useState(() => searchParams.get('customer') ?? '');
  const [validDays, setValidDays] = useState('30');
  const [paymentTerms, setPaymentTerms] = useState('Net 30 days');
  const [deliveryTerms, setDeliveryTerms] = useState('Ex-Works / By Road · 2-3 weeks');
  const [discountAmount, setDiscountAmount] = useState(0);
  const [taxRatePct, setTaxRatePct] = useState(18);
  const [notes, setNotes] = useState('');
  const [revisionReason, setRevisionReason] = useState('');
  const [loadedFromTarget, setLoadedFromTarget] = useState(false);
  const [files, setFiles] = useState<File[]>([]);
  const [costingConfig, setCostingConfig] = useState<QuotationCostingConfig>();
  const [costConfigMessage, setCostConfigMessage] = useState('');
  const [saving, setSaving] = useState(false);
  const [formError, setFormError] = useState('');
  const [lines, setLines] = useState<QLine[]>(
    rfqLines.length > 0 ? rfqLines.map((l, i) => newLine(i + 1, l)) : [newLine(1)]
  );
  const [bomBreakups, setBomBreakups] = useState<Record<string, {
    rawCost: number;
    consCost: number;
    labourCost: number;
    machineCost: number;
    otherCost: number;
    totalCost: number;
  }>>({});
  const [expandedBom, setExpandedBom] = useState<Record<string, boolean>>({});
  const canConfigureCosting = ['owner', 'OWNER'].includes(currentUser.role);

  useEffect(() => {
    if (!targetQuoteId || loadedFromTarget) return;
    const q = quotations.find(item => item.id === targetQuoteId);
    if (!q) {
      salesApi.quotation(targetQuoteId).then((apiQ: any) => {
        if (apiQ) {
          if (apiQ.customerId) setCustomerId(apiQ.customerId);
          if (apiQ.paymentTerms) setPaymentTerms(apiQ.paymentTerms);
          if (apiQ.deliveryTerms) setDeliveryTerms(apiQ.deliveryTerms);
          if (apiQ.discountAmount != null) setDiscountAmount(Number(apiQ.discountAmount));
          if (apiQ.taxRatePct != null) setTaxRatePct(Number(apiQ.taxRatePct));
          if (apiQ.notes) setNotes(apiQ.notes);
          if (apiQ.validUntil) {
            const diffDays = Math.round((new Date(apiQ.validUntil).getTime() - Date.now()) / 86400000);
            setValidDays(String(Math.max(1, diffDays)));
          }
          if (apiQ.lines && apiQ.lines.length > 0) {
            setLines(apiQ.lines.map((l: any, i: number) => ({
              id: l.id || `ql-${Date.now()}-${i + 1}`,
              itemId: l.productId || '',
              drawingNumber: l.drawingNumber || '',
              drawingRevision: l.drawingRevision || 'A',
              productName: l.productName || '',
              orderedQty: Number(l.orderedQty) || 1,
              uom: l.uom || 'Nos',
              unitPrice: l.unitPrice != null ? Number(l.unitPrice) : undefined,
              requiredDate: l.requiredDate || '',
              labourHours: l.labourHours || {},
              machineHours: l.machineHours || {},
              rfqLineId: l.rfqLineId,
              specificationValues: l.specificationValues || {},
            })));
          }
          setLoadedFromTarget(true);
        }
      }).catch(() => {});
      return;
    }

    if (q.customerId) setCustomerId(q.customerId);
    if (q.paymentTerms) setPaymentTerms(q.paymentTerms);
    if (q.deliveryTerms) setDeliveryTerms(q.deliveryTerms);
    if (q.discountAmount != null) setDiscountAmount(Number(q.discountAmount));
    if (q.taxRatePct != null) setTaxRatePct(Number(q.taxRatePct));
    if (q.notes) setNotes(q.notes);
    if (q.validUntil) {
      const diffDays = Math.round((new Date(q.validUntil).getTime() - Date.now()) / 86400000);
      setValidDays(String(Math.max(1, diffDays)));
    }
    const qLines = quotationLines.filter(l => l.quotationId === targetQuoteId);
    if (qLines.length > 0) {
      setLines(qLines.map((l, i) => ({
        id: l.id || `ql-${Date.now()}-${i + 1}`,
        itemId: l.productId || '',
        drawingNumber: l.drawingNumber || '',
        drawingRevision: l.drawingRevision || 'A',
        productName: l.productName || '',
        orderedQty: Number(l.orderedQty) || 1,
        uom: l.uom || 'Nos',
        unitPrice: l.unitPrice != null ? Number(l.unitPrice) : undefined,
        requiredDate: l.requiredDate || '',
        labourHours: l.labourHours || {},
        machineHours: l.machineHours || {},
        rfqLineId: l.rfqLineId,
        specificationValues: l.specificationValues || {},
      })));
    }
    setLoadedFromTarget(true);
  }, [targetQuoteId, quotations, quotationLines, loadedFromTarget]);

  const loadBomForLine = async (lineId: string, itemId: string) => {
    if (!itemId) return;
    try {
      const bom = await api<any>(`/api/v1/items/${itemId}/bom`);
      if (bom) {
        let rawCost = 0;
        let consCost = 0;
        (bom.lines || []).forEach((bLine: any) => {
          const qty = Number(bLine.qty) || 0;
          const unitCost = Number(bLine.unitCost) || 0;
          const scrap = Number(bLine.scrapFactor) || 0;
          const lineTotal = qty * unitCost * (1 + scrap / 100);
          if (bLine.componentItemType === 'CONSUMABLE') {
            consCost += lineTotal;
          } else {
            rawCost += lineTotal;
          }
        });
        const labourCost = Number(bom.labourCostPerUnit) || 0;
        const machineCost = Number(bom.machineCostPerUnit) || 0;
        const otherCost = Number(bom.otherCostPerUnit) || 0;
        const totalCost = Math.round((rawCost + consCost + labourCost + machineCost + otherCost) * 100) / 100;

        setBomBreakups(prev => ({
          ...prev,
          [lineId]: {
            rawCost: Math.round(rawCost * 100) / 100,
            consCost: Math.round(consCost * 100) / 100,
            labourCost,
            machineCost,
            otherCost,
            totalCost,
          }
        }));

        setLines(current => current.map(l => {
          if (l.id === lineId && (l.unitPrice === undefined || l.unitPrice === null || l.unitPrice === 0)) {
            return { ...l, unitPrice: totalCost };
          }
          return l;
        }));
      }
    } catch {
      // No BOM configured for this item
    }
  };

  useEffect(() => {
    lines.forEach(l => {
      if (l.itemId && !bomBreakups[l.id]) {
        void loadBomForLine(l.id, l.itemId);
      }
    });
  }, [lines]);

  useEffect(() => {
    salesApi.quotationCostingConfig().then((config) => {
      setCostingConfig(config);
    }).catch((error) => {
      setCostConfigMessage(error instanceof Error ? error.message : 'Costing configuration could not be loaded.');
    });
  }, []);

  if (rfqId && !rfq) return <div className="p-8 text-sm text-[var(--text-2)]">RFQ not found.</div>;
  if (rfq?.status === 'cancelled') return <p className="p-8 text-sm text-[var(--text-2)]">Quotations cannot be created from a cancelled RFQ.</p>;

  const directCustomer = customers.find(c => c.id === customerId);
  const activeCustomerId = rfq ? rfq.customerId : (directCustomer?.id ?? '');
  const activeCustomerName = rfq ? rfq.customerName : (directCustomer?.tradingName || directCustomer?.legalName || 'Customer');

  const addLine = () => setLines(ls => [...ls, newLine(ls.length + 1)]);
  const removeLine = (id: string) => setLines(ls => ls.filter(l => l.id !== id));
  const updateLine = (id: string, field: keyof QLine, value: any) =>
    setLines(ls => ls.map(l => l.id === id ? { ...l, [field]: value } : l));

  const subtotal = lines.reduce((s, l) => s + l.orderedQty * (l.unitPrice ?? 0), 0);
  const discountPct = subtotal > 0 ? ((discountAmount || 0) / subtotal) * 100 : 0;
  const taxable = Math.max(0, subtotal - (discountAmount || 0));
  const taxAmount = Math.round((taxable * (taxRatePct || 0)) / 100);
  const totalValue = Math.round(taxable + taxAmount);
  const fmtMoney = (n: number) => `₹${n.toLocaleString('en-IN')}`;
  const validUntil = addDays(DEMO_NOW, parseInt(validDays) || 30);

  const totalEstimatedBomCost = lines.reduce((sum, l) => {
    const unitCost = bomBreakups[l.id]?.totalCost;
    return unitCost != null ? sum + (unitCost * l.orderedQty) : sum;
  }, 0);
  const hasBomCosting = lines.some(l => bomBreakups[l.id] != null);
  const grossProfit = taxable - totalEstimatedBomCost;
  const overallGrossMarginPct = taxable > 0 && hasBomCosting
    ? ((taxable - totalEstimatedBomCost) / taxable) * 100
    : 0;

  const updateHours = (id: string, field: 'labourHours' | 'machineHours', name: string, value: string) => {
    const hours = value === '' ? 0 : Math.max(0, Number(value) || 0);
    setLines(current => current.map(line => line.id === id
      ? { ...line, [field]: { ...line[field], [name]: hours } }
      : line));
  };

  const activeCustomers = customers.filter(c => c.status === 'active');

  const handleSubmit = async () => {
    if (saving) return;
    setFormError('');

    if (isRevising && !revisionReason.trim()) {
      setFormError('Revision reason is mandatory to issue a new quotation revision.');
      return;
    }

    const quotation: CustomerQuotation = {
      id: targetQuoteId || '',
      quotationNumber: targetQuote?.quotationNumber || '',
      rfqId: rfq?.id,
      rfqNumber: rfq?.rfqNumber,
      isDirectQuotation: isDirect,
      customerId: activeCustomerId,
      customerName: activeCustomerName,
      quotationDate: targetQuote?.quotationDate || DEMO_NOW,
      validUntil,
      currency: 'INR',
      status: isEditing ? (targetQuote?.status || 'draft') : 'draft',
      subtotal,
      discountAmount: discountAmount || 0,
      taxRatePct: taxRatePct || 0,
      taxAmount,
      totalValue,
      paymentTerms,
      deliveryTerms,
      revision: isRevising ? ((targetQuote?.revision ?? 1) + 1) : (targetQuote?.revision ?? 1),
      owner: currentUser.name,
      notes: isRevising ? revisionReason.trim() : notes,
    };

    const qLines: CustomerQuotationLine[] = lines.map((l, i) => ({
      id: l.id || `${quotation.id}-l${i + 1}`,
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
      labourHours: l.labourHours,
      machineHours: l.machineHours,
      rfqLineId: l.rfqLineId,
      specificationValues: l.specificationValues,
    }));

    setSaving(true);
    try {
      if (isRevising) {
        const saved = await reviseQuotation(targetQuoteId, quotation, qLines);
        if (saved) {
          navigate(`/sales/quotation/${saved.id}`);
        } else {
          setFormError('Quotation revision could not be created.');
        }
      } else if (isEditing) {
        const saved = await updateQuotation(targetQuoteId, { ...quotation, lines: qLines });
        if (saved) {
          navigate(`/sales/quotation/${saved.id || targetQuoteId}`);
        } else {
          setFormError('Quotation changes could not be saved.');
        }
      } else {
        const saved = isDirect ? await createDirectQuotation(quotation, qLines) : await addQuotation(quotation, qLines);
        if (saved) {
          try {
            for (const file of files) await uploadLinkedDocument(file, { type: 'Quotation', id: saved.id, ref: saved.quotationNumber });
          } catch (error) {
            showToast({ type: 'error', title: 'Quotation saved; not all documents attached', body: `${error instanceof Error ? error.message : 'Upload failed.'} Attach the remaining files from the quotation view.` });
          }
          navigate(`/sales/quotation/${saved.id}`, { state: { from: rfq ? `/sales/rfq/${rfq.id}` : '/sales?tab=rfqs' } });
        } else {
          setFormError('Quotation could not be saved. Please try again.');
        }
      }
    } catch (err: unknown) {
      setFormError(err instanceof Error ? err.message : 'Quotation could not be saved.');
    } finally {
      setSaving(false);
    }
  };

  const backTarget = targetQuoteId ? `/sales/quotation/${targetQuoteId}` : rfqId ? `/sales/rfq/${rfqId}` : '/sales?tab=quotations';
  const pageTitle = isRevising
    ? `Revise Quotation ${targetQuote?.quotationNumber || ''}`
    : isEditing
    ? `Edit Quotation ${targetQuote?.quotationNumber || ''}`
    : isDirect
    ? 'New Direct Quotation'
    : 'New Quotation';

  const pageSubtitle = isRevising
    ? `Creates revision ${((targetQuote?.revision ?? 1) + 1)} · Existing quote will be marked superseded`
    : isEditing
    ? `Editing draft quotation · Changes will be saved directly`
    : rfq
    ? `Against ${rfq.rfqNumber} · ${rfq.customerName}`
    : 'Direct Quotation (standalone, no prior RFQ required)';

  return (
    <div className="flex flex-col h-full overflow-auto">
      <div className="flex-shrink-0 px-6 py-4 border-b flex items-center gap-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
        <button onClick={() => navigate(backTarget)} className="p-1.5 rounded-lg hover:bg-[var(--surface-2)]">
          <ArrowLeft size={18} className="text-[var(--text-2)]" />
        </button>
        <div>
          <h1 className="text-lg font-bold text-[var(--text)]">
            {pageTitle}
          </h1>
          <p className="text-xs text-[var(--text-2)]">
            {pageSubtitle}
          </p>
        </div>
      </div>

      <div className="flex-1 overflow-auto p-6">
        <div className="max-w-3xl mx-auto space-y-5">

          {isRevising && (
            <div className="p-4 rounded-xl border border-purple-200 dark:border-purple-800 bg-purple-50/70 dark:bg-purple-950/30 text-xs space-y-2">
              <span className="font-bold text-sm text-purple-900 dark:text-purple-200 block">Quotation Revision Reason *</span>
              <p className="text-purple-800 dark:text-purple-300">
                Please enter why this quotation is being revised. This reason will be recorded on the revision audit log.
              </p>
              <textarea
                required
                rows={2}
                value={revisionReason}
                onChange={e => setRevisionReason(e.target.value)}
                className="eoss-input w-full mt-1"
                placeholder="e.g. Scope modified, revised pricing per customer negotiation, updated drawing rev…"
              />
            </div>
          )}

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
                      {activeCustomers.map(c => (
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
                {discountPct > 100 && <p className="text-xs text-red-600 mt-1">Discount cannot exceed the subtotal (100%).</p>}
                {discountPct > 30 && discountPct <= 100 && <p className="text-xs text-amber-600 mt-1">Discount is over 30% — please confirm.</p>}
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
                      const selectedId = e.target.value;
                      const item = catalog.find(entry => entry.id === selectedId);
                      updateLine(l.id, 'itemId', selectedId);
                      if (item) {
                        if (!l.productName) updateLine(l.id, 'productName', item.name);
                        if (item.uomSymbol) updateLine(l.id, 'uom', item.uomSymbol);
                        if (item.gstRate != null) updateLine(l.id, 'gstRate', Number(item.gstRate));
                      }
                      if (selectedId) {
                        void loadBomForLine(l.id, selectedId);
                      }
                    }} className="eoss-input">
                      <option value="">{catalog.length ? 'Select an item…' : 'No items in the item master'}</option>
                      {catalog.map(item => <option key={item.id} value={item.id}>{item.code} · {item.name}</option>)}
                    </select>
                  </Field>
                  <Field label="Drawing No. *" className="md:col-span-2">
                    <input value={l.drawingNumber} onChange={e => updateLine(l.id, 'drawingNumber', e.target.value)} className="eoss-input" />
                  </Field>
                  <Field label="Rev">
                    <input value={l.drawingRevision} onChange={e => updateLine(l.id, 'drawingRevision', e.target.value)} className="eoss-input" />
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
                    <input type="number" min={0} value={l.unitPrice ?? ''} onChange={e => updateLine(l.id, 'unitPrice', e.target.value === '' ? undefined : parseFloat(e.target.value) || 0)} className="eoss-input w-full" />
                    {bomBreakups[l.id] && (() => {
                      const bomCost = bomBreakups[l.id].totalCost;
                      const quoted = l.unitPrice ?? 0;
                      const marginPct = quoted > 0 ? ((quoted - bomCost) / quoted) * 100 : (quoted === bomCost ? 0 : -100);
                      const isZeroMargin = Math.abs(quoted - bomCost) < 0.01;
                      return (
                        <div className="flex items-center gap-1.5 mt-1 text-[11px]">
                          <span className="text-[var(--text-3)]">BOM: ₹{bomCost.toLocaleString('en-IN')}</span>
                          <span className={`px-1.5 py-0.5 rounded text-[10px] font-semibold border ${
                            isZeroMargin
                              ? 'bg-gray-100 text-gray-700 border-gray-300'
                              : marginPct > 0
                              ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                              : 'bg-rose-50 text-rose-700 border-rose-200'
                          }`}>
                            {isZeroMargin ? '0.0% margin' : `${marginPct > 0 ? '+' : ''}${marginPct.toFixed(1)}% margin`}
                          </span>
                        </div>
                      );
                    })()}
                  </Field>
                  <Field label="Line Value">
                    <input value={l.unitPrice != null ? fmtMoney(l.orderedQty * l.unitPrice) : '—'} disabled className="eoss-input opacity-60 cursor-not-allowed font-mono w-full" />
                    {bomBreakups[l.id] && l.unitPrice != null && (() => {
                      const lineEstCost = bomBreakups[l.id].totalCost * l.orderedQty;
                      const lineVal = l.orderedQty * l.unitPrice;
                      const lineProfit = lineVal - lineEstCost;
                      return (
                        <div className="mt-1 text-[11px] font-mono text-[var(--text-3)]">
                          Est. Profit: <span style={{ color: lineProfit > 0 ? 'var(--success, #16a34a)' : lineProfit === 0 ? 'var(--text-2)' : 'var(--error, #dc2626)' }}>
                            {lineProfit >= 0 ? '+' : ''}₹{Math.round(lineProfit).toLocaleString('en-IN')}
                          </span>
                        </div>
                      );
                    })()}
                  </Field>
                  {bomBreakups[l.id] && (
                    <div className="md:col-span-3 mt-1 pt-1">
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          onClick={() => setExpandedBom(prev => ({ ...prev, [l.id]: !prev[l.id] }))}
                          className="text-xs font-semibold text-[var(--brand)] hover:underline inline-flex items-center gap-1"
                        >
                          {expandedBom[l.id] ? '▾ Hide BOM Cost Breakup' : '▸ View BOM Cost Breakup'}
                          <span className="font-normal text-[var(--text-3)]">(BOM Est: ₹{bomBreakups[l.id].totalCost.toLocaleString('en-IN')})</span>
                        </button>
                        <button
                          type="button"
                          onClick={() => updateLine(l.id, 'unitPrice', bomBreakups[l.id].totalCost)}
                          className="text-[11px] px-2 py-0.5 rounded border bg-[var(--surface-2)] text-[var(--text-2)] hover:bg-[var(--surface-3)]"
                        >
                          Apply BOM Price
                        </button>
                      </div>
                      {expandedBom[l.id] && (
                        <div className="mt-2 p-3 rounded-lg border bg-[var(--surface-2)] text-xs space-y-2" style={{ borderColor: 'var(--border)' }}>
                          <div className="grid grid-cols-2 sm:grid-cols-5 gap-2 font-mono">
                            <div>
                              <span className="text-[10px] text-[var(--text-3)] block uppercase">Raw Materials</span>
                              <span className="font-semibold">₹{bomBreakups[l.id].rawCost.toLocaleString('en-IN')}</span>
                            </div>
                            <div>
                              <span className="text-[10px] text-[var(--text-3)] block uppercase">Consumables</span>
                              <span className="font-semibold">₹{bomBreakups[l.id].consCost.toLocaleString('en-IN')}</span>
                            </div>
                            <div>
                              <span className="text-[10px] text-[var(--text-3)] block uppercase">Labour / Unit</span>
                              <span className="font-semibold">₹{bomBreakups[l.id].labourCost.toLocaleString('en-IN')}</span>
                            </div>
                            <div>
                              <span className="text-[10px] text-[var(--text-3)] block uppercase">Machine / Unit</span>
                              <span className="font-semibold">₹{bomBreakups[l.id].machineCost.toLocaleString('en-IN')}</span>
                            </div>
                            <div>
                              <span className="text-[10px] text-[var(--text-3)] block uppercase">Other Mfg / Unit</span>
                              <span className="font-semibold">₹{bomBreakups[l.id].otherCost.toLocaleString('en-IN')}</span>
                            </div>
                          </div>
                          <div className="pt-2 border-t flex justify-between items-center font-bold text-xs" style={{ borderColor: 'var(--border)' }}>
                            <span>Total Estimated Manufacturing Cost / Unit:</span>
                            <span className="font-mono text-sm text-[var(--brand)]">₹{bomBreakups[l.id].totalCost.toLocaleString('en-IN')}</span>
                          </div>
                        </div>
                      )}
                    </div>
                  )}
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

          {hasBomCosting && (
            <Card title="Estimated Cost & Gross Margin">
              <p className="text-xs text-[var(--text-2)] mb-4">
                Derived directly from the approved BOM per finished unit and quoted prices. When quoted price matches BOM cost, margin is 0%.
              </p>
              <div className="grid grid-cols-2 sm:grid-cols-4 gap-3 text-xs">
                <div className="p-3 rounded-xl border bg-[var(--surface-2)]" style={{ borderColor: 'var(--border)' }}>
                  <span className="text-[10px] text-[var(--text-3)] block uppercase tracking-wider font-medium">Quoted Revenue (Ex. Tax)</span>
                  <span className="text-base font-bold font-mono text-[var(--text)] mt-1 block">{fmtMoney(taxable)}</span>
                  <span className="text-[10px] text-[var(--text-3)] mt-0.5 block">Subtotal less discount</span>
                </div>
                <div className="p-3 rounded-xl border bg-[var(--surface-2)]" style={{ borderColor: 'var(--border)' }}>
                  <span className="text-[10px] text-[var(--text-3)] block uppercase tracking-wider font-medium">Estimated BOM Cost</span>
                  <span className="text-base font-bold font-mono text-[var(--text)] mt-1 block">{fmtMoney(Math.round(totalEstimatedBomCost))}</span>
                  <span className="text-[10px] text-[var(--text-3)] mt-0.5 block">Standard BOM manufacturing cost</span>
                </div>
                <div className="p-3 rounded-xl border bg-[var(--surface-2)]" style={{ borderColor: 'var(--border)' }}>
                  <span className="text-[10px] text-[var(--text-3)] block uppercase tracking-wider font-medium">Estimated Gross Profit</span>
                  <span className="text-base font-bold font-mono mt-1 block" style={{ color: grossProfit > 0 ? 'var(--success, #16a34a)' : grossProfit === 0 ? 'var(--text-2)' : 'var(--error, #dc2626)' }}>
                    {grossProfit >= 0 ? '+' : ''}{fmtMoney(Math.round(grossProfit))}
                  </span>
                  <span className="text-[10px] text-[var(--text-3)] mt-0.5 block">Revenue − BOM cost</span>
                </div>
                <div className="p-3 rounded-xl border bg-[var(--surface-2)]" style={{ borderColor: 'var(--border)' }}>
                  <span className="text-[10px] text-[var(--text-3)] block uppercase tracking-wider font-medium">Gross Margin</span>
                  <div className="flex items-center gap-2 mt-1">
                    <span className="text-base font-bold font-mono" style={{ color: overallGrossMarginPct > 0 ? 'var(--success, #16a34a)' : overallGrossMarginPct === 0 ? 'var(--text-2)' : 'var(--error, #dc2626)' }}>
                      {Math.abs(overallGrossMarginPct) < 0.01 ? '0.0%' : `${overallGrossMarginPct > 0 ? '+' : ''}${overallGrossMarginPct.toFixed(1)}%`}
                    </span>
                    <span className={`text-[10px] font-semibold px-2 py-0.5 rounded-full border ${
                      overallGrossMarginPct > 0
                        ? 'bg-emerald-50 text-emerald-700 border-emerald-200'
                        : Math.abs(overallGrossMarginPct) < 0.01
                        ? 'bg-gray-100 text-gray-700 border-gray-300'
                        : 'bg-rose-50 text-rose-700 border-rose-200'
                    }`}>
                      {overallGrossMarginPct > 0 ? 'Profitable' : Math.abs(overallGrossMarginPct) < 0.01 ? '0% Margin (At Cost)' : 'Below Cost'}
                    </span>
                  </div>
                  <span className="text-[10px] text-[var(--text-3)] mt-0.5 block">
                    {Math.abs(overallGrossMarginPct) < 0.01 ? 'Quoted at standard BOM cost' : `${overallGrossMarginPct > 0 ? '+' : ''}${overallGrossMarginPct.toFixed(1)}% margin`}
                  </span>
                </div>
              </div>
            </Card>
          )}

          {/* Notes */}
          <Card title="Notes to Customer">
            <textarea value={notes} onChange={e => setNotes(e.target.value)} className="eoss-input w-full" rows={3} placeholder="Validity, delivery lead time, exclusions, special terms…" />
          </Card>
          <Card title="Documents">
            <label className="text-xs text-[var(--text-2)]">Attach documents
              <input aria-label="Quotation documents" type="file" multiple disabled={saving} onChange={event => setFiles(Array.from(event.target.files ?? []))} className="block w-full mt-2 text-xs" />
            </label>
            <p className="text-xs text-[var(--text-3)] mt-2">Files are uploaded when the quotation is saved (up to 10 MB each).</p>
          </Card>
        </div>
      </div>

      <div className="flex-shrink-0 px-6 py-4 border-t" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
        {lines.some(l => l.unitPrice === 0 || l.unitPrice === undefined) && (
          <p className="text-xs text-amber-600 mb-2">One or more lines have no unit price — the quotation total will be ₹0.</p>
        )}
        {formError && <p className="text-sm text-[var(--error)] mb-2">{formError}</p>}
        <div className="flex items-center justify-between gap-3">
          <button onClick={() => navigate(backTarget)} className="px-4 py-2 rounded-lg text-sm border" style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}>
            Cancel
          </button>
          <button
            onClick={handleSubmit}
            disabled={saving || lines.some(l => !l.itemId || !l.productName) || (isDirect && !directCustomer)}
            className="flex items-center gap-1.5 px-5 py-2 rounded-lg text-sm font-semibold text-white disabled:opacity-40"
            style={{ background: 'var(--brand-fill)' }}
          >
            <Check size={15} /> {saving ? 'Saving…' : isRevising ? 'Issue Revised Quotation' : isEditing ? 'Save Changes' : 'Save Quotation'}
          </button>
        </div>
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
