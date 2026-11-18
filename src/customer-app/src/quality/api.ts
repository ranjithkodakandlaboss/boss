import { api, safeRandomUUID } from "../auth/api";

// ---- Config options (loaded from /api/v1/config/options/{category}) ----

export type ConfigOption = { id: string; code: string; label: string };

export function listConfigOptions(category: string) {
  return api<ConfigOption[]>(`/api/v1/config/options/${category}`);
}

// ---- Types ----

export type InspectionSummary = {
  id: string;
  inspectionNumber: string;
  inspectionType: string;
  referenceType: string;
  referenceId: string;
  referenceLabel?: string;
  itemId?: string;
  itemCode?: string;
  itemName?: string;
  lotId?: string;
  quantityInspected?: number;
  quantityAccepted?: number;
  quantityRejected?: number;
  result?: string;
  status: string;
  inspectorId?: string;
  inspectorName?: string;
  inspectionDate?: string;
  notes?: string;
  templateId?: string;
  templateName?: string;
  createdAt?: string;
};

export type ChecklistResponse = {
  id: string;
  checklistItemId: string;
  itemDescription: string;
  responseType: string;
  isMandatory: boolean;
  responseValue?: string;
  responsePass?: boolean;
  observation?: string;
  answered: boolean;
};

export type NcrSummary = {
  id: string;
  ncrNumber: string;
  inspectionId?: string;
  itemId?: string;
  itemCode?: string;
  itemName?: string;
  lotId?: string;
  jobId?: string;
  defectCategoryId?: string;
  defectCategoryLabel?: string;
  defectDescription?: string;
  severity?: string;
  quantity?: number;
  status: string;
  disposition?: string;
  raisedBy?: string;
  raisedByName?: string;
  concessionStatus?: string;
  createdAt?: string;
};

export type Concession = {
  id: string;
  ncrId: string;
  justification?: string;
  approvedBy?: string;
  approvedByName?: string;
  approvalNotes?: string;
  status: string;
  createdAt?: string;
};

export type NcrDetail = NcrSummary & {
  inspectionNumber?: string;
  jobNumber?: string;
  rootCause?: string;
  resolution?: string;
  resolvedBy?: string;
  resolvedByName?: string;
  resolvedAt?: string;
  concessions?: Concession[];
  updatedAt?: string;
};

export type InspectionDetail = InspectionSummary & {
  checklistResponses?: ChecklistResponse[];
  linkedNcrs?: NcrSummary[];
  updatedAt?: string;
};

export type DispatchEligibility = {
  jobId: string;
  eligible: boolean;
  reason?: string;
  blockers?: string[];
  inspectionStatus?: string;
  clearanceCertificateStatus?: string;
  clearanceCertificateId?: string;
};

export type ChecklistItem = {
  id: string;
  sortOrder: number;
  description: string;
  responseType: string;
  isMandatory: boolean;
  unit?: string;
  specMin?: number;
  specMax?: number;
};

export type ChecklistTemplate = {
  id: string;
  name: string;
  description?: string;
  scope?: string;
  inspectionType?: string;
  isActive: boolean;
  items?: ChecklistItem[];
  createdAt?: string;
};

export type ClearanceCertificate = {
  id: string;
  certNumber: string;
  jobId: string;
  jobNumber?: string;
  customerId?: string;
  customerName?: string;
  status: string;
  issuedAt?: string;
  issuedBy?: string;
  issuedByName?: string;
  issuedByCustomerContact?: string;
  customerRef?: string;
  notes?: string;
  createdAt?: string;
};

// ---- Helpers ----

function post<T>(path: string, body: unknown) {
  return api<T>(path, {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(body),
  });
}

function patch<T>(path: string, body: unknown) {
  return api<T>(path, {
    method: "PATCH",
    headers: { "Content-Type": "application/json", "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(body),
  });
}

function put<T>(path: string, body: unknown) {
  return api<T>(path, {
    method: "PUT",
    headers: { "Content-Type": "application/json", "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(body),
  });
}

// ---- Inspections ----

export function listInspections(params?: { type?: string; status?: string; q?: string }) {
  const qs = new URLSearchParams();
  if (params?.type) qs.set("type", params.type);
  if (params?.status) qs.set("status", params.status);
  if (params?.q) qs.set("q", params.q);
  return api<InspectionSummary[]>(`/api/v1/quality/inspections?${qs}`);
}

export function getInspection(id: string) {
  return api<InspectionDetail>(`/api/v1/quality/inspections/${id}`);
}

export function createInspection(body: {
  inspectionType: string;
  referenceType: string;
  referenceId: string;
  itemId?: string;
  lotId?: string;
  quantityInspected?: number;
  templateId?: string;
  inspectorId?: string;
  inspectionDate?: string;
  notes?: string;
}) {
  return post<InspectionDetail>("/api/v1/quality/inspections", body);
}

export function recordResult(inspectionId: string, body: {
  result: string;
  quantityAccepted?: number;
  quantityRejected?: number;
  checklistResponses?: {
    checklistItemId: string;
    responseValue?: string;
    responsePass?: boolean;
    observation?: string;
  }[];
  notes?: string;
}) {
  return post<InspectionDetail>(`/api/v1/quality/inspections/${inspectionId}/result`, body);
}

// ---- NCRs ----

export function listNcrs(params?: { status?: string; q?: string }) {
  const qs = new URLSearchParams();
  if (params?.status) qs.set("status", params.status);
  if (params?.q) qs.set("q", params.q);
  return api<NcrSummary[]>(`/api/v1/quality/ncrs?${qs}`);
}

export function getNcr(id: string) {
  return api<NcrDetail>(`/api/v1/quality/ncrs/${id}`);
}

export function createNcr(body: {
  inspectionId?: string;
  itemId?: string;
  lotId?: string;
  jobId?: string;
  defectCategoryId?: string;
  defectDescription?: string;
  severity?: string;
  quantity?: number;
  rootCause?: string;
}) {
  return post<NcrDetail>("/api/v1/quality/ncrs", body);
}

export function updateNcr(id: string, body: {
  status?: string;
  disposition?: string;
  rootCause?: string;
  resolution?: string;
}) {
  return patch<NcrDetail>(`/api/v1/quality/ncrs/${id}`, body);
}

export function createConcession(ncrId: string, body: {
  justification: string;
}) {
  return post<NcrDetail>(`/api/v1/quality/ncrs/${ncrId}/concession`, body);
}

// ---- Clearance Certificates ----

export function listClearanceCertificates(jobId?: string) {
  const qs = jobId ? `?jobId=${encodeURIComponent(jobId)}` : "";
  return api<ClearanceCertificate[]>(`/api/v1/quality/clearance-certificates${qs}`);
}

export function createClearanceCertificate(body: {
  jobId: string;
  issuedByCustomerContact?: string;
  customerRef?: string;
  notes?: string;
}) {
  return post<ClearanceCertificate>("/api/v1/quality/clearance-certificates", body);
}

// ---- Dispatch Eligibility ----

export function checkDispatchEligibility(jobId: string) {
  return api<DispatchEligibility>(`/api/v1/quality/jobs/${jobId}/dispatch-eligibility`);
}

// ---- Checklist Templates ----

export function listTemplates(params?: { scope?: string; inspectionType?: string; activeOnly?: boolean }) {
  const qs = new URLSearchParams();
  if (params?.scope) qs.set("scope", params.scope);
  if (params?.inspectionType) qs.set("inspectionType", params.inspectionType);
  if (params?.activeOnly) qs.set("activeOnly", "true");
  return api<ChecklistTemplate[]>(`/api/v1/quality/checklist-templates?${qs}`);
}

export function getTemplate(id: string) {
  return api<ChecklistTemplate>(`/api/v1/quality/checklist-templates/${id}`);
}

export function createTemplate(body: {
  name: string;
  description?: string;
  scope?: string;
  inspectionType?: string;
  items?: {
    sortOrder: number;
    description: string;
    responseType: string;
    isMandatory: boolean;
    unit?: string;
    specMin?: number;
    specMax?: number;
  }[];
}) {
  return post<ChecklistTemplate>("/api/v1/quality/checklist-templates", body);
}

export function updateTemplate(id: string, body: {
  name?: string;
  description?: string;
  scope?: string;
  inspectionType?: string;
  isActive?: boolean;
  items?: {
    id?: string;
    sortOrder: number;
    description: string;
    responseType: string;
    isMandatory: boolean;
    unit?: string;
    specMin?: number;
    specMax?: number;
  }[];
}) {
  return put<ChecklistTemplate>(`/api/v1/quality/checklist-templates/${id}`, body);
}

// ---- Display helpers ----

const STATUS_LABELS: Record<string, string> = {
  pending: "Pending",
  in_progress: "In Progress",
  completed: "Completed",
  cancelled: "Cancelled",
  open: "Open",
  concession_requested: "Concession Requested",
  concession_granted: "Concession Granted",
  rework: "Rework",
  scrapped: "Scrapped",
  closed: "Closed",
  issued: "Issued",
};

export function statusLabel(status?: string) {
  if (!status) return "—";
  return STATUS_LABELS[status] ?? status.replace(/_/g, " ");
}

export function inspectionTypeLabel(type?: string) {
  const MAP: Record<string, string> = {
    incoming: "Incoming",
    in_process: "In-Process",
    final: "Final",
    customer: "Customer",
    dispatch_readiness: "Dispatch Readiness",
  };
  if (!type) return "—";
  return MAP[type] ?? type.replace(/_/g, " ");
}

export function severityLabel(severity?: string) {
  const MAP: Record<string, string> = {
    critical: "Critical",
    major: "Major",
    minor: "Minor",
    medium: "Medium",
  };
  if (!severity) return "—";
  return MAP[severity] ?? severity;
}

export function displayDate(value?: string | null) {
  if (!value) return "—";
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return value;
  return d.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}
