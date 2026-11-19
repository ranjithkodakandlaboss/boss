import type { VaultDocument } from './types';

export const REMINDER_WINDOW_DAYS = 30;

export interface DocumentExpiryInfo {
  daysToExpiry: number | null;
  status: 'none' | 'ok' | 'expiring' | 'expired';
}

export function computeDocumentExpiry(doc: VaultDocument, now: Date): DocumentExpiryInfo {
  if (!doc.expiryDate) return { daysToExpiry: null, status: 'none' };
  const expiryTime = new Date(doc.expiryDate).getTime();
  const days = Math.ceil((expiryTime - now.getTime()) / 86400000);
  if (days < 0) return { daysToExpiry: days, status: 'expired' };
  if (days <= (doc.reminderDays ?? REMINDER_WINDOW_DAYS)) return { daysToExpiry: days, status: 'expiring' };
  return { daysToExpiry: days, status: 'ok' };
}

export function listExpiringDocuments(documents: VaultDocument[], now: Date) {
  return documents
    .filter(d => d.status !== 'superseded' && d.status !== 'archived')
    .map(d => ({ doc: d, info: computeDocumentExpiry(d, now) }))
    .filter(x => x.info.status === 'expiring' || x.info.status === 'expired');
}
