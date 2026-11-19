export type ID = string;

export interface VaultDocument {
  id: ID;
  title: string;
  docType: string;
  category: string;
  siteId?: ID;
  uploadedBy: string;
  uploadedAt: string;
  issueDate?: string;
  expiryDate?: string;
  version: number;
  fileSize: string;
  fileName?: string;
  reminderDays?: number;
  linkedEntities: { type: string; ref: string; id?: string }[];
  certStatus?: 'ai_extracted' | 'verified' | 'pending';
  aiClassification?: string;
  obligation?: { renewalDue: string; responsible: string };
  supersedesDocId?: ID;
  renewedFromDocId?: ID;
  status: 'active' | 'superseded' | 'expired' | 'archived';
  archivedAt?: string;
  archivedBy?: string;
  archiveReason?: string;
}

export interface DocumentHistoryEntry {
  id: string;
  action: string;
  description: string;
  actor: string;
  timestamp: string;
  details?: Record<string, unknown>;
}
