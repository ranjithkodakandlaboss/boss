import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { signIn } from "../auth/session";
import CustomerPOCreate from "./proto/CustomerPOCreate";
import CustomerEdit from "./proto/CustomerEdit";
import RFQDetail from "./proto/RFQDetail";
import QuotationDetail from "./proto/QuotationDetail";
import CustomerDetail from "./proto/CustomerDetail";
import POAmend from "./proto/POAmend";
import PODetail from "./proto/PODetail";
import RFQCreate from "./proto/RFQCreate";
import QuotationCreate from "./proto/QuotationCreate";
import Sales from "./proto/Sales";
import { SalesProvider, useStore } from "./proto/runtime";
import { salesApi } from "./api";

const customer = {
  id: "c1", code: "NW", name: "North Works", legalName: "North Works",
  gstin: "27AABCU9603R1ZX", pan: "AABCU9603R",
  address: { line1: "10 Industrial Road", city: "Pune", state: "Maharashtra" },
  contacts: [{ name: "Buyer", email: "buyer@example.test", phone: "9999999999", isPrimary: true }],
  status: "active", rating: 3,
};
const items = [
  { id: "fg", code: "FG", name: "Finished shaft", itemType: "FINISHED_GOOD", baseUomId: "u1", uomId: "u1", uomSymbol: "ea" },
  { id: "sf", code: "SF", name: "Semi-finished shaft", itemType: "SEMI_FINISHED", baseUomId: "u1", uomId: "u1" },
  { id: "rm", code: "RM", name: "Raw steel", itemType: "RAW_MATERIAL", baseUomId: "u1", uomId: "u1" },
  { id: "sp", code: "SP", name: "Spare bearing", itemType: "SPARE_PART", baseUomId: "u1", uomId: "u1" },
];
let poCreated = false;
let cancelled = false;
let documentFailure = false;
let inactiveCustomer = false;
let deactivationFailure = false;
let orderStatus = "open";
const uploaded: Record<string, unknown>[] = [];
function json(value: unknown, status = 200) { return new Response(JSON.stringify(value), { status }); }
function page(content: unknown[]) { return json({ content, totalElements: content.length }); }

beforeEach(() => {
  signIn("test-session");
  poCreated = false;
  cancelled = false;
  documentFailure = false;
  inactiveCustomer = false;
  deactivationFailure = false;
  orderStatus = "open";
  uploaded.length = 0;
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/history")) return json([]);
    if (url.includes("/documents")) {
      if (init?.method === "POST") {
        if (documentFailure) return json({ message: "Document storage is unavailable" }, 503);
        const data = init.body as FormData;
        const doc = { id: "d1", title: data.get("title"), docType: "Drawing", category: "Sales Documents",
          version: 1, status: "active", linkedEntities: [{ type: data.get("linkedEntityType"), id: data.get("linkedEntityId"), ref: data.get("linkedEntityRef") }] };
        uploaded.push(doc);
        return json(doc, 201);
      }
      return page(uploaded);
    }
    if (url.includes("/customers")) {
      if (init?.method === "DELETE") return deactivationFailure ? json({ message: "Customer has an active Customer PO" }, 409) : new Response(null, { status: 204 });
      return init?.method === "PATCH" ? json({ ...customer, ...JSON.parse(String(init.body)) }) : page([{ ...customer, status: inactiveCustomer ? "inactive" : "active" }]);
    }
    if (url.includes("/quotations/costing-config")) return json({ labourRates: [], machineRates: [], overheadPerUnit: 0,
      consumablesPerUnit: 0, packagingPerUnit: 0, transportPerUnit: 0, targetMarginPct: 20, minimumMarginPct: 10, approvalThresholdPct: 15 });
    if (url.includes("/quotations")) {
      const quote = { id: "q1", quotationNumber: "QT-1", customerId: "c1", customerName: customer.name,
        revision: 1, status: cancelled ? "cancelled" : "draft", lines: [], negotiationHistory: [] };
      return init?.method === "POST" ? json(quote, 201) : page([quote]);
    }
    if (url.includes("/rfqs")) {
      const rfq = { id: "r1", rfqNumber: "RFQ-1", customerId: "c1", customerName: customer.name,
        status: cancelled ? "cancelled" : "sent", lines: [] };
      return init?.method === "POST" ? json(rfq, 201) : page([rfq]);
    }
    if (url.includes("/cpos") && init?.method === "POST") { poCreated = true; return json({ id: "po1" }, 201); }
    if (url.includes("/cpos")) return page(poCreated ? [{ id: "po1", cpoNumber: "CPO-1", customerId: "c1", customerName: customer.name, status: cancelled ? "cancelled" : orderStatus, lines: [] }] : []);
    if (url.includes("/items")) return page(url.includes("itemType=") ? [items[0]] : items);
    if (url.includes("/uoms")) return page([{ id: "u1", symbol: "ea", name: "Each" }]);
    if (url.includes("/company")) return json({ sites: [{ id: "s1", name: "Main Plant" }] });
    return page([]);
  }));
});

function PoLoaded() {
  const { customerPos } = useStore();
  return <p>{customerPos.some(po => po.id === "po1") ? "Created PO is loaded" : "PO not found"}</p>;
}
function renderSales(path: string) {
  return render(<MemoryRouter initialEntries={[path]}><SalesProvider><Routes>
    <Route path="/sales" element={<Sales />} />
    <Route path="/sales/po/new" element={<CustomerPOCreate />} />
    <Route path="/sales/po/:id" element={<PoLoaded />} />
    <Route path="/sales/po/:id/amend" element={<POAmend />} />
    <Route path="/sales/po/:id/view" element={<PODetail />} />
    <Route path="/sales/customers/:id/edit" element={<CustomerEdit />} />
    <Route path="/sales/customers/:id" element={<p>Customer saved</p>} />
    <Route path="/sales/customers/:id/view" element={<CustomerDetail />} />
    <Route path="/sales/rfq/:id" element={<RFQDetail />} />
    <Route path="/sales/rfq/new" element={<RFQCreate />} />
    <Route path="/sales/quotation/:id" element={<QuotationDetail />} />
    <Route path="/sales/quotation/new" element={<QuotationCreate />} />
  </Routes></SalesProvider></MemoryRouter>);
}

it("offers only finished and semi-finished items on direct Customer POs", async () => {
  renderSales("/sales/po/new");
  expect(await screen.findByRole("option", { name: /Finished shaft/ })).toBeInTheDocument();
  expect(screen.getByRole("option", { name: /Semi-finished shaft/ })).toBeInTheDocument();
  expect(screen.queryByRole("option", { name: /Raw steel/ })).not.toBeInTheDocument();
  expect(screen.queryByRole("option", { name: /Spare bearing/ })).not.toBeInTheDocument();
});

it("reloads the newly created direct PO before opening its detail", async () => {
  renderSales("/sales/po/new");
  await screen.findByRole("option", { name: /Finished shaft/ });
  const selects = screen.getAllByRole("combobox");
  await userEvent.selectOptions(selects[0], "c1");
  await userEvent.selectOptions(selects[1], "s1");
  await userEvent.type(screen.getByPlaceholderText(/Repeat blanket contract/i), "Customer confirmed");
  await userEvent.selectOptions(selects[2], "fg");
  await userEvent.type(screen.getByPlaceholderText("Brief description of the item/work *"), "Finished shaft");
  await userEvent.click(screen.getByRole("button", { name: "Record Direct Customer PO" }));
  expect(await screen.findByText("Created PO is loaded")).toBeInTheDocument();
});

it("round-trips the customer address and rating when editing", async () => {
  renderSales("/sales/customers/c1/edit");
  const address = await screen.findByRole("textbox", { name: "Address" });
  expect(address).toHaveValue("10 Industrial Road");
  await userEvent.selectOptions(screen.getByRole("combobox", { name: /Customer rating/ }), "5");
  await userEvent.click(screen.getByRole("button", { name: "Save Changes" }));
  await screen.findByText("Customer saved");
  const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === "PATCH");
  expect(JSON.parse(String(call?.[1]?.body))).toMatchObject({ rating: 5, address: { line1: "10 Industrial Road" } });
});

it("clears a customer rating without silently restoring the previous rating", async () => {
  renderSales("/sales/customers/c1/edit");
  await userEvent.selectOptions(await screen.findByRole("combobox", { name: /Customer rating/ }), "");
  await userEvent.click(screen.getByRole("button", { name: "Save Changes" }));
  await screen.findByText("Customer saved");
  const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === "PATCH");
  expect(JSON.parse(String(call?.[1]?.body)).rating).toBeNull();
});

it("searches the Customer Directory by GSTIN case-insensitively and ignores GSTIN spaces", async () => {
  renderSales("/sales?tab=customers");
  const search = await screen.findByPlaceholderText("Search customer name, GSTIN or city");
  await userEvent.type(search, "27 aabcu9603r1zx");

  expect(await screen.findByText("27AABCU9603R1ZX")).toBeInTheDocument();
  expect(screen.getByText("1 of 1 customers")).toBeInTheDocument();

  await userEvent.clear(search);
  await userEvent.type(search, "pUnE");
  expect(screen.getByText("27AABCU9603R1ZX")).toBeInTheDocument();
});

it.each(["rfqs", "pos"])("aligns %s line counts with their column header", async tab => {
  poCreated = true;
  renderSales(`/sales?tab=${tab}`);
  const header = await screen.findByRole("columnheader", { name: /Lines/ });
  const table = header.closest("table")!;
  const index = within(table).getAllByRole("columnheader").indexOf(header);
  const cells = within(table).getAllByRole("row")[1].querySelectorAll("td");
  expect(cells[index]).toHaveClass("text-left");
  expect(cells[index]).not.toHaveClass("text-center");
});

it("keeps an inactive customer read-only while allowing explicit reactivation", async () => {
  inactiveCustomer = true;
  renderSales("/sales/customers/c1/view");
  await screen.findByRole("button", { name: "Reactivate" });
  expect(screen.queryByRole("button", { name: "Edit" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: /Add RFQ|Add Quotation|Add Customer PO/ })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Documents" }));
  expect(screen.queryByLabelText("Attach document")).not.toBeInTheDocument();
});

it("blocks the edit URL for an inactive customer", async () => {
  inactiveCustomer = true;
  renderSales("/sales/customers/c1/edit");
  expect(await screen.findByText(/Inactive customers cannot be edited/)).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Save Changes" })).not.toBeInTheDocument();
});

it("reactivates a customer without submitting a profile edit", async () => {
  inactiveCustomer = true;
  renderSales("/sales/customers/c1/view");
  await userEvent.click(await screen.findByRole("button", { name: "Reactivate" }));
  const call = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === "PATCH");
  expect(JSON.parse(String(call?.[1]?.body))).toEqual({ status: "active" });
});

it.each([
  ["/sales/rfq/r1", "Create Quotation"],
  ["/sales/quotation/q1", "Submit to Customer"],
  ["/sales/po/po1/view", "Create Revised PO"],
])("does not offer mutations on an inactive customer's record at %s", async (path, action) => {
  inactiveCustomer = true;
  poCreated = true;
  renderSales(path);
  if (path.includes("/po/")) await screen.findByRole("heading", { name: "CPO-1" });
  else await screen.findByText(path.includes("quotation") ? "QT-1" : "RFQ-1");
  expect(screen.queryByRole("button", { name: action })).not.toBeInTheDocument();
  if (!path.includes("quotation")) await userEvent.click(screen.getByRole("button", { name: /Documents/ }));
  expect(screen.queryByLabelText("Attach document")).not.toBeInTheDocument();
});

it("keeps the customer open and displays a failed deactivation", async () => {
  deactivationFailure = true;
  renderSales("/sales/customers/c1/view");
  await userEvent.click(await screen.findByRole("button", { name: "Deactivate" }));
  await userEvent.click(screen.getAllByRole("button", { name: "Deactivate" })[1]);
  expect(await screen.findByText("Customer has an active Customer PO")).toBeInTheDocument();
  expect(screen.getByRole("heading", { name: "North Works" })).toBeInTheDocument();
});

it("routes pending PO approval through material feasibility rather than reopening the PO", async () => {
  poCreated = true;
  orderStatus = "feasibility_pending";
  renderSales("/sales/po/po1/view");
  await userEvent.click(await screen.findByRole("button", { name: "Approve Customer PO" }));
  expect(screen.getByRole("button", { name: "Confirm" })).toBeInTheDocument();
  expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === "PATCH")).toBe(false);
  expect(screen.queryByText("Customer PO approved")).not.toBeInTheDocument();
});

it("uploads an RFQ attachment and reloads the persisted linked document", async () => {
  renderSales("/sales/rfq/r1");
  await userEvent.click(await screen.findByRole("button", { name: /Documents/ }));
  await userEvent.upload(screen.getByLabelText("Attach document"), new File(["drawing"], "drawing.pdf", { type: "application/pdf" }));
  expect(await screen.findByRole("button", { name: /drawing.pdf/ })).toBeInTheDocument();
  expect(uploaded[0]).toMatchObject({ linkedEntities: [{ type: "RFQ", id: "r1", ref: "RFQ-1" }] });
});

it("rejects Sales attachments above the backend's 10 MB limit before upload", async () => {
  renderSales("/sales/rfq/r1");
  await userEvent.click(await screen.findByRole("button", { name: /Documents/ }));
  const contents = new Uint8Array(10 * 1024 * 1024 + 1);
  await userEvent.upload(
    screen.getByLabelText("Attach document"),
    new File([contents], "oversized.pdf", { type: "application/pdf" }),
  );

  expect(await screen.findByRole("alert")).toHaveTextContent("no larger than 10 MB");
  expect(vi.mocked(fetch).mock.calls.filter(([url, init]) =>
    String(url).includes("/documents") && init?.method === "POST",
  )).toHaveLength(0);
});

it.each([
  ["/sales/quotation/q1", "Quotation", "q1", "QT-1"],
  ["/sales/customers/c1/view", "Customer", "c1", "North Works"],
  ["/sales/po/po1/amend", "CustomerPO", "po1", "CPO-1"],
])("persists attachments from %s with the correct linked record", async (path, type, id, ref) => {
  poCreated = true;
  renderSales(path);
  if (type === "Customer") await userEvent.click(await screen.findByRole("button", { name: "Documents" }));
  await userEvent.upload(await screen.findByLabelText("Attach document"), new File(["drawing"], "evidence.pdf", { type: "application/pdf" }));
  expect(await screen.findByRole("button", { name: /evidence.pdf/ })).toBeInTheDocument();
  expect(uploaded[0]).toMatchObject({ linkedEntities: [{ type, id, ref }] });
});

it("shows an attachment storage failure instead of claiming upload success", async () => {
  documentFailure = true;
  renderSales("/sales/rfq/r1");
  await userEvent.click(await screen.findByRole("button", { name: /Documents/ }));
  await userEvent.upload(screen.getByLabelText("Attach document"), new File(["drawing"], "drawing.pdf", { type: "application/pdf" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("Document storage is unavailable");
  expect(uploaded).toHaveLength(0);
});

it("uploads selected documents only after creating the RFQ record", async () => {
  const { container } = renderSales("/sales/rfq/new?customer=c1");
  await screen.findByRole("option", { name: "North Works" });
  fireEvent.change(container.querySelectorAll('input[type="date"]')[1], { target: { value: "2026-12-01" } });
  await userEvent.click(screen.getByRole("button", { name: "Next" }));
  await userEvent.selectOptions(screen.getAllByRole("combobox")[0], "fg");
  await userEvent.type(screen.getByPlaceholderText("DWG-TC-001"), "DRG-1");
  await userEvent.click(screen.getByRole("button", { name: "Next" }));
  await userEvent.click(screen.getByRole("button", { name: "Next" }));
  await userEvent.upload(screen.getByLabelText("RFQ documents"), new File(["drawing"], "rfq-evidence.pdf", { type: "application/pdf" }));
  expect(uploaded).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "Next" }));
  await userEvent.click(screen.getByRole("button", { name: "Submit RFQ" }));
  await userEvent.click(await screen.findByRole("button", { name: /Documents/ }));
  expect(await screen.findByRole("button", { name: /rfq-evidence.pdf/ })).toBeInTheDocument();
  expect(uploaded[0]).toMatchObject({ linkedEntities: [{ type: "RFQ", id: "r1", ref: "RFQ-1" }] });
});

it("uploads selected documents only after creating the quotation record", async () => {
  renderSales("/sales/quotation/new?customer=c1");
  await screen.findByRole("option", { name: /Finished shaft/ });
  await userEvent.selectOptions(screen.getAllByRole("combobox")[2], "fg");
  await userEvent.upload(screen.getByLabelText("Quotation documents"), new File(["drawing"], "quote-evidence.pdf", { type: "application/pdf" }));
  expect(uploaded).toHaveLength(0);
  await userEvent.click(screen.getByRole("button", { name: "Save Quotation" }));
  expect(await screen.findByRole("button", { name: /quote-evidence.pdf/ })).toBeInTheDocument();
  expect(uploaded[0]).toMatchObject({ linkedEntities: [{ type: "Quotation", id: "q1", ref: "QT-1" }] });
});

it("does not turn cancelled quotations into actionable drafts", async () => {
  cancelled = true;
  renderSales("/sales/quotation/q1");
  expect(await screen.findByText("Cancelled")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Submit to Customer" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Revise Quotation" })).not.toBeInTheDocument();
});

it("does not offer copying a cancelled RFQ or uploading to it", async () => {
  cancelled = true;
  renderSales("/sales/rfq/r1");
  await screen.findByText("RFQ-1");
  expect(screen.queryByRole("button", { name: "Copy RFQ" })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: /Documents/ }));
  expect(screen.queryByLabelText("Attach document")).not.toBeInTheDocument();
});

it("blocks material reservations, amendments, invoices and uploads on cancelled POs", async () => {
  cancelled = true;
  poCreated = true;
  renderSales("/sales/po/po1/view");
  await screen.findByRole("heading", { name: "CPO-1" });
  expect(screen.getByRole("button", { name: "Confirm & Reserve" })).toBeDisabled();
  expect(screen.queryByRole("button", { name: "Create Revised PO" })).not.toBeInTheDocument();
  expect(screen.queryByRole("link", { name: /Create Customer Invoice/ })).not.toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Documents" }));
  expect(screen.queryByLabelText("Attach document")).not.toBeInTheDocument();
});

it("downloads the saved Customer PO document from the PO detail", async () => {
  poCreated = true;
  const download = vi.spyOn(salesApi, "customerPoPdf").mockResolvedValue();
  try {
    renderSales("/sales/po/po1/view");
    await userEvent.click(await screen.findByRole("button", { name: "PDF / Print" }));
    expect(download).toHaveBeenCalledWith("po1");
  } finally {
    download.mockRestore();
  }
});
