import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { signIn, signOut } from "../auth/session";
import PoDetailPage from "../sales/proto/PODetail";
import { SalesProvider } from "../sales/proto/runtime";
import ProcurementHome from "./proto/Procurement";
import SupplierRFQCreate from "./proto/SupplierRFQCreate";
import { ProcurementProvider, useStore } from "./proto/runtime";
import Suppliers from "./proto/Suppliers";

const supplierId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const prId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const lineId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const itemId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const uomId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
const siteId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
const poId = "11111111-1111-4111-8111-111111111111";
const poLineId = "22222222-2222-4222-8222-222222222222";
const quoteId = "33333333-3333-4333-8333-333333333333";
const spoId = "44444444-4444-4444-8444-444444444444";

const suppliers = [
  { id: supplierId, code: "STEEL", name: "Steel House", legalName: "Steel House Pvt Ltd", status: "active", address: { city: "Pune", state: "MH", category: "Raw Material" }, paymentTerms: 30, contacts: [] },
  { id: "99999999-9999-4999-8999-999999999999", code: "BOLT", name: "Bolt Co", legalName: "Bolt Co", status: "active", address: { city: "Chennai", state: "TN" }, contacts: [] },
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

function page(content: unknown[]) {
  return JSON.stringify({ content, totalElements: content.length });
}

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status });
}

function installFetch(handler: (url: string, init?: RequestInit) => Response) {
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => Promise.resolve(handler(String(input), init))));
}

function procurementFetch(url: string, init?: RequestInit) {
  if (url.includes("/suppliers") && init?.method === "POST") {
    const body = JSON.parse(String(init.body));
    return json({ id: supplierId, ...body, status: "active" }, 201);
  }
  if (url.includes("/suppliers")) return json(JSON.parse(page(url.includes("status=inactive") ? [] : suppliers)));
  if (url.includes("/prs") && init?.method === "POST") return json(pr, 201);
  if (url.includes("/prs")) return json(JSON.parse(page([pr])));
  if (url.includes("/spos")) return json(JSON.parse(page([])));
  if (url.includes("/items") || url.includes("/uoms")) return json(JSON.parse(page([])));
  if (url.includes("/company")) return json({ companyName: "North Works", sites: [{ id: siteId, name: "Plant" }], stores: [] });
  if (url.includes("supplier-quotations")) return json(JSON.parse(page([])));
  return json({});
}

beforeEach(() => {
  signIn("test-session");
  delete document.body.dataset.spo;
  delete document.body.dataset.spoError;
  installFetch(procurementFetch);
  return () => signOut();
});

function renderSuppliers() {
  return render(
    <MemoryRouter initialEntries={["/suppliers"]}>
      <ProcurementProvider>
        <Routes>
          <Route path="/suppliers" element={<Suppliers />} />
          <Route path="/suppliers/:id" element={<p>Supplier opened</p>} />
        </Routes>
      </ProcurementProvider>
    </MemoryRouter>,
  );
}

it("lists suppliers and filters by trading name", async () => {
  renderSuppliers();
  expect(await screen.findByRole("heading", { name: "Suppliers" })).toBeInTheDocument();
  expect(screen.getAllByText("Steel House").length).toBeGreaterThan(0);
  expect(screen.getAllByText("Bolt Co").length).toBeGreaterThan(0);
  await userEvent.type(screen.getByPlaceholderText("Search suppliers…"), "steel");
  expect(screen.getAllByText("Steel House").length).toBeGreaterThan(0);
  expect(screen.queryAllByText("Bolt Co")).toHaveLength(0);
});

it("rejects an invalid GSTIN before calling the supplier API", async () => {
  renderSuppliers();
  await userEvent.click(await screen.findByRole("button", { name: "Add Supplier" }));
  const dialog = screen.getByRole("dialog", { name: "Add Supplier" });
  const fields = within(dialog).getAllByRole("textbox");
  await userEvent.type(fields[0], "Steel House Pvt Ltd");
  await userEvent.type(fields[2], "Pune");
  await userEvent.type(fields[4], "NOT-A-GSTIN");
  await userEvent.click(within(dialog).getByRole("button", { name: "Save Supplier" }));
  expect(screen.getByText("Enter a valid GSTIN")).toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls.some((call) => String(call[0]).includes("/suppliers") && call[1]?.method === "POST")).toBe(false);
});

it("creates a supplier and shows the API error when the save is rejected", async () => {
  renderSuppliers();
  await userEvent.click(await screen.findByRole("button", { name: "Add Supplier" }));
  const dialog = screen.getByRole("dialog", { name: "Add Supplier" });
  const fields = within(dialog).getAllByRole("textbox");
  await userEvent.type(fields[0], "Steel House Pvt Ltd");
  await userEvent.type(fields[2], "Pune");
  await userEvent.type(fields[4], "27AAPFU0939F1ZV");
  await userEvent.click(within(dialog).getByRole("button", { name: "Save Supplier" }));
  expect(await screen.findByText("Supplier added")).toBeInTheDocument();
  const create = vi.mocked(fetch).mock.calls.find((call) => String(call[0]).includes("/suppliers") && call[1]?.method === "POST");
  expect(JSON.parse(String(create?.[1]?.body)).name).toBe("Steel House Pvt Ltd");
  expect((create?.[1]?.headers as Headers).get("Idempotency-Key")).toBeTruthy();

  installFetch((url, init) => {
    if (url.includes("/suppliers") && init?.method === "POST") return json({ message: "Supplier code already exists" }, 409);
    return procurementFetch(url, init);
  });
  await userEvent.click(screen.getByRole("button", { name: "Add Supplier" }));
  const again = screen.getByRole("dialog", { name: "Add Supplier" });
  const retryFields = within(again).getAllByRole("textbox");
  await userEvent.type(retryFields[0], "Steel House Pvt Ltd");
  await userEvent.type(retryFields[2], "Pune");
  await userEvent.click(within(again).getByRole("button", { name: "Save Supplier" }));
  expect(await screen.findByText("Supplier code already exists")).toBeInTheDocument();
});

it("opens a supplier from the directory", async () => {
  renderSuppliers();
  await userEvent.click((await screen.findAllByText("Steel House"))[0]);
  expect(await screen.findByText("Supplier opened")).toBeInTheDocument();
});

it("shows purchase requisitions loaded from the API", async () => {
  render(
    <MemoryRouter initialEntries={["/procurement"]}>
      <ProcurementProvider>
        <ProcurementHome />
      </ProcurementProvider>
    </MemoryRouter>,
  );
  expect(await screen.findByRole("link", { name: "PR-100" })).toBeInTheDocument();
});

const customerPo = {
  id: poId,
  cpoNumber: "CPO-9",
  customerId: "c1",
  customerName: "North Works",
  status: "open",
  requiredByDate: "2026-12-15",
  lines: [{ id: poLineId, itemId, itemCode: "SHAFT", itemName: "Shaft", description: "Shaft", qty: 4, uomSymbol: "EA", unitPrice: 10, lineTotal: 40 }],
};

function salesFetch(url: string, init?: RequestInit, created?: { count: number }, feasibleMaterials?: Array<{ itemId: string; storeId?: string; requested: number; reserved: number; shortfall: number }>) {
  if (url.includes(`/cpos/${poId}/material-availability`)) return json({ cpoId: poId, materials: [{ itemId, itemCode: "SHAFT", itemName: "Shaft", uomSymbol: "EA", requiredQty: 4, eligibleStockQty: feasibleMaterials ? 0 : 4, reservedQty: 0, availableQty: feasibleMaterials ? 0 : 4, shortageQty: feasibleMaterials ? 4 : 0 }] });
  if (url.includes(`/cpos/${poId}/feasibility`) && init?.method === "POST") return json({ cpoId: poId, feasible: !feasibleMaterials, materials: feasibleMaterials ?? [{ itemId, storeId: undefined, requested: 4, reserved: 4, shortfall: 0 }] });
  if (url.includes("/cpos")) return json(JSON.parse(page([customerPo])));
  if (url.includes("/items")) return json(JSON.parse(page([{ id: itemId, code: "SHAFT", name: "Shaft", uomId, uomSymbol: "EA", status: "active", itemType: "FINISHED_GOOD" }])));
  if (url.includes("/uoms")) return json(JSON.parse(page([{ id: uomId, symbol: "EA" }])));
  if (url.includes("/company")) return json({ companyName: "North Works", sites: [{ id: siteId, name: "Plant" }], stores: [{ id: "55555555-5555-4555-8555-555555555555", siteId, name: "Main", code: "MAIN", siteCode: "P1" }] });
  if (url.includes("/prs") && init?.method === "POST") {
    if (created) created.count += 1;
    return json({ ...pr, id: prId, prNumber: "PR-SHORT", originType: "customer_po", customerPoId: poId, status: "draft" }, 201);
  }
  if (url.includes("/prs")) return json(JSON.parse(page([])));
  if (url.includes("/spos") || url.includes("/customers") || url.includes("/rfqs") || url.includes("/quotations") || url.includes("/boms") || url.includes("/reservations")) return json(JSON.parse(page([])));
  return json({});
}

function renderPo() {
  return render(
    <MemoryRouter initialEntries={[`/sales/po/${poId}`]}>
      <SalesProvider>
        <Routes>
          <Route path="/sales/po/:id" element={<PoDetailPage />} />
        </Routes>
      </SalesProvider>
    </MemoryRouter>,
  );
}

it("does not create a purchase requisition when all materials are available", async () => {
  // Feasibility returns feasible=true with no shortfalls — no PR should be created
  installFetch((url, init) => salesFetch(url, init));
  renderPo();
  expect((await screen.findAllByText("CPO-9")).length).toBeGreaterThan(0);
  await userEvent.click(screen.getByRole("button", { name: "Confirm & Reserve" }));
  await userEvent.click(screen.getByRole("button", { name: "Confirm" }));
  expect((await screen.findAllByText(/All materials are available/)).length).toBeGreaterThan(0);
  expect(vi.mocked(fetch).mock.calls.some((call) => String(call[0]).includes("/prs") && call[1]?.method === "POST")).toBe(false);
  expect(vi.mocked(fetch).mock.calls.some((call) => String(call[0]).includes(`/cpos/${poId}/feasibility`) && call[1]?.method === "POST")).toBe(true);
});

it("creates one linked purchase requisition for BOM material shortfalls and uses idempotency key", async () => {
  const created = { count: 0 };
  // Feasibility returns feasible=false with shortfall on SHAFT item
  const shortfallMaterials = [{ itemId, storeId: undefined, requested: 4, reserved: 0, shortfall: 4 }];
  installFetch((url, init) => salesFetch(url, init, created, shortfallMaterials));
  renderPo();
  expect((await screen.findAllByText("CPO-9")).length).toBeGreaterThan(0);
  await userEvent.click(screen.getByRole("button", { name: "Confirm & Reserve" }));
  await userEvent.click(screen.getByRole("button", { name: "Confirm" }));
  expect((await screen.findAllByText(/PR-SHORT/)).length).toBeGreaterThan(0);
  const posts = () => vi.mocked(fetch).mock.calls.filter((call) => String(call[0]).includes("/prs") && call[1]?.method === "POST");
  expect(posts()).toHaveLength(1);
  const body = JSON.parse(String(posts()[0][1]?.body));
  expect(body.originType).toBe("customer_po");
  expect(body.customerPoId).toBe(poId);
  // PR lines must be for the raw material (itemId), not the finished product
  expect(body.lines[0].itemId).toBe(itemId);
  expect(body.lines[0].qty).toBe(4);
  // Idempotency key must be stable across retries
  const key = (posts()[0][1]?.headers as Headers).get("Idempotency-Key");
  expect(key).toBeTruthy();
  await userEvent.click(screen.getByRole("button", { name: "Confirm & Reserve" }));
  await userEvent.click(screen.getByRole("button", { name: "Confirm" }));
  expect(await screen.findAllByText(/PR-SHORT/)).not.toHaveLength(0);
  expect(posts()).toHaveLength(2);
  expect((posts()[1][1]?.headers as Headers).get("Idempotency-Key")).toBe(key);
  expect(created.count).toBe(2);
});

function renderSupplierRfq(mode: "invite" | "quote") {
  return render(
    <MemoryRouter initialEntries={[`/procurement/supplier-rfq/new?mode=${mode}&pr=${prId}`]}>
      <ProcurementProvider>
        <Routes>
          <Route path="/procurement/supplier-rfq/new" element={<SupplierRFQCreate />} />
        </Routes>
      </ProcurementProvider>
    </MemoryRouter>,
  );
}

it("opens invite and record-quotation for a purchase requisition whose lines are catalog items", async () => {
  installFetch((url, init) => {
    if (url.includes("/items") || url.includes("/uoms")) {
      return json(JSON.parse(page([{ id: itemId, code: "SHAFT", name: "Shaft", uomId, symbol: "EA", uomSymbol: "EA" }])));
    }
    return procurementFetch(url, init);
  });
  const { unmount } = renderSupplierRfq("invite");
  expect(await screen.findByRole("heading", { name: "Invite Suppliers for Quotation" })).toBeInTheDocument();
  expect(screen.getByText("Steel House")).toBeInTheDocument();
  unmount();
  renderSupplierRfq("quote");
  expect(await screen.findByRole("heading", { name: "Record Supplier Quotation" })).toBeInTheDocument();
  expect(screen.getByText("Shaft")).toBeInTheDocument();
});

function QuoteHarness() {
  const { purchaseReqs, addSupplierQuotation, approveFinalizeQuotation, generateSPOFromQuotation } = useStore();
  return (
    <div>
      <p>{purchaseReqs[0]?.prNumber}</p>
      <button onClick={() => addSupplierQuotation({
        prId,
        supplierId,
        supplierName: "Steel House",
        validUntil: "2026-12-20",
        deliveryDays: 5,
        lines: [{ prLineId: lineId, unitPrice: 12, leadTimeDays: 5 }],
      })}>Record quotation</button>
      <button onClick={() => approveFinalizeQuotation(quoteId)}>Select quotation</button>
      <button onClick={async () => {
        const result = await generateSPOFromQuotation(quoteId);
        if (result.spo) document.body.dataset.spo = result.spo.poNumber;
        if (result.error) document.body.dataset.spoError = result.error;
      }}>Create SPO</button>
    </div>
  );
}

it("records a quotation, selects it, and creates the supplier purchase order from that quotation", async () => {
  let quoteStatus = "requested";
  installFetch((url, init) => {
    if (url.includes("/suppliers")) return json(JSON.parse(page(url.includes("status=inactive") ? [] : suppliers)));
    if (url.includes("/items") || url.includes("/uoms")) return json(JSON.parse(page([{ id: itemId, code: "SHAFT", name: "Shaft", uomId, symbol: "EA", uomSymbol: "EA" }])));
    if (url.includes("/company")) return json({ companyName: "North Works", sites: [{ id: siteId, name: "Plant" }], stores: [] });
    if (url.includes("/supplier-quotations/comparison")) {
      return json({ prId, lines: [{ prLineId: lineId, itemId, quantity: 4, offers: [{ supplierQuotationId: quoteId, supplierId, unitPrice: 12, leadTimeDays: 5 }] }] });
    }
    if (url.endsWith(`/supplier-quotations/${quoteId}/review`) && init?.method === "POST") {
      quoteStatus = "under_review";
      return json({ id: quoteId, prId, supplierId, status: quoteStatus, lines: [{ id: "l", prLineId: lineId, itemId, unitPrice: 12, leadTimeDays: 5 }] });
    }
    if (url.endsWith(`/supplier-quotations/${quoteId}/select`) && init?.method === "POST") {
      quoteStatus = "selected";
      return json({ id: quoteId, prId, supplierId, status: quoteStatus, lines: [{ id: "l", prLineId: lineId, itemId, unitPrice: 12, leadTimeDays: 5 }] });
    }
    if (url.includes(`/prs/${prId}/supplier-quotations`) && init?.method === "POST") {
      return json({ id: quoteId, prId, supplierId, status: "requested" }, 201);
    }
    if (url.includes(`/supplier-quotations/${quoteId}/receive`) && init?.method === "POST") {
      quoteStatus = "received";
      return json({ id: quoteId, prId, supplierId, supplierName: "Steel House", status: "received", lines: [{ id: "l", prLineId: lineId, itemId, unitPrice: 12, leadTimeDays: 5 }] });
    }
    if (url.includes(`/supplier-quotations/${quoteId}`)) {
      return json({ id: quoteId, prId, supplierId, status: quoteStatus, lines: [{ id: "l", prLineId: lineId, itemId, unitPrice: 12, leadTimeDays: 5 }] });
    }
    if (url.includes("/supplier-quotations")) return json(JSON.parse(page([{ id: quoteId, prId, supplierId, status: "requested", lines: [] }])));
    if (url.includes("/spos") && init?.method === "POST") {
      const body = JSON.parse(String(init.body));
      return json({ id: spoId, spoNumber: "SPO-1", ...body, status: "draft", lines: body.lines }, 201);
    }
    if (url.includes("/spos")) return json(JSON.parse(page([])));
    if (url.includes("/prs")) return json(JSON.parse(page([pr])));
    return json({});
  });
  render(
    <MemoryRouter>
      <ProcurementProvider>
        <QuoteHarness />
      </ProcurementProvider>
    </MemoryRouter>,
  );
  expect(await screen.findByText("PR-100")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Record quotation" }));
  expect(await screen.findByText("Supplier quotation recorded")).toBeInTheDocument();
  const receive = vi.mocked(fetch).mock.calls.find((call) => String(call[0]).includes("/receive"));
  expect(JSON.parse(String(receive?.[1]?.body)).lines[0]).toMatchObject({ prLineId: lineId, unitPrice: 12, leadTimeDays: 5 });
  await userEvent.click(screen.getByRole("button", { name: "Select quotation" }));
  expect(await screen.findByText("Quotation selected")).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Create SPO" }));
  expect(await screen.findByText("Quotation selected")).toBeInTheDocument();
  await vi.waitFor(() => expect(document.body.dataset.spo).toBe("SPO-1"));
  const spo = vi.mocked(fetch).mock.calls.find((call) => String(call[0]).includes("/spos") && call[1]?.method === "POST");
  expect(JSON.parse(String(spo?.[1]?.body))).toMatchObject({ supplierId, prId, supplierQuotationId: quoteId });
  expect((spo?.[1]?.headers as Headers).get("Idempotency-Key")).toBe(`spo-from:${quoteId}`);
});
