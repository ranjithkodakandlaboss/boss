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
  blockerReason?: string;
  actualStartAt?: string;
  actualEndAt?: string;
  // Planning and execution (new – nullable for historical jobs)
  plannedStart?: string;
  plannedEnd?: string;
  estimatedDurationMin?: number;
  completedQty?: number;
  blockedQty?: number;
  assignedOperator?: string;
  assignedOperatorId?: string;
  variancePct?: number;
};

export type StagePlanEntry = {
  seqNo: number;
  stageName: string;
  workCentre?: string;
  estimatedDurationMin: number;
  plannedStart: string;
  plannedEnd: string;
};

export type PlanPreview = {
  estimatedStartDate: string;
  expectedCompletionDate: string;
  deliveryRisk: string;
  deliveryRiskReason?: string;
  stages: StagePlanEntry[];
};

export type CustomerOption = { id: string; name: string; code?: string };
export type ItemOption = { id: string; name: string; code?: string; uomId?: string; uomSymbol?: string };

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
  stageId?: string;
  inspectionNumber?: string;
  inspectionType?: string;
  status?: string;
  result?: string;
  quantityInspected?: number;
  quantityAccepted?: number;
  quantityRejected?: number;
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
  materialStatus?: string;
  labourStatus?: string;
  overheadStatus?: string;
};

export type CostEntry = {
  id: string;
  entryType: string;
  description: string;
  qty?: number;
  unitCost?: number;
  amount: number;
  estimated: boolean;
  createdAt: string;
};

export type HistoricalCostReference = {
  completedOrderCount: number;
  totalQuantity: number;
  totalActualCost: number;
  weightedAverageUnitCost?: number;
  basis: string;
};

export type JobCosting = {
  jobId: string;
  jobNumber: string;
  estimatedCost: CostSummary;
  actualCost: CostSummary;
  variance?: number;
  estimatedProfit?: number;
  actualProfit?: number;
  estimatedCostBasis: string;
  historicalReference: HistoricalCostReference;
  entries: CostEntry[];
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
  holdReason?: string;
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
  lineNo: number;
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
  issued: "Issued",
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

export function getJobCosting(id: string) {
  return api<JobCosting>(`/api/v1/jobs/${id}/costing`);
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
  cpoId?: string;
  cpoLineId?: string;
  customerId?: string;
  itemId?: string;
  uomId?: string;
  siteId?: string;
  routingTemplateId: string;
  scheduledStart?: string;
  scheduledEnd?: string;
  priority?: string;
  quantity?: number;
}) {
  return post<ProductionJob>("/api/v1/jobs", body);
}

export function previewPlan(body: {
  routingTemplateId: string;
  quantity: number;
  deliveryDate: string;
}): Promise<PlanPreview> {
  // Frontend-only preview: returns mock data until the backend endpoint is wired
  return Promise.resolve(buildMockPlan(body.deliveryDate));
}

function buildMockPlan(deliveryDate: string): PlanPreview {
  const end = new Date(deliveryDate);
  const stages: StagePlanEntry[] = [
    { seqNo: 1, stageName: "Material Prep", workCentre: "Store", estimatedDurationMin: 240, plannedStart: "", plannedEnd: "" },
    { seqNo: 2, stageName: "Machining", workCentre: "CNC Bay", estimatedDurationMin: 480, plannedStart: "", plannedEnd: "" },
    { seqNo: 3, stageName: "Assembly", workCentre: "Assembly", estimatedDurationMin: 360, plannedStart: "", plannedEnd: "" },
    { seqNo: 4, stageName: "Finishing", workCentre: "Finishing", estimatedDurationMin: 240, plannedStart: "", plannedEnd: "" },
  ];
  let cursor = new Date(end);
  for (let i = stages.length - 1; i >= 0; i--) {
    const stageEnd = new Date(cursor);
    cursor.setDate(cursor.getDate() - Math.ceil(stages[i].estimatedDurationMin / 480));
    stages[i].plannedEnd = stageEnd.toISOString().split("T")[0];
    stages[i].plannedStart = cursor.toISOString().split("T")[0];
  }
  const today = new Date();
  const startDate = new Date(cursor);
  const daysToStart = Math.round((startDate.getTime() - today.getTime()) / 86400000);
  const risk = daysToStart < -1 ? "delayed" : daysToStart < 2 ? "at_risk" : "on_track";
  const riskReason = risk === "delayed" ? "Production cannot start in time to meet the delivery date"
    : risk === "at_risk" ? "Very little buffer before production must start"
    : undefined;
  return {
    estimatedStartDate: startDate.toISOString().split("T")[0],
    expectedCompletionDate: end.toISOString().split("T")[0],
    deliveryRisk: risk,
    deliveryRiskReason: riskReason,
    stages,
  };
}

export function listCustomers(): Promise<CustomerOption[]> {
  return api<{ content?: CustomerOption[] }>("/api/v1/customers?size=200")
    .then((page) => page.content ?? [])
    .catch(() => []);
}

export function listItems(): Promise<ItemOption[]> {
  return api<{ content?: ItemOption[] }>("/api/v1/items?size=200&itemType=finished_goods")
    .then((page) => page.content ?? [])
    .catch(() => []);
}

export function listSites(): Promise<{ id: string; name: string }[]> {
  return api<{ id: string; name: string }[]>("/api/v1/sites")
    .catch(() => []);
}

export function savePlan(jobId: string, stages: Array<{ stageId: string; plannedStart: string; plannedEnd: string }>) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/plan`, { stages });
}

export type BackendPlanPreviewStage = {
  stageId: string;
  stageName: string;
  seqNo: number;
  estimatedDurationMin: number;
  plannedStart: string;
  plannedEnd: string;
};

export function previewPlanForJob(jobId: string, deliveryDate: string): Promise<BackendPlanPreviewStage[]> {
  return api<{ stages: BackendPlanPreviewStage[] }>(
    `/api/v1/jobs/${jobId}/plan-preview?deliveryDate=${deliveryDate}`
  ).then((r) => r.stages ?? []);
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

export function completeJob(jobId: string) {
  return post<ProductionJob>(`/api/v1/jobs/${jobId}/complete`, {});
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
  return post<CostEntry>(`/api/v1/jobs/${jobId}/costing`, { entryType, description, amount });
}

export type JobLotReservation = {
  jobId: string;
  lotId: string;
  itemId: string;
  reservedQty: number;
};

export async function usableLots(itemId: string, jobId: string) {
  const base = `/api/v1/inventory/lots?itemId=${encodeURIComponent(itemId)}&page=0&size=200`;
  const byStatus = `${base}&status=`;
  const [all, available, reserved, owned] = await Promise.all([
    api<Page<LotOption>>(base).catch(() => ({ content: [] as LotOption[] })),
    api<Page<LotOption>>(`${byStatus}available`).catch(() => ({ content: [] as LotOption[] })),
    api<Page<LotOption>>(`${byStatus}reserved`).catch(() => ({ content: [] as LotOption[] })),
    api<JobLotReservation[]>(`/api/v1/inventory/reservations/lots?jobId=${encodeURIComponent(jobId)}`),
  ]);
  const ownedIds = new Set((Array.isArray(owned) ? owned : [])
    .filter((row) => row.itemId === itemId)
    .map((row) => row.lotId));
  const seen = new Set<string>();
  // Merge all fetched lots; prefer the richer record (all-lots query may have more detail)
  const content = [...(all.content ?? []), ...(available.content ?? []), ...(reserved.content ?? [])].filter((lot) => {
    if (seen.has(lot.id)) return false;
    const status = (lot.status || "").toLowerCase();
    // Exclude lots that are explicitly blocked
    if (["quarantine", "rejected", "hold", "qc_hold"].includes(status)) return false;
    // Reserved lots are only usable if this job owns the reservation
    if (status === "reserved" && !ownedIds.has(lot.id)) return false;
    seen.add(lot.id);
    return true;
  });
  return { content };
}
