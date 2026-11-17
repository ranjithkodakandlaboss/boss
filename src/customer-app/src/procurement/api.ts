import { api, safeRandomUUID } from "../auth/api";
import { allPages } from "../sales/api";

export type Page<T> = { content?: T[]; totalElements?: number; page?: number; size?: number };
export type SupplierContact = { name?: string; role?: string; email?: string; phone?: string; isPrimary?: boolean };
export type SupplierRecord = {
  id: string;
  code: string;
  name: string;
  legalName?: string;
  gstin?: string;
  pan?: string;
  supplierCategoryId?: string;
  supplierCategoryLabel?: string;
  address?: { city?: string; state?: string; category?: string; line1?: string };
  status: string;
  performanceScore?: number | null;
  paymentTerms?: number;
  creditLimit?: number;
  contacts?: SupplierContact[];
};
export type PrLineRecord = {
  id: string;
  seq?: number;
  lineNo?: number;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  qty: number;
  quantity?: number;
  orderedQty?: number;
  uomId: string;
  uomSymbol?: string;
  estUnitPrice?: number;
  requiredDate?: string;
  note?: string;
  notes?: string;
};
export type PrRecord = {
  id: string;
  prNumber: string;
  siteId?: string;
  originType?: string;
  jobId?: string;
  customerPoId?: string;
  status: string;
  priority?: string;
  prTypeLabel?: string;
  requiredDate?: string;
  notes?: string;
  requestedBy?: string;
  approvedBy?: string;
  approvedAt?: string;
  rejectionReason?: string;
  lines?: PrLineRecord[];
  totalQty?: number;
  createdAt?: string;
  updatedAt?: string;
};
export type QuoteLineRecord = {
  id: string;
  prLineId: string;
  itemId: string;
  unitPrice: number;
  leadTimeDays?: number;
  isSelected?: boolean;
};
export type QuoteRecord = {
  id: string;
  prId: string;
  supplierId: string;
  supplierName?: string;
  status: string;
  validUntil?: string;
  lines?: QuoteLineRecord[];
};
export type ComparisonOffer = {
  supplierQuotationId: string;
  supplierId: string;
  supplierName?: string;
  status: string;
  unitPrice: number;
  leadTimeDays?: number;
  validUntil?: string;
  isSelected?: boolean;
};
export type ComparisonLine = {
  prLineId: string;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  quantity: number;
  uomSymbol?: string;
  offers: ComparisonOffer[];
};
export type SpoLineRecord = {
  id: string;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  orderedQty: number;
  receivedQty?: number;
  unitPrice: number;
  lineTotal?: number;
  expectedDeliveryDate?: string;
};
export type SpoRecord = {
  id: string;
  spoNumber: string;
  prId?: string;
  supplierQuotationId?: string;
  supplierId: string;
  supplierName?: string;
  status: string;
  lines?: SpoLineRecord[];
  totalAmount?: number;
  createdAt?: string;
};

function key(value?: string) {
  return value || safeRandomUUID();
}

function write<T>(path: string, method: string, body?: unknown, idempotencyKey?: string) {
  return api<T>(path, {
    method,
    body: body === undefined ? undefined : JSON.stringify(body),
    headers: { "Idempotency-Key": key(idempotencyKey) },
  });
}

export const procurementApi = {
  suppliers: async () => {
    const [active, inactive] = await Promise.all([
      allPages<SupplierRecord>("/api/v1/suppliers?status=active"),
      allPages<SupplierRecord>("/api/v1/suppliers?status=inactive"),
    ]);
    return [...active, ...inactive];
  },
  createSupplier: (body: unknown, idempotencyKey?: string) => write<SupplierRecord>("/api/v1/suppliers", "POST", body, idempotencyKey),
  updateSupplier: (id: string, body: unknown) => write<SupplierRecord>(`/api/v1/suppliers/${id}`, "PATCH", body),
  prs: (customerPoId?: string) => allPages<PrRecord>(customerPoId ? `/api/v1/prs?customerPoId=${customerPoId}` : "/api/v1/prs"),
  pr: (id: string) => api<PrRecord>(`/api/v1/prs/${id}`),
  createPr: (body: unknown, idempotencyKey?: string) => write<PrRecord>("/api/v1/prs", "POST", body, idempotencyKey),
  updatePr: (id: string, body: unknown) => write<PrRecord>(`/api/v1/prs/${id}`, "PUT", body),
  submitPr: (id: string) => write<PrRecord>(`/api/v1/prs/${id}/submit`, "POST", undefined, `pr-submit:${id}`),
  approvePr: (id: string) => write<PrRecord>(`/api/v1/prs/${id}/approve`, "POST", undefined, `pr-approve:${id}`),
  rejectPr: (id: string, reason: string) => write<PrRecord>(`/api/v1/prs/${id}/reject`, "POST", { reason }, `pr-reject:${id}`),
  cancelPr: (id: string) => write<PrRecord>(`/api/v1/prs/${id}/cancel`, "POST", undefined, `pr-cancel:${id}`),
  quotations: (prId: string) => allPages<QuoteRecord>(`/api/v1/prs/${prId}/supplier-quotations`),
  createQuotation: (prId: string, supplierId: string) => write<QuoteRecord>(
    `/api/v1/prs/${prId}/supplier-quotations`,
    "POST",
    { supplierId },
    `sq-request:${prId}:${supplierId}`,
  ),
  quotation: (id: string) => api<QuoteRecord>(`/api/v1/supplier-quotations/${id}`),
  receiveQuotation: (id: string, body: unknown) => write<QuoteRecord>(`/api/v1/supplier-quotations/${id}/receive`, "POST", body, `sq-receive:${id}`),
  reviewQuotation: (id: string) => write<QuoteRecord>(`/api/v1/supplier-quotations/${id}/review`, "POST", undefined, `sq-review:${id}`),
  selectQuotation: (id: string) => write<QuoteRecord>(`/api/v1/supplier-quotations/${id}/select`, "POST", undefined, `sq-select:${id}`),
  rejectQuotation: (id: string) => write<QuoteRecord>(`/api/v1/supplier-quotations/${id}/reject`, "POST", undefined, `sq-reject:${id}`),
  comparison: (prId: string) => api<{ prId: string; lines: ComparisonLine[] }>(`/api/v1/prs/${prId}/supplier-quotations/comparison`),
  spos: () => allPages<SpoRecord>("/api/v1/spos"),
  spo: (id: string) => api<SpoRecord>(`/api/v1/spos/${id}`),
  createSpo: (body: unknown, idempotencyKey: string) => write<SpoRecord>("/api/v1/spos", "POST", body, idempotencyKey),
  issueSpo: (id: string) => write<SpoRecord>(`/api/v1/spos/${id}/issue`, "POST", undefined, `spo-issue:${id}`),
  acknowledgeSpo: (id: string) => write<SpoRecord>(`/api/v1/spos/${id}/acknowledge`, "POST", undefined, `spo-ack:${id}`),
  cancelSpo: (id: string) => write<SpoRecord>(`/api/v1/spos/${id}/cancel`, "POST", undefined, `spo-cancel:${id}`),
};
