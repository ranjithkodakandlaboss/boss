import { api, safeRandomUUID } from "../auth/api";
import type { ProductionJob } from "../production/api";

export type Page<T> = { content?: T[]; totalElements?: number; page?: number };

export type Store = { id: string; siteId: string; name: string; code?: string; siteCode?: string; storeType?: string };
export type Site = { id: string; name: string; code: string; siteType?: string };
export type Company = { stores?: Store[]; sites?: Site[] };

export type Position = {
  itemId: string;
  itemCode: string;
  itemName: string;
  storeId: string;
  storeName: string;
  totalQty: number;
  reservedQty: number;
  availableQty: number;
  inTransitQty?: number;
  reorderPoint: number | null;
  uomSymbol: string;
};

export type Lot = {
  id: string;
  itemId: string;
  itemCode: string;
  itemName: string;
  storeId: string;
  storeName: string;
  siteId?: string;
  batchRef?: string | null;
  lotNumber?: string | null;
  grnLineId?: string | null;
  qty: number;
  reservedQty: number;
  availableQty: number;
  uomSymbol: string;
  status: string;
  expiryDate?: string | null;
  createdAt?: string;
};

export type Movement = {
  id: string;
  lotId: string;
  movementType: string;
  qty: number;
  referenceType?: string | null;
  referenceId?: string | null;
  note?: string | null;
  createdAt?: string;
};

export type Item = {
  id: string;
  code: string;
  name: string;
  description?: string | null;
  itemType?: string;
  categoryId?: string | null;
  categoryLabel?: string | null;
  uomId?: string;
  uomSymbol?: string;
  standardCost?: number | null;
  reorderPoint?: number | null;
  status?: string;
  productKind?: "MANUFACTURED" | "TRADED" | null;
  specifications?: ItemSpecification[];
};

export type ItemSpecification = {
  key: string;
  label: string;
  uomId: string;
  uomSymbol?: string;
  minValue: number | null;
  maxValue: number | null;
  defaultValue: number | null;
};

export type Uom = { id: string; name: string; symbol: string };
export type Category = { id: string; code?: string; label?: string; name?: string };

export function categoryName(category: Category) {
  return category.label || category.name || category.code || "";
}

export type JobReservation = {
  jobId: string;
  jobNumber: string;
  siteId: string;
  itemId: string;
  itemCode: string;
  itemName: string;
  reservedQty: number;
};

export function quantity(value: number, uom = "") {
  const formatted = new Intl.NumberFormat(undefined, { maximumFractionDigits: 3 }).format(Number(value) || 0);
  return uom ? `${formatted} ${uom}` : formatted;
}

export async function allPages<T>(path: string): Promise<T[]> {
  const rows: T[] = [];
  const size = 200;
  let page = 0;
  let total = Number.POSITIVE_INFINITY;
  while (rows.length < total) {
    const joiner = path.includes("?") ? "&" : "?";
    const result = await api<Page<T>>(`${path}${joiner}page=${page}&size=${size}`);
    const content = result.content ?? [];
    rows.push(...content);
    total = result.totalElements ?? content.length;
    page += 1;
    if (content.length === 0) break;
  }
  return rows;
}

export function loadStores() {
  return api<Company>("/api/v1/company").then((company) => company.stores ?? []);
}

export function loadPositions(query = "") {
  return allPages<Position>(`/api/v1/inventory/positions${query}`);
}

export function loadLots(query = "") {
  const path = query ? `/api/v1/inventory/lots?${query}` : "/api/v1/inventory/lots";
  return allPages<Lot>(path);
}

export function loadJobs() {
  return allPages<ProductionJob>("/api/v1/jobs");
}

export function loadReservations() {
  return api<JobReservation[]>("/api/v1/inventory/reservations");
}

export function mutate<T>(path: string, method: "POST" | "PATCH", body: unknown) {
  return api<T>(path, {
    method,
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: JSON.stringify(body),
  });
}
