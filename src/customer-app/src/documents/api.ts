import { api, safeRandomUUID } from "../auth/api";
import type { DocumentHistoryEntry, VaultDocument } from "./types";

export interface DocumentListParams {
  category?: string;
  status?: string;
  attention?: string;
  includeArchived?: boolean;
  q?: string;
  page?: number;
  size?: number;
}

export interface PageResponse<T> {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
}

export function listDocuments(params: DocumentListParams = {}): Promise<PageResponse<VaultDocument>> {
  const query = new URLSearchParams();
  if (params.category && params.category !== 'All') query.set('category', params.category);
  if (params.status) query.set('status', params.status);
  if (params.attention && params.attention !== 'all') query.set('attention', params.attention);
  if (params.includeArchived) query.set('includeArchived', 'true');
  if (params.q) query.set('q', params.q);
  if (params.page !== undefined) query.set('page', String(params.page));
  if (params.size !== undefined) query.set('size', String(params.size));
  const qs = query.toString();
  return api<PageResponse<VaultDocument>>(`/api/v1/documents${qs ? `?${qs}` : ''}`);
}

export function getDocument(id: string): Promise<VaultDocument> {
  return api<VaultDocument>(`/api/v1/documents/${id}`);
}

export function uploadDocument(formData: FormData): Promise<VaultDocument> {
  return api<VaultDocument>('/api/v1/documents', {
    method: 'POST',
    headers: { 'Idempotency-Key': safeRandomUUID() },
    body: formData,
  });
}

export function uploadLinkedDocument(file: File, entity: { type: string; id: string; ref: string }): Promise<VaultDocument> {
  if (file.size > 20 * 1024 * 1024) throw new Error('Choose a document no larger than 20 MB.');
  const body = new FormData();
  body.append('file', file);
  body.append('title', file.name);
  body.append('category', entity.type === 'Customer' ? 'Customer Documents' : 'Sales Documents');
  body.append('docType', `${entity.type} Document`);
  body.append('linkedEntityType', entity.type);
  body.append('linkedEntityId', entity.id);
  body.append('linkedEntityRef', entity.ref);
  return uploadDocument(body);
}

export function renewDocument(id: string, formData: FormData): Promise<VaultDocument> {
  return api<VaultDocument>(`/api/v1/documents/${id}/renew`, {
    method: 'POST',
    headers: { 'Idempotency-Key': safeRandomUUID() },
    body: formData,
  });
}

export function archiveDocument(id: string, reason: string): Promise<VaultDocument> {
  return api<VaultDocument>(`/api/v1/documents/${id}/archive`, {
    method: 'PATCH',
    headers: { 'Idempotency-Key': safeRandomUUID() },
    body: JSON.stringify({ reason }),
  });
}

export function downloadDocumentUrl(id: string, version?: number): string {
  const base = import.meta.env.VITE_CUSTOMER_API_URL ?? "http://127.0.0.1:8080";
  return `${base}/api/v1/documents/${id}/download${version ? `?version=${version}` : ''}`;
}

export async function downloadDocument(id: string, fileName?: string, version?: number): Promise<void> {
  const { currentToken } = await import('../auth/session');
  const base = import.meta.env.VITE_CUSTOMER_API_URL ?? "http://127.0.0.1:8080";
  const qs = version ? `?version=${version}` : '';
  const headers: Record<string, string> = {};
  const token = currentToken();
  if (token) headers['Authorization'] = `Bearer ${token}`;
  const tenantId = sessionStorage.getItem('boss.tenant');
  if (tenantId) headers['X-Tenant-ID'] = tenantId;
  const response = await fetch(`${base}/api/v1/documents/${id}/download${qs}`, { headers });
  if (!response.ok) throw new Error(`Download failed: ${response.statusText}`);
  const blob = await response.blob();
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = fileName || `document-${id}`;
  document.body.appendChild(a);
  a.click();
  document.body.removeChild(a);
  URL.revokeObjectURL(url);
}

export function getDocumentHistory(id: string): Promise<DocumentHistoryEntry[]> {
  return api<DocumentHistoryEntry[]>(`/api/v1/documents/${id}/history`);
}

export interface SimpleOption {
  id: string;
  name: string;
  ref?: string;
}

export async function loadSuppliers(): Promise<SimpleOption[]> {
  try {
    const res = await api<{ content?: Array<{ id: string; name: string; legalName?: string }> } | Array<{ id: string; name: string }>>('/api/v1/suppliers?size=200');
    const items = Array.isArray(res) ? res : (res.content ?? []);
    return items.map(s => ({ id: s.id, name: s.name }));
  } catch {
    return [];
  }
}

export async function loadCustomers(): Promise<SimpleOption[]> {
  try {
    const res = await api<{ content?: Array<{ id: string; name: string; legalName?: string }> } | Array<{ id: string; name: string }>>('/api/v1/customers?size=200');
    const items = Array.isArray(res) ? res : (res.content ?? []);
    return items.map(c => ({ id: c.id, name: c.name }));
  } catch {
    return [];
  }
}

export async function loadJobs(): Promise<Array<{ id: string; jobNumber: string; description?: string }>> {
  try {
    const res = await api<{ content?: Array<{ id: string; jobNumber: string; description?: string }> } | Array<{ id: string; jobNumber: string; description?: string }>>('/api/v1/jobs?size=200');
    const items = Array.isArray(res) ? res : (res.content ?? []);
    return items.map(j => ({ id: j.id, jobNumber: j.jobNumber, description: j.description || j.jobNumber }));
  } catch {
    return [];
  }
}

export async function loadAssets(): Promise<Array<{ id: string; assetCode: string; name: string }>> {
  try {
    const res = await api<Array<{ id: string; assetNumber?: string; assetCode?: string; name: string }>>('/api/v1/assets?includeArchived=false');
    if (Array.isArray(res)) {
      return res.map(a => ({ id: a.id, assetCode: a.assetNumber || a.assetCode || a.id.slice(0, 8), name: a.name }));
    }
    return [];
  } catch {
    return [];
  }
}
