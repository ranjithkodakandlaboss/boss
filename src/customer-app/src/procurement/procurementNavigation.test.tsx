import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { signIn, signOut } from "../auth/session";
import GRNCreate from "./proto/GRNCreate";
import GRNDetail from "./proto/GRNDetail";
import IncomingInspection from "./proto/IncomingInspection";
import PRCreate from "./proto/PRCreate";
import PRDetail from "./proto/PRDetail";
import ProcurementHome from "./proto/Procurement";
import SupplierDetail from "./proto/SupplierDetail";
import SupplierPOAmend from "./proto/SupplierPOAmend";
import SupplierRFQCreate from "./proto/SupplierRFQCreate";
import SupplierQuotationDetail from "./proto/SupplierQuotationDetail";
import SupplierPODetail from "./proto/SupplierPODetail";
import SupplierReturn from "./proto/SupplierReturn";
import Suppliers from "./proto/Suppliers";
import { ProcurementProvider } from "./proto/runtime";

const supplierId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const prId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const lineId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const itemId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const uomId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
const siteId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
const storeId = "12121212-1212-4121-8121-121212121212";
const requestId = "33333333-3333-4333-8333-333333333333";
const quoteId = "34343434-3434-4434-8434-343434343434";
const spoId = "44444444-4444-4444-8444-444444444444";
const spoLineId = "45454545-4545-4454-8454-454545454545";
const grnId = "55555555-5555-4555-8555-555555555555";
const grnLineId = "56565656-5656-4565-8565-565656565656";
const lotId = "57575757-5757-4575-8575-575757575757";
const inspectionId = "58585858-5858-4585-8585-585858585858";

const suppliers = [
  { id: supplierId, code: "STEEL", name: "Steel House", legalName: "Steel House Pvt Ltd", status: "active", address: { city: "Pune", state: "MH", category: "Steel" }, paymentTerms: 30, contacts: [{ name: "Asha", email: "asha@steel.test", phone: "999", isPrimary: true }] },
];
const pr = {
  id: prId,
  prNumber: "PR-100",
  siteId,
  originType: "manual",
  status: "approved",
  priority: "normal",
  requiredDate: "2026-12-01",
  createdAt: "2026-11-17T00:00:00Z",
  lines: [{ id: lineId, itemId, itemCode: "SHAFT", itemName: "Shaft", qty: 4, uomId, uomSymbol: "EA" }],
};
const pendingPr = { ...pr, id: "abababab-abab-4aba-8aba-abababababab", prNumber: "PR-200", status: "submitted" };
const requested = { id: requestId, prId, supplierId, supplierName: "Steel House", status: "requested", lines: [] };
const received = {
  id: quoteId, prId, supplierId, supplierName: "Steel House", status: "received", supplierQuoteRef: "QT-9",
  lines: [{ id: "ql", prLineId: lineId, itemId, quantity: 7, uomId, uomSymbol: "BOX", unitPrice: 12, leadTimeDays: 5 }],
};
const spo = {
  id: spoId, spoNumber: "SPO-1", prId, supplierId, supplierName: "Steel House", supplierQuotationId: quoteId, status: "issued",
  lines: [{ id: spoLineId, itemId, itemCode: "SHAFT", itemName: "Shaft", orderedQty: 4, receivedQty: 0, unitPrice: 12 }],
};
const grn = {
  id: grnId, grnNumber: "GRN-1", spoId, spoNumber: "SPO-1", supplierId, supplierName: "Steel House", siteId, status: "draft", receivedDate: "2026-11-17",
  lines: [{ id: grnLineId, spoLineId, itemId, itemCode: "SHAFT", itemName: "Shaft", receivedQty: 4, acceptedQty: 0, rejectedQty: 0, uomSymbol: "EA", lotIds: [lotId], lotNumbers: ["LOT-1"], lotStatuses: ["quarantine"], lotQuantities: [4] }],
};

function page(content: unknown[]) {
  return JSON.stringify({ content, totalElements: content.length });
}
function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status });
}

function installFetch() {
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/suppliers") && init?.method === "POST") return Promise.resolve(json({ id: supplierId, status: "active" }, 201));
    if (url.includes("/suppliers")) return Promise.resolve(json(JSON.parse(page(url.includes("status=inactive") ? [] : suppliers))));
    if (url.includes("/items") || url.includes("/uoms")) return Promise.resolve(json(JSON.parse(page([{ id: itemId, code: "SHAFT", name: "Shaft", uomId, symbol: "EA", uomSymbol: "EA" }]))));
    if (url.includes("/company")) return Promise.resolve(json({ companyName: "North Works", sites: [{ id: siteId, name: "Plant", code: "PUNE" }], stores: [{ id: storeId, siteId, name: "Raw Store", code: "RAW", siteCode: "PUNE", storeType: "raw_material" }] }));
    if (url.includes("/supplier-quotations") && init?.method === "POST" && url.includes("/receive")) return Promise.resolve(json({ ...received, status: "received" }));
    if (url.includes(`/prs/${prId}/supplier-quotations`) && init?.method === "POST") return Promise.resolve(json(requested, 201));
    if (url.includes("/supplier-quotations")) return Promise.resolve(json(JSON.parse(page([requested, received]))));
    if (url.includes("/amendments")) return Promise.resolve(json([]));
    if (url.includes("/inspections")) return Promise.resolve(json([{ id: inspectionId, inspectionNumber: "IN-1", grnId, lotId, itemId, quantityInspected: 4, status: "pending" }]));
    if (url.includes("/grns")) return Promise.resolve(json(JSON.parse(page([grn]))));
    if (url.includes("/spos")) return Promise.resolve(json(JSON.parse(page([spo]))));
    if (url.includes("/prs")) return Promise.resolve(json(JSON.parse(page([pr, pendingPr]))));
    if (url.includes("/supplier-performance")) return Promise.resolve(json({ supplierId, totalReceipts: 0 }));
    return Promise.resolve(json({}));
  }));
}

beforeEach(() => {
  signIn("test-session");
  installFetch();
  return () => signOut();
});

function show(path: string, element: JSX.Element) {
  const pattern = path.split("?")[0].replace(/[0-9a-f-]{36}/gi, ":id");
  return render(
    <MemoryRouter initialEntries={[path]}>
      <ProcurementProvider>
        <Routes>
          <Route path={pattern} element={element} />
        </Routes>
      </ProcurementProvider>
    </MemoryRouter>,
  );
}

it("keeps an unanswered invitation on the record-quote form instead of an empty quotation page", async () => {
  show(`/procurement/pr/${prId}`, <PRDetail />);
  expect((await screen.findAllByRole("button", { name: "Record Quote" })).length).toBeGreaterThan(0);
  expect(screen.queryByRole("link", { name: "View Quote" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "View Quote" })).not.toBeInTheDocument();
});

it("sends a quotation request and records the supplier price", async () => {
  const view = show(`/procurement/supplier-rfq/new?mode=invite&pr=${prId}`, <SupplierRFQCreate />);
  const supplier = await screen.findByText("Steel House");
  await userEvent.click(within(supplier.closest("label") as HTMLElement).getByRole("checkbox"));
  await userEvent.click(screen.getByRole("button", { name: /Review Recipients/ }));
  await userEvent.click(screen.getByRole("button", { name: /Confirm & Send Quotation Requests/ }));
  expect(await screen.findByText("Quotation requests sent")).toBeInTheDocument();
  const request = vi.mocked(fetch).mock.calls.find((call) => String(call[0]).includes("/supplier-quotations") && call[1]?.method === "POST");
  expect(JSON.parse(String(request?.[1]?.body))).toEqual({ supplierId });
  view.unmount();

  show(`/procurement/supplier-rfq/new?mode=quote&pr=${prId}`, <SupplierRFQCreate />);
  expect(await screen.findByRole("heading", { name: "Record Supplier Quotation" })).toBeInTheDocument();
  const price = screen.getAllByRole("spinbutton").find((input) => input.getAttribute("value") === "0" && input.className.includes("w-28"));
  expect(price).toBeTruthy();
  await userEvent.clear(price as HTMLElement);
  await userEvent.type(price as HTMLElement, "12");
  await userEvent.selectOptions(screen.getByDisplayValue("Select supplier…").closest("select") as HTMLElement, supplierId);
  await userEvent.click(screen.getByRole("button", { name: "Save Supplier Quotation" }));
  expect(await screen.findByText("Supplier quotation recorded")).toBeInTheDocument();
  const receive = vi.mocked(fetch).mock.calls.find((call) => String(call[0]).includes("/receive"));
  expect(JSON.parse(String(receive?.[1]?.body)).lines[0]).toMatchObject({ prLineId: lineId, unitPrice: 12 });
});

it("opens the quotation, supplier order, receipt, inspection, and return screens", async () => {
  const pages: Array<[string, JSX.Element, string]> = [
    [`/procurement/quote/${quoteId}`, <SupplierQuotationDetail />, "Steel House"],
    [`/procurement/spo/${spoId}`, <SupplierPODetail />, "SPO-1"],
    [`/procurement/grn/new?spo=${spoId}`, <GRNCreate />, "Raw Store"],
    [`/procurement/grn/${grnId}`, <GRNDetail />, "GRN-1"],
    [`/quality/inspection/${inspectionId}`, <IncomingInspection />, "IN-1"],
    ["/procurement/supplier-return", <SupplierReturn />, "Supplier Return"],
    ["/procurement/pr/new", <PRCreate />, "New Purchase Requisition"],
    [`/procurement/pr/${prId}/edit`, <PRCreate />, "Edit PR-100"],
    [`/procurement/spo/${spoId}/amend`, <SupplierPOAmend />, "Amend Supplier PO"],
    ["/suppliers", <Suppliers />, "Steel House"],
    [`/suppliers/${supplierId}`, <SupplierDetail />, "Steel House"],
  ];
  for (const [path, element, text] of pages) {
    const view = show(path, element);
    expect((await screen.findAllByText(text)).length, path).toBeGreaterThan(0);
    view.unmount();
  }
});

it("shows the persisted quotation quantity and unit rather than the current PR values", async () => {
  show(`/procurement/quote/${quoteId}`, <SupplierQuotationDetail />);
  const material = await screen.findByText("SHAFT");
  const row = within(material.closest("tr") as HTMLElement);
  expect(row.getByText("7")).toBeInTheDocument();
  expect(row.getByText("BOX")).toBeInTheDocument();
  expect(row.getByText("₹84")).toBeInTheDocument();
});

it("opens every procurement list, including a purchase requisition waiting for approval", async () => {
  show("/procurement?tab=pr", <ProcurementHome />);
  expect(await screen.findByRole("button", { name: /Purchase Requisitions/ })).toBeInTheDocument();
  expect(screen.getByText("PR-100")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /Supplier Quotations/ }));
  expect(screen.getAllByText("QT-9").length).toBeGreaterThan(0);
  await userEvent.click(screen.getByRole("button", { name: /Supplier POs/ }));
  expect(screen.getAllByText("SPO-1").length).toBeGreaterThan(0);
  await userEvent.click(screen.getByRole("button", { name: /Receiving & Inventory/ }));
  expect(screen.getByText("GRN-1")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /Approvals/ }));
  expect(screen.getByRole("button", { name: "PR-200" })).toBeInTheDocument();
  expect(screen.getByText("Purchase Requisition")).toBeInTheDocument();
});
