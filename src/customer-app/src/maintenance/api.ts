import { api, safeRandomUUID } from "../auth/api";

export type Asset = {
  id: string;
  assetCode: string;
  name: string;
  category?: string;
  siteId: string;
  siteName?: string;
  workCentre?: string;
  manufacturer?: string;
  model?: string;
  serialNumber?: string;
  criticality: string;
  meterType: string;
  currentReading?: number | null;
  lastServiceReading?: number | null;
  lastServiceDate?: string | null;
  nextServiceDate?: string | null;
  pmFrequencyMetric?: string | null;
  pmFrequencyHours?: number | null;
  pmFrequencyDays?: number | null;
  responsibleUserId?: string | null;
  responsiblePerson?: string | null;
  installDate?: string | null;
  status: string;
  archiveReason?: string | null;
  archivedAt?: string | null;
  openEvents: number;
};

export type MaintenanceEvent = {
  id: string;
  eventNumber: string;
  assetId: string;
  assetCode: string;
  assetName: string;
  eventType: string;
  status: string;
  severity?: string | null;
  symptom: string;
  technicianUserId?: string | null;
  technicianName: string;
  plannedDate?: string | null;
  expectedDurationHours?: number | null;
  downtimeStart?: string | null;
  downtimeEnd?: string | null;
  completionDate?: string | null;
  downtimeHours?: number | null;
  affectedJobs: { id: string; jobNumber: string }[];
  rootCause?: string | null;
  correctiveAction?: string | null;
  workDone?: string | null;
  resolutionNotes?: string | null;
  partsUsed?: string | null;
  notes?: string | null;
  createdAt?: string;
};

export type ActivityEntry = { action: string; actor?: string | null; at: string };

export type MeterReading = {
  id: string;
  reading: number;
  previousReading?: number | null;
  readingDate: string;
  readingType: string;
  recordedBy?: string | null;
  notes?: string | null;
};

export type ConfigOption = { id: string; code: string; label: string };

export type Lookups = {
  sites: { id: string; name: string; code: string }[];
  technicians: { id: string; name: string }[];
  jobs: { id: string; jobNumber: string; status: string }[];
};

export type AssetInput = Partial<Omit<Asset, "id" | "openEvents" | "siteName" | "archiveReason" | "archivedAt">>;

export type EventInput = {
  assetId: string;
  eventType: string;
  severity?: string;
  symptom: string;
  technicianUserId?: string;
  technicianName?: string;
  plannedDate?: string;
  expectedDurationHours?: number;
  downtimeStart?: string;
  downtimeEnd?: string;
  completionDate?: string;
  affectedJobIds?: string[];
  correctiveAction?: string;
  notes?: string;
  rootCause?: string;
  workDone?: string;
  resolutionNotes?: string;
  partsUsed?: string;
};

function send<T>(method: string, path: string, body?: unknown) {
  return api<T>(path, {
    method,
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

const base = "/api/v1/maintenance";

export const listAssets = (includeArchived: boolean) => api<Asset[]>(`${base}/assets?includeArchived=${includeArchived}`);
export const getAsset = (id: string) => api<Asset>(`${base}/assets/${id}`);
export const createAsset = (input: AssetInput) => send<Asset>("POST", `${base}/assets`, input);
export const updateAsset = (id: string, input: AssetInput) => send<Asset>("PUT", `${base}/assets/${id}`, input);
export const archiveAsset = (id: string, reason: string) => send<Asset>("POST", `${base}/assets/${id}/archive`, { reason });
export const listReadings = (id: string) => api<MeterReading[]>(`${base}/assets/${id}/readings`);
export const logReading = (id: string, body: { reading: number; readingDate?: string; readingType: string; notes?: string }) =>
  send<Asset>("POST", `${base}/assets/${id}/readings`, body);
export const markRepaired = (id: string, body: { completionDate: string; rootCause: string; workDone: string; resolutionNotes?: string }) =>
  send<MaintenanceEvent>("POST", `${base}/assets/${id}/repair`, body);
export const listEvents = (assetId?: string) => api<MaintenanceEvent[]>(`${base}/events${assetId ? `?assetId=${assetId}` : ""}`);
export const getEvent = (id: string) => api<MaintenanceEvent>(`${base}/events/${id}`);
export const createEvent = (input: EventInput) => send<MaintenanceEvent>("POST", `${base}/events`, input);
export const startEvent = (id: string) => send<MaintenanceEvent>("POST", `${base}/events/${id}/start`);
export const closeEvent = (id: string, body: {
  completionDate?: string; rootCause?: string; workDone?: string; resolutionNotes?: string;
  correctiveAction?: string; partsUsed?: string;
}) => send<MaintenanceEvent>("POST", `${base}/events/${id}/close`, body);
export const assetActivity = (id: string) => api<ActivityEntry[]>(`${base}/assets/${id}/activity`);
export const eventActivity = (id: string) => api<ActivityEntry[]>(`${base}/events/${id}/activity`);
export const createConfigOption = (category: string, body: { code: string; label: string; sortOrder: number }) =>
  send<ConfigOption>("POST", `/api/v1/config/options/${category}`, body);
export const lookups = () => api<Lookups>(`${base}/lookups`);
export const configOptions = (category: string) => api<ConfigOption[]>(`/api/v1/config/options/${category}`);

// Only tenant-configurable categories may be extended inline; system-controlled ones are never offered.
export const INLINE_ADD_CATEGORIES = ["ASSET_CATEGORY", "WORK_CENTRE", "ASSET_CRITICALITY", "MAINTENANCE_SEVERITY"];
export const CONFIG_ADMIN_ROLES = ["OWNER"];
export const MANAGE_ROLES = ["OWNER", "OPS_MANAGER", "PRODUCTION_SUPERVISOR"];

export function displayDate(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}

export function displayDateTime(value?: string | null) {
  if (!value) return "—";
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return value;
  return date.toLocaleString("en-IN", { day: "numeric", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit" });
}

export type DueStatus = "ok" | "upcoming" | "due" | "overdue" | "in_maintenance" | "unknown";

export type DueInfo = {
  status: DueStatus;
  remainingHours: number | null;
  remainingDays: number | null;
  nextDueReading: number | null;
  nextDueDate: string | null;
  summary: string;
};

// Calendar and meter thresholds are classified separately; the more severe one drives the status.
export function computeDue(asset: Asset, now = new Date()): DueInfo {
  if (asset.status === "in_maintenance" || asset.status === "breakdown") {
    return { status: "in_maintenance", remainingHours: null, remainingDays: null, nextDueReading: null, nextDueDate: null,
      summary: asset.status === "breakdown" ? "Asset is under breakdown maintenance." : "Asset is currently under maintenance." };
  }
  const unit = asset.meterType && asset.meterType !== "none" ? asset.meterType : "metric";
  const frequency = asset.pmFrequencyHours ?? null;
  const current = asset.currentReading ?? null;
  let nextDueReading: number | null = null;
  let remainingHours: number | null = null;
  if (frequency && asset.lastServiceReading != null) {
    nextDueReading = asset.lastServiceReading + frequency;
    if (current != null) remainingHours = nextDueReading - current;
  }
  let nextDueDate: string | null = null;
  let remainingDays: number | null = null;
  if (asset.pmFrequencyDays && asset.lastServiceDate) {
    const due = new Date(asset.lastServiceDate);
    due.setDate(due.getDate() + asset.pmFrequencyDays);
    nextDueDate = due.toISOString().slice(0, 10);
    remainingDays = Math.round((due.getTime() - now.getTime()) / 86400000);
  } else if (asset.nextServiceDate) {
    nextDueDate = asset.nextServiceDate;
    remainingDays = Math.round((new Date(asset.nextServiceDate).getTime() - now.getTime()) / 86400000);
  }
  const rank: Record<DueStatus, number> = { overdue: 3, due: 2, upcoming: 1, ok: 0, unknown: -1, in_maintenance: -1 };
  const classify = (remaining: number | null, threshold: number): DueStatus => {
    if (remaining == null) return "unknown";
    if (remaining < 0) return "overdue";
    if (remaining === 0) return "due";
    return remaining <= threshold ? "upcoming" : "ok";
  };
  const hoursStatus = classify(remainingHours, 50);
  const calendarStatus = classify(remainingDays, 7);
  const byHours = rank[hoursStatus] >= rank[calendarStatus];
  const status = byHours ? hoursStatus : calendarStatus;
  let summary = "No maintenance schedule configured for this asset.";
  if (status === "overdue") {
    summary = byHours ? `Overdue by ${Math.abs(remainingHours!)} ${unit}.` : `Overdue by ${Math.abs(remainingDays!)} days (was due ${nextDueDate}).`;
  } else if (status === "due") {
    summary = byHours ? `Maintenance due now (${unit} threshold reached).` : "Maintenance due today.";
  } else if (status === "upcoming") {
    summary = byHours ? `Due in ${remainingHours} ${unit}.` : `Due in ${remainingDays} days (${nextDueDate}).`;
  } else if (status === "ok") {
    summary = "On schedule.";
  }
  return { status, remainingHours, remainingDays, nextDueReading, nextDueDate, summary };
}

export function nextServiceLabel(asset: Asset) {
  if (asset.status === "retired") return "—";
  const due = computeDue(asset);
  if (due.status === "in_maintenance") return "In maintenance";
  const parts: string[] = [];
  if (due.remainingHours != null) parts.push(`${due.remainingHours} ${asset.meterType} left`);
  if (due.remainingDays != null) parts.push(`${due.remainingDays} days left`);
  return parts.length ? parts.join(" · ") : "Not scheduled";
}
