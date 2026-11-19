import { useCallback, useEffect, useState, type ChangeEvent } from 'react';
import { FileText, Upload } from 'lucide-react';
import { downloadDocument, listDocuments, uploadLinkedDocument } from '../../../documents/api';
import type { VaultDocument } from '../../../documents/types';

interface LinkedDocumentsSectionProps {
  entityType: string;
  entityId?: string;
  entityRef: string;
  entityName?: string;
  title?: string;
  readOnly?: boolean;
}

export default function LinkedDocumentsSection({
  entityType, entityId, entityRef, entityName, title = 'Documents', readOnly = false,
}: LinkedDocumentsSectionProps) {
  const [documents, setDocuments] = useState<VaultDocument[]>([]);
  const [loading, setLoading] = useState(true);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const load = useCallback(async () => {
    const records: VaultDocument[] = [];
    let page = 0;
    let result;
    do {
      result = await listDocuments({ page: page++, size: 100 });
      records.push(...result.content);
    } while (result.content.length > 0 && records.length < result.totalElements);
    setDocuments(records);
  }, []);

  useEffect(() => {
    load().catch(error => setError(error instanceof Error ? error.message : 'Documents could not be loaded.'))
      .finally(() => setLoading(false));
  }, [load]);

  const normalize = (value?: string) => (value ?? '').replace(/[^a-z0-9]/gi, '').toLowerCase();
  const matched = documents.filter(doc => doc.status !== 'archived' && doc.status !== 'superseded'
    && doc.linkedEntities.some(link => normalize(link.type) === normalize(entityType)
      && (entityId && (link.id === entityId || link.ref === entityId)
        || link.ref === entityRef || !!entityName && link.ref === entityName)));

  async function attach(event: ChangeEvent<HTMLInputElement>) {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file || !entityId || readOnly) return;
    if (file.size > 20 * 1024 * 1024) {
      setError('Choose a document no larger than 20 MB.');
      return;
    }
    setError('');
    setUploading(true);
    try {
      await uploadLinkedDocument(file, { type: entityType, id: entityId, ref: entityRef });
      await load();
    } catch (error) {
      setError(error instanceof Error ? error.message : 'Document could not be attached.');
    } finally {
      setUploading(false);
    }
  }

  return <section className="rounded-xl border overflow-hidden" style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}>
    <div className="px-4 py-3 border-b flex items-center justify-between gap-3" style={{ borderColor: 'var(--border)', background: 'var(--surface-2)' }}>
      <span className="text-sm font-semibold text-[var(--text)]">{title} ({matched.length})</span>
      {!readOnly && entityId && <label className="inline-flex items-center gap-1 text-xs font-semibold text-[var(--brand)] cursor-pointer">
        <Upload size={13} /> {uploading ? 'Uploading...' : 'Attach document'}
        <input aria-label="Attach document" type="file" className="sr-only" disabled={uploading} onChange={attach} />
      </label>}
    </div>
    {error && <p role="alert" className="px-4 py-3 text-xs text-[var(--error)]">{error}</p>}
    {loading ? <p role="status" className="px-4 py-5 text-sm text-[var(--text-3)]">Loading documents...</p>
      : matched.length === 0 ? <p className="px-4 py-5 text-sm text-[var(--text-3)]">No active documents linked to {entityRef}.</p>
        : matched.map(doc => <button key={doc.id} type="button" className="w-full px-4 py-3 flex items-center gap-2 text-left hover:bg-[var(--surface-2)]"
          onClick={() => downloadDocument(doc.id, doc.fileName ?? doc.title).catch(error => setError(error instanceof Error ? error.message : 'Document could not be downloaded.'))}>
          <FileText size={15} className="shrink-0 text-[var(--brand)]" />
          <span className="min-w-0"><span className="block truncate text-sm font-medium">{doc.title}</span>
            <span className="block text-xs text-[var(--text-3)]">{doc.docType} · v{doc.version}</span></span>
        </button>)}
  </section>;
}
