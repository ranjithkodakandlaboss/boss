import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { signIn, signOut } from "../auth/session";
import QuotationDetailPage from "./proto/QuotationDetail";
import { SalesProvider } from "./proto/runtime";

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
  createdAt: "2026-11-17T00:00:00Z",
};

function page(content: unknown[]) {
  return JSON.stringify({ content, totalElements: content.length });
}

function installFetch(handler: (url: string, init?: RequestInit) => Response | Promise<Response>) {
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => Promise.resolve(handler(String(input), init))));
}

beforeEach(() => {
  signIn("test-session");
  quotation.status = "sent";
  installFetch((url, init) => {
    if (url.includes("/accept")) return new Response(JSON.stringify({ id: "po1", cpoNumber: "CPO-1", customerId: "c1", customerName: "North Works", status: "open", lines: [] }), { status: 201 });
    if (url.includes("/quotations")) return new Response(page([quotation]), { status: 200 });
    if (url.includes("/customers") || url.includes("/rfqs") || url.includes("/cpos") || url.includes("/items") || url.includes("/uoms")) return new Response(page([]), { status: 200 });
    if (url.includes("/history")) return new Response("[]", { status: 200 });
    return new Response("{}", { status: init?.method === "POST" ? 201 : 200 });
  });
  return () => signOut();
});

function renderQuote() {
  return render(
    <MemoryRouter initialEntries={["/sales/quotation/q1"]}>
      <SalesProvider>
        <Routes>
          <Route path="/sales/quotation/:id" element={<QuotationDetailPage />} />
          <Route path="/sales/po/:id" element={<p>PO opened</p>} />
        </Routes>
      </SalesProvider>
    </MemoryRouter>,
  );
}

it("records owner acceptance against the open revision and creates one Customer PO", async () => {
  renderQuote();
  expect(await screen.findByText("QT-1")).toBeInTheDocument();
  expect(screen.getByText(/Rev 2/)).toBeInTheDocument();
  expect(screen.getByText(/does not send email or WhatsApp/)).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Mark Accepted" }));
  await userEvent.click(screen.getByRole("button", { name: "Accept" }));
  expect(await screen.findByText("PO opened")).toBeInTheDocument();
  const acceptCall = vi.mocked(fetch).mock.calls.find((call) => String(call[0]).includes("/accept"));
  expect(acceptCall?.[1]?.method).toBe("POST");
});

it("keeps submit on the quotation when the status response includes an empty Customer PO number", async () => {
  quotation.status = "draft";
  let submitted = false;
  installFetch((url) => {
    if (url.includes("/status")) {
      submitted = true;
      return new Response(JSON.stringify({ ...quotation, status: "sent", cpoNumber: null }), { status: 200 });
    }
    if (url.includes("/quotations")) return new Response(page([{ ...quotation, status: submitted ? "sent" : "draft" }]), { status: 200 });
    if (url.includes("/customers") || url.includes("/rfqs") || url.includes("/cpos") || url.includes("/items") || url.includes("/uoms")) return new Response(page([]), { status: 200 });
    if (url.includes("/history")) return new Response("[]", { status: 200 });
    return new Response("{}", { status: 200 });
  });
  renderQuote();
  await userEvent.click(await screen.findByRole("button", { name: "Submit to Customer" }));
  await userEvent.click(screen.getByRole("button", { name: "Submit" }));
  expect(await screen.findByText("Submitted")).toBeInTheDocument();
  expect(screen.queryByText("PO opened")).not.toBeInTheDocument();
});
