import { api, safeRandomUUID } from "../auth/api";

export type ConfigOption = { id: string; code: string; label: string };

export function listConfigOptions(category: string): Promise<ConfigOption[]> {
  return api<ConfigOption[]>(`/api/v1/config/options/${category}`).catch(() => []);
}

export function createConfigOption(category: string, code: string, label: string): Promise<ConfigOption> {
  return api<ConfigOption>(`/api/v1/config/options/${category}`, {
    method: "POST",
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify({ code: code.toUpperCase().replace(/\s+/g, "_"), label, sortOrder: 0 }),
  });
}

export type Site = { id: string; name: string; code: string };
export type Store = { id: string; siteId: string; name: string; code: string };

export type Customer = {
  id: string;
  name: string;
  tradingName?: string;
  code?: string;
};

export type Job = {
  id: string;
  jobNumber: string;
  customerId?: string;
  customerName?: string;
  customerPoId?: string;
  customerPoNumber?: string;
  description?: string;
  status: string;
};

export type QualityEligibility = {
  jobId: string;
  eligible: boolean;
  reason: string;
  blockers: string[];
  inspectionStatus?: string;
  clearanceCertificateStatus?: string;
  clearanceCertificateId?: string;
};

export type DispatchLine = {
  id: string;
  dispatchId: string;
  cpoLineId?: string;
  jobId?: string;
  jobNumber?: string;
  itemId: string;
  itemCode: string;
  itemName: string;
  lotId?: string;
  lotNumber?: string;
  quantity: number;
  uomId: string;
  uomSymbol?: string;
  packingSpecId?: string;
  clearanceCertId?: string;
  lineNo: number;
};

export type DispatchPackage = {
  id: string;
  dispatchId: string;
  packageCode: string;
  packageType: string;
  productDescription?: string;
  quantity: number;
  uom?: string;
  weightKg?: number;
  lengthMm?: number;
  widthMm?: number;
  heightMm?: number;
  packingNotes?: string;
  createdAt: string;
};

export type DispatchHistory = {
  id: string;
  action: string;
  title: string;
  description: string;
  actorName?: string;
  timestamp: string;
};

export type LinkedInvoice = {
  id: string;
  invoiceNumber: string;
  status: string;
  totalAmount: number;
  invoiceDate?: string;
};

export type DispatchSummary = {
  id: string;
  dispatchNumber: string;
  customerId: string;
  customerName: string;
  cpoId?: string;
  cpoNumber?: string;
  jobId?: string;
  jobNumber?: string;
  jobs?: string[];
  sourceSiteId: string;
  sourceSiteName: string;
  sourceStoreId?: string;
  sourceStoreName?: string;
  carrier?: string;
  dispatchMethodOptionId?: string;
  dispatchMethodLabel?: string;
  vehicleNumber?: string;
  driverName?: string;
  lrNumber?: string;
  dispatchDate: string;
  expectedDelivery?: string;
  totalQty?: number;
  lineCount?: number;
  status: string;
  hasPoD: boolean;
  createdAt: string;
};

export type DispatchDetail = DispatchSummary & {
  destination?: string;
  actualDeliveryDate?: string;
  deliveryCondition?: "good" | "shortage" | "partial_damage" | "damaged" | "partial";
  podReference?: string;
  deliveryNotes?: string;
  receivedByName?: string;
  receivedAt?: string;
  qualityReleaseId?: string;
  qualityReleaseStatus?: string;
  qualityReleaseBy?: string;
  qualityInspectionId?: string;
  qualityInspectionNumber?: string;
  qualityInspectionResult?: string;
  failedInspectionId?: string;
  failedInspectionNumber?: string;
  linkedNcrId?: string;
  linkedNcrNumber?: string;
  challanNumber?: string;
  challanGcsPath?: string;
  lines: DispatchLine[];
  packages: DispatchPackage[];
  history: DispatchHistory[];
  linkedInvoices: LinkedInvoice[];
  updatedAt: string;
};

export type CreateDispatchRequest = {
  customerId: string;
  customerName?: string;
  cpoId?: string;
  jobId?: string;
  sourceSiteId?: string;
  sourceSiteName?: string;
  sourceStoreId?: string;
  sourceStoreName?: string;
  carrier?: string;
  dispatchMethodOptionId?: string;
  vehicleNumber?: string;
  driverName?: string;
  lrNumber?: string;
  dispatchDate: string;
  expectedDelivery?: string;
  destination?: string;
  notes?: string;
  jobs?: string[];
  lines?: {
    cpoLineId?: string;
    jobId?: string;
    itemId?: string;
    quantity: number;
    uomId?: string;
    lotId?: string;
    packingSpecId?: string;
    clearanceCertId?: string;
  }[];
  packages?: {
    packageCode: string;
    packageType: string;
    productDescription?: string;
    quantity: number;
    uom?: string;
    weightKg?: number;
    lengthMm?: number;
    widthMm?: number;
    heightMm?: number;
    packingNotes?: string;
  }[];
};

export type RecordPodRequest = {
  receivedBy: string;
  deliveredDate?: string;
  condition?: string;
  podReference?: string;
  notes?: string;
  signatureRef?: string;
};

export type UpdateStatusRequest = {
  status: string;
  notes?: string;
};

// API calls

export function listDispatches(params?: { siteId?: string; status?: string; q?: string }): Promise<DispatchSummary[]> {
  const query = new URLSearchParams();
  if (params?.siteId && params.siteId !== "all") query.set("siteId", params.siteId);
  if (params?.status && params.status !== "all") query.set("status", params.status);
  if (params?.q) query.set("q", params.q);
  const qStr = query.toString();
  return api<DispatchSummary[]>(`/api/v1/dispatches${qStr ? `?${qStr}` : ""}`);
}

export function getDispatch(id: string): Promise<DispatchDetail> {
  return api<DispatchDetail>(`/api/v1/dispatches/${id}`);
}

export function createDispatch(req: CreateDispatchRequest): Promise<DispatchDetail> {
  return api<DispatchDetail>("/api/v1/dispatches", {
    method: "POST",
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(req),
  });
}

export function updateDispatchStatus(id: string, req: UpdateStatusRequest): Promise<DispatchDetail> {
  return api<DispatchDetail>(`/api/v1/dispatches/${id}/status`, {
    method: "PATCH",
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(req),
  });
}

export function recordPoD(id: string, req: RecordPodRequest): Promise<DispatchDetail> {
  return api<DispatchDetail>(`/api/v1/dispatches/${id}/pod`, {
    method: "POST",
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(req),
  });
}

export function deleteDispatch(id: string): Promise<void> {
  return api<void>(`/api/v1/dispatches/${id}`, {
    method: "DELETE",
    headers: { "Idempotency-Key": safeRandomUUID() },
  });
}

export function checkDispatchEligibility(jobId: string): Promise<QualityEligibility> {
  return api<QualityEligibility>(`/api/v1/quality/jobs/${jobId}/dispatch-eligibility`);
}

export function loadCompanySitesAndStores(): Promise<{ sites: Site[]; stores: Store[] }> {
  return api<{ sites?: Site[]; stores?: Store[] }>("/api/v1/company")
    .then((res) => ({
      sites: res.sites ?? [],
      stores: res.stores ?? [],
    }))
    .catch(() => ({ sites: [], stores: [] }));
}

export function loadCustomersList(): Promise<Customer[]> {
  return api<{ content?: Customer[] } | Customer[]>("/api/v1/customers?size=200")
    .then((res) => (Array.isArray(res) ? res : res.content ?? []))
    .catch(() => []);
}

export function loadJobsList(): Promise<Job[]> {
  return api<{ content?: Job[] } | Job[]>("/api/v1/jobs?size=200")
    .then((res) => (Array.isArray(res) ? res : res.content ?? []))
    .catch(() => []);
}
