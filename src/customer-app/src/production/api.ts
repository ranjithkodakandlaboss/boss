import { api, safeRandomUUID } from "../auth/api";

export type Page<T> = { content?: T[]; totalElements?: number; page?: number; size?: number };

export type JobStage = {
  id: string;
  seqNo: number;
  stageName: string;
  stageCode?: string;
  status: string;
  requiresInspection: boolean;
  inspectionStatus: string;
  isOptional: boolean;
  canSkip: boolean;
  workCentre?: string;
  assetId?: string;
  assetCode?: string;
  actualStartAt?: string;
  actualEndAt?: string;
};

export type MaterialRequirement = {
  id: string;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  requiredQty: number;
  issuedQty: number;
  returnedQty: number;
  netIssuedQty: number;
  uomSymbol?: string;
  readiness: string;
};

export type MaterialMovement = {
  id: string;
  movementId?: string;
  requirementId?: string;
  sourceIssueId?: string;
  lotId?: string;
  issueType: string;
  quantity: number;
  unitCost?: number;
  createdAt?: string;
};

export type Inspection = {
  id: string;
  stageId: string;
  inspectionNumber?: string;
  inspectionType?: string;
  status?: string;
  result?: string;
  ncrId?: string;
  ncrStatus?: string;
  concessionStatus?: string;
};

export type CostSummary = {
  materialCost?: number;
  labourCost?: number;
  overheadCost?: number;
  totalCost?: number;
  sellingPrice?: number;
  marginPct?: number;
};

export type ProductionJob = {
  id: string;
  jobNumber: string;
  cpoId?: string;
  cpoLineId?: string;
  cpoNumber?: string;
  customerId?: string;
  customerName?: string;
  itemId?: string;
  itemCode?: string;
  itemName?: string;
  qty?: number;
  uomSymbol?: string;
  routingTemplateId?: string;
  routingTemplateName?: string;
  routingTemplateVersion?: string;
  status: string;
  priority?: string;
  siteId?: string;
  siteName?: string;
  plannedStart?: string;
  plannedEnd?: string;
  actualStart?: string;
  actualEnd?: string;
  currentStage?: string;
  materialReadiness?: string;
  risk?: string;
  daysRemaining?: number;
  projectedCompletion?: string;
  projectionBasis?: string;
  stages?: JobStage[];
  materials?: MaterialRequirement[];
  movements?: MaterialMovement[];
  inspections?: Inspection[];
  estimatedCost?: CostSummary;
  actualCost?: CostSummary;
  variance?: number;
  createdAt?: string;
};

export type EligibleLine = {
  cpoId: string;
  cpoNumber: string;
  cpoLineId: string;
  customerId?: string;
  customerName?: string;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  qty?: number;
  remainingQty?: number;
  unitPrice?: number;
  sellingPrice?: number;
  siteId?: string;
  siteName?: string;
  requiredBy?: string;
  status?: string;
};

export type RoutingTemplate = { id: string; name: string; version?: string; finishedItemId?: string; stageCount: number };

export type LotOption = {
  id: string;
  itemId: string;
  lotNumber?: string | null;
  batchRef?: string | null;
  qty?: number;
  reservedQty?: number;
  availableQty?: number;
  uomSymbol?: string;
  unitCost?: number;
  status?: string;
  siteId?: string;
  storeName?: string;
};

const READINESS_LABELS: Record<string, string> = {
  pending: "Pending",
  available: "Available",
  partial: "Partial",
  shortage: "Shortage",
  awaiting_procurement: "Awaiting Procurement",
  awaiting_grn: "Awaiting goods receipt",
  quality_hold: "Quality Hold",
  reserved: "Reserved",
};

export function readinessLabel(value?: string) {
  if (!value) return "Pending";
  return READINESS_LABELS[value] ?? value.replace(/_/g, " ");
}

export function money(value?: number | null) {
  if (value == null) return "—";
  return `₹${Number(value).toLocaleString("en-IN", { maximumFractionDigits: 2 })}`;
}

export function displayDate(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

function post<T>(path: string, body: unknown) {
  return api<T>(path, {
    method: "POST",
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(body),
  });
}

export function listJobs() {
  return api<Page<ProductionJob>>("/api/v1/jobs?page=0&size=200");
}

export function getJob(id: string) {
  return api<ProductionJob>(`/api/v1/jobs/${id}`);
}

export function featureFlags() {
  return api<Record<string, boolean>>("/api/v1/jobs/features");
}

export function eligibleLines() {
  return api<EligibleLine[]>("/api/v1/jobs/eligible-cpos");
}

export function routingTemplates(itemId: string) {
  return api<RoutingTemplate[]>(`/api/v1/jobs/routing-templates?itemId=${itemId}`);
}

export function createRoutingTemplate(name: string, stageName: string, stages?: Array<{ name: string; workCentre?: string; assetId?: string; stageConfigOptionId?: string }>) {
  const parsed = stages && stages.length > 0
    ? stages
    : stageName.split(",").map((part) => part.trim()).filter(Boolean).map((stage) => ({ name: stage }));
  return post<RoutingTemplate>("/api/v1/jobs/routing-templates", { name, stageName: parsed[0]?.name || stageName, stages: parsed });
}

export function createJob(body: {
  cpoId: string;
  cpoLineId: string;
  routingTemplateId: string;
  scheduledStart?: string;
  scheduledEnd?: string;
  priority?: string;
  quantity?: number;
}) {
  return post<ProductionJob>("/api/v1/jobs", body);
}

export function cancelJob(id: string, reason: string) {
  return post<ProductionJob>(`/api/v1/jobs/${id}/cancel`, { reason });
}

export function holdJob(id: string, reason: string) {
  return post<ProductionJob>(`/api/v1/jobs/${id}/hold`, { reason });
}

export function releaseHold(id: string, reason: string) {
  return post<ProductionJob>(`/api/v1/jobs/${id}/release-hold`, { reason });
}

export function startStage(jobId: string, stageId: string, note: string) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/stages/${stageId}/start`, { note });
}

export function completeStage(jobId: string, stageId: string, note: string) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/stages/${stageId}/complete`, { note });
}

export function skipStage(jobId: string, stageId: string, reason: string) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/stages/${stageId}/skip`, { reason });
}

export function issueMaterial(jobId: string, requirementId: string, lotId: string, qty: number, note: string) {
  return post<MaterialMovement>(`/api/v1/jobs/${jobId}/materials/issues`, { requirementId, lotId, qty, note });
}

export function returnMaterial(jobId: string, sourceIssueId: string, qty: number, note: string) {
  return post<MaterialMovement>(`/api/v1/jobs/${jobId}/materials/returns`, { sourceIssueId, qty, note });
}

export function openInspection(jobId: string, stageId: string) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/stages/${stageId}/inspections`, {});
}

export function recordInspection(jobId: string, inspectionId: string, result: "pass" | "fail", note: string) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/inspections/${inspectionId}/result`, {
    result, quantityAccepted: result === "pass" ? 1 : 0, quantityRejected: result === "fail" ? 1 : 0, note,
  });
}

export function concede(jobId: string, inspectionId: string, note: string) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/inspections/${inspectionId}/concession`, {
    concessionType: "use_as_is", note,
  });
}

export function addCost(jobId: string, entryType: "LABOUR" | "OVERHEAD" | "OTHER", description: string, amount: number) {
  return post(`/api/v1/jobs/${jobId}/costing`, { entryType, description, amount });
}

export type JobLotReservation = {
  jobId: string;
  lotId: string;
  itemId: string;
  reservedQty: number;
};

export async function usableLots(itemId: string, jobId: string) {
  const query = `/api/v1/inventory/lots?itemId=${encodeURIComponent(itemId)}&page=0&size=200&status=`;
  const [available, reserved, owned] = await Promise.all([
    api<Page<LotOption>>(`${query}available`),
    api<Page<LotOption>>(`${query}reserved`),
    api<JobLotReservation[]>(`/api/v1/inventory/reservations/lots?jobId=${encodeURIComponent(jobId)}`),
  ]);
  const ownedIds = new Set((Array.isArray(owned) ? owned : [])
    .filter((row) => row.itemId === itemId)
    .map((row) => row.lotId));
  const seen = new Set<string>();
  const content = [...(available.content ?? []), ...(reserved.content ?? [])].filter((lot) => {
    if (seen.has(lot.id)) return false;
    if (lot.status === "reserved" && !ownedIds.has(lot.id)) return false;
    seen.add(lot.id);
    return true;
  });
  return { content };
}
