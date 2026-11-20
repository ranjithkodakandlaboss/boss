// @ts-nocheck
import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { Search, Building2, Phone, Mail, ChevronRight, Star, Pencil } from 'lucide-react';
import { useStore } from './runtime';
import StatusChip, { getStatusLabel } from '../../components/shared/StatusChip';
import EmptyState from './workflow/EmptyState';
import SortableHeader from '../../components/shared/SortableHeader';
import { sortRows } from './tableSort';

type CustomerSortKey = 'customer' | 'gstin' | 'contact' | 'terms' | 'rfqs' | 'quotes' | 'pos' | 'rating' | 'status';

function countLabel(count: number, singular: string, plural: string) {
  return `${count} ${count === 1 ? singular : plural}`;
}

function normalizeText(value: string | null | undefined) {
  return (value ?? '').trim().toLowerCase().replace(/\s+/g, ' ');
}

function normalizeGstin(value: string | null | undefined) {
  return (value ?? '').replace(/\s+/g, '').toUpperCase();
}

export default function CustomerDirectory() {
  const navigate = useNavigate();
  const location = useLocation();
  const { customers, customerPos, rfqs, quotations } = useStore();
  const openCustomer = (id: string) => navigate(`/sales/customers/${id}`, { state: { background: location, from: `${location.pathname}${location.search}` } });
  const openRelated = (tab: 'rfqs' | 'quotations' | 'pos', customerId: string) => {
    navigate(`/sales?tab=${tab}&customer=${encodeURIComponent(customerId)}`);
  };
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState<'all' | 'active' | 'inactive'>('all');
  const [sortKey, setSortKey] = useState<CustomerSortKey>('customer');
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
  const filtered = customers.filter(c => {
    const q = normalizeText(search);
    const gstinQuery = normalizeGstin(search);
    const matchSearch = !q
      || normalizeText(c.legalName).includes(q)
      || normalizeText(c.tradingName).includes(q)
      || normalizeGstin(c.gstin).includes(gstinQuery)
      || normalizeText(c.city).includes(q);
    const matchStatus = statusFilter === 'all' || c.status === statusFilter;
    return matchSearch && matchStatus;
  });
  const sortedCustomers = sortRows(filtered, sortKey, sortDir, (customer, key) => {
    const poCount = customerPos.filter(po => po.customerId === customer.id).length;
    const rfqCount = rfqs.filter(rfq => rfq.customerId === customer.id).length;
    const quoteCount = quotations.filter(quote => quote.customerId === customer.id).length;
    const values: Record<string, unknown> = { customer: customer.tradingName, gstin: customer.gstin, contact: customer.contactPerson, terms: customer.paymentTerms, rfqs: rfqCount, quotes: quoteCount, pos: poCount, rating: customer.rating ?? 0, status: customer.status };
    return values[key] ?? customer.tradingName;
  });
  const handleSort = (key: CustomerSortKey) => {
    if (sortKey === key) setSortDir(direction => direction === 'asc' ? 'desc' : 'asc');
    else { setSortKey(key); setSortDir('asc'); }
  };

  return (
    <div className="flex flex-col h-full">
      {/* Filters */}
      <div className="flex-shrink-0 flex items-center gap-3 px-6 py-3 border-b flex-wrap" style={{ borderColor: 'var(--border)', background: 'var(--surface-2)' }}>
        <div className="eoss-search-wrap flex-1">
          <Search size={15} className="absolute left-3 top-1/2 -translate-y-1/2 text-[var(--text-2)]" />
          <input
            type="text"
            placeholder="Search customer name, GSTIN or city"
            value={search}
            onChange={e => setSearch(e.target.value)}
            className="eoss-search-input"
          />
        </div>
        <select value={statusFilter} onChange={e => setStatusFilter(e.target.value as typeof statusFilter)} className="eoss-input min-h-[36px]" aria-label="Customer status">
          <option value="all">All customer statuses</option>
          <option value="active">{getStatusLabel('active')}</option>
          <option value="inactive">{getStatusLabel('inactive')}</option>
        </select>
      </div>
      <p className="px-4 py-2 text-xs" style={{ color: 'var(--text-2)' }}>{filtered.length} of {customers.length} customers</p>

      {/* Table */}
      {filtered.length === 0 ? (
        <div className="flex-1 p-6"><EmptyState icon={<Building2 size={22} />} title="No customers found" body="Try adjusting your search or filters." /></div>
      ) : (
        <div className="flex-1 overflow-auto">
          <table className="eoss-table">
            <thead>
              <tr style={{ borderBottom: '1px solid var(--border)', background: 'var(--surface-2)' }}>
                {([['Customer', 'customer'], ['GSTIN / State', 'gstin'], ['Contact', 'contact'], ['Terms', 'terms'], ['RFQs', 'rfqs'], ['Quotes', 'quotes'], ['Customer POs', 'pos'], ['Rating', 'rating'], ['Status', 'status']] as [string, CustomerSortKey][]).map(([label, key]) => (
                  <SortableHeader key={label} label={label} active={sortKey === key} direction={sortDir} onSort={() => handleSort(key)} className="text-left px-4 py-2.5" />
                ))}
                <th />
              </tr>
            </thead>
            <tbody>
              {sortedCustomers.map((c, i) => (
                <tr
                  key={c.id}
                  className="hover:bg-[var(--surface-2)] cursor-pointer transition-colors"
                  style={{ borderBottom: i < filtered.length - 1 ? '1px solid var(--border)' : 'none' }}
                  onClick={() => openCustomer(c.id)}
                >
                  <td className="px-4 py-3">
                    <div className="font-semibold text-[var(--text)]">{c.tradingName}</div>
                    <div className="text-xs text-[var(--text-2)]">{c.legalName}</div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="text-xs font-mono text-[var(--text)]">{c.gstin}</div>
                    <div className="text-xs text-[var(--text-2)] whitespace-nowrap">{c.state} · {c.city}</div>
                  </td>
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-1 text-xs text-[var(--text-2)]">
                      <Phone size={11} /> {c.phone}
                    </div>
                    <div className="flex items-center gap-1 text-xs text-[var(--text-2)] mt-0.5 truncate max-w-[15rem]">
                      <Mail size={11} /> {c.email}
                    </div>
                  </td>
                  <td className="px-4 py-3 text-xs text-[var(--text-2)]">{c.paymentTerms}</td>
                  <td className="px-4 py-3">
                    <button type="button" onClick={e => { e.stopPropagation(); openRelated('rfqs', c.id); }} className="text-xs font-semibold text-[var(--brand)] hover:underline">
                      {countLabel(rfqs.filter(rfq => rfq.customerId === c.id).length, 'RFQ', 'RFQs')}
                    </button>
                  </td>
                  <td className="px-4 py-3">
                    <button type="button" onClick={e => { e.stopPropagation(); openRelated('quotations', c.id); }} className="text-xs font-semibold text-[var(--brand)] hover:underline">
                      {countLabel(quotations.filter(quote => quote.customerId === c.id).length, 'Quote', 'Quotes')}
                    </button>
                  </td>
                  <td className="px-4 py-3">
                    <button type="button" onClick={e => { e.stopPropagation(); openRelated('pos', c.id); }} className="text-xs font-semibold text-[var(--brand)] hover:underline">
                      {countLabel(customerPos.filter(po => po.customerId === c.id).length, 'PO', 'POs')}
                    </button>
                  </td>
                  <td className="px-4 py-3">
                    {c.rating != null && (
                      <div className="flex items-center gap-1 text-xs">
                        <Star size={11} className="text-amber-400 fill-amber-400" />
                        <span className="font-semibold text-[var(--text)]">{c.rating.toFixed(1)}</span>
                      </div>
                    )}
                  </td>
                  <td className="px-4 py-3">
                    <StatusChip status={c.status as 'active' | 'archived'} />
                  </td>
                  <td className="px-4 py-3 text-right flex items-center justify-end gap-1">
                    <button onClick={e => { e.stopPropagation(); navigate(`/sales/customers/${c.id}/edit`, { state: { background: location, from: `${location.pathname}${location.search}` } }); }} className="p-1 rounded hover:bg-[var(--surface-2)]" title="Edit customer">
                      <Pencil size={13} className="text-[var(--text-3)]" />
                    </button>
                    <ChevronRight size={15} className="text-[var(--text-2)]" />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

    </div>
  );
}
