import { useState } from 'react';
import { X } from 'lucide-react';
import { archiveDocument } from './api';
import type { VaultDocument } from './types';

interface ArchiveDocumentModalProps {
  doc: VaultDocument;
  onClose: () => void;
  onSuccess: (archivedDoc: VaultDocument) => void;
}

export default function ArchiveDocumentModal({ doc, onClose, onSuccess }: ArchiveDocumentModalProps) {
  const [archiveReason, setArchiveReason] = useState('');
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState('');

  const handleConfirm = async () => {
    if (!archiveReason.trim()) return;
    try {
      setSubmitting(true);
      setErr('');
      const updated = await archiveDocument(doc.id, archiveReason.trim());
      onSuccess(updated);
    } catch (error: any) {
      setErr(error.message || 'Failed to archive document');
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center bg-black/40 backdrop-blur-sm p-4">
      <div className="bg-[var(--surface)] border border-[var(--border)] rounded-2xl w-full max-w-md p-6 shadow-2xl">
        <div className="flex justify-between items-center mb-3">
          <h3 className="font-bold text-[var(--text)]">Archive Document</h3>
          <button type="button" onClick={onClose} aria-label="Close modal">
            <X size={16} className="text-[var(--text-3)]" />
          </button>
        </div>
        <p className="text-xs mb-3" style={{ color: 'var(--text-2)' }}>
          Archiving removes <strong>{doc.title}</strong> from active alerts and expiry reminders while preserving its
          full audit history.
        </p>
        <label className="block text-xs font-medium text-[var(--text-2)] mb-1">Archive reason *</label>
        <textarea
          rows={3}
          className="eoss-input w-full text-sm"
          value={archiveReason}
          onChange={e => setArchiveReason(e.target.value)}
          placeholder="e.g. Superseded by new plant certificate, contract ended…"
          disabled={submitting}
        />
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
            disabled={!archiveReason.trim() || submitting}
            onClick={handleConfirm}
            className="flex-1 py-2.5 rounded-xl text-sm font-semibold text-white disabled:opacity-50"
            style={{ background: 'var(--error,#DC2626)' }}
          >
            {submitting ? 'Archiving…' : 'Archive Document'}
          </button>
        </div>
      </div>
    </div>
  );
}
