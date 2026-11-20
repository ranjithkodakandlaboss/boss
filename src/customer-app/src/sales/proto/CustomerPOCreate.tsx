// @ts-nocheck
import { useEffect, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { ArrowLeft, CheckCircle2, FileText, Plus, Trash2 } from 'lucide-react';
import { useStore } from './runtime';
import { useReturnNavigation } from './useReturnNavigation';
import AddIfEmpty from './AddIfEmpty';
import { newCustomer, saveSite } from './quickMasters';
import type { CustomerPO } from './types';
import { salesApi } from '../../sales/api';
import { allPages } from '../../inventory/api';

const TODAY = new Date().toISOString().slice(0, 10);

interface DirectLine {
  id: string;
  itemId: string;
  uomId: string;
  uomSymbol: string;
  drawingNumber: string;
  description: string;
  orderedQty: number;
  rate: number;
  gstRate: number;
}

interface CatalogueItem {
  id: string;
  code: string;
  name: string;
  baseUomId: string;
  baseUomSymbol?: string;
}
interface OrderItem extends CatalogueItem {
  itemType?: string;
  uomId?: string;
  uomSymbol?: string;
}

export default function CustomerPOCreate() {
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const initialQuoteParam = searchParams.get('quotationId') ?? '';

  const {
    quotations, quotationLines, customerPos, customers: allCustomers, sites,
    addCustomer, reload, convertQuotationToPO, showToast,
  } = useStore();
  const customers = allCustomers.filter(c => c.status === 'active');
  const { goBack, originUrl } = useReturnNavigation('/sales?tab=pos');

  const available = quotations.filter(q => q.status === 'accepted' && !customerPos.some(po => po.quotationId === q.id));
  const [entryMode, setEntryMode] = useState<'quotation' | 'direct'>(
    initialQuoteParam || available.length > 0 ? 'quotation' : 'direct',
  );
  const [quotationId, setQuotationId] = useState(
    initialQuoteParam || (available[0]?.id ?? ''),
  );
  const selected = available.find(q => q.id === quotationId);
  const selectedQuoteLines = quotationLines.filter(l => l.quotationId === quotationId);

  // Direct Order state
  const [customerId, setCustomerId] = useState(() => searchParams.get('customer') || '');
  const [directReason, setDirectReason] = useState('');
  const [deliverToSiteId, setDeliverToSiteId] = useState(sites[0]?.id ?? '');
  const [paymentTerms, setPaymentTerms] = useState('');
  const [deliveryTerms, setDeliveryTerms] = useState('');
  const [customerPoRef, setCustomerPoRef] = useState('');
  const [confirmationNote, setConfirmationNote] = useState('');
  const [creating, setCreating] = useState(false);
  const [catalogueItems, setCatalogueItems] = useState<CatalogueItem[]>([]);
  const [directLines, setDirectLines] = useState<DirectLine[]>([
    { id: `dl-${Date.now()}`, itemId: '', uomId: '', uomSymbol: '', drawingNumber: '', description: '', orderedQty: 1, rate: 0, gstRate: 18 },
  ]);

  useEffect(() => {
    allPages<OrderItem>('/api/v1/items?status=active').then(items => {
      setCatalogueItems(items.filter(i => ['finished_good', 'finished_goods', 'semi_finished', 'traded', 'service'].includes((i.itemType ?? '').toLowerCase())).map(i => ({
        id: i.id, code: i.code, name: i.name,
        baseUomId: i.baseUomId ?? i.uomId ?? '',
        baseUomSymbol: i.baseUomSymbol ?? i.uomSymbol ?? '',
      })));
    }).catch(error => showToast({ type: 'error', title: 'Order items did not load', body: error instanceof Error ? error.message : 'Please try again.' }));
  }, []);

  // If quotations finish loading after mount and 'direct' was selected by default, switch to quotation mode
  useEffect(() => {
    if (!initialQuoteParam && available.length > 0 && entryMode === 'direct') {
      setEntryMode('quotation');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [available.length]);

  // Escape key closes/cancels the form
  useEffect(() => {
    const handler = (e: KeyboardEvent) => { if (e.key === 'Escape') goBack(); };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, []);

  const directCustomer = customers.find(c => c.id === customerId);
  const directTotal = directLines.reduce((s, l) => s + l.orderedQty * l.rate, 0);

  const create = async () => {
    if (creating) return;
    if (entryMode === 'quotation') {
      if (!selected) return;
      setCreating(true);
      const saved = await convertQuotationToPO(selected.id, {
        id: '', poNumber: '', customerId: selected.customerId, customerName: selected.customerName,
        acceptedDate: TODAY, paymentTerms: selected.paymentTerms ?? 'Net 30', status: 'active',
        totalValue: selected.totalValue, currency: selected.currency, lineCount: selectedQuoteLines.length,
        deliveryTerms: selected.deliveryTerms ?? '', quotationId: selected.id,
        customerPoRef, confirmationNote,
      });
      if (saved) navigate(`/sales/po/${saved.id}`, { state: { from: originUrl } });
      else setCreating(false);
      return;
    }
    // Direct Order
    if (!customerId || !deliverToSiteId || !directReason.trim() || directLines.some(l => !l.itemId || !l.uomId || !l.description.trim() || l.orderedQty <= 0)) {
      showToast({ type: 'error', title: 'Missing required fields', body: 'Select a customer, site, enter a reason, and ensure each line has an item, description, and quantity.' });
      return;
    }
    setCreating(true);
    try {
      const payTermsDays = paymentTerms ? parseInt(paymentTerms.replace(/\D/g, ''), 10) || 30 : 30;
      const saved = await salesApi.createDirectCpo({
        customerId,
        siteId: deliverToSiteId,
        directOrderReason: directReason,
        customerPoRef: customerPoRef || undefined,
        deliveryTerms: deliveryTerms || undefined,
        paymentTerms: payTermsDays,
        lines: directLines.map(l => ({
          itemId: l.itemId,
          uomId: l.uomId,
          drawingNumber: l.drawingNumber || undefined,
          description: l.description,
          quantity: l.orderedQty,
          unitPrice: l.rate,
          gstRate: l.gstRate,
        })),
      });
      await reload();
      navigate(`/sales/po/${saved.id}`, { state: { from: originUrl } });
    } catch (err) {
      showToast({ type: 'error', title: 'Could not create Direct Order', body: err instanceof Error ? err.message : 'Please try again.' });
      setCreating(false);
    }
  };

  return (
    <div className="flex flex-col h-full bg-[var(--bg)]">
      <div className="flex-shrink-0 flex items-center justify-between gap-3 px-6 py-4 border-b border-[var(--border)] bg-[var(--surface)]">
        <div className="flex items-center gap-3">
          <button type="button" onClick={() => goBack()} className="p-1.5 rounded-lg hover:bg-[var(--surface-2)]">
            <ArrowLeft size={18} className="text-[var(--text-2)]" />
          </button>
          <div>
            <h1 className="text-lg font-bold text-[var(--text)]">Add Customer PO</h1>
            <p className="text-xs text-[var(--text-2)] mt-0.5">
              Convert an accepted quotation or log a verified Direct Order without manufacturing upstream RFQs.
            </p>
          </div>
        </div>
        <div className="flex items-center gap-1 p-1 rounded-xl bg-[var(--surface-2)] border border-[var(--border)]">
          <button
            type="button"
            onClick={() => setEntryMode('quotation')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${
              entryMode === 'quotation' ? 'bg-[var(--surface)] text-[var(--text)] shadow-sm' : 'text-[var(--text-2)]'
            }`}
          >
            From Accepted Quotation
          </button>
          <button
            type="button"
            onClick={() => setEntryMode('direct')}
            className={`px-3 py-1.5 rounded-lg text-xs font-semibold ${
              entryMode === 'direct' ? 'bg-[var(--surface)] text-[var(--text)] shadow-sm' : 'text-[var(--text-2)]'
            }`}
          >
            Direct Order (No RFQ)
          </button>
        </div>
      </div>

      <div className="flex-1 overflow-auto p-6">
        <div className="space-y-5">
          {entryMode === 'quotation' ? (
            <>
              <div className="eoss-card p-5 space-y-4">
                <h2 className="text-sm font-bold text-[var(--text)]">Accepted Quotation Source</h2>
                {available.length === 0 ? (
                  <div className="rounded-lg p-4 text-sm bg-[var(--surface-2)] text-[var(--text-2)]">
                    No accepted quotations are currently pending conversion. Accept a quotation first or switch to <strong>Direct Order (No RFQ)</strong> above.
                  </div>
                ) : (
                  <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
                    <div className="md:col-span-2">
                      <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Accepted Quotation *</label>
                      <select value={quotationId} onChange={e => setQuotationId(e.target.value)} className="eoss-input w-full">
                        <option value="">Select an accepted quotation…</option>
                        {available.map(q => (
                          <option key={q.id} value={q.id}>
                            {q.quotationNumber} · {q.customerName} · ₹{q.totalValue.toLocaleString('en-IN')}
                          </option>
                        ))}
                      </select>
                    </div>
                    <div>
                      <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Fulfilling Site</label>
                      <AddIfEmpty
                        empty={sites.length === 0}
                        title="No sites yet"
                        hint="Add the plant or warehouse that will fulfil this order."
                        fields={[{ key: 'name', label: 'Site name' }]}
                        submitLabel="Save site"
                        onSubmit={async (values) => {
                          const saved = await saveSite(values.name || '');
                          const created = (saved.sites ?? []).find((site) => site.name === (values.name || '').trim());
                          await reload();
                          if (created?.id) setDeliverToSiteId(created.id);
                        }}
                      >
                        <select value={deliverToSiteId} onChange={e => setDeliverToSiteId(e.target.value)} className="eoss-input w-full">
                          <option value="">Select a site…</option>
                          {sites.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                        </select>
                      </AddIfEmpty>
                    </div>
                  </div>
                )}
              </div>

              {selected && (
                <div className="eoss-card overflow-hidden">
                  <div className="px-4 py-3 border-b border-[var(--border)] bg-[var(--surface-2)] flex items-center gap-2">
                    <FileText size={15} className="text-[var(--brand)]" />
                    <span className="text-sm font-semibold text-[var(--text)]">
                      {selected.quotationNumber} · {selected.customerName}
                    </span>
                  </div>
                  <div className="p-4 space-y-3">
                    <div className="grid grid-cols-3 gap-3">
                      <Summary label="Lines" value={String(selectedQuoteLines.length)} />
                      <Summary label="Total Value" value={`₹${selected.totalValue.toLocaleString('en-IN')}`} />
                      <Summary label="Payment Terms" value={selected.paymentTerms ?? 'Net 30 days'} />
                    </div>
                    <div className="grid md:grid-cols-2 gap-3">
                      <label className="block text-xs font-medium text-[var(--text-2)]">Customer PO reference
                        <input value={customerPoRef} onChange={e => setCustomerPoRef(e.target.value)} className="eoss-input w-full mt-1" />
                      </label>
                      <label className="block text-xs font-medium text-[var(--text-2)]">Confirmation note
                        <input value={confirmationNote} onChange={e => setConfirmationNote(e.target.value)} className="eoss-input w-full mt-1" />
                      </label>
                    </div>
                    <p className="text-xs text-[var(--text-2)]">
                      Accepting this quotation creates one Customer PO. The accepted price stays locked. Originating RFQ {selected.rfqNumber ? `(${selected.rfqNumber})` : ''} and quotation {selected.quotationNumber} stay linked.
                    </p>
                  </div>
                </div>
              )}
            </>
          ) : (
            <>
              <div className="eoss-card p-5 space-y-4">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold text-[var(--text)]">Direct Order Header</h2>
                  <span className="px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-amber-50 text-amber-800 border border-amber-200">
                    Direct Order — Standalone PO
                  </span>
                </div>
                <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
                  <div>
                    <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Customer *</label>
                    <AddIfEmpty
                      empty={customers.length === 0}
                      title="No customers yet"
                      hint="Add the business customer here."
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
                      <select className="eoss-input w-full" value={customerId} onChange={e => setCustomerId(e.target.value)}>
                        <option value="">Select customer…</option>
                        {customers.map(c => (
                          <option key={c.id} value={c.id}>{c.tradingName || c.legalName || 'Customer'}</option>
                        ))}
                      </select>
                    </AddIfEmpty>
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Fulfilling Site</label>
                    <AddIfEmpty
                      empty={sites.length === 0}
                      title="No sites yet"
                      hint="Add the plant or warehouse that will fulfil this order."
                      fields={[{ key: 'name', label: 'Site name' }]}
                      submitLabel="Save site"
                      onSubmit={async (values) => {
                        const saved = await saveSite(values.name || '');
                        const created = (saved.sites ?? []).find((site) => site.name === (values.name || '').trim());
                        await reload();
                        if (created?.id) setDeliverToSiteId(created.id);
                      }}
                    >
                      <select className="eoss-input w-full" value={deliverToSiteId} onChange={e => setDeliverToSiteId(e.target.value)}>
                        <option value="">Select a site…</option>
                        {sites.map(s => <option key={s.id} value={s.id}>{s.name}</option>)}
                      </select>
                    </AddIfEmpty>
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Payment Terms</label>
                    <input className="eoss-input w-full" placeholder="e.g. Net 30 days" value={paymentTerms} onChange={e => setPaymentTerms(e.target.value)} />
                  </div>
                  <div>
                    <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Delivery Terms</label>
                    <input className="eoss-input w-full" placeholder="e.g. Ex-Works / By Road" value={deliveryTerms} onChange={e => setDeliveryTerms(e.target.value)} />
                  </div>
                  <div className="md:col-span-2">
                    <label className="block text-xs font-semibold text-[var(--text-2)] mb-1">
                      Direct Order Reason / Origin Context *
                    </label>
                    <input
                      className="eoss-input w-full"
                      placeholder="e.g. Repeat blanket contract order received directly via customer email"
                      value={directReason}
                      onChange={e => setDirectReason(e.target.value)}
                    />
                  </div>
                </div>
              </div>

              <div className="eoss-card p-5 space-y-3">
                <div className="flex items-center justify-between">
                  <h2 className="text-sm font-bold text-[var(--text)]">Order Line Items ({directLines.length})</h2>
                  <button
                    type="button"
                    onClick={() =>
                      setDirectLines(ls => [
                        ...ls,
                        {
                          id: `dl-${Date.now()}`,
                          itemId: '',
                          uomId: '',
                          uomSymbol: '',
                          drawingNumber: '',
                          description: '',
                          orderedQty: 1,
                          rate: 0,
                          gstRate: 18,
                        },
                      ])
                    }
                    className="btn-ghost text-xs inline-flex items-center gap-1"
                  >
                    <Plus size={12} /> Add Line
                  </button>
                </div>
                <div className="space-y-2.5">
                  {directLines.map((l, idx) => (
                    <div key={l.id} className="p-3 rounded-xl border border-[var(--border)] bg-[var(--surface-2)]/40 space-y-2">
                      {/* Row 1: Item, Drawing #, GST % */}
                      <div className="grid grid-cols-2 md:grid-cols-5 gap-2 items-end">
                        <div className="col-span-2">
                          <label className="block text-[10px] text-[var(--text-3)] mb-0.5">Item *</label>
                          <select
                            className="eoss-input w-full text-xs"
                            value={l.itemId}
                            onChange={e => {
                              const item = catalogueItems.find(ci => ci.id === e.target.value);
                              setDirectLines(ls => ls.map((x, i) => i === idx ? { ...x, itemId: e.target.value, uomId: item?.baseUomId ?? '', uomSymbol: item?.baseUomSymbol ?? '' } : x));
                            }}
                          >
                            <option value="">Select item…</option>
                            {catalogueItems.map(ci => <option key={ci.id} value={ci.id}>{ci.code} — {ci.name}</option>)}
                          </select>
                        </div>
                        <div>
                          <label className="block text-[10px] text-[var(--text-3)] mb-0.5">Drawing # (optional)</label>
                          <input
                            className="eoss-input w-full text-xs font-mono"
                            placeholder="e.g. DRG-001"
                            value={l.drawingNumber}
                            onChange={e => setDirectLines(ls => ls.map((x, i) => (i === idx ? { ...x, drawingNumber: e.target.value } : x)))}
                          />
                        </div>
                        <div>
                          <label className="block text-[10px] text-[var(--text-3)] mb-0.5">GST % *</label>
                          <input
                            type="number"
                            min={0}
                            max={100}
                            step="0.01"
                            className="eoss-input w-full text-xs font-mono"
                            value={l.gstRate}
                            onChange={e => setDirectLines(ls => ls.map((x, i) => (i === idx ? { ...x, gstRate: Number(e.target.value) || 0 } : x)))}
                          />
                        </div>
                        <div />
                      </div>
                      {/* Row 2: Description, Qty, Rate, Total */}
                      <div className="grid grid-cols-2 md:grid-cols-5 gap-2 items-end">
                        <div className="col-span-2">
                          <label className="block text-[10px] text-[var(--text-3)] mb-0.5">Description *</label>
                          <input
                            className="eoss-input w-full text-xs"
                            placeholder="Brief description of the item/work *"
                            value={l.description}
                            onChange={e => setDirectLines(ls => ls.map((x, i) => (i === idx ? { ...x, description: e.target.value } : x)))}
                          />
                        </div>
                        <div>
                          <label className="block text-[10px] text-[var(--text-3)] mb-0.5">Qty {l.uomSymbol ? `(${l.uomSymbol})` : ''} *</label>
                          <input
                            type="number"
                            min={0.001}
                            step="any"
                            className="eoss-input w-full text-xs font-mono"
                            value={l.orderedQty}
                            onChange={e => setDirectLines(ls => ls.map((x, i) => (i === idx ? { ...x, orderedQty: Number(e.target.value) || 1 } : x)))}
                          />
                        </div>
                        <div>
                          <label className="block text-[10px] text-[var(--text-3)] mb-0.5">Rate (₹) *</label>
                          <input
                            type="number"
                            min={0}
                            className="eoss-input w-full text-xs font-mono"
                            value={l.rate}
                            onChange={e => setDirectLines(ls => ls.map((x, i) => (i === idx ? { ...x, rate: Number(e.target.value) || 0 } : x)))}
                          />
                        </div>
                        <div className="flex items-center justify-between gap-2">
                          <div>
                            <div className="text-[10px] text-[var(--text-3)]">Total</div>
                            <span className="font-mono text-xs font-semibold text-[var(--text)]">
                              ₹{(l.orderedQty * l.rate).toLocaleString('en-IN')}
                            </span>
                            {l.gstRate > 0 && (
                              <div className="text-[10px] text-[var(--text-3)]">+{l.gstRate}% GST</div>
                            )}
                          </div>
                          {directLines.length > 1 && (
                            <button
                              type="button"
                              onClick={() => setDirectLines(ls => ls.filter((_, i) => i !== idx))}
                              className="p-1 text-red-500 hover:bg-red-50 rounded"
                            >
                              <Trash2 size={13} />
                            </button>
                          )}
                        </div>
                      </div>
                    </div>
                  ))}
                </div>
                <div className="text-right font-mono text-sm font-bold text-[var(--text)]">
                  Order Total: ₹{Math.round(directTotal).toLocaleString('en-IN')}
                </div>
              </div>
            </>
          )}
        </div>
      </div>

      <div className="flex-shrink-0 flex justify-between px-6 py-4 border-t border-[var(--border)] bg-[var(--surface)]">
        <button type="button" onClick={() => goBack()} className="px-4 py-2 rounded-lg border border-[var(--border)] text-sm text-[var(--text-2)]">
          Cancel
        </button>
        <button
          type="button"
          disabled={
            creating || (entryMode === 'quotation'
              ? !selected
              : !directCustomer || !deliverToSiteId || !directReason.trim() || directLines.some(l => !l.itemId || !l.uomId || !l.description.trim() || l.orderedQty <= 0))
          }
          onClick={create}
          className="flex items-center gap-2 px-5 py-2 rounded-lg text-sm font-semibold text-white disabled:opacity-40"
          style={{ background: 'var(--brand-fill)' }}
        >
          <CheckCircle2 size={14} /> {creating ? 'Creating…' : (entryMode === 'quotation' ? 'Create Customer PO' : 'Record Direct Customer PO')}
        </button>
      </div>
    </div>
  );
}

function Summary({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <div className="text-[11px] text-[var(--text-3)]">{label}</div>
      <div className="text-sm font-semibold mt-0.5 text-[var(--text)]">{value}</div>
    </div>
  );
}
