import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Outlet } from "react-router-dom";
import { ApiError } from "../../auth/api";
import type { CompanyRecord } from "../../auth/types";
import { api } from "../../auth/api";
import { useCustomer } from "../../auth/CustomerState";
import { allPages } from "../../sales/api";
import Toast from "./workflow/Toast";
import {
  procurementApi,
  type AmendmentRecord,
  type GrnRecord,
  type InspectionRecord,
  type PrRecord,
  type QuoteRecord,
  type SpoRecord,
  type SupplierPerformanceRecord,
  type SupplierRecord,
} from "../api";

type ToastItem = { id: string; type: "success" | "error" | "warning" | "info"; title: string; body?: string };

const ProcurementContext = createContext<any>(null);

function failure(error: unknown) {
  return error instanceof ApiError || error instanceof Error ? error.message : "The request failed";
}

function money(value: unknown) {
  const number = Number(value ?? 0);
  return Number.isFinite(number) ? number : 0;
}

function termsLabel(value?: number | null) {
  if (value == null) return "";
  if (value === 0) return "Advance";
  return `Net ${value}`;
}

function termsDays(value?: string | number | null) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  const text = String(value ?? "");
  if (/advance/i.test(text)) return 0;
  const match = /(\d+)/.exec(text);
  return match ? Number(match[1]) : 30;
}

function codeFor(name: string) {
  const slug = name.toUpperCase().replace(/[^A-Z0-9]/g, "").slice(0, 10) || "SUP";
  return `${slug}-${Date.now().toString(36).toUpperCase()}`;
}

function isUuid(value?: string) {
  return Boolean(value && /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value));
}

function quoteNumber(id: string) {
  return `SQ-${id.slice(0, 8).toUpperCase()}`;
}

function supplierBody(form: Record<string, unknown>, existing?: { code?: string }) {
  const legalName = String(form.legalName || form.name || "").trim();
  const tradingName = String(form.tradingName || form.name || legalName).trim();
  const contactName = String(form.contactPerson || "").trim();
  return {
    code: existing?.code || codeFor(tradingName || legalName),
    name: tradingName || legalName,
    legalName,
    gstin: String(form.gstin || "").trim().toUpperCase() || undefined,
    pan: String(form.pan || form.panNumber || "").trim().toUpperCase() || undefined,
    supplierCategoryLabel: String(form.category || ""),
    paymentTerms: termsDays(form.paymentTerms as string),
    address: {
      city: String(form.city || ""),
      state: String(form.state || ""),
      category: String(form.category || ""),
    },
    contacts: contactName ? [{
      name: contactName,
      email: String(form.email || "") || undefined,
      phone: String(form.phone || "") || undefined,
      isPrimary: true,
    }] : undefined,
    status: form.status,
  };
}

function mapSupplier(record: SupplierRecord, performance?: SupplierPerformanceRecord | null) {
  const contact = record.contacts?.find((item) => item.isPrimary) ?? record.contacts?.[0];
  const address = record.address ?? {};
  return {
    id: record.id,
    code: record.code,
    partyType: "Supplier" as const,
    legalName: record.legalName || record.name,
    tradingName: record.name,
    panNumber: record.pan || "",
    gstin: record.gstin || "",
    state: address.state || "",
    city: address.city || "",
    paymentTerms: termsLabel(record.paymentTerms),
    currency: "INR",
    category: address.category || record.supplierCategoryLabel || "",
    rating: undefined,
    onTimeDeliveryPct: performance?.onTimeDeliveryPct ?? null,
    qualityRejectionPct: performance?.qualityRejectionPct ?? null,
    avgLeadTimeDays: performance?.avgLeadTimeDays ?? null,
    receiptCount: performance?.totalReceipts ?? 0,
    performancePending: (performance?.totalReceipts ?? 0) === 0,
    status: record.status === "inactive" ? "inactive" : "active",
    contactPerson: contact?.name || "",
    phone: contact?.phone || "",
    email: contact?.email || "",
    since: "",
  };
}

function mapPr(record: PrRecord, sites: { id: string; name: string }[], customerPoNumber?: string) {
  const lines = (record.lines ?? []).map((line) => ({
    id: line.id,
    prId: record.id,
    materialItemId: line.itemId,
    materialCode: line.itemCode,
    materialName: line.itemName,
    description: line.itemName,
    requiredQty: money(line.qty),
    uom: line.uomSymbol || "",
    uomId: line.uomId,
    estimatedPrice: money(line.estUnitPrice),
    requiredDate: line.requiredDate || record.requiredDate || "",
    notes: line.notes || line.note || "",
    orderedQty: money(line.orderedQty),
  }));
  const site = sites.find((item) => item.id === record.siteId);
  return {
    id: record.id,
    prNumber: record.prNumber,
    raisedBy: "Requester",
    department: record.prTypeLabel || "",
    raisedDate: (record.createdAt || "").slice(0, 10),
    requiredBy: record.requiredDate || "",
    sourceType: record.originType === "customer_po" ? "shortage" : record.originType,
    originType: record.originType,
    sourceRefId: record.customerPoId,
    sourceRefLabel: customerPoNumber,
    customerPoId: record.customerPoId,
    jobId: record.jobId,
    status: record.status === "submitted" ? "pending" : record.status,
    apiStatus: record.status,
    lineCount: lines.length,
    totalQty: money(record.totalQty),
    priority: record.priority || "normal",
    deliverToSiteId: record.siteId,
    deliverToSiteName: site?.name || "",
    purpose: record.notes,
    reason: record.notes,
    notes: record.notes,
    rejectionReason: record.rejectionReason,
    lines,
  };
}

function mapQuote(record: QuoteRecord, pr?: ReturnType<typeof mapPr>) {
  const lines = (record.lines ?? []).map((line) => {
    const prLine = pr?.lines?.find((item) => item.id === line.prLineId);
    return {
      id: line.id,
      quoteId: record.id,
      prLineId: line.prLineId,
      materialItemId: line.itemId,
      materialCode: prLine?.materialCode,
      materialName: prLine?.materialName || "Material",
      quotedQty: prLine?.requiredQty || 0,
      uom: prLine?.uom || "",
      uomId: prLine?.uomId,
      unitPrice: money(line.unitPrice),
      taxPct: money(line.taxRate),
      lineTotal: money(line.unitPrice) * money(prLine?.requiredQty),
      leadTimeDays: line.leadTimeDays,
      isSelected: line.isSelected,
    };
  });
  const landed = lines.reduce((sum, line) => sum + line.lineTotal, 0);
  return {
    id: record.id,
    quoteNumber: record.supplierQuoteRef || quoteNumber(record.id),
    prId: record.prId,
    prNumber: pr?.prNumber,
    supplierId: record.supplierId,
    supplierName: record.supplierName || "Supplier",
    quoteDate: "",
    validUntil: record.validUntil || "",
    currency: "INR",
    deliveryDays: lines.find((line) => line.leadTimeDays != null)?.leadTimeDays ?? 0,
    paymentTerms: termsLabel(record.paymentTerms),
    deliveryCharges: money(record.freightAmount),
    discountAmount: money(record.discountAmount),
    taxAmount: money(record.taxAmount),
    selectionReason: record.selectionReason,
    deliveryStatus: record.deliveryStatus || "not_sent",
    deliveryRecipient: record.deliveryRecipient,
    deliveryError: record.deliveryError,
    lines,
    status: record.status,
    landedTotal: landed,
    subtotal: landed,
    recordType: record.status === "requested" ? "invitation" : "quotation",
  };
}

function mapSpo(record: SpoRecord, pr?: ReturnType<typeof mapPr>) {
  const lines = (record.lines ?? []).map((line) => ({
    id: line.id,
    spoId: record.id,
    materialItemId: line.itemId,
    materialCode: line.itemCode,
    materialName: line.itemName,
    orderedQty: money(line.orderedQty),
    receivedQty: money(line.receivedQty),
    acceptedQty: 0,
    rejectedQty: 0,
    uom: "",
    rate: money(line.unitPrice),
    value: money(line.lineTotal || money(line.unitPrice) * money(line.orderedQty)),
    status: record.status,
    expectedDeliveryDate: line.expectedDeliveryDate,
  }));
  return {
    id: record.id,
    poNumber: record.spoNumber,
    spoNumber: record.spoNumber,
    supplierId: record.supplierId,
    supplierName: record.supplierName || "Supplier",
    prId: record.prId,
    supplierQuotationId: record.supplierQuotationId,
    raisedDate: (record.createdAt || "").slice(0, 10),
    committedDate: lines.find((line) => line.expectedDeliveryDate)?.expectedDeliveryDate || "",
    status: record.status === "declined" ? "supplier_declined" : record.status,
    apiStatus: record.status,
    revision: record.revision ?? 1,
    declineReason: record.declineReason,
    totalValue: money(record.totalAmount),
    currency: "INR",
    lineCount: lines.length,
    paymentTerms: "",
    originatingRequirementId: pr?.customerPoId,
    lines,
  };
}

function mapGrn(record: GrnRecord, spo?: ReturnType<typeof mapSpo>) {
  const lines = (record.lines ?? []).map((line) => ({
    id: line.id,
    spoLineId: line.spoLineId,
    materialItemId: line.itemId,
    materialCode: line.itemCode,
    materialName: line.itemName,
    receivedQty: money(line.receivedQty),
    acceptedQty: money(line.acceptedQty),
    rejectedQty: money(line.rejectedQty),
    orderedQty: spo?.lines?.find((item: { id: string }) => item.id === line.spoLineId)?.orderedQty,
    previouslyReceivedQty: 0,
    uom: line.uomSymbol || "",
    lots: (line.lotNumbers ?? []).map((lotNumber, index) => {
      const quantity = money(line.lotQuantities?.[index] ?? line.receivedQty);
      const stored = line.lotStatuses?.[index] || "quarantine";
      return {
        id: line.lotIds?.[index],
        lotNumber,
        qty: quantity,
        primaryQty: quantity,
        primaryUom: line.uomSymbol || "",
        status: stored === "quarantine" ? "qc_hold" : stored,
        grnId: record.id,
      };
    }),
    lotIds: line.lotIds ?? [],
  }));
  return {
    id: record.id,
    grnNumber: record.grnNumber,
    spoId: record.spoId,
    spoNumber: record.spoNumber || spo?.poNumber,
    supplierId: record.supplierId,
    supplierName: record.supplierName || spo?.supplierName,
    siteId: record.siteId,
    status: grnStatus(record),
    receivedDate: record.receivedDate || "",
    deliveryChallan: record.supplierDcRef,
    vehicleNumber: record.vehicleNumber,
    lineCount: lines.length,
    lines,
  };
}

function grnStatus(record: GrnRecord) {
  if (record.status === "draft") return "pending_qc";
  if (record.status !== "closed") return record.status === "partially_accepted" ? "partial" : record.status;
  const accepted = (record.lines ?? []).reduce((sum, line) => sum + money(line.acceptedQty), 0);
  const rejected = (record.lines ?? []).reduce((sum, line) => sum + money(line.rejectedQty), 0);
  if (rejected === 0) return "accepted";
  if (accepted === 0) return "rejected";
  return "partial";
}

function mapInspection(record: InspectionRecord) {
  return {
    id: record.id,
    inspectionNumber: record.inspectionNumber,
    grnId: record.grnId,
    lotId: record.lotId,
    itemId: record.itemId,
    quantityInspected: money(record.quantityInspected),
    quantityAccepted: money(record.quantityAccepted),
    quantityRejected: money(record.quantityRejected),
    result: record.result || "",
    status: record.status,
    notes: record.notes || "",
  };
}

function mapAmendment(record: AmendmentRecord, spo?: ReturnType<typeof mapSpo>) {
  return {
    id: record.id,
    entityType: "supplier_po",
    poId: record.spoId,
    poNumber: spo?.poNumber,
    revision: record.revision,
    reason: record.reason,
    changeNote: record.changeNote,
    status: record.status,
  };
}

export function useStore() {
  const value = useContext(ProcurementContext);
  if (!value) throw new Error("Procurement records are not loaded");
  return value;
}

export function ProcurementProvider({ children }: { children?: ReactNode }) {
  const customer = useCustomer();
  const [ready, setReady] = useState(false);
  const [suppliers, setSuppliers] = useState<any[]>([]);
  const [purchaseReqs, setPurchaseReqs] = useState<any[]>([]);
  const [supplierQuotations, setSupplierQuotations] = useState<any[]>([]);
  const [supplierPos, setSupplierPos] = useState<any[]>([]);
  const [supplierPoLines, setSupplierPoLines] = useState<any[]>([]);
  const [materials, setMaterials] = useState<any[]>([]);
  const [uoms, setUoms] = useState<{ id: string; symbol: string }[]>([]);
  const [sites, setSites] = useState<any[]>([]);
  const [storeLocations, setStoreLocations] = useState<any[]>([]);
  const [grns, setGrns] = useState<any[]>([]);
  const [lots, setLots] = useState<any[]>([]);
  const [inspections, setInspections] = useState<any[]>([]);
  const [poAmendments, setPoAmendments] = useState<any[]>([]);
  const [toasts, setToasts] = useState<ToastItem[]>([]);
  const dataRef = useRef<any>({});
  const chains = useRef(new Map<string, Promise<void>>());

  const showToast = useCallback((toast: Omit<ToastItem, "id">) => {
    const id = `${Date.now()}-${Math.random()}`;
    setToasts((current) => [...current, { ...toast, id }]);
    window.setTimeout(() => setToasts((current) => current.filter((item) => item.id !== id)), 5000);
  }, []);
  const removeToast = useCallback((id: string) => setToasts((current) => current.filter((item) => item.id !== id)), []);

  const reload = useCallback(async () => {
    const [supplierRows, prRows, spoRows, itemRows, uomRows] = await Promise.all([
      procurementApi.suppliers(),
      procurementApi.prs(),
      procurementApi.spos(),
      allPages<any>("/api/v1/items"),
      allPages<any>("/api/v1/uoms"),
    ]);
    let company: CompanyRecord | null = null;
    try {
      company = await api<CompanyRecord>("/api/v1/company");
    } catch {
      company = null;
    }
    const nextSites = (company?.sites ?? []).filter((site) => site.id && site.name).map((site) => ({
      id: site.id as string,
      name: site.name,
      code: site.code,
      city: site.city,
      state: site.state,
      status: "active",
    }));
    const poNumbers = new Map<string, string>();
    const mappedPrs = prRows.map((row) => mapPr(row, nextSites, poNumbers.get(row.customerPoId || "")));
    const quoteGroups = await Promise.all(mappedPrs.map(async (pr) => {
      try {
        const rows = await procurementApi.quotations(pr.id);
        return rows.map((row) => mapQuote(row, pr));
      } catch {
        return [];
      }
    }));
    const quotes = quoteGroups.flat();
    const mappedSpos = spoRows.map((row) => mapSpo(row, mappedPrs.find((pr) => pr.id === row.prId)));
    const [grnRows, amendmentGroups, performanceRows] = await Promise.all([
      procurementApi.grns().catch(() => [] as GrnRecord[]),
      Promise.all(spoRows.map(async (spo) => {
        try { return await procurementApi.amendments(spo.id); } catch { return [] as AmendmentRecord[]; }
      })),
      Promise.all(supplierRows.map(async (supplier) => {
        try { return await procurementApi.performance(supplier.id); } catch { return null; }
      })),
    ]);
    const mappedGrns = grnRows.map((row) => mapGrn(row, mappedSpos.find((spo) => spo.id === row.spoId)));
    const inspectionGroups = await Promise.all(grnRows.map(async (grn) => {
      try {
        const rows = await procurementApi.inspections(grn.id);
        return Array.isArray(rows) ? rows : [];
      } catch { return [] as InspectionRecord[]; }
    }));
    setSuppliers(supplierRows.map((row, index) => mapSupplier(row, performanceRows[index])));
    setPurchaseReqs(mappedPrs);
    setSupplierQuotations(quotes.filter((quote) => quote.status !== "requested"));
    setSupplierPos(mappedSpos);
    setSupplierPoLines(mappedSpos.flatMap((spo) => spo.lines.map((line: any) => ({ ...line, poId: spo.id }))));
    setGrns(mappedGrns);
    setInspections(inspectionGroups.flat().map(mapInspection));
    setLots(mappedGrns.flatMap((grn) => grn.lines.flatMap((line) => line.lots.map((lot) => ({
      ...lot,
      materialName: line.materialName,
      supplierLotRef: lot.lotNumber,
    })))));
    setPoAmendments(amendmentGroups.flat().map((row) => mapAmendment(row, mappedSpos.find((spo) => spo.id === row.spoId))));
    setMaterials(itemRows.map((item) => ({
      id: item.id,
      itemCode: item.code,
      name: item.name,
      primaryUom: item.uomSymbol,
      uomId: item.uomId,
      active: item.status !== "inactive",
    })));
    setUoms(uomRows.map((uom) => ({ id: uom.id, symbol: uom.symbol })));
    setSites(nextSites);
    setStoreLocations((company?.stores ?? []).flatMap((store) => {
      if (!store.id) return [];
      const siteId = store.siteId || nextSites.find((site) => site.code && site.code === store.siteCode)?.id;
      return [{ id: store.id, name: store.name, siteId, locationType: store.storeType, code: store.code }];
    }));
    dataRef.current = {
      suppliers: supplierRows,
      prs: mappedPrs,
      quotes,
      spos: mappedSpos,
      uoms: uomRows,
      sites: nextSites,
    };
  }, []);

  useEffect(() => {
    let live = true;
    reload()
      .catch((error) => { if (live) showToast({ type: "error", title: "Procurement records did not load", body: failure(error) }); })
      .finally(() => { if (live) setReady(true); });
    return () => { live = false; };
  }, [reload, showToast]);

  const roles = customer.user?.roles.map((role) => role.role) ?? [];
  const role = roles.includes("OWNER")
    ? "owner"
    : roles.some((item) => item.includes("PURCHASE") || item.includes("BUYER"))
      ? "buyer"
      : roles.length === 0 ? "owner" : "viewer";
  const currentUser = { id: customer.user?.id || "", name: customer.user?.displayName || customer.user?.email || "Owner", role };

  const guard = useCallback(async <T,>(work: () => Promise<T>) => {
    try {
      const result = await work();
      await reload();
      return result;
    } catch (error) {
      showToast({ type: "error", title: "Not saved", body: failure(error) });
      return null;
    }
  }, [reload, showToast]);

  const enqueue = useCallback((id: string, work: () => Promise<void>) => {
    const previous = chains.current.get(id) ?? Promise.resolve();
    const next = previous.catch(() => undefined).then(work);
    chains.current.set(id, next);
    return next;
  }, []);

  const value = useMemo(() => {
    const requested = (dataRef.current.quotes as any[] | undefined)?.filter((quote) => quote.status === "requested")
      ?? [];
    const supplierInvitations = requested.map((quote) => ({
      id: quote.id,
      prId: quote.prId,
      prNumber: quote.prNumber,
      supplierId: quote.supplierId,
      supplierName: quote.supplierName,
      channel: "email",
      requestedLineIds: (quote.lines ?? []).map((line: any) => line.prLineId),
      createdAt: "",
      sentDate: quote.deliveryStatus === "sent" ? "Sent" : "",
      responseDueDate: quote.validUntil,
      status: "awaiting_quotation",
      quotationId: undefined,
      deliveryStatus: quote.deliveryStatus,
      deliveryRecipient: quote.deliveryRecipient,
      deliveryError: quote.deliveryError,
      contactEmail: quote.deliveryRecipient,
    }));
    const approvalRequests = purchaseReqs
      .filter((pr) => pr.apiStatus === "submitted" || pr.status === "pending")
      .map((pr) => ({
        id: pr.id,
        entityType: "purchase_requisition",
        entityRef: pr.prNumber,
        entityId: pr.id,
        status: "pending",
        requestedBy: pr.raisedBy,
        summary: pr.notes || "Purchase requisition submitted for approval",
      }));

    return {
      ready,
      currentUser,
      suppliers,
      purchaseReqs,
      supplierQuotations,
      supplierInvitations,
      supplierPos,
      supplierPoLines,
      grns,
      lots,
      inspections,
      materials,
      sites,
      storeLocations,
      documents: [],
      auditEvents: [],
      supplierBills: [],
      supplierPayments: [],
      supplierPaymentAllocations: [],
      supplierCatalog: [],
      poAmendments,
      approvalRequests,
      quotations: [],
      companyConfig: {
        companyName: customer.tenant?.name || "BOSS",
        tradingName: customer.tenant?.name || "BOSS",
        legalName: customer.tenant?.name || "",
        registeredAddress: "",
        city: "",
        state: "",
        pincode: "",
        gstin: "",
        pan: "",
        phone: "",
        email: "",
      },
      toasts,
      showToast,
      removeToast,
      reload,
      addSupplier: async (party: any) => guard(async () => {
        const saved = await procurementApi.createSupplier(supplierBody(party));
        showToast({ type: "success", title: "Supplier added", body: `${saved.name} has been registered.` });
        return mapSupplier(saved);
      }),
      updateSupplier: async (id: string, patch: any) => {
        const existing = (dataRef.current.suppliers as SupplierRecord[] | undefined)?.find((item) => item.id === id);
        const current = suppliers.find((item) => item.id === id);
        return guard(async () => {
          const saved = await procurementApi.updateSupplier(id, supplierBody({ ...current, ...patch }, existing));
          showToast({ type: "success", title: patch.status === "inactive" ? "Supplier deactivated" : "Supplier updated" });
          return mapSupplier(saved);
        });
      },
      addPurchaseReq: async (pr: any) => guard(async () => {
        const siteId = isUuid(pr.deliverToSiteId) ? pr.deliverToSiteId : dataRef.current.sites?.[0]?.id;
        if (!isUuid(siteId)) throw new Error("Choose a receiving site before submitting the purchase requisition.");
        const lines = (pr.lines ?? []).map((line: any) => {
          const material = materials.find((item) => item.id === line.materialItemId);
          const uom = uoms.find((item) => item.symbol === line.uom) || uoms.find((item) => item.id === material?.uomId);
          if (!isUuid(line.materialItemId) || !uom) {
            throw new Error("Select a catalog item and unit for every purchase requisition line.");
          }
          return {
            itemId: line.materialItemId,
            qty: Number(line.requiredQty),
            uomId: uom.id,
            estUnitPrice: line.estimatedPrice || undefined,
            requiredDate: line.requiredDate || pr.requiredBy || undefined,
            notes: line.notes || undefined,
          };
        });
        let saved = await procurementApi.createPr({
          originType: "manual",
          siteId,
          prTypeLabel: pr.department || undefined,
          requiredDate: pr.requiredBy || undefined,
          priority: pr.priority || "normal",
          notes: [pr.purpose, pr.notes].filter(Boolean).join("\n") || undefined,
          lines,
        });
        if (saved.status === "draft") saved = await procurementApi.submitPr(saved.id);
        if (["owner", "buyer"].includes(currentUser.role) && saved.status === "submitted") {
          try {
            saved = await procurementApi.approvePr(saved.id);
          } catch (error) {
            showToast({ type: "warning", title: "Submitted for approval", body: failure(error) });
          }
        }
        showToast({ type: "success", title: "Purchase Requisition created", body: `${saved.prNumber} is ${saved.status}.` });
        return mapPr(saved, dataRef.current.sites ?? []);
      }),
      updatePurchaseReq: async (id: string, patch: any) => guard(async () => {
        const current = purchaseReqs.find((item) => item.id === id);
        if (!current) throw new Error("Purchase requisition was not found.");
        if (patch.status === "approved") {
          let saved = current.apiStatus === "draft" ? await procurementApi.submitPr(id) : current;
          if (saved.status === "submitted" || saved.apiStatus === "submitted" || current.apiStatus === "submitted") {
            saved = await procurementApi.approvePr(id);
          }
          showToast({ type: "success", title: "PR Approved", body: `${saved.prNumber || current.prNumber} approved.` });
          return mapPr(saved, sites);
        }
        if (patch.status === "rejected") {
          const saved = await procurementApi.rejectPr(id, patch.rejectionReason || patch.reason || "Rejected");
          showToast({ type: "info", title: "PR Rejected", body: patch.rejectionReason || patch.reason });
          return mapPr(saved, sites);
        }
        if (patch.status === "cancelled") {
          const saved = await procurementApi.cancelPr(id);
          showToast({ type: "info", title: "PR cancelled" });
          return mapPr(saved, sites);
        }
        if (current.apiStatus !== "draft") throw new Error("Only a draft purchase requisition can be edited.");
        const saved = await procurementApi.updatePr(id, {
          siteId: isUuid(patch.deliverToSiteId) ? patch.deliverToSiteId : current.deliverToSiteId,
          priority: patch.priority || current.priority,
          requiredDate: patch.requiredBy || current.requiredBy,
          prTypeLabel: patch.department || current.department,
          notes: patch.notes ?? current.notes,
          lines: (patch.lines ?? current.lines ?? []).map((line: any) => ({
            itemId: line.materialItemId,
            qty: Number(line.requiredQty),
            uomId: line.uomId || uoms.find((item) => item.symbol === line.uom)?.id,
            estUnitPrice: line.estimatedPrice || undefined,
            requiredDate: line.requiredDate || undefined,
            notes: line.notes || undefined,
          })),
        });
        showToast({ type: "success", title: "Purchase Requisition updated" });
        return mapPr(saved, sites);
      }),
      sendSupplierInvitations: async (input: { prId: string; supplierIds: string[] }) => guard(async () => {
        const created = [];
        for (const supplierId of input.supplierIds) {
          created.push(await procurementApi.createQuotation(input.prId, supplierId));
        }
        showToast({ type: "success", title: "Quotation requests sent", body: `${created.length} supplier${created.length === 1 ? "" : "s"} can now quote.` });
        return created;
      }),
      addSupplierQuotation: async (quote: any) => guard(async () => {
        const existing = ((dataRef.current.quotes as any[] | undefined) ?? []).find((item) => item.prId === quote.prId && item.supplierId === quote.supplierId && item.status === "requested");
        const created = existing ? { id: existing.id } : await procurementApi.createQuotation(quote.prId, quote.supplierId);
        const pr = purchaseReqs.find((item) => item.id === quote.prId);
        const lines = (quote.lines ?? []).map((line: any) => {
          const prLine = pr?.lines?.find((item: any) => item.id === line.prLineId || item.materialItemId === line.materialItemId);
          if (!prLine) throw new Error("Each quoted line must match a purchase requisition line.");
          return {
            prLineId: prLine.id,
            unitPrice: Number(line.unitPrice),
            leadTimeDays: Number(line.leadTimeDays || quote.deliveryDays || 0),
            taxRate: line.taxPct == null ? undefined : Number(line.taxPct),
          };
        });
        const saved = await procurementApi.receiveQuotation(created.id, {
          validUntil: quote.validUntil || undefined,
          supplierQuoteRef: quote.quoteNumber || undefined,
          paymentTerms: termsDays(quote.paymentTerms),
          freightAmount: Number(quote.deliveryCharges || 0),
          discountAmount: Number(quote.discountAmount || 0),
          taxAmount: Number(quote.taxAmount || 0),
          lines,
        });
        showToast({ type: "success", title: "Supplier quotation recorded", body: `${quote.supplierName} prices are saved.` });
        return mapQuote(saved, pr);
      }),
      selectQuotation: (id: string) => enqueue(id, async () => {
        const current = await procurementApi.quotation(id);
        if (current.status === "received") await procurementApi.reviewQuotation(id);
      }).catch((error) => showToast({ type: "error", title: "Quotation was not updated", body: failure(error) })),
      approveFinalizeQuotation: (id: string, _name?: string, reason?: string) => enqueue(id, async () => {
        const current = await procurementApi.quotation(id);
        const status = current.status === "received" ? (await procurementApi.reviewQuotation(id)).status : current.status;
        if (status === "under_review") await procurementApi.selectQuotation(id, reason);
        await reload();
        showToast({ type: "success", title: "Quotation selected", body: "The supplier was selected. A purchase order is not created until you draft one." });
      }).catch((error) => showToast({ type: "error", title: "Quotation was not selected", body: failure(error) })),
      rejectQuotationApproval: (id: string) => enqueue(id, async () => {
        await procurementApi.rejectQuotation(id);
        await reload();
        showToast({ type: "warning", title: "Quotation rejected" });
      }).catch((error) => showToast({ type: "error", title: "Quotation was not rejected", body: failure(error) })),
      updateSupplierQuotation: async () => {
        showToast({ type: "info", title: "Quotation terms are fixed", body: "The API stores the received price and lead time. Record a new quotation to change them." });
        return null;
      },
      updateSupplierInvitation: async (id: string, patch: any) => {
        if (patch.status === "declined") return enqueue(id, async () => {
          await procurementApi.rejectQuotation(id);
          await reload();
          showToast({ type: "warning", title: "Supplier quotation declined" });
        });
        return null;
      },
      generateSPOFromQuotation: async (quoteId: string) => {
        try {
          await (chains.current.get(quoteId) ?? Promise.resolve());
          const quote = await procurementApi.quotation(quoteId);
          if (quote.status !== "selected") {
            return { error: "Select the quotation before creating a supplier purchase order." };
          }
          const existing = supplierPos.find((item) => item.supplierQuotationId === quoteId && item.status !== "cancelled");
          if (existing) return { spo: existing, error: "A supplier purchase order already exists for this quotation." };
          const pr = purchaseReqs.find((item) => item.id === quote.prId);
          const lines = (quote.lines ?? []).map((line) => {
            const prLine = pr?.lines?.find((item: any) => item.id === line.prLineId);
            if (!prLine?.uomId) throw new Error("The purchase requisition line is missing its unit.");
            return {
              prLineId: line.prLineId,
              itemId: line.itemId,
              orderedQty: Number(prLine.requiredQty),
              unitPrice: Number(line.unitPrice),
              uomId: prLine.uomId,
              expectedDeliveryDate: pr?.requiredBy || undefined,
            };
          });
          const saved = await procurementApi.createSpo({
            supplierId: quote.supplierId,
            prId: quote.prId,
            supplierQuotationId: quote.id,
            lines,
          }, `spo-from:${quote.id}`);
          await reload();
          return { spo: mapSpo(saved, pr) };
        } catch (error) {
          return { error: failure(error) };
        }
      },
      addSupplierPO: async () => {
        showToast({ type: "error", title: "Supplier PO was not created", body: "Create the supplier purchase order from a selected quotation." });
        return null;
      },
      updateSupplierPo: async (id: string, patch: any) => guard(async () => {
        if (patch.status === "cancelled") return procurementApi.cancelSpo(id);
        showToast({ type: "info", title: "Supplier PO fields are fixed", body: "Issue, acknowledge, or cancel the order. Other edits are not in the API." });
        return null;
      }),
      setSupplierPoStatus: async (id: string, status: string) => guard(async () => {
        if (status === "cancelled") {
          await procurementApi.cancelSpo(id);
          showToast({ type: "info", title: "Supplier PO cancelled" });
        }
      }),
      confirmAndIssueSPO: async (id: string) => {
        try {
          await procurementApi.issueSpo(id);
          await reload();
          return { nonSelectedInvitations: [] };
        } catch (error) {
          return { error: failure(error), nonSelectedInvitations: [] };
        }
      },
      recordSupplierPOConfirmation: async (id: string, decision: string, detail?: { declineReason?: string }) => {
        try {
          if (decision === "declined") {
            await procurementApi.declineSpo(id, detail?.declineReason || "Supplier declined the purchase order");
          } else {
            await procurementApi.acknowledgeSpo(id);
          }
          await reload();
          return {};
        } catch (error) {
          return { error: failure(error) };
        }
      },
      notifyNonSelectedSuppliers: () => showToast({
        type: "info",
        title: "Suppliers are not notified from BOSS",
        body: "Tell the other suppliers outside BOSS. No message was sent.",
      }),
      addPoAmendment: async (draft: {
        poId: string; reason: string; changeNote: string; lineId?: string;
        lineChanges?: { quantity?: number; rate?: number; requiredDate?: string };
        headerChanges?: { paymentTerms?: string; committedDate?: string };
      }) => {
        try {
          const saved = await procurementApi.submitAmendment(draft.poId, {
            reason: draft.reason,
            changeNote: draft.changeNote,
            lineId: draft.lineId || null,
            quantity: draft.lineChanges?.quantity || null,
            unitPrice: draft.lineChanges?.rate || null,
            expectedDate: draft.lineChanges?.requiredDate || draft.headerChanges?.committedDate || null,
            paymentTerms: draft.headerChanges?.paymentTerms ? termsDays(draft.headerChanges.paymentTerms) : null,
          });
          await reload();
          showToast({ type: "success", title: "Amendment submitted", body: "The current purchase order stays binding until the owner approves it." });
          return saved;
        } catch (error) {
          const message = failure(error);
          showToast({ type: "error", title: "Amendment was not saved", body: message });
          return { error: message };
        }
      },
      resolvePoAmendment: async (id: string, decision: string) => {
        const amendment = poAmendments.find((item) => item.id === id);
        if (!amendment) {
          showToast({ type: "error", title: "Amendment was not found" });
          return;
        }
        try {
          await procurementApi.decideAmendment(amendment.poId, id, decision === "approved");
          await reload();
          showToast({ type: "success", title: decision === "approved" ? "Amendment approved" : "Amendment rejected" });
        } catch (error) {
          showToast({ type: "error", title: "Amendment was not decided", body: failure(error) });
        }
      },
      resolveApproval: async (id: string, decision: string, _actor?: string, note?: string) => guard(async () => {
        if (decision === "rejected") await procurementApi.rejectPr(id, note || "Rejected");
        else await procurementApi.approvePr(id);
        showToast({ type: "success", title: decision === "rejected" ? "PR rejected" : "PR approved" });
      }),
      recordGRNReceipt: async (input: {
        spoId: string; receivedDate: string; deliveryChallan: string; vehicleNumber?: string;
        storeLocationId?: string; notes?: string;
        lines: Array<{ spoLineId: string; receivedQty: number; notes?: string; lots: Array<{ lotNumber: string; qty: number; expiryDate?: string; supplierLotRef?: string }> }>;
      }) => {
        if (!input.storeLocationId) return { error: "Choose the receiving store." };
        try {
          const signature = input.lines.map((line) => `${line.spoLineId}:${line.receivedQty}`).join("|");
          const saved = await procurementApi.createGrn({
            spoId: input.spoId,
            receivedDate: input.receivedDate,
            deliveryChallan: input.deliveryChallan,
            vehicleNumber: input.vehicleNumber,
            storeId: input.storeLocationId,
            notes: input.notes,
            lines: input.lines.map((line) => ({
              spoLineId: line.spoLineId,
              receivedQty: line.receivedQty,
              rejectedQty: 0,
              notes: line.notes,
              lots: line.lots.map((lot) => ({
                lotNumber: lot.lotNumber,
                qty: lot.qty,
                expiryDate: lot.expiryDate || null,
                supplierLotRef: lot.supplierLotRef || null,
              })),
            })),
          }, `grn:${input.spoId}:${input.deliveryChallan}:${signature}`);
          await reload();
          return { grn: { id: saved.id, grnNumber: saved.grnNumber } };
        } catch (error) {
          return { error: failure(error) };
        }
      },
      recordSupplierReturn: async (input: { grnId: string; reason: string; lines: Array<{ grnLineId: string; lotId: string; quantity: number }> }) => {
        try {
          const signature = input.lines.map((line) => `${line.lotId}:${line.quantity}`).join("|");
          const saved = await procurementApi.createSupplierReturn({
            grnId: input.grnId,
            reason: input.reason,
            lines: input.lines,
          }, `return:${input.grnId}:${signature}`);
          await reload();
          return saved;
        } catch (error) {
          return { error: failure(error) };
        }
      },
      rejectGRN: () => showToast({ type: "info", title: "Reject the lot from inspection", body: "Open the incoming inspection and record the rejected quantity. Rejected stock stays in quarantine." }),
      retryQuotationDelivery: (id: string) => enqueue(id, async () => {
        const saved = await procurementApi.deliverQuotation(id);
        await reload();
        showToast(saved.deliveryStatus === "sent"
          ? { type: "success", title: "Quotation request sent", body: saved.deliveryRecipient }
          : { type: "error", title: "Quotation request was not sent", body: saved.deliveryError || "The email provider did not accept the message." });
      }).catch((error) => showToast({ type: "error", title: "Quotation request was not sent", body: failure(error) })),
      startInspectionForGRN: async (grnId: string, lotId: string) => {
        try {
          const saved = await procurementApi.startInspection(grnId, lotId);
          await reload();
          return mapInspection(saved);
        } catch (error) {
          showToast({ type: "error", title: "Inspection was not started", body: failure(error) });
          return null;
        }
      },
      completeInspection: async (id: string, body: { acceptedQty: number; rejectedQty: number; hold: boolean; notes?: string }) => {
        try {
          const saved = await procurementApi.completeInspection(id, body);
          await reload();
          showToast({ type: "success", title: body.hold ? "Lot kept on hold" : "Inspection recorded" });
          return mapInspection(saved);
        } catch (error) {
          showToast({ type: "error", title: "Inspection was not recorded", body: failure(error) });
          return null;
        }
      },
      releaseLotFromHold: async (lotId: string) => {
        const lot = lots.find((item) => item.id === lotId);
        const inspection = inspections.find((item) => item.lotId === lotId && item.status !== "completed");
        try {
          const current = inspection ?? await procurementApi.startInspection(lot.grnId, lotId);
          await procurementApi.completeInspection(current.id, {
            acceptedQty: Number(lot.primaryQty || lot.qty),
            rejectedQty: 0,
            hold: false,
          });
          await reload();
          showToast({ type: "success", title: "Lot released", body: "The inspected quantity is now available." });
        } catch (error) {
          showToast({ type: "error", title: "Lot was not released", body: failure(error) });
        }
      },
    };
  }, [approvalSafe(purchaseReqs), currentUser, customer.tenant?.name, grns, guard, inspections, lots, materials, poAmendments, purchaseReqs, ready, reload, removeToast, showToast, sites, storeLocations, supplierPoLines, supplierPos, supplierQuotations, suppliers, toasts, uoms, enqueue]);

  return (
    <ProcurementContext.Provider value={value}>
      {ready ? children ?? <Outlet /> : <p role="status" className="p-8 text-sm text-[var(--text-2)]">Loading procurement records…</p>}
      <Toast />
    </ProcurementContext.Provider>
  );
}

function approvalSafe(rows: any[]) {
  return rows.map((row) => row.id + row.status).join(",");
}
