import { useEffect, useRef, useState, type ChangeEvent } from 'react';
import { X } from 'lucide-react';
import {
  loadAssets,
  loadCustomers,
  loadJobs,
  loadSuppliers,
  MAX_DOCUMENT_UPLOAD_BYTES,
  MAX_DOCUMENT_UPLOAD_MESSAGE,
  uploadDocument,
  type SimpleOption,
} from './api';
import type { VaultDocument } from './types';

const CATEGORIES = [
  'Job Documents',
  'Quality Documents',
  'Material Certificates',
  'Supplier Documents',
  'Company Certificates',
  'Asset Documents',
  'Customer Documents',
  'Sales Documents',
];

interface UploadModalProps {
  onClose: () => void;
  onSuccess: (doc: VaultDocument) => void;
}

export default function UploadModal({ onClose, onSuccess }: UploadModalProps) {
  const fileRef = useRef<HTMLInputElement>(null);
  const [form, setForm] = useState({
    title: '',
    category: 'Job Documents',
    docType: '',
    linkType: '' as '' | 'Supplier' | 'Customer' | 'Job' | 'Asset',
    linkId: '',
    issueDate: '',
    expiryDate: '',
    reminderDays: '30',
    file: null as File | null,
  });

  const [suppliers, setSuppliers] = useState<SimpleOption[]>([]);
  const [customers, setCustomers] = useState<SimpleOption[]>([]);
  const [jobs, setJobs] = useState<Array<{ id: string; jobNumber: string; description?: string }>>([]);
  const [assets, setAssets] = useState<Array<{ id: string; assetCode: string; name: string }>>([]);
  const [loadingOptions, setLoadingOptions] = useState(false);
  const [err, setErr] = useState('');
  const [submitting, setSubmitting] = useState(false);

  useEffect(() => {
    let mounted = true;
    setLoadingOptions(true);
    Promise.all([loadSuppliers(), loadCustomers(), loadJobs(), loadAssets()])
      .then(([s, c, j, a]) => {
        if (!mounted) return;
        setSuppliers(s);
        setCustomers(c);
        setJobs(j);
        setAssets(a);
      })
      .catch(() => {})
      .finally(() => {
        if (mounted) setLoadingOptions(false);
      });
    return () => {
      mounted = false;
    };
  }, []);

  const linkOptions =
    form.linkType === 'Supplier'
      ? suppliers.map(s => ({ id: s.id, ref: s.name }))
      : form.linkType === 'Customer'
      ? customers.map(c => ({ id: c.id, ref: c.name }))
      : form.linkType === 'Job'
      ? jobs.map(j => ({ id: j.id, ref: j.jobNumber }))
      : form.linkType === 'Asset'
      ? assets.map(a => ({ id: a.id, ref: `${a.assetCode} — ${a.name}` }))
      : [];

  const handleFileChange = (e: ChangeEvent<HTMLInputElement>) => {
    const selected = e.target.files?.[0] ?? null;
    if (selected) {
      setForm(f => ({
        ...f,
        file: selected,
        title: f.title.trim() ? f.title : selected.name.replace(/\.[^.]+$/, ''),
      }));
    }
  };

  const handleSave = async () => {
    setErr('');
    if (!form.title.trim()) {
      setErr('Title is required');
      return;
    }
    if (!form.file) {
      setErr('Please select a file');
      return;
    }
    if (form.file.size > MAX_DOCUMENT_UPLOAD_BYTES) {
      setErr(MAX_DOCUMENT_UPLOAD_MESSAGE);
      return;
    }
    if (form.issueDate && form.expiryDate && form.expiryDate < form.issueDate) {
      setErr('Expiry date cannot be earlier than issue date');
      return;
    }
    const remDays = Number(form.reminderDays);
    if (form.reminderDays && (isNaN(remDays) || remDays < 1 || remDays > 365)) {
      setErr('Reminder days must be between 1 and 365');
      return;
    }

    const chosenOption = linkOptions.find(o => o.id === form.linkId);

    const formData = new FormData();
    formData.append('file', form.file);
    formData.append('title', form.title.trim());
    formData.append('category', form.category);
    formData.append('docType', form.docType.trim() || 'General');
    if (form.issueDate) formData.append('issueDate', form.issueDate);
    if (form.expiryDate) formData.append('expiryDate', form.expiryDate);
    if (form.reminderDays) formData.append('reminderDays', form.reminderDays);
    if (form.linkType && form.linkId) {
      formData.append('linkedEntityType', form.linkType);
      formData.append('linkedEntityId', form.linkId);
      if (chosenOption) formData.append('linkedEntityRef', chosenOption.ref);
    }

    try {
      setSubmitting(true);
      const savedDoc = await uploadDocument(formData);
      onSuccess(savedDoc);
    } catch (error: any) {
      setErr(error.message || 'Failed to upload document');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl w-full max-w-md p-6 shadow-2xl max-h-[90vh] overflow-y-auto">
        <div className="flex justify-between items-center mb-4">
          <h3 className="font-bold text-[var(--text)]">Upload Document</h3>
          <button type="button" onClick={onClose} aria-label="Close modal">
            <X size={16} className="text-[var(--text-3)]" />
          </button>
        </div>
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Title *</label>
            <input
              className="eoss-input w-full"
              value={form.title}
              onChange={e => setForm(f => ({ ...f, title: e.target.value }))}
              placeholder="Document title"
              disabled={submitting}
            />
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Category</label>
              <select
                className="eoss-input w-full"
                value={form.category}
                onChange={e => setForm(f => ({ ...f, category: e.target.value }))}
                disabled={submitting}
              >
                {CATEGORIES.map(c => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Doc Type</label>
              <input
                className="eoss-input w-full"
                value={form.docType}
                onChange={e => setForm(f => ({ ...f, docType: e.target.value }))}
                placeholder="Certificate, Drawing…"
                disabled={submitting}
              />
            </div>
          </div>
          <div className="grid grid-cols-2 gap-3">
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Linked record type</label>
              <select
                className="eoss-input w-full"
                value={form.linkType}
                onChange={e =>
                  setForm(f => ({
                    ...f,
                    linkType: e.target.value as typeof f.linkType,
                    linkId: '',
                  }))
                }
                disabled={submitting}
              >
                <option value="">None</option>
                <option value="Supplier">Supplier</option>
                <option value="Customer">Customer</option>
                <option value="Job">Job</option>
                <option value="Asset">Asset</option>
              </select>
            </div>
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Linked record</label>
              <select
                className="eoss-input w-full"
                disabled={!form.linkType || submitting || loadingOptions}
                value={form.linkId}
                onChange={e => setForm(f => ({ ...f, linkId: e.target.value }))}
              >
                <option value="">
                  {loadingOptions
                    ? 'Loading records…'
                    : form.linkType
                    ? `Select ${form.linkType.toLowerCase()}…`
                    : 'Select type first'}
                </option>
                {linkOptions.map(o => (
                  <option key={o.id} value={o.id}>
                    {o.ref}
                  </option>
                ))}
              </select>
            </div>
          </div>
          <div className="grid grid-cols-3 gap-2.5">
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Issue date</label>
              <input
                type="date"
                className="eoss-input w-full"
                value={form.issueDate}
                onChange={e => setForm(f => ({ ...f, issueDate: e.target.value }))}
                disabled={submitting}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Expiry date</label>
              <input
                type="date"
                className="eoss-input w-full"
                value={form.expiryDate}
                onChange={e => setForm(f => ({ ...f, expiryDate: e.target.value }))}
                disabled={submitting}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Remind (days)</label>
              <input
                type="number"
                min={1}
                max={365}
                className="eoss-input w-full"
                value={form.reminderDays}
                onChange={e => setForm(f => ({ ...f, reminderDays: e.target.value }))}
                disabled={submitting}
              />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">File *</label>
            <input
              ref={fileRef}
              type="file"
              className="hidden"
              onChange={handleFileChange}
              disabled={submitting}
            />
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              className="w-full py-2 border-2 border-dashed border-[var(--border)] rounded-xl text-xs text-[var(--text-3)] hover:border-[var(--brand)] transition-colors"
              disabled={submitting}
            >
              {form.file ? form.file.name : 'Click to browse files…'}
            </button>
          </div>
        </div>
        {err && <p className="text-xs text-[var(--danger,#DC2626)] mt-2">{err}</p>}
        <div className="flex gap-3 mt-5">
          <button
            type="button"
            onClick={onClose}
            className="flex-1 py-2.5 border border-[var(--border)] rounded-xl text-sm text-[var(--text-2)]"
            disabled={submitting}
          >
            Cancel
          </button>
          <button
            type="button"
            onClick={handleSave}
            disabled={submitting}
            className="flex-1 py-2.5 bg-[var(--brand-fill)] text-white rounded-xl text-sm font-medium disabled:opacity-50"
          >
            {submitting ? 'Uploading…' : 'Upload'}
          </button>
        </div>
      </div>
    </div>
  );
}
