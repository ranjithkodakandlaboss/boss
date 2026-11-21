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
  const { rfqs, customers, addCustomer, addQuotation, createDirectQuotation, currentUser, catalog, uoms, reload, showToast } = useStore();

  const rfq = rfqs.find(r => r.id === rfqId) as (typeof rfqs[0] & Record<string, any>) | undefined;
  const rfqLines: any[] = rfq?.lines ?? [];
  const isDirect = !rfqId;

  const [customerId, setCustomerId] = useState(() => searchParams.get('customer') ?? '');
  const [validDays, setValidDays] = useState('30');
  const [paymentTerms, setPaymentTerms] = useState('Net 30 days');
  const [deliveryTerms, setDeliveryTerms] = useState('Ex-Works / By Road · 2-3 weeks');
  const [discountAmount, setDiscountAmount] = useState(0);
  const [taxRatePct, setTaxRatePct] = useState(18);
  const [notes, setNotes] = useState('');
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
      labourHours: l.labourHours,
      machineHours: l.machineHours,
      rfqLineId: l.rfqLineId,
      specificationValues: l.specificationValues,
    }));

    setSaving(true);
    try {
      const saved = isDirect ? await createDirectQuotation(quotation, qLines) : await addQuotation(quotation, qLines);
      if (saved) {
        try {
          for (const file of files) await uploadLinkedDocument(file, { type: 'Quotation', id: saved.id, ref: saved.quotationNumber });
        } catch (error) {
          showToast({ type: 'error', title: 'Quotation saved; not all documents attached', body: `${error instanceof Error ? error.message : 'Upload failed.'} Attach the remaining files from the quotation view.` });
        }
        navigate(`/sales/quotation/${saved.id}`, { state: { from: rfq ? `/sales/rfq/${rfq.id}` : '/sales?tab=rfqs' } });
      }
      else setFormError('Quotation could not be saved. Please try again.');
    } catch (err: unknown) {
      setFormError(err instanceof Error ? err.message : 'Quotation could not be saved.');
    } finally {
      setSaving(false);
    }
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
                    <input type="number" min={0} value={l.unitPrice ?? ''} onChange={e => updateLine(l.id, 'unitPrice', e.target.value === '' ? undefined : parseFloat(e.target.value) || 0)} className="eoss-input" />
                  </Field>
                  <Field label="Line Value">
                    <input value={l.unitPrice != null ? fmtMoney(l.orderedQty * l.unitPrice) : '—'} disabled className="eoss-input opacity-60 cursor-not-allowed font-mono" />
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
                {(costingConfig?.labourRates.length || costingConfig?.machineRates.length) ? (
                  <div className="grid md:grid-cols-2 gap-3 mt-3">
                    {costingConfig.labourRates.map((rate) => (
                      <Field key={`labour-${rate.name}`} label={`${rate.name} hours`}>
                        <input type="number" min={0} step="0.1" value={l.labourHours[rate.name] ?? 0}
                          onChange={(event) => updateHours(l.id, 'labourHours', rate.name, event.target.value)} className="eoss-input w-full" />
                      </Field>
                    ))}
                    {costingConfig.machineRates.map((rate) => (
                      <Field key={`machine-${rate.name}`} label={`${rate.name} machine hours`}>
                        <input type="number" min={0} step="0.1" value={l.machineHours[rate.name] ?? 0}
                          onChange={(event) => updateHours(l.id, 'machineHours', rate.name, event.target.value)} className="eoss-input w-full" />
                      </Field>
                    ))}
                  </div>
                ) : null}
              </div>
            ))}

            <div className="mt-4 pt-3 border-t flex justify-end" style={{ borderColor: 'var(--border)' }}>
              <div className="text-right">
                <div className="text-xs text-[var(--text-3)]">Total Quotation Value</div>
                <div className="text-xl font-bold text-[var(--text)] font-mono">{fmtMoney(totalValue)}</div>
              </div>
            </div>
          </Card>

          <Card title="Estimated Costing">
            <p className="text-xs text-[var(--text-2)]">
              BOSS calculates the estimate from the approved BOM, weighted-average on-hand inventory valuation,
              configured rates, and the hours entered for each activity. The saved estimate and margin appear on
              the quotation before it can be submitted.
            </p>
            {costConfigMessage && <p role="alert" className="mt-2 text-xs text-[var(--error)]">{costConfigMessage}</p>}
            {canConfigureCosting && costingConfig && (
              <details className="mt-4 border-t pt-3" style={{ borderColor: 'var(--border)' }}>
                <summary className="cursor-pointer text-xs font-semibold text-[var(--brand)]">Configure costing and margin controls</summary>
                <div className="mt-3"><CostingConfigEditor initialConfig={costingConfig} onSaved={setCostingConfig} /></div>
              </details>
            )}
          </Card>

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
            <Check size={15} /> {saving ? 'Saving…' : 'Save Quotation'}
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
