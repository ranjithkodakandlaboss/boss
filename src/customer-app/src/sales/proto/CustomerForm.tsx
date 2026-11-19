// @ts-nocheck
import React, { useState } from 'react';
import { ArrowLeft, Save } from 'lucide-react';
import type { Party } from './types';

const INDIAN_STATES = [
  'Andhra Pradesh', 'Arunachal Pradesh', 'Assam', 'Bihar', 'Chhattisgarh',
  'Goa', 'Gujarat', 'Haryana', 'Himachal Pradesh', 'Jharkhand', 'Karnataka',
  'Kerala', 'Madhya Pradesh', 'Maharashtra', 'Manipur', 'Meghalaya', 'Mizoram',
  'Nagaland', 'Odisha', 'Punjab', 'Rajasthan', 'Sikkim', 'Tamil Nadu',
  'Telangana', 'Tripura', 'Uttar Pradesh', 'Uttarakhand', 'West Bengal',
  'Andaman and Nicobar Islands', 'Chandigarh', 'Dadra and Nagar Haveli and Daman and Diu',
  'Delhi', 'Jammu and Kashmir', 'Ladakh', 'Lakshadweep', 'Puducherry',
];

export default function CustomerForm({ customer, title, primaryLabel = 'Save Changes', existingLegalNames, existingGstins, onSave, onCancel }: {
  customer: Party;
  title: string;
  primaryLabel?: string;
  existingLegalNames?: string[];
  existingGstins?: string[];
  onSave: (patch: Partial<Party>) => void;
  onCancel: () => void;
}) {
  const [form, setForm] = useState({ ...customer });
  const [error, setError] = useState('');
  const update = (key: keyof Party, value: string | number) => setForm(prev => ({ ...prev, [key]: value }));

  const save = (e: React.FormEvent) => {
    e.preventDefault();
    if (!form.legalName.trim()) return setError('Legal / Business Name is required.');
    if (!form.city.trim()) return setError('City is required.');
    if (!form.contactPerson.trim()) return setError('Primary Contact is required.');
    if (!form.email.trim()) return setError('Email is required.');
    if (!/^\S+@\S+\.\S+$/.test(form.email)) return setError('Enter a valid email address.');
    if (!form.gstin.trim()) return setError('GSTIN is required.');
    if (!/^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$/.test(form.gstin.toUpperCase())) return setError('GSTIN must match the GST format (e.g. 27AABCU9603R1ZX).');
    if (existingGstins) {
      const existing = existingGstins.filter(g => g.toUpperCase() === form.gstin.toUpperCase());
      const isOwn = customer.gstin?.toUpperCase() === form.gstin.toUpperCase();
      if (existing.length > 0 && !isOwn) return setError('This GSTIN is already registered to another customer.');
    }
    if (existingLegalNames) {
      const existing = existingLegalNames.filter(n => n.toLowerCase() === form.legalName.trim().toLowerCase());
      const isOwn = customer.legalName?.toLowerCase() === form.legalName.trim().toLowerCase();
      if (existing.length > 0 && !isOwn) return setError('A customer with this legal name already exists.');
    }
    if (form.panNumber && !/^[A-Z]{5}[0-9]{4}[A-Z]$/i.test(form.panNumber.trim())) return setError('PAN must be 10 characters in the format AAAAA9999A (e.g. ABCDE1234F).');
    if (form.phone && !/^[+]?[0-9][0-9 \-()]{6,17}$/.test(form.phone.trim())) return setError('Enter a valid phone number (digits, spaces, +, - or brackets; 7–18 characters).');
    onSave({
      legalName: form.legalName.trim(),
      tradingName: form.tradingName.trim(),
      gstin: form.gstin.toUpperCase(),
      panNumber: form.panNumber.toUpperCase(),
      city: form.city.trim(),
      state: form.state.trim(),
      address: form.address?.trim() ?? '',
      contactPerson: form.contactPerson.trim(),
      phone: form.phone.trim(),
      email: form.email.trim(),
      paymentTerms: form.paymentTerms,
      creditLimit: Number(form.creditLimit) || 0,
    });
  };

  return <form onSubmit={save} className="flex flex-col h-full">
    <div className="flex-shrink-0 flex items-center gap-3 px-6 py-4 border-b" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
      <button type="button" onClick={onCancel} className="p-1.5 rounded-lg hover:bg-[var(--surface-2)]"><ArrowLeft size={18} style={{ color: 'var(--text-2)' }} /></button>
      <div>
        <h1 className="text-lg font-bold" style={{ color: 'var(--text)' }}>{title}</h1>
        <p className="text-xs mt-0.5" style={{ color: 'var(--text-2)' }}>Fields marked * are required</p>
      </div>
    </div>
    <div className="flex-1 overflow-auto p-6">
      <div className="max-w-3xl mx-auto space-y-5">
        <section className="rounded-xl border p-5" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
          <h2 className="text-sm font-bold mb-4" style={{ color: 'var(--text)' }}>Business identity and contact</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>Legal / Business Name *
              <input required type="text" value={form.legalName} onChange={e => update('legalName', e.target.value)} className="eoss-input w-full mt-1" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>Trading Name
              <input type="text" value={form.tradingName} onChange={e => update('tradingName', e.target.value)} className="eoss-input w-full mt-1" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>Primary Contact *
              <input required type="text" value={form.contactPerson} onChange={e => update('contactPerson', e.target.value)} className="eoss-input w-full mt-1" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>Email *
              <input required type="email" value={form.email} onChange={e => update('email', e.target.value)} className="eoss-input w-full mt-1" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>Phone
              <input type="tel" value={form.phone} onChange={e => update('phone', e.target.value)} className="eoss-input w-full mt-1" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>GSTIN *
              <input required type="text" value={form.gstin} onChange={e => update('gstin', e.target.value)} placeholder="e.g. 27XXXXX9999X1ZX" className="eoss-input w-full mt-1 font-mono" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>PAN
              <input type="text" value={form.panNumber} onChange={e => update('panNumber', e.target.value)} className="eoss-input w-full mt-1 font-mono" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>City *
              <input required type="text" value={form.city} onChange={e => update('city', e.target.value)} className="eoss-input w-full mt-1" />
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>State
              <select value={form.state} onChange={e => update('state', e.target.value)} className="eoss-input w-full mt-1">
                <option value="">Select state…</option>
                {INDIAN_STATES.map(s => <option key={s} value={s}>{s}</option>)}
              </select>
            </label>
            <label className="text-xs font-medium md:col-span-2" style={{ color: 'var(--text-2)' }}>Address
              <textarea value={form.address ?? ''} onChange={e => update('address', e.target.value)} rows={2} className="eoss-input w-full mt-1" />
            </label>
          </div>
        </section>
        <section className="rounded-xl border p-5" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
          <h2 className="text-sm font-bold mb-4" style={{ color: 'var(--text)' }}>Commercial terms</h2>
          <div className="grid grid-cols-1 md:grid-cols-2 gap-4">
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>Payment Terms
              <select value={form.paymentTerms} onChange={e => update('paymentTerms', e.target.value)} className="eoss-input w-full mt-1">
                {['Advance', 'Net 15', 'Net 30', 'Net 45', 'Net 60'].map(v => <option key={v}>{v}</option>)}
              </select>
            </label>
            <label className="text-xs font-medium" style={{ color: 'var(--text-2)' }}>Credit Limit (₹)
              <input type="number" min="0" value={form.creditLimit ?? 0} onChange={e => update('creditLimit', Number(e.target.value))} className="eoss-input w-full mt-1" />
            </label>
          </div>
        </section>
        {error && <p className="text-sm" style={{ color: 'var(--error,#DC2626)' }}>{error}</p>}
      </div>
    </div>
    <div className="sticky bottom-0 flex-shrink-0 flex justify-between px-6 py-4 border-t" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
      <button type="button" onClick={onCancel} className="px-4 py-2 rounded-lg border text-sm" style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}>Cancel</button>
      <button type="submit" className="flex items-center gap-2 px-5 py-2 rounded-lg text-sm font-semibold text-white" style={{ background: 'var(--brand)' }}><Save size={14} /> {primaryLabel}</button>
    </div>
  </form>;
}
