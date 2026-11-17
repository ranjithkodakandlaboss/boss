import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import App from "../../App";
import type { PurchaseRequisition, SupplierQuotation, SupplierComparison, SupplierPurchaseOrder } from "../../api/procurement";

const ids = {
  pr: "00000000-0000-4000-8000-000000000001",
  line: "00000000-0000-4000-8000-000000000002",
  item: "00000000-0000-4000-8000-000000000003",
  uom: "00000000-0000-4000-8000-000000000004",
  supplier: "00000000-0000-4000-8000-000000000005",
  quote: "00000000-0000-4000-8000-000000000006",
  spo: "00000000-0000-4000-8000-000000000007",
};

const prRecord = (status: string): PurchaseRequisition => ({
  id: ids.pr,
  prNumber: "PR-2026-001",
  originType: "manual",
  status,
  priority: "normal",
  requiredDate: "2026-12-01",
  lines: [{
    id: ids.line,
    seq: 1,
    lineNo: 1,
    itemId: ids.item,
    itemCode: "MAT-01",
    itemName: "Steel Sheet",
    qty: 5,
    quantity: 5,
    orderedQty: 0,
    uomId: ids.uom,
    uomSymbol: "kg",
  }],
  totalQty: 5,
  totalOrderedQty: 0,
  createdAt: "2026-11-17T00:00:00Z",
});

const quotation = (status: string): SupplierQuotation => ({
  id: ids.quote,
  prId: ids.pr,
  supplierId: ids.supplier,
  supplierName: "Current Supplier",
  status,
  validUntil: "2026-12-15",
  lines: status === "requested" ? [] : [{
    id: "00000000-0000-4000-8000-000000000008",
    prLineId: ids.line,
    itemId: ids.item,
    unitPrice: 125,
    leadTimeDays: 7,
    isSelected: status === "selected",
  }],
});

const comparison: SupplierComparison = {
  prId: ids.pr,
  lines: [{
    prLineId: ids.line,
    itemId: ids.item,
    itemCode: "MAT-01",
    itemName: "Steel Sheet",
    quantity: 5,
    uomId: ids.uom,
    uomSymbol: "kg",
    offers: [{
      supplierQuotationId: ids.quote,
      supplierId: ids.supplier,
      supplierName: "Current Supplier",
      status: "under_review",
      unitPrice: 125,
      leadTimeDays: 7,
      validUntil: "2026-12-15",
      isSelected: false,
    }],
  }],
};

const spoRecord: SupplierPurchaseOrder = {
  id: ids.spo,
  spoNumber: "SPO-2026-001",
  prId: ids.pr,
  supplierQuotationId: ids.quote,
  supplierId: ids.supplier,
  supplierName: "Current Supplier",
  status: "draft",
  lines: [{
    id: "00000000-0000-4000-8000-000000000009",
    itemId: ids.item,
    itemCode: "MAT-01",
    itemName: "Steel Sheet",
    orderedQty: 5,
    receivedQty: 0,
    unitPrice: 125,
    lineTotal: 625,
  }],
  totalAmount: 625,
  createdAt: "2026-11-17T00:00:00Z",
};

function jsonResponse(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
}

function renderAt(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><App /></MemoryRouter>);
}

function stubAuth() {
  window.bossCustomerAuth = { getIdToken: vi.fn().mockResolvedValue("test-firebase-id-token") };
}

function stubFetch(implementation: (url: URL, init: RequestInit) => Response | Promise<Response>) {
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo | URL, init: RequestInit = {}) =>
    implementation(new URL(String(input), "http://localhost"), init)));
  return vi.mocked(fetch);
}

describe("procurement workflows", () => {
  beforeEach(() => {
    cleanup();
    vi.restoreAllMocks();
    vi.unstubAllGlobals();
    stubAuth();
  });

  it("loads real PR list data and filters by requisition status", async () => {
    stubFetch((url) => {
      if (url.pathname === "/api/v1/prs") return jsonResponse({ content: [prRecord("submitted")], page: 0, size: 50, totalElements: 1 });
      throw new Error(`Unexpected request: ${url}`);
    });

    renderAt("/procurement?tab=pr");
    expect(await screen.findByText("PR-2026-001")).toBeInTheDocument();
    expect(screen.getAllByText("Submitted")).toHaveLength(2);
  });

  it("creates a draft PR from catalog item and UOM, then follows API response", async () => {
    const calls: { url: URL; init: RequestInit }[] = [];
    stubFetch((url, init) => {
      calls.push({ url, init });
      if (url.pathname === "/api/v1/items") return jsonResponse({ content: [{ id: ids.item, code: "MAT-01", name: "Steel Sheet", uomId: ids.uom, uomSymbol: "kg", status: "active" }], page: 0, size: 50, totalElements: 1 });
      if (url.pathname === "/api/v1/uoms") return jsonResponse([{ id: ids.uom, name: "Kilogram", symbol: "kg" }]);
      if (url.pathname === "/api/v1/prs" && init.method === "POST") return jsonResponse(prRecord("draft"), 201);
      if (url.pathname === `/api/v1/prs/${ids.pr}`) return jsonResponse(prRecord("draft"));
      if (url.pathname === `/api/v1/prs/${ids.pr}/supplier-quotations`) return jsonResponse({ content: [], page: 0, size: 50, totalElements: 0 });
      if (url.pathname.endsWith("/supplier-quotations/comparison")) return jsonResponse({ prId: ids.pr, lines: [] });
      throw new Error(`Unexpected request: ${url.pathname}`);
    });

    const user = userEvent.setup();
    renderAt("/procurement/pr/new");
    await user.type(await screen.findByLabelText("Required By"), "2026-12-01");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    await user.selectOptions(await screen.findByLabelText("Select Catalog Material"), ids.item);
    await user.type(screen.getByLabelText("Quantity"), "5");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText("Review & Submit")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create Purchase Requisition" }));

    await screen.findByText("PR-2026-001");
    const createCall = calls.find(({ url, init }) => url.pathname === "/api/v1/prs" && init.method === "POST");
    expect(createCall).toBeDefined();
    expect(JSON.parse(String(createCall?.init.body))).toMatchObject({
      originType: "manual",
      priority: "normal",
      requiredDate: "2026-12-01",
      lines: [{ itemId: ids.item, qty: 5, uomId: ids.uom }],
    });
    expect(new Headers(createCall?.init.headers).get("Idempotency-Key")).toBeTruthy();
  });

  it("confirms and approves only a submitted PR through the approval endpoint", async () => {
    const approve = vi.fn();
    stubFetch((url, init) => {
      if (url.pathname === `/api/v1/prs/${ids.pr}` && init.method !== "POST") return jsonResponse(prRecord(approve.mock.calls.length ? "approved" : "submitted"));
      if (url.pathname === `/api/v1/prs/${ids.pr}/supplier-quotations`) return jsonResponse({ content: [], page: 0, size: 50, totalElements: 0 });
      if (url.pathname.endsWith("/supplier-quotations/comparison")) return jsonResponse({ prId: ids.pr, lines: [] });
      if (url.pathname === `/api/v1/prs/${ids.pr}/approve`) {
        approve();
        return jsonResponse(prRecord("approved"));
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    });

    renderAt(`/procurement/pr/${ids.pr}`);
    await screen.findByRole("button", { name: "Approve" });
    await userEvent.click(screen.getByRole("button", { name: "Approve" }));
    await userEvent.click(screen.getByRole("button", { name: "Approve PR" }));
    await waitFor(() => expect(approve).toHaveBeenCalledOnce());
    expect(await screen.findAllByText("Approved")).not.toHaveLength(0);
  });

  it("records a supplier offer using the quotation receive contract and shows comparison data", async () => {
    const receive = vi.fn();
    stubFetch((url, init) => {
      if (url.pathname === `/api/v1/supplier-quotations/${ids.quote}`) return jsonResponse(quotation(receive.mock.calls.length ? "received" : "requested"));
      if (url.pathname === `/api/v1/prs/${ids.pr}`) return jsonResponse(prRecord("approved"));
      if (url.pathname.endsWith("/supplier-quotations/comparison")) return jsonResponse(comparison);
      if (url.pathname === `/api/v1/supplier-quotations/${ids.quote}/receive`) {
        receive(JSON.parse(String(init.body)));
        return jsonResponse(quotation("received"));
      }
      throw new Error(`Unexpected request: ${url.pathname}`);
    });

    const user = userEvent.setup();
    renderAt(`/procurement/quote/${ids.quote}`);
    await user.type(await screen.findByLabelText("Steel Sheet unit price"), "125");
    await user.type(screen.getByLabelText("Steel Sheet lead time"), "7");
    await user.click(screen.getByRole("button", { name: "Record Quotation" }));

    await waitFor(() => expect(receive).toHaveBeenCalledOnce());
    expect(receive).toHaveBeenCalledWith({
      validUntil: "2026-12-15",
      lines: [{ prLineId: ids.line, unitPrice: 125, leadTimeDays: 7 }],
    });
  });

  it("creates a Supplier PO only from a selected quotation and retains both links", async () => {
    let created = false;
    const calls: string[] = [];
    stubFetch((url, init) => {
      calls.push(`${init.method ?? "GET"} ${url.pathname}`);
      if (url.pathname === `/api/v1/supplier-quotations/${ids.quote}`) return jsonResponse(quotation("selected"));
      if (url.pathname === `/api/v1/prs/${ids.pr}`) return jsonResponse(prRecord("approved"));
      if (url.pathname.endsWith("/supplier-quotations/comparison")) return jsonResponse(comparison);
      if (url.pathname === "/api/v1/spos" && init.method === "POST") {
        created = true;
        const body = JSON.parse(String(init.body));
        expect(body).toMatchObject({ prId: ids.pr, supplierId: ids.supplier, supplierQuotationId: ids.quote });
        return jsonResponse(spoRecord, 201);
      }
      if (url.pathname === `/api/v1/spos/${ids.spo}`) return jsonResponse(spoRecord);
      throw new Error(`Unexpected request: ${url.pathname}`);
    });

    const user = userEvent.setup();
    renderAt(`/procurement/spo/new?quotation=${ids.quote}`);
    await user.click(await screen.findByRole("button", { name: "Create Supplier PO" }));
    const dialogs = await screen.findAllByRole("dialog");
    await user.click(within(dialogs[dialogs.length - 1]).getByRole("button", { name: "Create Supplier PO" }));
    expect(await screen.findByText("SPO-2026-001")).toBeInTheDocument();
    expect(created).toBe(true);
    expect(calls).toContain(`GET /api/v1/spos/${ids.spo}`);
    expect(screen.getByText("Selected supplier quotation")).toBeInTheDocument();
    expect(screen.getByText("Purchase Requisition")).toBeInTheDocument();
  });

  it("surfaces API errors and rejects incomplete requisition lines before saving", async () => {
    stubFetch((url) => {
      if (url.pathname === "/api/v1/prs") return jsonResponse({ message: "Procurement service unavailable" }, 503);
      if (url.pathname === "/api/v1/items") return jsonResponse({ content: [], page: 0, size: 50, totalElements: 0 });
      if (url.pathname === "/api/v1/uoms") return jsonResponse([]);
      throw new Error(`Unexpected request: ${url.pathname}`);
    });
    const user = userEvent.setup();
    const { unmount } = renderAt("/procurement");
    expect(await screen.findByRole("alert")).toHaveTextContent("Procurement service unavailable");
    unmount();

    renderAt("/procurement/pr/new");
    await user.click(await screen.findByRole("button", { name: "Continue" }));
    expect(await screen.findByText("Choose a required-by date before continuing.")).toBeInTheDocument();
    await user.type(screen.getByLabelText("Required By"), "2026-12-01");
    await user.click(screen.getByRole("button", { name: "Continue" }));
    await user.click(screen.getByRole("button", { name: "Continue" }));
    expect(await screen.findByText(/Choose an item and unit of measure/)).toBeInTheDocument();
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === "POST")).toBe(false);
  });
});
