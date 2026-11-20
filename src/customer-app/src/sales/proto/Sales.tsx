// @ts-nocheck
import React, { useEffect, useState } from 'react';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import {
  Plus, Search, ChevronRight, ArrowUpRight, Calendar,
  FileText, Briefcase, DollarSign, Clock, User, X, Pencil, XCircle
} from 'lucide-react';
import RecordSheet from '../../components/shared/RecordSheet';
import { useStore } from './runtime';
import AddIfEmpty from './AddIfEmpty';
import { newCustomer } from './quickMasters';
import StatusChip, { getStatusLabel } from '../../components/shared/StatusChip';
import clsx from 'clsx';
import type { CustomerRFQ, CustomerPO } from './types';
import SortableHeader from '../../components/shared/SortableHeader';
import { sortRows } from './tableSort';
import CustomerDirectory from './CustomerDirectory';

type POTab = 'pos' | 'rfqs' | 'quotations' | 'customers';
type SalesSortKey = 'number' | 'name' | 'date' | 'requiredBy' | 'value' | 'lines' | 'terms' | 'status' | 'channel' | 'quotation' | 'owner';

export default function Sales({ backgroundSearch }: { backgroundSearch?: string }) {
  const { rfqs, quotations, customerPos, customers, jobs, currentUser, showToast, updateCustomerPo, updateRfq, addCustomerPO } = useStore();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams, setSearchParams] = useSearchParams();
  const background = (location.state as { background?: { pathname: string; search: string }; from?: string } | null)?.background;
  const fromSearch = (location.state as { from?: string } | null)?.from ?? '';
  // When Sales renders as a background behind a sheet (e.g. /sales/customers/new),
  // location.pathname is the sheet path, not '/sales'. Recover the tab from navigation state.
  const listSearch = backgroundSearch ?? (background?.pathname === '/sales'
    ? background.search
    : location.pathname === '/sales'
      ? location.search
      : fromSearch.startsWith('/sales?') ? fromSearch.slice('/sales'.length) : '');
  const listParams = new URLSearchParams(listSearch.replace(/^\?/, ''));
  const requestedTab = listParams.get('tab');
  const tab: POTab = requestedTab === 'rfqs' || requestedTab === 'quotations' || requestedTab === 'customers' || requestedTab === 'pos' ? requestedTab : 'pos';
  const customerFilter = listParams.get('customer') ?? '';
  const selectTab = (next: POTab) => {
    const params = new URLSearchParams(listParams);
    params.set('tab', next);
    params.delete('customer');
    navigate({ pathname: '/sales', search: `?${params.toString()}` });
  };
  const openWithOrigin = (path: string) => {
    const list = background?.pathname === '/sales' ? background : { pathname: '/sales', search: location.pathname === '/sales' ? location.search : `?tab=${tab}` };
    navigate(path, { state: { background: list, from: `${list.pathname}${list.search}` } });
  };
  const [search, setSearch] = useState(() => searchParams.get('q') ?? '');
  const [statusFilter, setStatusFilter] = useState(() => searchParams.get('status') ?? 'all');
  const [rfqStatusFilter, setRfqStatusFilter] = useState(() => searchParams.get('rfqStatus') ?? 'all');
  const [sortKey, setSortKey] = useState<SalesSortKey>(() => (searchParams.get('sort') as SalesSortKey) || 'date');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>(() => (searchParams.get('dir') === 'asc' ? 'asc' : 'desc'));

  useEffect(() => {
    if (location.pathname !== '/sales' || background) return;
    const next = new URLSearchParams(searchParams);
    next.set('tab', tab);
    if (search.trim()) next.set('q', search.trim()); else next.delete('q');
    if (statusFilter !== 'all') next.set('status', statusFilter); else next.delete('status');
    if (rfqStatusFilter !== 'all') next.set('rfqStatus', rfqStatusFilter); else next.delete('rfqStatus');
    if (sortKey !== 'date') next.set('sort', sortKey); else next.delete('sort');
    if (sortDir !== 'desc') next.set('dir', sortDir); else next.delete('dir');
    if (next.toString() !== searchParams.toString()) setSearchParams(next, { replace: true });
  }, [tab, search, statusFilter, rfqStatusFilter, sortKey, sortDir, searchParams, setSearchParams, location.pathname, background]);
  const [selectedPoId, setSelectedPoId] = useState<string | null>(null);
  const [showNewRFQ, setShowNewRFQ] = useState(false);
  const [showDirectOrder, setShowDirectOrder] = useState(false);
  const [selectedRfq, setSelectedRfq] = useState<CustomerRFQ | null>(null);

  const handleCancelPo = (id: string) => {
    const po = customerPos.find(p => p.id === id);
    const linkedJobs = jobs.filter(j => j.customerPoId === id);
    const hasActiveJobs = linkedJobs.some(j => !['cancelled', 'complete'].includes(j.status));
    if (!po || po.status === 'complete' || po.status === 'cancelled' || hasActiveJobs) { showToast({ type: 'error', title: 'Cannot cancel PO', body: hasActiveJobs ? 'PO has active jobs. Cancel jobs first.' : 'This Customer PO is already closed or cancelled.' }); return; }
    updateCustomerPo(id, { status: 'cancelled' });
  };

  const handleDeleteRfq = (id: string) => {
    const rfq = rfqs.find(r => r.id === id);
    if (!rfq || rfq.status === 'cancelled') return;
    updateRfq(id, { status: 'cancelled' });
  };

  const canCreate = ['owner', 'sales'].includes(currentUser.role);

  const filteredPosBase = customerPos.filter(p => {
    const q = search.toLowerCase();
    const matchSearch = !q || p.poNumber.toLowerCase().includes(q) || p.customerName.toLowerCase().includes(q);
    const matchStatus = statusFilter === 'all' || p.status === statusFilter;
    return matchSearch && matchStatus && (!customerFilter || p.customerId === customerFilter);
  });
  const filteredPos = sortRows(filteredPosBase, sortKey, sortDir, (p, key) => {
    const values: Record<string, unknown> = {
      number: p.poNumber, name: p.customerName, date: p.acceptedDate, value: p.totalValue,
      lines: p.lineCount, terms: p.paymentTerms, status: p.status,
    };
    return values[key] ?? p.acceptedDate;
  });

  const filteredRfqsBase = rfqs.filter(r => {
    const q = search.toLowerCase();
    return (rfqStatusFilter === 'all' || r.status === rfqStatusFilter)
      && (!customerFilter || r.customerId === customerFilter)
      && (!q || r.rfqNumber.toLowerCase().includes(q) || r.customerName.toLowerCase().includes(q));
  });
  const filteredRfqs = sortRows(filteredRfqsBase, sortKey, sortDir, (r, key) => {
    const values: Record<string, unknown> = {
      number: r.rfqNumber, name: r.customerName, date: r.receivedDate, requiredBy: r.requiredBy, lines: r.lineCount,
      channel: r.channel, quotation: r.status === 'quoted' ? 'Quoted' : 'Pending', status: r.status, owner: r.owner,
    };
    return values[key] ?? r.receivedDate;
  });

  const latestQuotations = [...quotations.reduce((latest, quote) => {
    const key = quote.quotationNumber.replace(/-R\d+$/i, "");
    const current = latest.get(key);
    const thisRev = quote.revision ?? 1;
    const curRev = current ? (current.revision ?? 1) : -1;
    const thisIsSuperseded = quote.status === 'superseded';
    const curIsSuperseded = current ? current.status === 'superseded' : true;
    // Prefer non-superseded; among equal-status prefer higher revision
    if (!current || (!thisIsSuperseded && curIsSuperseded) || (thisIsSuperseded === curIsSuperseded && thisRev > curRev)) {
      latest.set(key, quote);
    }
    return latest;
  }, new Map<string, typeof quotations[number]>()).values()];
  const filteredQuotations = latestQuotations.filter(qt => {
    const q = search.toLowerCase();
    return (!customerFilter || qt.customerId === customerFilter)
      && (!q || qt.quotationNumber.toLowerCase().includes(q) || qt.customerName.toLowerCase().includes(q) || (qt.rfqNumber ?? '').toLowerCase().includes(q));
  });

  const handleSort = (key: SalesSortKey) => {
    if (sortKey === key) setSortDir(direction => direction === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('asc'); }
  };

  const selectedPo = customerPos.find(p => p.id === selectedPoId);
  const poJobs = selectedPo ? jobs.filter(j => j.customerPoId === selectedPo.id) : [];

  const totalActive = customerPos.filter(p => p.status === 'active').reduce((s, p) => s + p.totalValue, 0);

  const poStatuses = ['all', 'pending', 'active', 'partial', 'complete', 'cancelled'];

  const fmt = (d: string) => {
    if (!d) return "—";
    const match = /^(\d{4})-(\d{2})-(\d{2})/.exec(d);
    const date = match ? new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3])) : new Date(d);
    return Number.isNaN(date.getTime()) ? "—" : date.toLocaleDateString('en-IN', { day: 'numeric', month: 'short', year: '2-digit' });
  };

  return (
    <div className="flex flex-col h-full">
      {/* Page Header */}
      <div
        className="flex-shrink-0 px-6 py-4 border-b flex items-center justify-between gap-4"
        style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
      >
        <div>
          <h1 className="text-lg font-bold" style={{ color: 'var(--text)' }}>{tab === 'pos' ? 'Customer POs' : tab === 'rfqs' ? 'Customer RFQs' : tab === 'quotations' ? 'Quotations' : 'Customer Directory'}</h1>
          <p className="text-xs mt-0.5" style={{ color: 'var(--text-2)' }}>
            <button type="button" onClick={() => { selectTab('pos'); setStatusFilter('active'); }} className="font-semibold" style={{ color: 'var(--brand)' }}>
              {customerPos.filter(p => p.status === 'active').length} Active Customer POs
            </button>
            {' '}· {customers.filter(c => c.status === 'active').length} Active Customers · ₹{(totalActive / 100000).toFixed(1)}L in progress
          </p>
        </div>
        {canCreate && <div className="flex items-center gap-2">
          {tab === 'pos' && <button onClick={() => openWithOrigin('/sales/po/new')} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: 'var(--brand-fill)' }}><Plus size={15} /> Add Customer PO</button>}
          {tab === 'rfqs' && (
            <div className="flex items-center gap-2">
              <button onClick={() => openWithOrigin('/sales/quotation/new')} className="flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-semibold border border-[var(--border)] bg-[var(--surface)] text-[var(--text)]">
                <Plus size={14} /> Direct Quotation
              </button>
              <button onClick={() => openWithOrigin('/sales/rfq/new')} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: 'var(--brand-fill)' }}><Plus size={15} /> Add RFQ</button>
            </div>
          )}
          {tab === 'customers' && <button onClick={() => openWithOrigin('/sales/customers/new')} className="flex items-center gap-1.5 px-4 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: 'var(--brand-fill)' }}><Plus size={15} /> Add New Customer</button>}
        </div>}
      </div>

      {/* Workspace Tabs — amber underline style */}
      <div
        className="flex-shrink-0 flex items-center gap-0 px-6 border-b"
        style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
      >
        {([
          { id: 'pos', label: `Customer POs (${filteredPos.length})` },
          { id: 'rfqs', label: `RFQs (${filteredRfqs.length})` },
          { id: 'quotations', label: `Quotations (${filteredQuotations.length})` },
          { id: 'customers', label: `Customer Directory (${customers.filter(c => c.status === 'active').length})` },
        ] as { id: POTab; label: string }[]).map(t => (
          <button
            key={t.id}
            onClick={() => selectTab(t.id)}
            className={clsx(
              'px-4 py-3 text-sm font-medium transition-colors relative whitespace-nowrap',
              tab === t.id
                ? 'font-semibold'
                : 'hover:opacity-80'
            )}
            style={{ color: tab === t.id ? 'var(--brand)' : 'var(--text-2)' }}
          >
            {t.label}
            {tab === t.id && (
              <span
                className="absolute bottom-0 left-0 right-0 h-0.5 rounded-t-full"
                style={{ background: 'var(--brand-fill)' }}
              />
            )}
          </button>
        ))}
      </div>

      {tab === 'customers' ? (
        <div className="flex-1 overflow-auto">
          <CustomerDirectory />
        </div>
      ) : <>
      {/* Filter Bar */}
      <div
        className="flex-shrink-0 flex items-center gap-3 px-6 py-3 border-b"
        style={{ borderColor: 'var(--border)', background: 'var(--surface-2)' }}
      >
        <div className="eoss-search-wrap flex-1">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--text-3)' }} />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder={tab === 'rfqs' ? 'Search RFQ number or customer name' : tab === 'quotations' ? 'Search quotation number, RFQ or customer' : 'Search Customer PO number or customer name'}
            className="eoss-search-input"
          />
        </div>
        {tab === 'pos' && <select value={statusFilter} onChange={e => setStatusFilter(e.target.value)} className="eoss-input min-h-[36px]" aria-label="Customer PO status"><option value="all">All Customer PO statuses</option>{poStatuses.filter(s => s !== 'all').map(s => <option key={s} value={s}>{getStatusLabel(s)}</option>)}</select>}
        {tab === 'rfqs' && <select value={rfqStatusFilter} onChange={e => setRfqStatusFilter(e.target.value)} className="eoss-input min-h-[36px]" aria-label="Customer RFQ status"><option value="all">All Customer RFQ statuses</option>{['open', 'quoted', 'converted', 'lost', 'cancelled'].map(s => <option key={s} value={s}>{getStatusLabel(s)}</option>)}</select>}
        {search && (
          <button
            onClick={() => setSearch('')}
            className="text-xs underline"
            style={{ color: 'var(--text-3)' }}
          >
            Clear
          </button>
        )}
      </div>

      {/* Content — master-detail split */}
      <div className="flex flex-1 overflow-hidden">
        {/* List panel */}
        <div
          className={clsx(
            'overflow-auto',
            selectedPo ? 'w-[58%] border-r' : 'w-full'
          )}
          style={{ borderColor: 'var(--border)' }}
        >
          {tab === 'pos' && (
            <>
            <div className="md:hidden p-3 space-y-2" aria-hidden="true">
              {filteredPos.map(p => (
                <button key={p.id} type="button" onClick={() => openWithOrigin(`/sales/po/${p.id}`)} tabIndex={-1}
                  className="w-full text-left rounded-xl border p-3" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
                  <div className="flex items-center justify-between gap-2">
                    <span className="font-mono text-xs font-bold" style={{ color: 'var(--brand)' }}>{p.poNumber}</span>
                    <StatusChip status={p.status} />
                  </div>
                  <div className="text-sm font-semibold text-[var(--text)] mt-1">{p.customerName}</div>
                  <div className="text-xs text-[var(--text-2)] mt-1">₹{(p.totalValue / 100000).toFixed(1)}L · {p.paymentTerms}</div>
                </button>
              ))}
            </div>
            <table className="eoss-table hidden md:table">
              <thead className="sticky top-0 z-10" style={{ background: 'var(--surface-2)' }}>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  {([['PO Number', 'number'], ['Customer', 'name'], ['Date', 'date'], ['Value', 'value'], ['Lines', 'lines'], ['Terms', 'terms'], ['Status', 'status']] as [string, SalesSortKey][]).map(([label, key]) => (
                    <SortableHeader key={label} label={label} active={sortKey === key} direction={sortDir} onSort={() => handleSort(key)} className="text-left px-4 py-2.5 text-xs font-semibold font-medium" style={{ color: 'var(--text-3)' }} />
                  ))}
                  <th />
                </tr>
              </thead>
              <tbody>
                {filteredPos.map(p => {
                  const isSelected = p.id === selectedPoId;
                  return (
                    <tr
                      key={p.id}
                      onClick={() => setSelectedPoId(isSelected ? null : p.id)}
                      className="cursor-pointer transition-colors"
                      style={{
                        borderBottom: '1px solid var(--border)',
                        background: isSelected ? 'var(--brand-50, rgba(var(--brand-rgb,112,72,245),0.06))' : 'var(--surface)',
                        borderLeft: isSelected ? '3px solid var(--brand)' : '3px solid transparent',
                      }}
                      onMouseEnter={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)'; }}
                      onMouseLeave={e => { if (!isSelected) (e.currentTarget as HTMLElement).style.background = 'var(--surface)'; }}
                    >
                      <td className="px-4 py-3">
                        <div className="flex items-center gap-1.5">
                          <span className="font-mono text-xs font-bold" style={{ color: 'var(--brand)' }}>{p.poNumber}</span>
                          {p.isDirectOrder && (
                            <span className="px-1.5 py-0.5 rounded text-[10px] font-semibold bg-amber-50 text-amber-800 border border-amber-200">
                              Direct Order
                            </span>
                          )}
                        </div>
                        {p.availabilityStatus && p.availabilityStatus !== 'not_assessed' && (
                          <div className="mt-1"><StatusChip status={p.availabilityStatus} size="sm" /></div>
                        )}
                        {p.hasAmendment && <div className="text-[10px] mt-0.5 font-medium" style={{ color: 'var(--warning)' }}>Amendment</div>}
                      </td>
                      <td className="px-4 py-3 font-medium" style={{ color: 'var(--text)' }}>{p.customerName}</td>
                      <td className="px-4 py-3 text-xs" style={{ color: 'var(--text-2)' }}>
                        {fmt(p.acceptedDate)}
                      </td>
                      <td className="px-4 py-3 font-mono font-semibold text-xs" style={{ color: 'var(--text)' }}>
                        ₹{(p.totalValue / 100000).toFixed(1)}L
                      </td>
                      <td className="px-4 py-3 text-left text-xs" style={{ color: 'var(--text-2)' }}>{p.lineCount}</td>
                      <td className="px-4 py-3 text-xs" style={{ color: 'var(--text-2)' }}>{p.paymentTerms}</td>
                      <td className="px-4 py-3">
                        <StatusChip status={p.status} />
                      </td>
                      <td className="px-4 py-3 w-[100px]" onClick={e => e.stopPropagation()}>
                        <div className="flex items-center gap-1">
                          <button type="button" onClick={() => openWithOrigin(`/sales/po/${p.id}`)}
                            className="text-xs px-2 py-1 rounded-lg font-semibold text-white" style={{ background: 'var(--brand-fill)' }}>
                            Open
                          </button>
                          <span className="inline-flex items-center gap-0.5">
                            {canCreate && p.status !== 'cancelled' && p.status !== 'complete' && jobs.filter(j => j.customerPoId === p.id).every(j => !['in_progress', 'complete'].includes(j.status)) ? (
                              <button onClick={() => openWithOrigin(`/sales/po/${p.id}/amend`)} className="p-1 rounded hover:bg-[var(--surface-2)]" title="Amend Customer PO"><Pencil size={12} style={{ color: 'var(--text-3)' }} /></button>
                            ) : <span className="inline-block w-[20px]" />}
                            {canCreate && p.status !== 'cancelled' && p.status !== 'complete' && jobs.filter(j => j.customerPoId === p.id).every(j => ['cancelled', 'complete'].includes(j.status)) ? (
                              <button onClick={() => handleCancelPo(p.id)} className="p-1 rounded hover:bg-[var(--surface-2)]" title="Cancel Customer PO"><XCircle size={12} style={{ color: 'var(--error,#DC2626)' }} /></button>
                            ) : <span className="inline-block w-[20px]" />}
                          </span>
                        </div>
                      </td>
                    </tr>
                  );
                })}
                {filteredPos.length === 0 && (
                  <tr><td colSpan={8} className="text-center py-12 text-sm" style={{ color: 'var(--text-3)' }}>No POs match the current filters</td></tr>
                )}
              </tbody>
            </table>
            </>
          )}

          {tab === 'rfqs' && (
            <table className="eoss-table">
              <thead className="sticky top-0 z-10" style={{ background: 'var(--surface-2)' }}>
                <tr style={{ borderBottom: '1px solid var(--border)' }}>
                  {([['RFQ Number', 'number'], ['Customer', 'name'], ['Received', 'date'], ['Required By', 'requiredBy'], ['Lines', 'lines'], ['Channel', 'channel'], ['Quotation', 'quotation'], ['Status', 'status'], ['Owner', 'owner']] as [string, SalesSortKey][]).map(([label, key]) => (
                    <SortableHeader key={label} label={label} active={sortKey === key} direction={sortDir} onSort={() => handleSort(key)} className="text-left px-4 py-2.5 text-xs font-semibold font-medium" style={{ color: 'var(--text-3)' }} />
                  ))}
                  <th />
                </tr>
              </thead>
              <tbody>
                {filteredRfqs.map(r => (
                  <tr
                    key={r.id}
                    className="cursor-pointer transition-colors"
                    style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface)' }}
                    onMouseEnter={e => { (e.currentTarget as HTMLElement).style.background = 'var(--surface-2)'; }}
                    onMouseLeave={e => { (e.currentTarget as HTMLElement).style.background = 'var(--surface)'; }}
                    onClick={() => openWithOrigin(`/sales/rfq/${r.id}`)}
                  >
                    <td className="px-4 py-3 font-mono text-xs font-bold" style={{ color: 'var(--brand)' }}>{r.rfqNumber}</td>
                    <td className="px-4 py-3 font-medium" style={{ color: 'var(--text)' }}>{r.customerName}</td>
                    <td className="px-4 py-3 text-xs" style={{ color: 'var(--text-2)' }}>{fmt(r.receivedDate)}</td>
                    <td className="px-4 py-3 text-xs" style={{ color: 'var(--text-2)' }}>{fmt(r.requiredBy)}</td>
                    <td className="px-4 py-3 text-left text-xs" style={{ color: 'var(--text-2)' }}>{r.lineCount}</td>
                    <td className="px-4 py-3 text-xs" style={{ color: 'var(--text-2)' }}>{r.channel}</td>
                    <td className="px-4 py-3 text-xs" onClick={e => e.stopPropagation()}>
                      {(() => {
                        const q = quotations.find(qt => qt.rfqId === r.id);
                        return q ? <button type="button" onClick={() => openWithOrigin(`/sales/quotation/${q.id}`)} className="font-mono font-semibold text-[var(--brand)] hover:underline">{q.quotationNumber}</button> : <span className="text-[var(--text-3)]">Pending</span>;
                      })()}
                    </td>
                    <td className="px-4 py-3"><StatusChip status={r.status} /></td>
                    <td className="px-4 py-3 text-xs" style={{ color: 'var(--text-2)' }}>{r.owner}</td>
                    <td className="px-4 py-3" onClick={e => e.stopPropagation()}>
                      <div className="flex items-center gap-1">
                        {canCreate && r.status === 'open' && (
                          <button onClick={e => { e.stopPropagation(); openWithOrigin(`/sales/rfq/${r.id}/quotation/new`); }}
                            className="text-xs px-2 py-1 rounded-lg font-semibold text-white" style={{ background: 'var(--brand-fill)' }}>
                            Quote
                          </button>
                        )}
                        {canCreate && r.status !== 'cancelled' && (
                          <button onClick={e => { e.stopPropagation(); handleDeleteRfq(r.id); }} className="p-1 rounded hover:bg-[var(--surface-2)]" title="Cancel Customer RFQ"><XCircle size={12} style={{ color: 'var(--error,#DC2626)' }} /></button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
                {filteredRfqs.length === 0 && (
                  <tr><td colSpan={10} className="text-center py-12 text-sm" style={{ color: 'var(--text-3)' }}>No RFQs found</td></tr>
                )}
              </tbody>
            </table>
          )}
          {tab === 'quotations' && (
            <table className="w-full text-sm">
              <thead style={{ background: 'var(--surface-2)', borderBottom: '1px solid var(--border)' }}>
                <tr>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">Quotation #</th>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">Customer</th>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">RFQ Ref</th>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">Quoted Date</th>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">Valid Until</th>
                  <th className="text-right px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">Total Value</th>
                  <th className="text-left px-4 py-2.5 text-xs font-semibold text-[var(--text-3)]">Status</th>
                </tr>
              </thead>
              <tbody>
                {filteredQuotations.map(qt => (
                  <tr
                    key={qt.id}
                    onClick={() => openWithOrigin(`/sales/quotation/${qt.id}`)}
                    className="cursor-pointer transition-colors hover:bg-[var(--surface-2)]"
                    style={{ borderBottom: '1px solid var(--border)' }}
                  >
                    <td className="px-4 py-3 font-mono text-xs font-bold text-[var(--brand)]">{qt.quotationNumber}</td>
                    <td className="px-4 py-3 text-sm font-medium text-[var(--text)]">{qt.customerName}</td>
                    <td className="px-4 py-3 font-mono text-xs text-[var(--text-2)]">{qt.rfqNumber ?? '—'}</td>
                    <td className="px-4 py-3 text-xs text-[var(--text-2)]">{fmt(qt.quotationDate)}</td>
                    <td className="px-4 py-3 text-xs text-[var(--text-2)]">{fmt(qt.validUntil)}</td>
                    <td className="px-4 py-3 text-right font-mono text-xs font-semibold text-[var(--text)]">₹{(qt.totalAmount ?? qt.totalValue ?? 0).toLocaleString('en-IN')}</td>
                    <td className="px-4 py-3"><StatusChip status={qt.status} /></td>
                  </tr>
                ))}
                {filteredQuotations.length === 0 && (
                  <tr><td colSpan={7} className="text-center py-12 text-sm" style={{ color: 'var(--text-3)' }}>No quotations found</td></tr>
                )}
              </tbody>
            </table>
          )}
        </div>

        {/* Detail Preview Panel */}
        {selectedPo && (
          <div className="w-[42%] overflow-auto flex flex-col" style={{ background: 'var(--surface)' }}>
            {/* Preview Header */}
            <div className="px-5 py-4 border-b flex items-start justify-between" style={{ borderColor: 'var(--border)' }}>
              <div>
                <div className="flex items-center gap-2 flex-wrap">
                  <span className="font-mono font-bold text-base" style={{ color: 'var(--brand)' }}>{selectedPo.poNumber}</span>
                  <StatusChip status={selectedPo.status} />
                  {selectedPo.hasAmendment && (
                    <span className="text-[10px] px-2 py-0.5 rounded-full border font-medium" style={{ background: 'var(--warning-bg)', color: 'var(--warning)', borderColor: 'var(--warning)' }}>
                      Amendment
                    </span>
                  )}
                </div>
                <p className="text-sm mt-0.5 font-medium" style={{ color: 'var(--text)' }}>{selectedPo.customerName}</p>
              </div>
              <button
                onClick={() => openWithOrigin(`/sales/po/${selectedPo.id}`)}
                className="flex items-center gap-1 text-xs font-semibold px-3 py-1.5 rounded-lg border transition-colors hover:opacity-80"
                style={{ borderColor: 'var(--brand)', color: 'var(--brand)' }}
              >
                Open full record <ArrowUpRight size={12} />
              </button>
            </div>

            {/* Value Summary */}
            <div className="px-5 py-4 border-b grid grid-cols-3 gap-4" style={{ borderColor: 'var(--border)', background: 'var(--surface-2)' }}>
              <Metric icon={<DollarSign size={14} />} label="Total Value" value={`₹${(selectedPo.totalValue / 100000).toFixed(2)}L`} />
              <Metric icon={<FileText size={14} />} label="Line Items" value={String(selectedPo.lineCount)} />
              <Metric icon={<Clock size={14} />} label="Terms" value={selectedPo.paymentTerms} small />
            </div>

            {/* Detail Fields */}
            <div className="px-5 py-4 space-y-4 flex-1">
              <Section title="PO Details">
                <Field label="Accepted Date" value={fmt(selectedPo.acceptedDate)} />
                <Field label="Delivery Terms" value={selectedPo.deliveryTerms} />
                <Field label="Currency" value={selectedPo.currency} />
                {selectedPo.quotationRef && <Field label="Quotation Ref" value={selectedPo.quotationRef} mono />}
              </Section>

              <Section title={`Linked Jobs (${poJobs.length})`}>
                {poJobs.length === 0 ? (
                  <p className="text-xs" style={{ color: 'var(--text-3)' }}>No jobs linked to this PO yet.</p>
                ) : (
                  <div className="space-y-2">
                    {poJobs.map(j => (
                      <button
                        key={j.id}
                        onClick={() => navigate(`/jobs/${j.id}`)}
                        className="w-full flex items-center justify-between px-3 py-2 rounded-lg border text-left hover:opacity-80 transition-opacity"
                        style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
                      >
                        <div>
                          <div className="text-xs font-mono font-bold" style={{ color: 'var(--brand)' }}>{j.jobNumber}</div>
                          <div className="text-xs mt-0.5" style={{ color: 'var(--text-2)' }}>{j.currentStage} · {getStatusLabel(j.status)}</div>
                        </div>
                        <div className="flex items-center gap-2">
                          <StatusChip status={j.status} />
                          <ChevronRight size={12} style={{ color: 'var(--text-3)' }} />
                        </div>
                      </button>
                    ))}
                  </div>
                )}
              </Section>

              <div className="pt-2 flex gap-2">
                <button
                  onClick={() => openWithOrigin(`/sales/po/${selectedPo.id}`)}
                  className="flex-1 py-2 rounded-lg text-sm font-semibold text-white transition-opacity hover:opacity-90"
                  style={{ background: 'var(--brand-fill)' }}
                >
                  Open Full Record
                </button>
                <button
                  onClick={() => openWithOrigin(`/sales/po/${selectedPo.id}/amend`)}
                  className="px-4 py-2 rounded-lg text-sm font-semibold border transition-colors hover:opacity-80"
                  style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}
                >
                  Amend
                </button>
              </div>
            </div>
          </div>
        )}
      </div>


      {/* New RFQ Modal */}
      {showNewRFQ && (
        <NewRFQModal
          onClose={() => setShowNewRFQ(false)}
          onSave={(rfq) => {
            showToast({ type: 'success', title: 'RFQ created', body: `${rfq.rfqNumber} opened.` });
            setShowNewRFQ(false);
            selectTab('rfqs');
          }}
        />
      )}
      {showDirectOrder && <DirectOrderModal onClose={() => setShowDirectOrder(false)} onSave={(po) => { addCustomerPO(po); showToast({ type: 'success', title: 'Direct customer order created', body: `${po.poNumber} is ready for review.` }); setShowDirectOrder(false); openWithOrigin(`/sales/po/${po.id}`); }} />}

      {/* RFQ Detail Drawer */}
      {selectedRfq && (
        <RecordSheet
          title={selectedRfq.rfqNumber}
          onClose={() => setSelectedRfq(null)}
          primary={{ label: 'Convert to PO', onClick: () => { showToast({ type: 'success', title: 'PO Created', body: `Purchase Order raised from ${selectedRfq.rfqNumber} for ${selectedRfq.customerName}.` }); setSelectedRfq(null); } }}
          secondary={{ label: 'Close', onClick: () => setSelectedRfq(null) }}
        >
          <div className="space-y-3 text-sm">
            <div className="flex justify-between">
              <span className="text-[var(--text-3)]">Customer</span>
              <span className="font-medium text-[var(--text)]">{selectedRfq.customerName}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[var(--text-3)]">Status</span>
              <StatusChip status={selectedRfq.status} />
            </div>
            <div className="flex justify-between">
              <span className="text-[var(--text-3)]">Received</span>
              <span className="text-[var(--text)]">{fmt(selectedRfq.receivedDate)}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[var(--text-3)]">Required By</span>
              <span className="text-[var(--text)]">{fmt(selectedRfq.requiredBy)}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[var(--text-3)]">Channel</span>
              <span className="text-[var(--text)]">{selectedRfq.channel}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[var(--text-3)]">Line Items</span>
              <span className="text-[var(--text)]">{selectedRfq.lineCount}</span>
            </div>
            <div className="flex justify-between">
              <span className="text-[var(--text-3)]">Owner</span>
              <span className="text-[var(--text)]">{selectedRfq.owner}</span>
            </div>
          </div>
        </RecordSheet>
      )}
      </>}
    </div>
  );
}

function NewRFQModal({ onClose, onSave }: { onClose: () => void; onSave: (r: CustomerRFQ) => void }) {
  const { currentUser, addRfq, addCustomer, customers: CUSTOMERS } = useStore();
  const [form, setForm] = useState({
    customerId: '', customerName: '', requiredBy: '', channel: 'Email', lineCount: '1',
  });
  const [err, setErr] = useState('');
  const save = () => {
    if (!form.customerId || !form.requiredBy) { setErr('Customer and required-by date are required'); return; }
    const id = `rfq-${Date.now()}`;
    const num = `RFQ-2026-${String(Math.floor(Math.random() * 900 + 100))}`;
    const rfq: CustomerRFQ = { id, rfqNumber: num, customerId: form.customerId, customerName: form.customerName, receivedDate: '2026-11-05', requiredBy: form.requiredBy, status: 'open', lineCount: Number(form.lineCount), channel: form.channel as CustomerRFQ['channel'], owner: currentUser.name };
    addRfq(rfq);
    onSave(rfq);
  };
  return (
    <RecordSheet
      title="New RFQ"
      onClose={onClose}
      primary={{ label: 'Create RFQ', onClick: save }}
      secondary={{ label: 'Cancel', onClick: onClose }}
    >
      <div className="space-y-3">
        <div>
          <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Customer *</label>
          <AddIfEmpty
            empty={CUSTOMERS.length === 0}
            title="No customers yet"
            hint="Add the business customer, then pick it for this RFQ."
            fields={[{ key: 'legalName', label: 'Legal name' }, { key: 'city', label: 'City' }]}
            submitLabel="Save customer"
            onSubmit={async (values) => {
              const name = (values.legalName || '').trim();
              const city = (values.city || '').trim();
              if (!name || !city) throw new Error('Enter a legal name and city.');
              const saved = await addCustomer(newCustomer(name, city));
              if (!saved?.id) throw new Error('Customer was not saved.');
              setForm(f => ({ ...f, customerId: saved.id, customerName: saved.tradingName || saved.legalName }));
            }}
          >
            <select className="eoss-input w-full" value={form.customerId} onChange={e => {
              const c = CUSTOMERS.find(x => x.id === e.target.value);
              setForm(f => ({ ...f, customerId: e.target.value, customerName: c?.tradingName ?? c?.legalName ?? '' }));
            }}>
              <option value="">Select customer…</option>
              {CUSTOMERS.map(c => <option key={c.id} value={c.id}>{c.tradingName || c.legalName || 'Customer'}</option>)}
            </select>
          </AddIfEmpty>
        </div>
        <div>
          <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Required By *</label>
          <input type="date" className="eoss-input w-full" value={form.requiredBy} onChange={e => setForm(f => ({ ...f, requiredBy: e.target.value }))} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Channel</label>
            <select className="eoss-input w-full" value={form.channel} onChange={e => setForm(f => ({ ...f, channel: e.target.value }))}>
              {['Email', 'Portal', 'WhatsApp', 'Phone', 'Tender'].map(c => <option key={c}>{c}</option>)}
            </select>
          </div>
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Line Items</label>
            <input type="number" className="eoss-input w-full" value={form.lineCount} onChange={e => setForm(f => ({ ...f, lineCount: e.target.value }))} min="1" />
          </div>
        </div>
      </div>
      {err && <p className="text-xs text-[var(--danger)] mt-2">{err}</p>}
    </RecordSheet>
  );
}

function DirectOrderModal({ onClose, onSave }: { onClose: () => void; onSave: (po: CustomerPO) => void }) {
  const { customers, currentUser, addCustomer } = useStore();
  const [customerId, setCustomerId] = useState('');
  const [value, setValue] = useState('');
  const customer = customers.find(c => c.id === customerId);
  const submit = () => {
    if (!customer || !value || Number(value) <= 0) return;
    const id = `cpo-direct-${Date.now()}`;
    onSave({ id, poNumber: `DPO-${String(Date.now()).slice(-6)}`, customerId, customerName: customer.tradingName || customer.legalName, acceptedDate: new Date().toISOString().slice(0, 10), paymentTerms: customer.paymentTerms, status: 'active', totalValue: Number(value), currency: customer.currency, lineCount: 1, deliveryTerms: 'To be confirmed' });
  };
  return (
    <RecordSheet title="Direct customer order" subtitle="Use only when no quotation was prepared. The record will be clearly labelled." onClose={onClose} secondary={{ label: "Cancel", onClick: onClose }} primary={{ label: "Create direct order", onClick: submit, disabled: !customer || !value }}>
      <div className="space-y-3">
        <label className="block text-xs text-[var(--text-2)]">Customer
          <AddIfEmpty empty={customers.length === 0} title="No customers yet" hint="Add the business customer here." fields={[{ key: 'legalName', label: 'Legal name' }, { key: 'city', label: 'City' }]} submitLabel="Save customer" onSubmit={async (values) => { const name = (values.legalName || '').trim(); const city = (values.city || '').trim(); if (!name || !city) throw new Error('Enter a legal name and city.'); const saved = await addCustomer(newCustomer(name, city)); if (!saved?.id) throw new Error('Customer was not saved.'); setCustomerId(saved.id); }}>
            <select className="eoss-input w-full mt-1" value={customerId} onChange={e => setCustomerId(e.target.value)}>
              <option value="">Select customer…</option>
              {customers.map(c => <option key={c.id} value={c.id}>{c.tradingName || c.legalName || 'Customer'}</option>)}
            </select>
          </AddIfEmpty>
        </label>
        <label className="block text-xs text-[var(--text-2)]">Order value
          <input type="number" min="1" className="eoss-input w-full mt-1" value={value} onChange={e => setValue(e.target.value)} />
        </label>
        <p className="text-[11px] text-[var(--text-3)]">Created by {currentUser.name}. Credit review is applied using the demo customer account.</p>
      </div>
    </RecordSheet>
  );
}

function Metric({ icon, label, value, small }: { icon: React.ReactNode; label: string; value: string; small?: boolean }) {
  return (
    <div>
      <div className="flex items-center gap-1 mb-1" style={{ color: 'var(--text-3)' }}>
        {icon}
        <span className="text-[10px] font-medium">{label}</span>
      </div>
      <div className={clsx('font-bold', small ? 'text-sm' : 'text-base')} style={{ color: 'var(--text)' }}>{value}</div>
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div>
      <div className="text-xs font-semibold font-medium mb-2" style={{ color: 'var(--text-3)' }}>{title}</div>
      <div className="space-y-2.5">{children}</div>
    </div>
  );
}

function Field({ label, value, mono }: { label: string; value: string; mono?: boolean }) {
  return (
    <div className="flex justify-between items-baseline gap-2">
      <span className="text-xs" style={{ color: 'var(--text-3)' }}>{label}</span>
      <span className={clsx('text-xs font-medium text-right', mono ? 'font-mono' : '')} style={{ color: 'var(--text)' }}>{value}</span>
    </div>
  );
}
