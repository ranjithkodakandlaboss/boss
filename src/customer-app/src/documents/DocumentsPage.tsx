import { useEffect, useMemo, useRef, useState } from 'react';
import { useNavigate, useSearchParams } from 'react-router-dom';
import { FileText, Search, Plus, RefreshCw, CheckCircle, AlertTriangle, XCircle, Info, X } from 'lucide-react';
import { useCustomer } from '../auth/CustomerState';
import StatusChip from '../components/shared/StatusChip';
import { formatDisplayDate } from '../sales/proto/dates';
import { listDocuments } from './api';
import { computeDocumentExpiry, listExpiringDocuments } from './documentExpiry';
import type { VaultDocument } from './types';
import UploadModal from './UploadModal';
import RenewDocumentModal from './RenewDocumentModal';

const CATEGORIES = [
  'All',
  'Job Documents',
  'Quality Documents',
  'Material Certificates',
  'Supplier Documents',
  'Company Certificates',
  'Asset Documents',
  'Customer Documents',
  'Sales Documents',
];

interface ToastState {
  type: 'success' | 'error' | 'warning' | 'info';
  title: string;
  body?: string;
}

export default function DocumentsPage() {
  const { user } = useCustomer();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();

  const attentionParam = searchParams.get('attention');
  const [search, setSearch] = useState(searchParams.get('search') ?? '');
  const [cat, setCat] = useState('All');
  const [showArchived, setShowArchived] = useState(false);
  const [attention, setAttention] = useState<'all' | 'expiring' | 'expired' | 'due'>(
    attentionParam === 'expiring' || attentionParam === 'expired' || attentionParam === 'due' ? attentionParam : 'all'
  );
  const [showUpload, setShowUpload] = useState(false);
  const [renewTarget, setRenewTarget] = useState<VaultDocument | null>(null);

  const [documents, setDocuments] = useState<VaultDocument[]>([]);
  const [loading, setLoading] = useState(true);
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

  const loadAll = async () => {
    try {
      setLoading(true);
      const res = await listDocuments({ includeArchived: true, size: 200 });
      setDocuments(res.content || []);
    } catch {
      setDocuments([]);
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    loadAll();
  }, []);

  const now = useMemo(() => new Date(), []);

  const visibleDocs = documents.filter(d => d.status !== 'superseded' && (showArchived || d.status !== 'archived'));
  const activeDocs = documents.filter(d => d.status !== 'superseded' && d.status !== 'archived');

  const filtered = visibleDocs.filter(d => {
    const matchSearch =
      !search ||
      d.title.toLowerCase().includes(search.toLowerCase()) ||
      d.docType.toLowerCase().includes(search.toLowerCase());
    const matchCat = cat === 'All' || d.category === cat;
    const expiry = computeDocumentExpiry(d, now);
    const matchAttention =
      attention === 'all' ||
      (attention === 'due' && (expiry.status === 'expiring' || expiry.status === 'expired')) ||
      expiry.status === attention;
    return matchSearch && matchCat && matchAttention;
  });

  const expiring = listExpiringDocuments(activeDocs, now);
  const expiredCount = expiring.filter(x => x.info.status === 'expired').length;
  const expiringCount = expiring.filter(x => x.info.status === 'expiring').length;

  const renewParam = searchParams.get('renew');
  const renewed = useRef<string | null>(null);
  useEffect(() => {
    if (!renewParam || renewed.current === renewParam) return;
    const target = documents.find(d => d.id === renewParam && d.status !== 'archived');
    if (target) {
      renewed.current = renewParam;
      setRenewTarget(target);
    }
  }, [renewParam, documents]);

  return (
    <div className="flex flex-col h-full">
      {/* Toolbar */}
      <div
        className="flex-shrink-0 flex items-center gap-3 px-5 py-3 border-b flex-wrap"
        style={{ borderColor: 'var(--border)', background: 'var(--surface)' }}
      >
        <div className="relative flex-1 min-w-[180px] max-w-xs">
          <Search size={14} className="absolute left-3 top-1/2 -translate-y-1/2" style={{ color: 'var(--text-3)' }} />
          <input
            value={search}
            onChange={e => setSearch(e.target.value)}
            placeholder="Search documents…"
            className="w-full pl-8 pr-3 py-1.5 text-sm rounded-lg border outline-none"
            style={{ borderColor: 'var(--border)', background: 'var(--surface)', color: 'var(--text)' }}
          />
        </div>

        <select
          value={cat}
          onChange={e => setCat(e.target.value)}
          className="md:hidden eoss-input text-xs py-1.5 max-w-[11rem]"
          aria-label="Document category"
        >
          {CATEGORIES.map(c => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>

        <div className="hidden md:flex gap-1.5 overflow-x-auto">
          {CATEGORIES.map(c => (
            <button
              key={c}
              onClick={() => setCat(c)}
              className="px-3 py-1.5 rounded-lg text-xs font-medium border transition-colors whitespace-nowrap"
              style={{
                background: cat === c ? 'var(--brand)' : 'var(--surface)',
                color: cat === c ? '#fff' : 'var(--text-2)',
                borderColor: cat === c ? 'var(--brand)' : 'var(--border)',
              }}
            >
              {c}
            </button>
          ))}
        </div>

        <div className="flex-1" />

        {expiringCount > 0 && (
          <button
            type="button"
            onClick={() => setAttention(attention === 'expiring' ? 'all' : 'expiring')}
            className="px-3 py-1.5 rounded-lg text-xs font-semibold border"
            style={{
              background: 'var(--warning-bg,#FFFBEB)',
              color: 'var(--warning,#D97706)',
              borderColor: attention === 'expiring' ? 'var(--warning,#D97706)' : 'transparent',
            }}
          >
            {expiringCount} expiring
          </button>
        )}

        {expiredCount > 0 && (
          <button
            type="button"
            onClick={() => setAttention(attention === 'expired' ? 'all' : 'expired')}
            className="px-3 py-1.5 rounded-lg text-xs font-semibold border"
            style={{
              background: 'var(--error-bg,#FEF2F2)',
              color: 'var(--error,#DC2626)',
              borderColor: attention === 'expired' ? 'var(--error,#DC2626)' : 'transparent',
            }}
          >
            {expiredCount} expired
          </button>
        )}

        <button
          type="button"
          onClick={() => setShowArchived(v => !v)}
          className="px-3 py-1.5 rounded-lg text-xs font-medium border"
          style={{
            background: showArchived ? 'var(--surface-2)' : 'var(--surface)',
            color: 'var(--text-2)',
            borderColor: showArchived ? 'var(--brand)' : 'var(--border)',
          }}
        >
          {showArchived ? 'Hide archived' : 'Show archived'}
        </button>

        {canManage && (
          <button
            type="button"
            onClick={() => setShowUpload(true)}
            className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-xs font-semibold text-white"
            style={{ background: 'var(--brand-fill)' }}
          >
            <Plus size={13} /> Upload
          </button>
        )}
      </div>

      {attention !== 'all' && (
        <div
          className="px-5 py-2 text-sm border-b"
          style={{ borderColor: 'var(--border)', background: 'var(--surface-2)' }}
        >
          These documents are expiring or already expired. Click Renew to upload a replacement file and set a new expiry
          date.
        </div>
      )}

      <div className="flex-1 overflow-auto p-5">
        <div className="space-y-2 max-w-4xl">
          {loading ? (
            <div className="text-center py-10 text-sm" style={{ color: 'var(--text-3)' }}>
              Loading documents…
            </div>
          ) : filtered.length === 0 ? (
            <div className="text-center py-10 text-sm" style={{ color: 'var(--text-3)' }}>
              No documents found
            </div>
          ) : (
            filtered.map(doc => {
              const expiry = computeDocumentExpiry(doc, now);
              const isArchived = doc.status === 'archived';
              return (
                <div
                  key={doc.id}
                  role="button"
                  tabIndex={0}
                  onClick={() => navigate(`/documents/${doc.id}`)}
                  onKeyDown={e => {
                    if (e.key === 'Enter' || e.key === ' ') navigate(`/documents/${doc.id}`);
                  }}
                  className="rounded-xl border p-4 flex items-center gap-3 hover:opacity-90 transition-opacity cursor-pointer"
                  style={{
                    borderColor: 'var(--border)',
                    background: 'var(--surface)',
                    opacity: isArchived ? 0.7 : 1,
                  }}
                >
                  <div
                    className="w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0"
                    style={{ background: 'var(--surface-2)' }}
                  >
                    <FileText size={16} style={{ color: 'var(--brand)' }} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <span className="text-sm font-medium" style={{ color: 'var(--text)' }}>
                        {doc.title}
                      </span>
                      {isArchived && (
                        <span
                          className="text-[10px] font-bold px-1.5 py-0.5 rounded"
                          style={{ background: 'var(--surface-2)', color: 'var(--text-3)' }}
                        >
                          Archived
                        </span>
                      )}
                      {!isArchived && expiry.status === 'expired' && (
                        <span
                          className="text-[10px] font-bold px-1.5 py-0.5 rounded"
                          style={{ background: 'var(--error-bg,#FEF2F2)', color: 'var(--error,#DC2626)' }}
                        >
                          Expired
                        </span>
                      )}
                      {!isArchived && expiry.status === 'expiring' && (
                        <span
                          className="text-[10px] font-bold px-1.5 py-0.5 rounded"
                          style={{ background: 'var(--warning-bg,#FFFBEB)', color: 'var(--warning,#D97706)' }}
                        >
                          {expiry.daysToExpiry}d left
                        </span>
                      )}
                      {doc.certStatus && <StatusChip status={doc.certStatus} size="sm" />}
                    </div>
                    <div className="mt-0.5 text-xs truncate" style={{ color: 'var(--text-3)' }}>
                      {doc.category} · {doc.docType}
                      {doc.expiryDate && ` · Expires ${formatDisplayDate(doc.expiryDate)}`}
                      {doc.linkedEntities && doc.linkedEntities.length > 0 &&
                        ` · ${doc.linkedEntities.map(e => e.ref).join(', ')}`}
                    </div>
                  </div>
                  {!isArchived && (expiry.status === 'expiring' || expiry.status === 'expired') && canManage && (
                    <button
                      type="button"
                      onClick={e => {
                        e.stopPropagation();
                        setRenewTarget(doc);
                      }}
                      className="flex items-center gap-1 text-xs font-semibold flex-shrink-0"
                      style={{ color: 'var(--brand)' }}
                    >
                      <RefreshCw size={12} /> Renew
                    </button>
                  )}
                  <button
                    type="button"
                    onClick={e => {
                      e.stopPropagation();
                      navigate(`/documents/${doc.id}`);
                    }}
                    className="text-xs font-semibold flex-shrink-0"
                    style={{ color: 'var(--brand)' }}
                  >
                    Open document →
                  </button>
                </div>
              );
            })
          )}
        </div>
      </div>

      {showUpload && (
        <UploadModal
          onClose={() => setShowUpload(false)}
          onSuccess={doc => {
            showToast({
              type: 'success',
              title: 'Document uploaded',
              body: `${doc.title} has been added to the vault.`,
            });
            setShowUpload(false);
            loadAll();
          }}
        />
      )}

      {renewTarget && (
        <RenewDocumentModal
          doc={renewTarget}
          onClose={() => setRenewTarget(null)}
          onSuccess={updated => {
            showToast({
              type: 'success',
              title: 'Document renewed',
              body: `${updated.title} renewed until ${formatDisplayDate(updated.expiryDate)}.`,
            });
            setRenewTarget(null);
            loadAll();
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
