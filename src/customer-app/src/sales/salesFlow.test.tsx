import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { signIn, signOut } from "../auth/session";
import { QuotationDetailPage } from "./SalesPages";

const quotation = {
  id: "q1",
  quotationNumber: "QT-1",
  rfqId: "r1",
  rfqNumber: "RFQ-1",
  customerId: "c1",
  customerName: "North Works",
  revision: 2,
  status: "sent",
  validUntil: "2026-12-01",
  lines: [{ id: "l1", itemId: "i1", uomId: "u1", itemName: "Shaft", qty: 2, unitPrice: 20, lineTotal: 40, uomSymbol: "ea" }],
  negotiationHistory: [],
  revisions: [{ id: "q0", revision: 1, status: "superseded" }, { id: "q1", revision: 2, status: "sent" }],
  paymentTerms: 30,
};

beforeEach(() => {
  signIn("test-session");
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo) => {
    const url = String(input);
    if (url.endsWith("/accept")) {
      return Promise.resolve(new Response(JSON.stringify({ id: "po1", cpoNumber: "CPO-1" }), { status: 201 }));
    }
    if (url.includes("/quotations/q1")) return Promise.resolve(new Response(JSON.stringify(quotation), { status: 200 }));
    return Promise.resolve(new Response("{}", { status: 200 }));
  }));
  return () => signOut();
});

it("records owner acceptance against the open revision and creates one Customer PO", async () => {
  render(
    <MemoryRouter initialEntries={["/sales/quotation/q1"]}>
      <Routes>
        <Route path="/sales/quotation/:id" element={<QuotationDetailPage />} />
        <Route path="/sales/po/:id" element={<p>PO opened</p>} />
      </Routes>
    </MemoryRouter>,
  );
  expect(await screen.findByRole("heading", { name: /QT-1/ })).toBeInTheDocument();
  expect(screen.getByText(/Rev 2/)).toBeInTheDocument();
  expect(screen.getByText(/does not send email or WhatsApp/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Mark Accepted" }));
  expect(await screen.findByText("PO opened")).toBeInTheDocument();
  const acceptCall = vi.mocked(fetch).mock.calls.find((call) => String(call[0]).endsWith("/accept"));
  expect(acceptCall?.[1]?.method).toBe("POST");
});

it("keeps submit on the quotation when the status response includes an empty Customer PO number", async () => {
  quotation.status = "draft";
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo) => {
    const url = String(input);
    if (url.includes("/status")) {
      return Promise.resolve(new Response(JSON.stringify({ ...quotation, status: "sent", cpoNumber: null }), { status: 200 }));
    }
    return Promise.resolve(new Response(JSON.stringify(quotation), { status: 200 }));
  }));
  render(
    <MemoryRouter initialEntries={["/sales/quotation/q1"]}>
      <Routes>
        <Route path="/sales/quotation/:id" element={<QuotationDetailPage />} />
        <Route path="/sales/po/:id" element={<p>PO opened</p>} />
      </Routes>
    </MemoryRouter>,
  );
  await userEvent.click(await screen.findByRole("button", { name: "Submit to Customer" }));
  expect(await screen.findByText("Sent")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "Submit to Customer" })).not.toBeInTheDocument();
  expect(screen.queryByText("PO opened")).not.toBeInTheDocument();
  quotation.status = "sent";
});
