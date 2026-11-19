import { useEffect, useMemo, useState } from 'react';
import { History, ArrowUpDown, Search } from 'lucide-react';
import { getDocumentHistory } from './api';
import type { DocumentHistoryEntry } from './types';

interface DocumentHistoryPanelProps {
  documentId: string;
  title?: string;
  subtitle?: string;
}

const fmtDateTime = (iso: string) => {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString('en-IN', {
    day: '2-digit',
    month: 'short',
    year: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  });
};

export default function DocumentHistoryPanel({
  documentId,
  title = 'Document Activity & Audit Trail',
  subtitle = 'Uploads, versions, renewals, archiving, and linked entity associations',
}: DocumentHistoryPanelProps) {
  const [events, setEvents] = useState<DocumentHistoryEntry[]>([]);
  const [loading, setLoading] = useState(true);
  const [newestFirst, setNewestFirst] = useState(true);
  const [query, setQuery] = useState('');

  useEffect(() => {
    let mounted = true;
    setLoading(true);
    getDocumentHistory(documentId)
      .then(res => {
        if (mounted) setEvents(res);
      })
      .catch(() => {
        if (mounted) setEvents([]);
      })
      .finally(() => {
        if (mounted) setLoading(false);
      });
    return () => {
      mounted = false;
    };
  }, [documentId]);

  const filteredEvents = useMemo(() => {
    const q = query.trim().toLowerCase();
    const list = q
      ? events.filter(
          e =>
            e.action.toLowerCase().includes(q) ||
            e.description.toLowerCase().includes(q) ||
            e.actor.toLowerCase().includes(q)
        )
      : events;

    return [...list].sort((a, b) =>
      newestFirst ? b.timestamp.localeCompare(a.timestamp) : a.timestamp.localeCompare(b.timestamp)
    );
  }, [events, query, newestFirst]);

  return (
    <div className="bg-[var(--surface)] border border-[var(--border)] rounded-xl overflow-hidden">
      <div className="px-4 py-3 border-b border-[var(--border)] bg-[var(--surface-2)] flex items-center justify-between gap-3 flex-wrap">
        <div>
          <h3 className="text-sm font-semibold text-[var(--text)] flex items-center gap-2">
            <History size={15} className="text-[var(--brand)]" />
            {title}
            <span className="text-xs font-mono px-1.5 py-0.5 rounded bg-[var(--surface)] border border-[var(--border)] text-[var(--text-2)]">
              {filteredEvents.length}
            </span>
          </h3>
          {subtitle && <p className="text-xs text-[var(--text-3)] mt-0.5">{subtitle}</p>}
        </div>
        <div className="flex items-center gap-2 flex-wrap">
          <div className="relative">
            <Search size={13} className="absolute left-2.5 top-1/2 -translate-y-1/2 text-[var(--text-3)]" />
            <input
              type="text"
              placeholder="Search history…"
              value={query}
              onChange={e => setQuery(e.target.value)}
              className="pl-8 pr-3 py-1 text-xs rounded-lg border border-[var(--border)] bg-[var(--surface)] text-[var(--text)] outline-none w-40"
            />
          </div>
          <button
            type="button"
            onClick={() => setNewestFirst(v => !v)}
            className="flex items-center gap-1 px-2.5 py-1 text-xs rounded-lg border border-[var(--border)] bg-[var(--surface)] text-[var(--text-2)] hover:bg-[var(--surface-2)]"
          >
            <ArrowUpDown size={12} />
            {newestFirst ? 'Newest first' : 'Oldest first'}
          </button>
        </div>
      </div>

      <div className="p-4">
        {loading ? (
          <p className="text-xs text-[var(--text-3)]">Loading history…</p>
        ) : filteredEvents.length === 0 ? (
          <p className="text-xs text-[var(--text-3)]">No activity recorded yet.</p>
        ) : (
          <div className="space-y-3">
            {filteredEvents.map(event => (
              <div
                key={event.id}
                className="flex items-start justify-between gap-3 p-3 rounded-lg border border-[var(--border)] bg-[var(--surface)] text-xs"
              >
                <div>
                  <div className="font-semibold text-[var(--text)]">{event.description}</div>
                  <div className="text-[var(--text-3)] mt-0.5">
                    By <span className="text-[var(--text-2)]">{event.actor}</span> · {fmtDateTime(event.timestamp)}
                  </div>
                </div>
                <span className="px-2 py-0.5 rounded text-[10px] font-mono font-medium bg-[var(--surface-2)] text-[var(--text-3)]">
                  {event.action}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
