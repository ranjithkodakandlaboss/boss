import { useEffect, useState } from 'react';
import { ArrowLeft, FileText, ExternalLink, RefreshCw, Archive, Download, CheckCircle, AlertTriangle, XCircle, Info, X } from 'lucide-react';
import { useNavigate, useParams } from 'react-router-dom';
import { useCustomer } from '../auth/CustomerState';
import StatusChip from '../components/shared/StatusChip';
import { formatDisplayDate } from '../sales/proto/dates';
import { downloadDocument, getDocument } from './api';
import { computeDocumentExpiry } from './documentExpiry';
import type { VaultDocument } from './types';
import RenewDocumentModal from './RenewDocumentModal';
import ArchiveDocumentModal from './ArchiveDocumentModal';
import DocumentHistoryPanel from './DocumentHistoryPanel';

interface ToastState {
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  body?: string;
}

export default function DocumentDetailPage() {
  const { id } = useParams<{ id: string }>();
  const navigate = useNavigate();
  const { user } = useCustomer();

  const [document, setDocument] = useState<VaultDocument | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [showRenew, setShowRenew] = useState(false);
  const [showArchive, setShowArchive] = useState(false);
  const [toast, setToast] = useState<ToastState | null>(null);

  const roles = (user?.roles ?? []).map(r => (typeof r === 'string' ? r : r.role).toLowerCase());
  const canManage = roles.length === 0 || roles.some(r =>
    ['owner', 'ops_manager', 'buyer', 'purchase_manager', 'qc', 'quality_inspector'].includes(r)
  );

  const showToast = (t: ToastState) => {
    setToast(t);
    setTimeout(() => {
      setToast(null);
    }, 4000);
  };

  const loadDoc = async () => {
    if (!id) return;
    try {
      setLoading(true);
      setError(null);
      const res = await getDocument(id);
      setDocument(res);
    } catch (err: any) {
      setError(err.message || 'Document not found');
      setDocument(null);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadDoc();
  }, [id]);

  if (loading) {
    return (
      <div className="p-8 text-center text-sm" style={{ color: 'var(--text-3)' }}>
        Loading document…
      </div>
    );
  }

  if (error || !document) {
    return (
      <div className="p-8 text-center text-sm" style={{ color: 'var(--text-2)' }}>
        Document not found.{' '}
        <button
          type="button"
          onClick={() => navigate('/documents')}
          className="underline"
          style={{ color: 'var(--brand)' }}
        >
          Back to Document Vault
        </button>
      </div>
    );
  }

  const now = new Date();
  const expiry = computeDocumentExpiry(document, now);
  const isArchived = document.status === 'archived';

  const resolveLinkedPath = (entity: { type: string; ref: string; id?: string }): string | null => {
    const t = entity.type.toLowerCase();
    if (t.includes('supplier') && !t.includes('po')) {
      return entity.id ? `/suppliers/${entity.id}` : '/suppliers';
    }
    if (t.includes('customer') && !t.includes('po') && !t.includes('invoice')) {
      return entity.id ? `/sales/customers/${entity.id}` : '/sales?tab=customers';
    }
    if (t === 'job' || entity.ref.toLowerCase().startsWith('j-')) {
      return entity.id ? `/jobs/${entity.id}` : `/jobs`;
    }
    if (t === 'asset' || t.includes('machine')) {
      return entity.id ? `/assets/${entity.id}` : `/assets`;
    }
    if (t.includes('maintenance') || entity.ref.toLowerCase().startsWith('me-')) {
      return entity.id ? `/assets/me/${entity.id}` : `/assets`;
    }
    if (t.includes('customer po') || entity.ref.toLowerCase().startsWith('cpo-')) {
      return entity.id ? `/sales/po/${entity.id}` : `/sales`;
    }
    if (t.includes('supplier po') || entity.ref.toLowerCase().startsWith('spo-')) {
      return entity.id ? `/procurement/spo/${entity.id}` : `/procurement`;
    }
    if (t.includes('requisition') || t === 'pr' || entity.ref.toLowerCase().startsWith('pr-')) {
      return entity.id ? `/procurement/pr/${entity.id}` : `/procurement`;
    }
    if (t.includes('inspection') || entity.ref.toLowerCase().startsWith('ins')) {
      return entity.id ? `/quality/inspection/${entity.id}` : `/quality`;
    }
    if (t.includes('grn') || entity.ref.toLowerCase().startsWith('grn-')) {
      return entity.id ? `/procurement/grn/${entity.id}` : `/procurement`;
    }
    if (t.includes('dispatch') || entity.ref.toLowerCase().startsWith('dsp-')) {
      return entity.id ? `/dispatch/${entity.id}` : `/dispatch`;
    }
    if (t.includes('invoice') || entity.ref.toLowerCase().startsWith('inv-')) {
      return entity.id ? `/finance/invoice/${entity.id}` : `/finance`;
    }
    return null;
  };

  const handleDownloadFile = () => {
    downloadDocument(document.id, document.fileName).catch((err: unknown) => {
      showToast({ type: 'error', title: 'Download failed', body: err instanceof Error ? err.message : 'Could not download file.' });
    });
  };

  return (
    <div className="flex flex-col h-full">
      {/* Header */}
      <div
        className="flex-shrink-0 px-6 py-4 border-b"
        style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
      >
        <div className="flex items-center gap-2 text-xs mb-3" style={{ color: 'var(--text-3)' }}>
          <button
            type="button"
            onClick={() => navigate('/documents')}
            className="hover:underline"
            style={{ color: 'var(--brand)' }}
          >
            Document Vault
          </button>
          <span>›</span>
          <span style={{ color: 'var(--text)' }}>{document.title}</span>
        </div>
        <div className="flex items-start justify-between gap-4 flex-wrap">
          <div className="flex items-start gap-3">
            <button
              type="button"
              onClick={() => navigate('/documents')}
              className="mt-0.5 p-1.5 rounded-lg hover:opacity-70"
              style={{ color: 'var(--text-3)' }}
              aria-label="Back to Document Vault"
            >
              <ArrowLeft size={16} />
            </button>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h1 className="text-xl font-bold" style={{ color: 'var(--text)' }}>
                  {document.title}
                </h1>
                {isArchived && <StatusChip status="archived" size="sm" />}
                {document.certStatus && <StatusChip status={document.certStatus} size="sm" />}
                {!isArchived && expiry.status === 'expired' && <StatusChip status="expired" size="sm" />}
                {!isArchived && expiry.status === 'expiring' && (
                  <span
                    className="text-[11px] font-semibold px-2 py-0.5 rounded-full"
                    style={{ background: 'var(--warning-bg,#FFFBEB)', color: 'var(--warning,#D97706)' }}
                  >
                    Expiring in {expiry.daysToExpiry}d
                  </span>
                )}
              </div>
              <p className="text-sm mt-0.5" style={{ color: 'var(--text-2)' }}>
                {document.docType} · {document.category}
              </p>
            </div>
          </div>
          <div className="flex items-center gap-2 flex-wrap">
            {canManage && !isArchived && document.status !== 'superseded' && (
              <button
                type="button"
                onClick={() => setShowRenew(true)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-semibold text-white"
                style={{ background: 'var(--brand-fill)' }}
              >
                <RefreshCw size={13} /> Renew
              </button>
            )}
            {canManage && !isArchived && (
              <button
                type="button"
                onClick={() => setShowArchive(true)}
                className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm font-medium"
                style={{ borderColor: 'var(--border)', color: 'var(--error,#DC2626)' }}
              >
                <Archive size={13} /> Archive
              </button>
            )}
            <button
              type="button"
              onClick={() => navigate('/documents')}
              className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm font-medium"
              style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}
            >
              <ExternalLink size={13} /> Document Vault
            </button>
          </div>
        </div>
      </div>

      {/* Content */}
      <div className="flex-1 overflow-auto p-6">
        <div className="max-w-3xl space-y-5">
          {isArchived && (
            <div
              className="rounded-xl border p-4 text-sm"
              style={{ borderColor: 'var(--border)', background: 'var(--surface-2)', color: 'var(--text-2)' }}
            >
              <strong style={{ color: 'var(--text)' }}>Archived</strong> on{' '}
              {formatDisplayDate(document.archivedAt)} by {document.archivedBy ?? 'System'}
              {document.archiveReason && <> — Reason: {document.archiveReason}</>}
            </div>
          )}

          {/* Document file information card */}
          <div
            className="rounded-xl border p-5"
            style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
          >
            <div className="flex items-center justify-between gap-3 mb-5 flex-wrap">
              <div
                role="button"
                tabIndex={0}
                onClick={handleDownloadFile}
                onKeyDown={e => {
                  if (e.key === 'Enter' || e.key === ' ') handleDownloadFile();
                }}
                className="flex items-center gap-3 cursor-pointer group"
                title="Click to download file"
              >
                <div
                  className="w-12 h-12 rounded-xl flex items-center justify-center group-hover:scale-105 transition-transform"
                  style={{ background: 'var(--surface-2)' }}
                >
                  <FileText size={22} style={{ color: 'var(--brand)' }} />
                </div>
                <div>
                  <div
                    className="font-semibold group-hover:underline flex items-center gap-1.5"
                    style={{ color: 'var(--text)' }}
                  >
                    {document.title}
                    <Download size={14} className="text-[var(--text-3)]" />
                  </div>
                  <div className="text-xs mt-0.5" style={{ color: 'var(--text-3)' }}>
                    {document.fileName ? `${document.fileName} · ` : ''}
                    {document.fileSize} · Version {document.version}
                  </div>
                </div>
              </div>
            </div>

            <dl className="grid grid-cols-2 gap-x-8 gap-y-4">
              <Detail label="Document type" value={document.docType} />
              <Detail label="Category" value={document.category} />
              <Detail label="Uploaded by" value={document.uploadedBy} />
              <Detail label="Uploaded on" value={formatDisplayDate(document.uploadedAt)} />
              <Detail label="Issue date" value={formatDisplayDate(document.issueDate)} />
              <Detail label="Expiry date" value={formatDisplayDate(document.expiryDate)} />
              {document.reminderDays !== undefined && (
                <Detail label="Renewal reminder" value={`${document.reminderDays} days before expiry`} />
              )}
            </dl>
          </div>

          {/* Linked records panel */}
          <div
            className="rounded-xl border overflow-hidden"
            style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
          >
            <div
              className="px-4 py-3 border-b text-sm font-semibold"
              style={{ borderColor: 'var(--border)', background: 'var(--surface-2)', color: 'var(--text)' }}
            >
              Linked records
            </div>
            <div className="p-4">
              {!document.linkedEntities || document.linkedEntities.length === 0 ? (
                <p className="text-sm" style={{ color: 'var(--text-3)' }}>
                  No linked records.
                </p>
              ) : (
                <div className="flex flex-wrap gap-2">
                  {document.linkedEntities.map(entity => {
                    const path = resolveLinkedPath(entity);
                    return path ? (
                      <button
                        key={`${entity.type}-${entity.ref}`}
                        type="button"
                        onClick={() => navigate(path)}
                        className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-xs font-medium hover:underline"
                        style={{
                          borderColor: 'var(--border)',
                          color: 'var(--brand)',
                          background: 'var(--surface-2)',
                        }}
                      >
                        {entity.type}: {entity.ref}
                        <ExternalLink size={11} />
                      </button>
                    ) : (
                      <span
                        key={`${entity.type}-${entity.ref}`}
                        className="px-3 py-1.5 rounded-lg border text-xs"
                        style={{ borderColor: 'var(--border)', color: 'var(--text-2)' }}
                      >
                        {entity.type}: {entity.ref}
                      </span>
                    );
                  })}
                </div>
              )}
            </div>
          </div>

          {/* Document Activity & Audit Trail */}
          <DocumentHistoryPanel documentId={document.id} />
        </div>
      </div>

      {showRenew && (
        <RenewDocumentModal
          doc={document}
          onClose={() => setShowRenew(false)}
          onSuccess={updated => {
            showToast({
              type: 'success',
              title: 'Document renewed',
              body: `${updated.title} renewed until ${formatDisplayDate(updated.expiryDate)}.`,
            });
            setShowRenew(false);
            loadDoc();
          }}
        />
      )}

      {showArchive && (
        <ArchiveDocumentModal
          doc={document}
          onClose={() => setShowArchive(false)}
          onSuccess={updated => {
            showToast({
              type: 'info',
              title: 'Document archived',
              body: `${updated.title} has been archived.`,
            });
            setShowArchive(false);
            loadDoc();
          }}
        />
      )}

      {toast && (
        <div className="fixed bottom-20 md:bottom-6 right-4 z-50 pointer-events-none">
          <div className="pointer-events-auto flex items-start gap-3 bg-[var(--surface)] border border-[var(--border)] rounded-xl shadow-xl px-4 py-3 max-w-sm">
            {toast.type === 'success' && <CheckCircle size={16} className="text-[var(--success,#16A34A)]" />}
            {toast.type === 'error' && <XCircle size={16} className="text-[var(--error,#DC2626)]" />}
            {toast.type === 'warning' && <AlertTriangle size={16} className="text-[var(--warning,#D97706)]" />}
            {toast.type === 'info' && <Info size={16} className="text-[var(--info,#2563EB)]" />}
            <div className="flex-1 min-w-0">
              <div className="text-sm font-semibold text-[var(--text)]">{toast.title}</div>
              {toast.body && <div className="text-xs text-[var(--text-2)] mt-0.5">{toast.body}</div>}
            </div>
            <button
              type="button"
              onClick={() => setToast(null)}
              className="text-[var(--text-3)] hover:text-[var(--text)] flex-shrink-0"
            >
              <X size={14} />
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs" style={{ color: 'var(--text-3)' }}>
        {label}
      </dt>
      <dd className="text-sm mt-0.5 font-medium" style={{ color: 'var(--text)' }}>
        {value}
      </dd>
    </div>
  );
}
