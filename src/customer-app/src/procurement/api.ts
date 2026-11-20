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
  prLineId: string | null;
  itemId: string;
  quantity?: number;
  uomId?: string;
  uomSymbol?: string;
  unitPrice: number;
  leadTimeDays?: number;
  isSelected?: boolean;
  taxRate?: number;
  grade?: string;
};
export type QuoteRecord = {
  id: string;
  prId: string;
  supplierId: string;
  supplierName?: string;
  status: string;
  validUntil?: string;
  lines?: QuoteLineRecord[];
  supplierQuoteRef?: string;
  paymentTerms?: number;
  paymentTermsText?: string;
  createdAt?: string;
  freightAmount?: number;
  discountAmount?: number;
  taxAmount?: number;
  selectionReason?: string;
  deliveryStatus?: string;
  deliveryRecipient?: string;
  deliveryError?: string;
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
  grade?: string;
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
  prLineId?: string | null;
  supplierQuotationLineId?: string | null;
  uomId?: string;
  uomSymbol?: string;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  orderedQty: number;
  receivedQty?: number;
  acceptedQty?: number;
  rejectedQty?: number;
  gstRate?: number;
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
  committedDate?: string;
  notes?: string;
  lines?: SpoLineRecord[];
  totalAmount?: number;
  paymentTerms?: number;
  paymentTermsText?: string;
  freightAmount?: number;
  taxAmount?: number;
  createdAt?: string;
  revision?: number;
  declineReason?: string;
};
export type GrnLineRecord = {
  id: string;
  spoLineId?: string | null;
  itemId: string;
  uomId?: string;
  itemCode?: string;
  itemName?: string;
  receivedQty: number;
  acceptedQty?: number;
  rejectedQty?: number;
  uomSymbol?: string;
  lotIds?: string[];
  lotNumbers?: string[];
  lotStatuses?: string[];
  lotQuantities?: number[];
  lotSupplierRefs?: string[];
  enteredLotNumber?: string;
  storeId?: string;
  storeName?: string;
};
export type GrnRecord = {
  id: string;
  grnNumber: string;
  spoId?: string;
  spoNumber?: string;
  supplierId: string;
  supplierName?: string;
  siteId?: string;
  siteName?: string;
  status: string;
  receivedDate?: string;
  supplierDcRef?: string;
  vehicleNumber?: string;
  receivedByName?: string;
  storeId?: string;
  storeName?: string;
  lines?: GrnLineRecord[];
};
export type AmendmentRecord = {
  id: string;
  spoId: string;
  revision: number;
  reason: string;
  changeNote: string;
  status: string;
};
export type SupplierReturnRecord = {
  id: string;
  returnNumber: string;
  grnId: string;
  spoId?: string;
  supplierId: string;
  status: string;
  reason: string;
};
export type InspectionRecord = {
  id: string;
  inspectionNumber: string;
  grnId: string;
  lotId: string;
  itemId: string;
  quantityInspected: number;
  quantityAccepted?: number | null;
  quantityRejected?: number | null;
  result?: string | null;
  status: string;
  notes?: string | null;
};
export type AmendmentFileRecord = {
  id: string;
  amendmentId: string;
  fileName: string;
  contentType: string;
  sizeBytes: number;
};
export type SupplierPerformanceRecord = {
  supplierId: string;
  supplierName?: string;
  totalReceipts: number;
  onTimeDeliveryPct?: number | null;
  qualityRejectionPct?: number | null;
  avgLeadTimeDays?: number | null;
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
  selectQuotation: (id: string, reason?: string) => write<QuoteRecord>(
    `/api/v1/supplier-quotations/${id}/select`,
    "POST",
    reason ? { reason } : {},
    reason ? `sq-select:${id}:${reason}` : `sq-select:${id}`,
  ),
  rejectQuotation: (id: string) => write<QuoteRecord>(`/api/v1/supplier-quotations/${id}/reject`, "POST", undefined, `sq-reject:${id}`),
  deliverQuotation: (id: string) => write<QuoteRecord>(`/api/v1/supplier-quotations/${id}/deliver`, "POST", undefined, `sq-deliver:${id}:${safeRandomUUID()}`),
  comparison: (prId: string) => api<{ prId: string; lines: ComparisonLine[] }>(`/api/v1/prs/${prId}/supplier-quotations/comparison`),
  spos: () => allPages<SpoRecord>("/api/v1/spos"),
  spo: (id: string) => api<SpoRecord>(`/api/v1/spos/${id}`),
  createSpo: (body: unknown, idempotencyKey: string) => write<SpoRecord>("/api/v1/spos", "POST", body, idempotencyKey),
  issueSpo: (id: string, body: { committedDate?: string; notes?: string }) =>
    write<SpoRecord>(`/api/v1/spos/${id}/issue`, "POST", body, `spo-issue:${id}`),
  acknowledgeSpo: (id: string) => write<SpoRecord>(`/api/v1/spos/${id}/acknowledge`, "POST", undefined, `spo-ack:${id}`),
  cancelSpo: (id: string) => write<SpoRecord>(`/api/v1/spos/${id}/cancel`, "POST", undefined, `spo-cancel:${id}`),
  declineSpo: (id: string, reason: string) => write<SpoRecord>(`/api/v1/spos/${id}/decline`, "POST", { reason }, `spo-decline:${id}`),
  amendments: (id: string) => api<AmendmentRecord[]>(`/api/v1/spos/${id}/amendments`),
  submitAmendment: (id: string, body: unknown) => write<AmendmentRecord>(`/api/v1/spos/${id}/amendments`, "POST", body),
  decideAmendment: (spoId: string, amendmentId: string, approve: boolean) => write<SpoRecord>(
    `/api/v1/spos/${spoId}/amendments/${amendmentId}/${approve ? "approve" : "reject"}`,
    "POST",
    undefined,
    `spo-amend:${amendmentId}:${approve ? "approve" : "reject"}`,
  ),
  uploadAmendmentFile: (spoId: string, amendmentId: string, file: File) => {
    const body = new FormData();
    body.append("file", file);
    return api<AmendmentFileRecord>(`/api/v1/spos/${spoId}/amendments/${amendmentId}/files`, {
      method: "POST",
      body,
      headers: { "Idempotency-Key": safeRandomUUID() },
    });
  },
  grns: (spoId?: string) => allPages<GrnRecord>(spoId ? `/api/v1/grns?spoId=${spoId}` : "/api/v1/grns"),
  createGrn: (body: unknown, idempotencyKey: string) => write<GrnRecord>("/api/v1/grns", "POST", body, idempotencyKey),
  rejectGrn: (id: string, reason: string) => write<GrnRecord>(`/api/v1/grns/${id}/reject`, "POST", { reason }, `grn-reject:${id}`),
  inspections: (grnId: string) => api<InspectionRecord[]>(`/api/v1/grns/${grnId}/inspections`),
  startInspection: (grnId: string, lotId: string) => write<InspectionRecord>(
    `/api/v1/grns/${grnId}/inspections?lotId=${lotId}`,
    "POST",
    undefined,
    `insp-start:${grnId}:${lotId}`,
  ),
  completeInspection: (id: string, body: { acceptedQty: number; rejectedQty: number; hold: boolean; notes?: string }) => write<InspectionRecord>(
    `/api/v1/quality-inspections/${id}/complete`,
    "POST",
    body,
    `insp-complete:${id}:${body.hold}:${body.acceptedQty}:${body.rejectedQty}:${body.notes || ""}`,
  ),
  createSupplierReturn: (body: unknown, idempotencyKey: string) => write<SupplierReturnRecord>("/api/v1/supplier-returns", "POST", body, idempotencyKey),
  performance: (supplierId: string) => api<SupplierPerformanceRecord>(`/api/v1/supplier-performance?supplierId=${supplierId}`),
};
