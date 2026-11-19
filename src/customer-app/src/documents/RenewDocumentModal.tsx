import { useRef, useState } from 'react';
import { X } from 'lucide-react';
import { renewDocument } from './api';
import type { VaultDocument } from './types';

interface RenewDocumentModalProps {
  doc: VaultDocument;
  onClose: () => void;
  onSuccess: (updatedDoc: VaultDocument) => void;
}

export default function RenewDocumentModal({ doc, onClose, onSuccess }: RenewDocumentModalProps) {
  const fileRef = useRef<HTMLInputElement>(null);

  const todayStr = new Date().toISOString().slice(0, 10);
  const defaultNextExpiry = (() => {
    const base = doc.expiryDate && doc.expiryDate > todayStr ? new Date(doc.expiryDate) : new Date();
    base.setFullYear(base.getFullYear() + 1);
    return base.toISOString().slice(0, 10);
  })();

  const [file, setFile] = useState<File | null>(null);
  const [issueDate, setIssueDate] = useState(todayStr);
  const [expiryDate, setExpiryDate] = useState(defaultNextExpiry);
  const [reminderDays, setReminderDays] = useState(String(doc.reminderDays ?? 30));
  const [notes, setNotes] = useState('');
  const [err, setErr] = useState('');
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async () => {
    setErr('');
    if (!file) {
      setErr('Please attach the renewed document file');
      return;
    }
    if (!expiryDate) {
      setErr('New expiry date is required');
      return;
    }
    if (issueDate && expiryDate < issueDate) {
      setErr('Expiry date cannot be earlier than issue date');
      return;
    }
    const remDays = Number(reminderDays);
    if (reminderDays && (isNaN(remDays) || remDays < 1 || remDays > 365)) {
      setErr('Reminder days must be between 1 and 365');
      return;
    }

    const formData = new FormData();
    formData.append('file', file);
    if (issueDate) formData.append('issueDate', issueDate);
    formData.append('expiryDate', expiryDate);
    if (reminderDays) formData.append('reminderDays', reminderDays);
    if (notes.trim()) formData.append('notes', notes.trim());

    try {
      setSubmitting(true);
      const updated = await renewDocument(doc.id, formData);
      onSuccess(updated);
    } catch (error: any) {
      setErr(error.message || 'Failed to renew document');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl w-full max-w-md p-6 shadow-2xl max-h-[90vh] overflow-y-auto">
        <div className="flex justify-between items-center mb-3">
          <h3 className="font-bold text-[var(--text)]">Renew Document</h3>
          <button type="button" onClick={onClose} aria-label="Close modal">
            <X size={16} className="text-[var(--text-3)]" />
          </button>
        </div>
        <p className="text-xs mb-4" style={{ color: 'var(--text-3)' }}>
          Replacing <strong style={{ color: 'var(--text)' }}>{doc.title}</strong> (v{doc.version}) with a new version.
          Previous version remains in audit history.
        </p>
        <div className="space-y-3">
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">New file *</label>
            <input
              ref={fileRef}
              type="file"
              className="hidden"
              onChange={e => setFile(e.target.files?.[0] ?? null)}
              disabled={submitting}
            />
            <button
              type="button"
              onClick={() => fileRef.current?.click()}
              className="w-full py-2.5 border-2 border-dashed border-[var(--border)] rounded-xl text-xs text-[var(--text-3)] hover:border-[var(--brand)] transition-colors"
              disabled={submitting}
            >
              {file ? file.name : 'Click to upload renewed certificate / file…'}
            </button>
          </div>
          <div className="grid grid-cols-3 gap-2.5">
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Issue date</label>
              <input
                type="date"
                className="eoss-input w-full"
                value={issueDate}
                onChange={e => setIssueDate(e.target.value)}
                disabled={submitting}
              />
            </div>
            <div>
              <label className="block text-xs font-medium text-[var(--text-2)] mb-1">New expiry *</label>
              <input
                type="date"
                className="eoss-input w-full"
                value={expiryDate}
                onChange={e => setExpiryDate(e.target.value)}
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
                value={reminderDays}
                onChange={e => setReminderDays(e.target.value)}
                disabled={submitting}
              />
            </div>
          </div>
          <div>
            <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Notes</label>
            <input
              className="eoss-input w-full"
              value={notes}
              onChange={e => setNotes(e.target.value)}
              placeholder="Renewal reference or certificate number…"
              disabled={submitting}
            />
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
            onClick={handleSubmit}
            disabled={submitting}
            className="flex-1 py-2.5 bg-[var(--brand-fill)] text-white rounded-xl text-sm font-medium disabled:opacity-50"
          >
            {submitting ? 'Saving…' : 'Save Renewal'}
          </button>
        </div>
      </div>
    </div>
  );
}
