import { api, safeRandomUUID } from "../auth/api";

export type Page<T> = { content?: T[]; page?: number; totalElements?: number };
export type Contact = { id?: string; name: string; role?: string; email?: string; phone?: string; isPrimary?: boolean };
export type Customer = {
  id: string;
  code: string;
  name: string;
  legalName?: string;
  gstin?: string;
  pan?: string;
  address?: { city?: string; state?: string; line1?: string };
  creditLimit?: number;
  creditDays?: number;
  status?: string;
  contacts?: Contact[];
};
export type RfqLine = {
  id?: string;
  seq?: number;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  qty: number;
  uomId: string;
  uomSymbol?: string;
  customerSpecRef?: string;
  drawingNumber?: string;
  drawingRevision?: string;
  material?: string;
  description?: string;
  targetPrice?: number;
  notes?: string;
  requiredBy?: string;
};
export type Rfq = {
  id: string;
  rfqNumber: string;
  customerId: string;
  customerName: string;
  status: string;
  requiredDate?: string;
  receivedDate?: string;
  customerRef?: string;
  notes?: string;
  deliveryTerms?: string;
  paymentTerms?: number;
  receivedVia?: string;
  ownerName?: string;
  technicalRequirements?: Record<string, unknown>;
  lines: RfqLine[];
  createdAt?: string;
};
export type QuoteLine = {
  id?: string;
  itemId: string;
  itemCode?: string;
  itemName?: string;
  qty: number;
  uomId: string;
  uomSymbol?: string;
  unitPrice: number;
  lineTotal?: number;
  deliveryWeeks?: number;
  gstRate?: number;
  discountPct?: number;
  drawingNumber?: string;
  drawingRevision?: string;
  description?: string;
};
export type RevisionLink = { id: string; revision: number; status: string };
export type Negotiation = { id: string; round: number; partyType: string; note?: string; createdAt: string };
export type Quotation = {
  id: string;
  quotationNumber: string;
  rfqId?: string;
  rfqNumber?: string;
  customerId: string;
  customerName: string;
  revision: number;
  status: string;
  validUntil?: string;
  lines: QuoteLine[];
  negotiationHistory?: Negotiation[];
  createdAt?: string;
  paymentTerms?: number;
  deliveryTerms?: string;
  notes?: string;
  discountAmount?: number;
  decisionChannel?: string;
  decisionNote?: string;
  decidedBy?: string;
  decidedAt?: string;
  cpoId?: string;
  cpoNumber?: string;
  revisions?: RevisionLink[];
};
export type CpoLine = {
  id: string;
  itemId?: string;
  itemCode?: string;
  itemName?: string;
  qty: number;
  uomSymbol?: string;
  unitPrice: number;
  gstRate?: number;
  discountPct?: number;
  lineTotal: number;
  description?: string;
  drawingNumber?: string;
  drawingRevision?: string;
  availability?: string;
  shortageQty?: number;
};
export type Cpo = {
  id: string;
  cpoNumber: string;
  customerId: string;
  customerName: string;
  quotationId?: string;
  quotationNumber?: string;
  quotationRevision?: number;
  rfqId?: string;
  rfqNumber?: string;
  customerPoRef?: string;
  status: string;
  orderDate?: string;
  requiredByDate?: string;
  paymentTerms?: number;
  notes?: string;
  discountAmount?: number;
  decisionChannel?: string;
  decisionNote?: string;
  decidedBy?: string;
  decidedAt?: string;
  lines: CpoLine[];
  createdAt?: string;
};
export type ItemOption = { id: string; code: string; name: string; uomId: string; uomSymbol?: string; gstRate?: number };
export type UomOption = { id: string; symbol: string; name: string };

export function createUom(body: { name: string; symbol: string; uomType?: string; isBase?: boolean }) {
  return api<UomOption>("/api/v1/uoms", {
    method: "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  });
}

export function createItem(body: { code: string; name: string; itemType: string; uomId: string }) {
  return api<ItemOption>("/api/v1/items", {
    method: "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  });
}
export type FieldChange = { field: string; previousValue: string; newValue: string };
export type AuditEntry = { id: string; actorId?: string; actorName?: string; action: string; createdAt: string; changes?: FieldChange[] };

function rows<T>(page: Page<T> | T[] | undefined): T[] {
  if (Array.isArray(page)) return page;
  return page?.content ?? [];
}

export async function allPages<T>(path: string): Promise<T[]> {
  const collected: T[] = [];
  let page = 0;
  let total = 0;
  do {
    const joiner = path.includes("?") ? "&" : "?";
    const result = await api<Page<T>>(`${path}${joiner}size=100&page=${page}`);
    const content = rows(result);
    total = result?.totalElements ?? content.length;
    collected.push(...content);
    page += 1;
    if (content.length === 0) break;
  } while (collected.length < total && page < 20);
  return collected;
}

function key() {
  return safeRandomUUID();
}

export const salesApi = {
  customers: (q = "") => api<Page<Customer>>(`/api/v1/customers?size=100${q ? `&q=${encodeURIComponent(q)}` : ""}`).then(rows),
  customer: (id: string) => api<Customer>(`/api/v1/customers/${id}`),
  saveCustomer: (body: unknown, id?: string) => api<Customer>(id ? `/api/v1/customers/${id}` : "/api/v1/customers", {
    method: id ? "PATCH" : "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  }),
  deactivateCustomer: (id: string) => api<void>(`/api/v1/customers/${id}`, { method: "DELETE", headers: { "Idempotency-Key": key() } }),
  items: () => api<Page<ItemOption>>("/api/v1/items?size=100").then(rows),
  uoms: () => api<Page<UomOption> | UomOption[]>("/api/v1/uoms?size=100").then(rows),
  rfqs: (q = "", customerId = "") => api<Page<Rfq>>(`/api/v1/rfqs?size=100${q ? `&q=${encodeURIComponent(q)}` : ""}${customerId ? `&customerId=${customerId}` : ""}`).then(rows),
  rfq: (id: string) => api<Rfq>(`/api/v1/rfqs/${id}`),
  saveRfq: (body: unknown, id?: string) => api<Rfq>(id ? `/api/v1/rfqs/${id}` : "/api/v1/rfqs", {
    method: id ? "PUT" : "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  }),
  rfqStatus: (id: string, status: string) => api<Rfq>(`/api/v1/rfqs/${id}/status`, {
    method: "PATCH",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify({ status }),
  }),
  quotations: (q = "", customerId = "") => api<Page<Quotation>>(`/api/v1/quotations?size=100${q ? `&q=${encodeURIComponent(q)}` : ""}${customerId ? `&customerId=${customerId}` : ""}`).then(rows),
  quotation: (id: string) => api<Quotation>(`/api/v1/quotations/${id}`),
  saveQuotation: (body: unknown, id?: string) => api<Quotation>(id ? `/api/v1/quotations/${id}` : "/api/v1/quotations", {
    method: id ? "PUT" : "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  }),
  quotationStatus: (id: string, status: string, note?: string) => api<Quotation>(`/api/v1/quotations/${id}/status`, {
    method: "PATCH",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify({ status, note }),
  }),
  revise: (id: string, body: unknown) => api<Quotation>(`/api/v1/quotations/${id}/revise`, {
    method: "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  }),
  accept: (id: string, body: { customerPoRef?: string; confirmationNote?: string }) => api<Cpo>(`/api/v1/quotations/${id}/accept`, {
    method: "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  }),
  async quotationPdf(id: string) {
    const token = (await import("../auth/session")).currentToken();
    const base = import.meta.env.VITE_CUSTOMER_API_URL ?? "http://127.0.0.1:8080";
    const response = await fetch(`${base}/api/v1/quotations/${id}/document`, {
      headers: token ? { Authorization: `Bearer ${token}` } : {},
    });
    if (!response.ok) throw new Error("Quotation PDF could not be downloaded");
    const blob = await response.blob();
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    const named = /filename="([^"]+)"/.exec(response.headers.get("Content-Disposition") || "");
    anchor.href = url;
    anchor.download = named?.[1] || `quotation-${id}.pdf`;
    anchor.click();
    window.open(url, "_blank", "noopener");
    URL.revokeObjectURL(url);
  },
  cpos: (q = "", customerId = "") => api<Page<Cpo>>(`/api/v1/cpos?size=100${q ? `&q=${encodeURIComponent(q)}` : ""}${customerId ? `&customerId=${customerId}` : ""}`).then(rows),
  cpo: (id: string) => api<Cpo>(`/api/v1/cpos/${id}`),
  updateCpo: (id: string, body: unknown) => api<Cpo>(`/api/v1/cpos/${id}`, {
    method: "PUT",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify(body),
  }),
  recordAvailability: (id: string, lines: Array<{ lineId: string; availability: string; shortageQty?: number }>) => api<Cpo>(`/api/v1/cpos/${id}/availability`, {
    method: "POST",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify({ lines }),
  }),
  cpoStatus: (id: string, status: string) => api<Cpo>(`/api/v1/cpos/${id}/status`, {
    method: "PATCH",
    headers: { "Idempotency-Key": key() },
    body: JSON.stringify({ status }),
  }),
  history: (entityType: string, entityId: string) => api<AuditEntry[]>(`/api/v1/sales/history?entityType=${entityType}&entityId=${entityId}`),
};

export function inr(value?: number) {
  return new Intl.NumberFormat("en-IN", { style: "currency", currency: "INR", maximumFractionDigits: 2 }).format(value ?? 0);
}

export function when(value?: string) {
  if (!value) return "—";
  return new Date(value).toLocaleDateString("en-IN", { day: "numeric", month: "short", year: "numeric" });
}
