export type Page<T> = {
  content: T[];
  page: number;
  size: number;
  totalElements: number;
};

export type PrLine = {
  id: string;
  seq: number;
  lineNo: number;
  itemId: string;
  itemCode: string;
  itemName: string;
  qty: number;
  quantity: number;
  orderedQty: number;
  uomId: string;
  uomSymbol: string;
  estUnitPrice?: number;
  requiredDate?: string | null;
  note?: string | null;
};

export type PurchaseRequisition = {
  id: string;
  prNumber: string;
  siteId?: string;
  originType: string;
  status: string;
  priority?: string;
  requiredDate?: string | null;
  notes?: string | null;
  lines: PrLine[];
  totalQty: number;
  totalOrderedQty: number;
  createdAt: string;
  approvedAt?: string | null;
};

export type SupplierQuotationLine = {
  id: string;
  prLineId: string;
  itemId: string;
  unitPrice: number;
  leadTimeDays?: number | null;
  isSelected: boolean;
};

export type SupplierQuotation = {
  id: string;
  prId: string;
  supplierId: string;
  supplierName: string;
  status: string;
  validUntil?: string | null;
  lines: SupplierQuotationLine[];
};

export type ComparisonOffer = {
  supplierQuotationId: string;
  supplierId: string;
  supplierName: string;
  status: string;
  unitPrice: number;
  leadTimeDays?: number | null;
  validUntil?: string | null;
  isSelected: boolean;
};

export type ComparisonLine = {
  prLineId: string;
  itemId: string;
  itemCode: string;
  itemName: string;
  quantity: number;
  uomId: string;
  uomSymbol: string;
  offers: ComparisonOffer[];
};

export type SupplierComparison = {
  prId: string;
  lines: ComparisonLine[];
};

export type SpoLine = {
  id: string;
  itemId: string;
  itemCode: string;
  itemName: string;
  orderedQty: number;
  receivedQty: number;
  unitPrice: number;
  lineTotal: number;
  expectedDeliveryDate?: string | null;
};

export type SupplierPurchaseOrder = {
  id: string;
  spoNumber: string;
  prId: string;
  supplierQuotationId: string;
  supplierId: string;
  supplierName: string;
  status: string;
  lines: SpoLine[];
  totalAmount: number;
  createdAt: string;
};

export type Item = {
  id: string;
  code: string;
  name: string;
  uomId: string;
  uomSymbol: string;
  status: string;
};

export type Supplier = {
  id: string;
  code: string;
  name: string;
  status: string;
};

export type Uom = {
  id: string;
  name: string;
  symbol: string;
};

type CustomerAuth = {
  getIdToken: () => Promise<string>;
};

declare global {
  interface Window {
    bossCustomerAuth?: CustomerAuth;
  }
}

const apiBaseUrl = (import.meta.env.VITE_BOSS_API_BASE_URL ?? "").replace(/\/$/, "");

export class ProcurementApiError extends Error {
  constructor(
    message: string,
    readonly status?: number,
  ) {
    super(message);
    this.name = "ProcurementApiError";
  }
}

function idempotencyKey() {
  return crypto.randomUUID();
}

export async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const auth = window.bossCustomerAuth;
  if (!auth) {
    throw new ProcurementApiError("Sign in to the Customer App to access procurement records.");
  }

  const token = await auth.getIdToken();
  if (!token) throw new ProcurementApiError("Your sign-in session has expired. Sign in again.");

  const method = (init.method ?? "GET").toUpperCase();
  const headers = new Headers(init.headers);
  headers.set("Authorization", `Bearer ${token}`);
  headers.set("Accept", "application/json");
  if (init.body) headers.set("Content-Type", "application/json");
  if (method !== "GET" && method !== "HEAD") headers.set("Idempotency-Key", idempotencyKey());

  let response: Response;
  try {
    response = await fetch(`${apiBaseUrl}/api/v1${path}`, { ...init, method, headers });
  } catch {
    throw new ProcurementApiError("Could not reach the customer API. Check your connection and try again.");
  }

  if (!response.ok) {
    let message = `The customer API returned ${response.status}.`;
    try {
      const body = await response.json() as { message?: string; detail?: string; title?: string };
      message = body.message ?? body.detail ?? body.title ?? message;
    } catch {
      // Some error responses have no JSON body.
    }
    if (response.status === 401) message = "Your sign-in session is invalid or expired. Sign in again.";
    if (response.status === 403) message = "Your role does not have permission to perform this procurement action.";
    throw new ProcurementApiError(message, response.status);
  }
  if (response.status === 204) return undefined as T;
  return response.json() as Promise<T>;
}

export const procurementApi = {
  listPrs: (params = "") => request<Page<PurchaseRequisition>>(`/prs${params}`),
  getPr: (id: string) => request<PurchaseRequisition>(`/prs/${id}`),
  createPr: (body: object) => request<PurchaseRequisition>("/prs", { method: "POST", body: JSON.stringify(body) }),
  submitPr: (id: string) => request<PurchaseRequisition>(`/prs/${id}/submit`, { method: "POST" }),
  approvePr: (id: string) => request<PurchaseRequisition>(`/prs/${id}/approve`, { method: "POST" }),
  listQuotations: (prId: string) => request<Page<SupplierQuotation>>(`/prs/${prId}/supplier-quotations`),
  requestQuotation: (prId: string, supplierId: string) => request<SupplierQuotation>(`/prs/${prId}/supplier-quotations`, {
    method: "POST",
    body: JSON.stringify({ supplierId }),
  }),
  getQuotation: (id: string) => request<SupplierQuotation>(`/supplier-quotations/${id}`),
  receiveQuotation: (id: string, body: object) => request<SupplierQuotation>(`/supplier-quotations/${id}/receive`, {
    method: "POST",
    body: JSON.stringify(body),
  }),
  reviewQuotation: (id: string) => request<SupplierQuotation>(`/supplier-quotations/${id}/review`, { method: "POST" }),
  selectQuotation: (id: string) => request<SupplierQuotation>(`/supplier-quotations/${id}/select`, { method: "POST" }),
  rejectQuotation: (id: string) => request<SupplierQuotation>(`/supplier-quotations/${id}/reject`, { method: "POST" }),
  compareQuotations: (prId: string) => request<SupplierComparison>(`/prs/${prId}/supplier-quotations/comparison`),
  listSpos: () => request<Page<SupplierPurchaseOrder>>("/spos"),
  getSpo: (id: string) => request<SupplierPurchaseOrder>(`/spos/${id}`),
  createSpo: (body: object) => request<SupplierPurchaseOrder>("/spos", { method: "POST", body: JSON.stringify(body) }),
  issueSpo: (id: string) => request<SupplierPurchaseOrder>(`/spos/${id}/issue`, { method: "POST" }),
  acknowledgeSpo: (id: string) => request<SupplierPurchaseOrder>(`/spos/${id}/acknowledge`, { method: "POST" }),
  cancelSpo: (id: string) => request<SupplierPurchaseOrder>(`/spos/${id}/cancel`, { method: "POST" }),
  listItems: () => request<Page<Item>>("/items?status=active&page=0&size=50"),
  listSuppliers: () => request<Page<Supplier>>("/suppliers?status=active&page=0&size=50"),
  listUoms: () => request<Uom[]>("/uoms"),
};
