// @ts-nocheck
import React, { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { ArrowLeft } from 'lucide-react';
import { useStore } from './runtime';
import StatusChip from '../../components/shared/StatusChip';

export default function IncomingInspection() {
  const { id } = useParams<{ id: string }>();
  const { inspections, lots, grns, currentUser, completeInspection } = useStore();
  const inspection = inspections.find(item => item.id === id);
  const lot = lots.find(item => item.id === inspection?.lotId);
  const grn = grns.find(item => item.id === inspection?.grnId);
  const quantity = Number(inspection?.quantityInspected || lot?.primaryQty || 0);
  const [accepted, setAccepted] = useState('');
  const [rejected, setRejected] = useState('');
  const [notes, setNotes] = useState(inspection?.notes || '');
  const canConcede = currentUser.role === 'owner';

  if (!inspection) {
    return (
      <div className="p-6 text-sm text-[var(--text-2)]">
        Inspection not found. <Link to="/procurement?tab=grn" className="text-[var(--brand)] underline">Back to receipts</Link>
      </div>
    );
  }

  const finished = inspection.status === 'completed';
  const record = (acceptedQty: number, rejectedQty: number, hold = false) => {
    completeInspection(inspection.id, { acceptedQty, rejectedQty, hold, notes });
  };

  return (
    <div className="max-w-3xl mx-auto p-4 md:p-6 space-y-4">
      <Link to={grn ? `/procurement/grn/${grn.id}` : '/procurement?tab=grn'} className="inline-flex items-center gap-1.5 text-sm text-[var(--text-2)]">
        <ArrowLeft size={14} /> Back to {grn?.grnNumber || 'receipt'}
      </Link>
      <div className="flex items-center gap-2 flex-wrap">
        <h1 className="text-xl font-bold font-mono">{inspection.inspectionNumber}</h1>
        <StatusChip status={finished ? (inspection.result || inspection.status) : 'pending_qc'} />
      </div>
      <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-4 text-sm grid grid-cols-2 gap-3">
        <Field label="Receipt" value={grn?.grnNumber || '—'} />
        <Field label="Lot" value={lot?.lotNumber || inspection.lotId} />
        <Field label="Quantity inspected" value={String(quantity)} />
        <Field label="Result" value={inspection.result || 'Waiting'} />
      </div>
      {finished ? (
        <div className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-4 text-sm">
          <p>Accepted {inspection.quantityAccepted}. Rejected {inspection.quantityRejected}.</p>
          {inspection.notes && <p className="mt-2 text-[var(--text-2)]">{inspection.notes}</p>}
          <p className="mt-2 text-xs text-[var(--text-3)]">Accepted quantity is available. Rejected quantity stays in quarantine for a supplier return.</p>
        </div>
      ) : (
        <form className="rounded-xl border border-[var(--border)] bg-[var(--surface)] p-4 space-y-3" onSubmit={event => event.preventDefault()}>
          <h2 className="font-semibold text-sm">Record incoming result</h2>
          <label className="block text-xs">Accepted quantity
            <input className="eoss-input w-full mt-1" type="number" min="0" step="0.001" value={accepted} onChange={event => setAccepted(event.target.value)} />
          </label>
          <label className="block text-xs">Rejected quantity
            <input className="eoss-input w-full mt-1" type="number" min="0" step="0.001" value={rejected} onChange={event => setRejected(event.target.value)} />
          </label>
          <label className="block text-xs">Notes
            <textarea className="eoss-input w-full mt-1" rows={3} value={notes} onChange={event => setNotes(event.target.value)} />
          </label>
          <div className="flex flex-wrap gap-2">
            <button type="button" className="px-3 py-1.5 rounded-lg border text-xs" onClick={() => record(0, 0, true)}>Keep on Hold</button>
            <button type="button" className="px-3 py-1.5 rounded-lg text-xs text-white" style={{ background: 'var(--success)' }} onClick={() => record(quantity, 0)}>Accept and Release</button>
            {canConcede && (
              <button type="button" className="px-3 py-1.5 rounded-lg text-xs text-white" style={{ background: 'var(--brand)' }} onClick={() => record(Number(accepted || 0), Number(rejected || 0))}>Partial Accept</button>
            )}
            <button type="button" className="px-3 py-1.5 rounded-lg text-xs text-white" style={{ background: 'var(--danger)' }} onClick={() => record(0, quantity)}>Reject</button>
          </div>
          <p className="text-[11px] text-[var(--text-3)]">A partial accept needs a concession note. The owner records it. Rejected stock is not scrapped and is not returned automatically.</p>
        </form>
      )}
    </div>
  );
}

function Field({ label, value }: { label: string; value: string }) {
  return <div><div className="text-[11px] text-[var(--text-3)]">{label}</div><div className="font-medium">{value}</div></div>;
}
