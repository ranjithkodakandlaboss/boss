import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { Outlet, useLocation } from "react-router-dom";
import { ApiError } from "../../auth/api";
import { useCustomer } from "../../auth/CustomerState";
import {
  allPages,
  salesApi,
  type Cpo,
  type Customer,
  type ItemOption,
  type Quotation,
  type Rfq,
  type UomOption,
} from "../api";
import type { AuditEvent, CustomerPO, CustomerQuotation, CustomerQuotationLine, CustomerRFQ, Party, POLine } from "./types";
import Toast from "./workflow/Toast";

type ToastItem = { id: string; type: "success" | "error" | "warning" | "info"; title: string; body?: string };
type CatalogItem = ItemOption & { gstRate?: number };

type SalesState = {
  ready: boolean;
  customers: Party[];
  rfqs: CustomerRFQ[];
  quotations: CustomerQuotation[];
  quotationLines: CustomerQuotationLine[];
  customerPos: CustomerPO[];
  poLines: POLine[];
  catalog: CatalogItem[];
  uoms: UomOption[];
  currentUser: { id: string; name: string; role: string };
  companyConfig: {
    companyName: string; tradingName: string; legalName: string; registeredAddress: string;
    city: string; state: string; pincode: string; gstin: string; pan: string; phone: string; email: string;
    bankName: string; bankBranch: string; bankAccountNo: string; ifscCode: string;
  };
  auditEvents: AuditEvent[];
  toasts: ToastItem[];
  jobs: any[];
  jobMaterialReqs: any[];
  purchaseReqs: any[];
  supplierInvitations: any[];
  supplierQuotations: any[];
  grns: any[];
  dispatches: any[];
  invoices: any[];
  payments: any[];
  paymentAllocations: any[];
  materials: any[];
  lots: any[];
  storeLocations: any[];
  sites: { id: string; name: string }[];
  supplierPos: any[];
  supplierPoLines: any[];
  documents: any[];
  boms: any[];
  poAmendments: any[];
  showToast: (toast: Omit<ToastItem, "id">) => void;
  removeToast: (id: string) => void;
  reload: () => Promise<void>;
  addCustomer: (customer: Party) => Promise<Party | null>;
  updateCustomer: (id: string, patch: Partial<Party>) => Promise<void | null>;
  deactivateCustomer: (id: string) => Promise<void | null>;
  addRfq: (rfq: CustomerRFQ) => Promise<CustomerRFQ | null>;
  updateRfq: (id: string, patch: Partial<CustomerRFQ>) => Promise<void | null>;
  addQuotation: (quotation: CustomerQuotation, lines: CustomerQuotationLine[]) => Promise<CustomerQuotation | null>;
  createDirectQuotation: (quotation: CustomerQuotation, lines: CustomerQuotationLine[]) => Promise<CustomerQuotation | null>;
  updateQuotation: (id: string, patch: Partial<CustomerQuotation>) => Promise<CustomerQuotation | null>;
  reviseQuotation: (id: string, next: CustomerQuotation, lines: CustomerQuotationLine[]) => Promise<CustomerQuotation | null>;
  convertQuotationToPO: (quotationId: string, po: CustomerPO & { customerPoRef?: string; confirmationNote?: string }) => Promise<CustomerPO | null>;
  updateCustomerPo: (id: string, patch: Partial<CustomerPO>) => Promise<void | null>;
  addCustomerPO: (po: CustomerPO) => Promise<CustomerPO | null>;
  addPoAmendment: (amendment: any) => Promise<void | null>;
  resolvePoAmendment: (...args: any[]) => void;
  assessAndAllocateCustomerPO: (...args: any[]) => { status: string; message: string };
  addDocument: (...args: any[]) => void;
  appendAuditEvent: (...args: any[]) => void;
};

const emptyLists = {
  jobs: [] as any[],
  jobMaterialReqs: [] as any[],
  purchaseReqs: [] as any[],
  supplierInvitations: [] as any[],
  supplierQuotations: [] as any[],
  grns: [] as any[],
  dispatches: [] as any[],
  invoices: [] as any[],
  payments: [] as any[],
  paymentAllocations: [] as any[],
  materials: [] as any[],
  lots: [] as any[],
  storeLocations: [] as any[],
  sites: [] as { id: string; name: string }[],
  supplierPos: [] as any[],
  supplierPoLines: [] as any[],
  documents: [] as any[],
  boms: [] as any[],
  poAmendments: [] as any[],
};

const SalesContext = createContext<SalesState | null>(null);

function failure(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : "The request failed";
}

function days(terms?: string | number | null) {
  if (typeof terms === "number" && Number.isFinite(terms)) return terms;
  const text = String(terms ?? "");
  if (/advance/i.test(text)) return 0;
  const match = /(\d+)/.exec(text);
  return match ? Number(match[1]) : 30;
}

function termsLabel(value?: number | null) {
  if (value == null) return "Net 30";
  if (value === 0) return "Advance";
  return `Net ${value}`;
}

function money(value?: number | string | null) {
  return Number(value ?? 0);
}

function customerCode(legalName: string) {
  const base = legalName.replace(/[^A-Za-z0-9]/g, "").slice(0, 10).toUpperCase() || "CUST";
  return `${base}${Date.now().toString().slice(-4)}`;
}

function toParty(customer: Customer): Party & { code?: string; line1?: string } {
  const contact = customer.contacts?.find((item) => item.isPrimary) ?? customer.contacts?.[0];
  const status = customer.status === "inactive" ? "inactive" : "active";
  return {
    id: customer.id,
    partyType: "Customer",
    legalName: customer.legalName || customer.name,
    tradingName: customer.name,
    panNumber: customer.pan || "",
    gstin: customer.gstin || "",
    state: customer.address?.state || "",
    paymentTerms: termsLabel(customer.creditDays),
    creditLimit: money(customer.creditLimit),
    currency: "INR",
    status,
    contactPerson: contact?.name || "",
    phone: contact?.phone || "",
    email: contact?.email || "",
    city: customer.address?.city || "",
    since: "",
    code: customer.code,
    line1: customer.address?.line1 || "",
  };
}

function partyBody(party: Partial<Party> & { code?: string; line1?: string }, existing?: Party & { code?: string; line1?: string }) {
  const legalName = (party.legalName || existing?.legalName || "").trim();
  const tradingName = (party.tradingName || existing?.tradingName || legalName).trim();
  return {
    code: party.code || existing?.code || customerCode(legalName),
    name: tradingName || legalName,
    legalName,
    gstin: (party.gstin ?? existing?.gstin ?? "").trim().toUpperCase() || null,
    pan: (party.panNumber ?? existing?.panNumber ?? "").trim().toUpperCase() || null,
    address: {
      line1: party.line1 ?? existing?.line1 ?? "",
      city: (party.city ?? existing?.city ?? "").trim(),
      state: (party.state ?? existing?.state ?? "").trim(),
    },
    creditLimit: Number(party.creditLimit ?? existing?.creditLimit ?? 0),
    creditDays: days(party.paymentTerms ?? existing?.paymentTerms),
    contacts: [{
      name: (party.contactPerson ?? existing?.contactPerson ?? "").trim(),
      email: (party.email ?? existing?.email ?? "").trim(),
      phone: (party.phone ?? existing?.phone ?? "").trim(),
      isPrimary: true,
    }],
  };
}

function rfqStatus(rfq: Rfq, quoted: boolean): CustomerRFQ["status"] {
  if (rfq.status === "cancelled") return "cancelled";
  if (rfq.status === "closed") return "converted";
  if (quoted) return "quoted";
  return "open";
}

function quoteStatus(status: string): CustomerQuotation["status"] {
  if (status === "sent" || status === "negotiating") return "submitted";
  if (status === "rejected") return "rejected";
  if (status === "expired") return "expired";
  if (status === "accepted") return "accepted";
  if (status === "superseded") return "superseded";
  return "draft";
}

function poStatus(status: string): CustomerPO["status"] {
  if (status === "open") return "active";
  if (status === "fulfilled") return "complete";
  if (status === "on_hold") return "on_hold";
  if (status === "partial") return "partial";
  if (status === "cancelled") return "cancelled";
  return "active";
}

function lineRequests(lines: Array<Record<string, unknown>>, catalog: CatalogItem[], uoms: UomOption[]) {
  return lines.map((line) => {
    const itemId = String(line.itemId || line.productId || "");
    const item = catalog.find((entry) => entry.id === itemId);
    const symbol = String(line.uom || line.uomSymbol || item?.uomSymbol || "");
    const uom = uoms.find((entry) => entry.symbol === symbol || entry.id === String(line.uomId || "")) || uoms.find((entry) => entry.id === item?.uomId);
    return {
      itemId: item?.id || "",
      qty: Number(line.qty ?? line.quantity ?? line.orderedQty ?? 0),
      uomId: uom?.id || item?.uomId || "",
      drawingNumber: String(line.drawingNumber || ""),
      drawingRevision: String(line.drawingRevision || line.revision || ""),
      material: String(line.material || ""),
      description: String(line.description || line.productName || ""),
      targetPrice: line.targetPrice == null || line.targetPrice === "" ? null : Number(line.targetPrice),
      notes: String(line.notes || ""),
      requiredBy: String(line.requiredBy || line.requiredDate || "") || null,
      unitPrice: Number(line.unitPrice ?? line.rate ?? 0),
      gstRate: line.gstRate == null ? item?.gstRate ?? null : Number(line.gstRate),
      discountPct: line.discountPct == null ? null : Number(line.discountPct),
    };
  });
}

function mapRfq(rfq: Rfq, quoted: boolean): CustomerRFQ {
  const tech = (rfq.technicalRequirements ?? {}) as Record<string, unknown>;
  return {
    id: rfq.id,
    rfqNumber: rfq.rfqNumber,
    customerId: rfq.customerId,
    customerName: rfq.customerName,
    receivedDate: rfq.receivedDate || rfq.createdAt || "",
    requiredBy: rfq.requiredDate || "",
    status: rfqStatus(rfq, quoted),
    lineCount: rfq.lines.length,
    channel: rfq.receivedVia || "Email",
    owner: rfq.ownerName || "",
    paymentTerms: termsLabel(rfq.paymentTerms),
    deliveryTerms: rfq.deliveryTerms || "",
    internalNotes: rfq.notes || "",
    customerEnquiryRef: rfq.customerRef || "",
    technicalRequirements: tech,
    lines: rfq.lines.map((line, index) => ({
      id: line.id || `${rfq.id}-${index + 1}`,
      rfqId: rfq.id,
      lineNumber: line.seq || index + 1,
      productId: line.itemId,
      productName: line.itemName || line.description || "",
      description: line.description || line.itemName || "",
      drawingNumber: line.drawingNumber || "",
      drawingRevision: line.drawingRevision || "",
      material: line.material || "",
      qty: Number(line.qty),
      quantity: Number(line.qty),
      uom: line.uomSymbol || "",
      uomId: line.uomId,
      itemId: line.itemId,
      requiredDate: line.requiredBy || "",
      requiredBy: line.requiredBy || "",
      targetPrice: line.targetPrice == null ? undefined : Number(line.targetPrice),
      notes: line.notes || "",
    })) as CustomerRFQ["lines"],
    documents: [],
    clarifications: [],
    revisions: [],
    apiStatus: rfq.status,
    createdAt: rfq.createdAt,
  } as CustomerRFQ;
}

function mapQuote(quote: Quotation): CustomerQuotation {
  const lines = quote.lines.map((line, index) => ({
    id: line.id || `${quote.id}-${index + 1}`,
    quotationId: quote.id,
    productId: line.itemId,
    productName: line.itemName || line.description || "",
    drawingNumber: line.drawingNumber || line.itemCode || "",
    drawingRevision: line.drawingRevision || "",
    orderedQty: Number(line.qty),
    uom: line.uomSymbol || "",
    uomId: line.uomId,
    unitPrice: money(line.unitPrice),
    value: money(line.lineTotal ?? Number(line.qty) * Number(line.unitPrice)),
    requiredDate: "",
    gstRate: line.gstRate == null ? undefined : Number(line.gstRate),
    discountPct: line.discountPct == null ? undefined : Number(line.discountPct),
    description: line.description || "",
  }));
  const subtotal = lines.reduce((sum, line) => sum + line.value, 0);
  return {
    id: quote.id,
    quotationNumber: quote.quotationNumber,
    rfqId: quote.rfqId,
    rfqNumber: quote.rfqNumber,
    customerId: quote.customerId,
    customerName: quote.customerName,
    quotationDate: quote.createdAt || "",
    validUntil: quote.validUntil || "",
    currency: "INR",
    status: quoteStatus(quote.status),
    totalValue: Math.max(0, subtotal - money(quote.discountAmount)),
    subtotal,
    discountAmount: money(quote.discountAmount),
    revision: quote.revision,
    owner: "",
    deliveryTerms: quote.deliveryTerms || "",
    paymentTerms: termsLabel(quote.paymentTerms),
    notes: quote.notes || "",
    createdAt: quote.createdAt,
    lines,
    apiStatus: quote.status,
    decisionNote: quote.decisionNote,
    decidedAt: quote.decidedAt,
    cpoId: quote.cpoId,
    cpoNumber: quote.cpoNumber,
    negotiationHistory: quote.negotiationHistory ?? [],
    revisions: quote.revisions ?? [],
  } as CustomerQuotation;
}

function linkRevisions(quotes: CustomerQuotation[]) {
  const groups = new Map<string, CustomerQuotation[]>();
  quotes.forEach((quote) => {
    const key = quote.quotationNumber.replace(/-R\d+$/i, "");
    groups.set(key, [...(groups.get(key) ?? []), quote]);
  });
  groups.forEach((group) => {
    const ordered = [...group].sort((a, b) => (a.revision ?? 1) - (b.revision ?? 1));
    ordered.forEach((quote, index) => {
      quote.parentQuotationId = index > 0 ? ordered[index - 1].id : undefined;
    });
  });
  return quotes;
}

function mapPo(po: Cpo): CustomerPO {
  const total = po.lines.reduce((sum, line) => sum + money(line.lineTotal), 0) - money(po.discountAmount);
  return {
    id: po.id,
    poNumber: po.cpoNumber,
    customerId: po.customerId,
    customerName: po.customerName,
    acceptedDate: po.orderDate || po.createdAt || "",
    paymentTerms: termsLabel(po.paymentTerms),
    status: poStatus(po.status),
    totalValue: total,
    currency: "INR",
    lineCount: po.lines.length,
    quotationRef: po.quotationNumber,
    quotationId: po.quotationId,
    rfqId: po.rfqId,
    rfqRef: po.rfqNumber,
    deliveryTerms: po.notes || "",
    originType: "from_quotation",
    commercialLocked: true,
    commercialLockReason: "The accepted quotation price stays on this Customer PO.",
    createdAt: po.createdAt,
    apiStatus: po.status,
    customerPoRef: po.customerPoRef,
    requiredByDate: po.requiredByDate,
    decisionNote: po.decisionNote,
  } as CustomerPO;
}

function mapPoLines(records: Cpo[]): POLine[] {
  return records.flatMap((po) => po.lines.map((line) => ({
    id: line.id,
    poId: po.id,
    drawingNumber: line.drawingNumber || line.itemCode || "",
    revision: line.drawingRevision || "",
    description: line.description || line.itemName || "",
    orderedQty: Number(line.qty),
    uom: line.uomSymbol || "",
    requiredDate: po.requiredByDate || "",
    rate: money(line.unitPrice),
    value: money(line.lineTotal),
    status: "pending" as const,
  })));
}

export function useStore() {
  const value = useContext(SalesContext);
  if (!value) throw new Error("Sales records are not loaded");
  return value;
}

export function SalesProvider({ children }: { children?: ReactNode }) {
  const customer = useCustomer();
  const location = useLocation();
  const [ready, setReady] = useState(false);
  const [customers, setCustomers] = useState<Party[]>([]);
  const [rfqs, setRfqs] = useState<CustomerRFQ[]>([]);
  const [quotations, setQuotations] = useState<CustomerQuotation[]>([]);
  const [customerPos, setCustomerPos] = useState<CustomerPO[]>([]);
  const [poLines, setPoLines] = useState<POLine[]>([]);
  const [catalog, setCatalog] = useState<CatalogItem[]>([]);
  const [uoms, setUoms] = useState<UomOption[]>([]);
  const [auditEvents, setAuditEvents] = useState<AuditEvent[]>([]);
  const [toasts, setToasts] = useState<ToastItem[]>([]);

  const showToast = useCallback((toast: Omit<ToastItem, "id">) => {
    const id = crypto.randomUUID();
    setToasts((current) => [...current, { ...toast, id }]);
    window.setTimeout(() => setToasts((current) => current.filter((item) => item.id !== id)), 4200);
  }, []);
  const removeToast = useCallback((id: string) => setToasts((current) => current.filter((item) => item.id !== id)), []);

  const reload = useCallback(async () => {
    const [customerRows, rfqRows, quoteRows, poRows, itemRows, uomRows] = await Promise.all([
      allPages<Customer>("/api/v1/customers"),
      allPages<Rfq>("/api/v1/rfqs"),
      allPages<Quotation>("/api/v1/quotations"),
      allPages<Cpo>("/api/v1/cpos"),
      allPages<CatalogItem>("/api/v1/items"),
      allPages<UomOption>("/api/v1/uoms"),
    ]);
    const quoted = new Set(quoteRows.map((quote) => quote.rfqId).filter(Boolean));
    setCustomers(customerRows.map(toParty));
    setRfqs(rfqRows.map((rfq) => mapRfq(rfq, quoted.has(rfq.id))));
    setQuotations(linkRevisions(quoteRows.map(mapQuote)));
    setCustomerPos(poRows.map(mapPo));
    setPoLines(mapPoLines(poRows));
    setCatalog(itemRows);
    setUoms(uomRows);
  }, []);

  useEffect(() => {
    let live = true;
    reload().catch((error) => { if (live) showToast({ type: "error", title: "Sales records did not load", body: failure(error) }); })
      .finally(() => { if (live) setReady(true); });
    return () => { live = false; };
  }, [reload, showToast]);

  useEffect(() => {
    const match = location.pathname.match(/^\/sales\/(customers|rfq|quotation|po)\/([^/]+)/);
    if (!match || match[2] === "new") return;
    const entityType = { customers: "CUSTOMER", rfq: "RFQ", quotation: "QUOTATION", po: "CPO" }[match[1]];
    const scope = { customers: "Customer", rfq: "CustomerRFQ", quotation: "CustomerQuotation", po: "CustomerPO" }[match[1]];
    if (!entityType || !scope) return;
    let live = true;
    salesApi.history(entityType, match[2]).then((entries) => {
      if (!live) return;
      setAuditEvents(entries.map((entry) => ({
        id: entry.id,
        actor: entry.actorName || "User",
        actorId: entry.actorId || "",
        scope,
        action: entry.action,
        entityId: match[2],
        entityRef: match[2],
        timestamp: entry.createdAt,
        source: "api" as const,
        changes: (entry.changes ?? []).map((change) => ({
          field: change.field,
          label: change.field,
          previousValue: change.previousValue,
          newValue: change.newValue,
        })),
      })));
    }).catch(() => { if (live) setAuditEvents([]); });
    return () => { live = false; };
  }, [location.pathname, ready]);

  const roles = customer.user?.roles.map((role) => role.role) ?? [];
  const role = roles.includes("OWNER") ? "owner" : roles.some((item) => item.includes("SALES")) ? "sales" : roles.length === 0 ? "owner" : "viewer";
  const currentUser = {
    id: customer.user?.id || "",
    name: customer.user?.displayName || customer.user?.email || "Owner",
    role,
  };
  const blank = "";
  const companyConfig = {
    companyName: customer.tenant?.name || "BOSS",
    tradingName: customer.tenant?.name || "BOSS",
    legalName: customer.tenant?.name || blank,
    registeredAddress: blank,
    city: blank,
    state: blank,
    pincode: blank,
    gstin: blank,
    pan: blank,
    phone: blank,
    email: blank,
    bankName: blank,
    bankBranch: blank,
    bankAccountNo: blank,
    ifscCode: blank,
  };

  const guard = useCallback(async <T,>(work: () => Promise<T>, success?: { title: string; body?: string }) => {
    try {
      const result = await work();
      await reload();
      if (success) showToast({ type: "success", ...success });
      return result;
    } catch (error) {
      showToast({ type: "error", title: "Not saved", body: failure(error) });
      return null;
    }
  }, [reload, showToast]);

  const value = useMemo<SalesState>(() => {
    const quotationLines = quotations.flatMap((quote) => (quote.lines as CustomerQuotationLine[] | undefined) ?? []);
    return {
      ...emptyLists,
      ready,
      customers,
      rfqs,
      quotations,
      quotationLines,
      customerPos,
      poLines,
      catalog,
      uoms,
      currentUser,
      companyConfig,
      auditEvents,
      toasts,
      showToast,
      removeToast,
      reload,
      addCustomer: (party) => guard(async () => {
        const saved = await salesApi.saveCustomer(partyBody(party));
        return toParty(saved);
      }, { title: "Customer added", body: `${party.tradingName || party.legalName} is in the directory.` }),
      updateCustomer: async (id, patch) => {
        const existing = customers.find((item) => item.id === id) as Party & { code?: string; line1?: string } | undefined;
        await guard(() => salesApi.saveCustomer(partyBody(patch, existing), id), { title: "Customer updated" });
      },
      deactivateCustomer: (id) => guard(() => salesApi.deactivateCustomer(id), { title: "Customer deactivated", body: "Historical RFQs, quotations and Customer POs stay readable." }),
      addRfq: (rfq) => guard(async () => {
        const lines = lineRequests((rfq.lines ?? []) as unknown as Array<Record<string, unknown>>, catalog, uoms);
        if (lines.length === 0 || lines.some((line) => !line.itemId || !line.uomId || line.qty <= 0)) {
          throw new Error("Choose an item master record, quantity and unit for every RFQ line.");
        }
        const saved = await salesApi.saveRfq({
          customerId: rfq.customerId,
          requiredDate: rfq.requiredBy || null,
          receivedDate: rfq.receivedDate || null,
          receivedVia: rfq.channel,
          paymentTerms: days(rfq.paymentTerms),
          deliveryTerms: rfq.deliveryTerms || null,
          notes: rfq.internalNotes || null,
          customerRef: rfq.customerEnquiryRef || null,
          technicalRequirements: rfq.technicalRequirements ?? {},
          lines: lines.map((line) => ({
            itemId: line.itemId, qty: line.qty, uomId: line.uomId, drawingNumber: line.drawingNumber,
            drawingRevision: line.drawingRevision, material: line.material, description: line.description,
            targetPrice: line.targetPrice, notes: line.notes, requiredBy: line.requiredBy,
          })),
        });
        return mapRfq(saved, false);
      }, { title: "RFQ created" }),
      updateRfq: async (id, patch) => {
        const current = rfqs.find((item) => item.id === id);
        if (current && (current as CustomerRFQ & { apiStatus?: string }).apiStatus !== "draft" && patch.lines) {
          showToast({ type: "error", title: "RFQ is locked", body: "Only a draft RFQ can be edited. Mark it received before quoting." });
          return;
        }
        const merged = { ...current, ...patch } as CustomerRFQ;
        const lines = lineRequests((merged.lines ?? []) as unknown as Array<Record<string, unknown>>, catalog, uoms);
        if (patch.status && !patch.lines) {
          const next = patch.status === "cancelled" ? "cancelled" : patch.status === "converted" ? "closed" : "sent";
          await guard(() => salesApi.rfqStatus(id, next), { title: "RFQ updated" });
          return;
        }
        await guard(() => salesApi.saveRfq({
          requiredDate: merged.requiredBy || null,
          receivedDate: merged.receivedDate || null,
          receivedVia: merged.channel,
          paymentTerms: days(merged.paymentTerms),
          deliveryTerms: merged.deliveryTerms || null,
          notes: merged.internalNotes || null,
          technicalRequirements: merged.technicalRequirements ?? {},
          lines: lines.map((line) => ({
            itemId: line.itemId, qty: line.qty, uomId: line.uomId, drawingNumber: line.drawingNumber,
            drawingRevision: line.drawingRevision, material: line.material, description: line.description,
            targetPrice: line.targetPrice, notes: line.notes, requiredBy: line.requiredBy,
          })),
        }, id), { title: "RFQ saved" });
      },
      addQuotation: (quotation, lines) => saveQuotation(quotation, lines, catalog, uoms, guard),
      createDirectQuotation: (quotation, lines) => saveQuotation(quotation, lines, catalog, uoms, guard),
      updateQuotation: async (id, patch) => {
        if (patch.status === "submitted") {
          const saved = await guard(() => salesApi.quotationStatus(id, "sent"), { title: "Quotation submitted", body: "Download the PDF and send it to the customer outside BOSS." });
          return saved ? mapQuote(saved as Quotation) : null;
        }
        if (patch.status === "accepted") {
          const saved = await guard(() => salesApi.accept(id, {}), { title: "Quotation accepted", body: "One Customer PO was created from the accepted price." });
          return saved ? mapPo(saved as Cpo) as unknown as CustomerQuotation : null;
        }
        if (patch.status === "rejected" || patch.status === "expired") {
          const saved = await guard(() => salesApi.quotationStatus(id, patch.status === "rejected" ? "rejected" : "expired", patch.notes), { title: patch.status === "rejected" ? "Quotation declined" : "No response recorded" });
          return saved ? mapQuote(saved as Quotation) : null;
        }
        const current = quotations.find((item) => item.id === id);
        const merged = { ...current, ...patch } as CustomerQuotation;
        const source = (patch.lines as CustomerQuotationLine[] | undefined) ?? (current?.lines as CustomerQuotationLine[] | undefined) ?? [];
        const saved = await guard(() => salesApi.saveQuotation(quoteBody(merged, source, catalog, uoms), id), { title: "Quotation saved" });
        return saved ? mapQuote(saved as Quotation) : null;
      },
      reviseQuotation: async (id, next, lines) => {
        const saved = await guard(() => salesApi.revise(id, {
          changeReason: next.notes || "Revision",
          ...quoteBody(next, lines, catalog, uoms),
        }), { title: "Revision drafted" });
        return saved ? mapQuote(saved as Quotation) : null;
      },
      convertQuotationToPO: async (quotationId, po) => {
        const existing = customerPos.find((item) => item.quotationId === quotationId);
        if (existing) return existing;
        const saved = await guard(() => salesApi.accept(quotationId, {
          customerPoRef: po.customerPoRef || undefined,
          confirmationNote: po.confirmationNote || po.directOrderReason || undefined,
        }), { title: "Customer PO created", body: "The accepted price is now locked on the Customer PO." });
        return saved ? mapPo(saved) : null;
      },
      updateCustomerPo: async (id, patch) => {
        if (patch.status === "cancelled" || patch.status === "on_hold" || patch.status === "active") {
          const next = patch.status === "active" ? "open" : patch.status === "on_hold" ? "on_hold" : "cancelled";
          await guard(() => salesApi.cpoStatus(id, next), { title: next === "open" ? "Customer PO released" : next === "on_hold" ? "Customer PO on hold" : "Customer PO cancelled" });
          return;
        }
        const current = customerPos.find((item) => item.id === id);
        await guard(() => salesApi.updateCpo(id, {
          customerPoRef: (current as CustomerPO & { customerPoRef?: string } | undefined)?.customerPoRef ?? null,
          notes: patch.deliveryTerms ?? current?.deliveryTerms ?? null,
          requiredByDate: (current as CustomerPO & { requiredByDate?: string } | undefined)?.requiredByDate ?? null,
          paymentTerms: days(patch.paymentTerms ?? current?.paymentTerms),
        }), { title: "Customer PO updated" });
      },
      addCustomerPO: async () => {
        showToast({ type: "error", title: "Quotation required", body: "A Customer PO is created by accepting a sent quotation. Direct entry is not saved." });
        return null;
      },
      addPoAmendment: async (amendment) => {
        if (amendment.lineChanges?.rate != null || amendment.lineChanges?.quantity != null) {
          showToast({ type: "error", title: "Accepted price is protected", body: "Quantity and rate on an accepted Customer PO are not revised." });
          return;
        }
        const current = customerPos.find((item) => item.id === amendment.poId);
        await guard(() => salesApi.updateCpo(amendment.poId, {
          notes: amendment.changeNote,
          paymentTerms: amendment.headerChanges?.paymentTerms ? days(amendment.headerChanges.paymentTerms) : days(current?.paymentTerms),
          customerPoRef: (current as CustomerPO & { customerPoRef?: string } | undefined)?.customerPoRef ?? null,
          requiredByDate: (current as CustomerPO & { requiredByDate?: string } | undefined)?.requiredByDate ?? null,
        }), { title: "Customer PO note saved", body: "Commercial prices were not changed." });
      },
      resolvePoAmendment: () => showToast({ type: "error", title: "No pending revision", body: "Accepted prices stay on the Customer PO. There is no separate approval queue." }),
      assessAndAllocateCustomerPO: () => {
        showToast({ type: "info", title: "Material check is later", body: "Stock reservation and purchase requisitions are outside Sales & Order." });
        return { status: "not_assessed", message: "Material availability is not part of this Sales & Order release." };
      },
      addDocument: () => showToast({ type: "info", title: "Document file was not stored", body: "Notes typed on the record are saved. File upload is not connected." }),
      appendAuditEvent: () => undefined,
    };
  }, [auditEvents, catalog, companyConfig, currentUser, customerPos, customers, guard, poLines, quotations, ready, reload, removeToast, rfqs, showToast, toasts, uoms]);

  return (
    <SalesContext.Provider value={value}>
      {ready ? children ?? <Outlet /> : <p role="status" className="p-8 text-sm text-[var(--text-2)]">Loading sales records…</p>}
      <Toast />
    </SalesContext.Provider>
  );
}

export const CUSTOMERS: Party[] = [];

function quoteBody(quotation: CustomerQuotation, lines: CustomerQuotationLine[], catalog: CatalogItem[], uoms: UomOption[]) {
  const mapped = lineRequests(lines as unknown as Array<Record<string, unknown>>, catalog, uoms);
  if (mapped.length === 0 || mapped.some((line) => !line.itemId || !line.uomId || line.qty <= 0)) {
    throw new Error("Choose an item master record for every quotation line and enter a price.");
  }
  return {
    customerId: quotation.customerId,
    rfqId: quotation.rfqId || null,
    validUntil: quotation.validUntil || null,
    paymentTerms: days(quotation.paymentTerms),
    deliveryTerms: quotation.deliveryTerms || null,
    notes: quotation.notes || null,
    discountAmount: quotation.discountAmount || 0,
    lines: mapped.map((line) => ({
      itemId: line.itemId,
      qty: line.qty,
      uomId: line.uomId,
      unitPrice: line.unitPrice,
      gstRate: line.gstRate,
      discountPct: line.discountPct,
      drawingNumber: line.drawingNumber,
      drawingRevision: line.drawingRevision,
      description: line.description,
    })),
  };
}

function saveQuotation(
  quotation: CustomerQuotation,
  lines: CustomerQuotationLine[],
  catalog: CatalogItem[],
  uoms: UomOption[],
  guard: <T>(work: () => Promise<T>, success?: { title: string; body?: string }) => Promise<T | null>,
) {
  return guard(async () => mapQuote(await salesApi.saveQuotation(quoteBody(quotation, lines, catalog, uoms))), {
    title: quotation.rfqId ? "Quotation created" : "Direct quotation created",
  });
}
