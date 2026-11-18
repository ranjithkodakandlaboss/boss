import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { signIn, signOut } from "../auth/session";
import { CustomerProvider } from "../auth/CustomerState";
import ConfigOptionsAdmin from "./ConfigOptionsAdmin";

const options = [
  { id: "system-cash", categoryCode: "PAYMENT_MODES", code: "CASH", label: "Cash", sortOrder: 0, isSystem: true, isActive: true },
  { id: "bank", categoryCode: "PAYMENT_MODES", code: "BANK", label: "Bank transfer", sortOrder: 10, isSystem: false, isActive: true },
  { id: "upi", categoryCode: "PAYMENT_MODES", code: "UPI", label: "UPI", sortOrder: 20, isSystem: false, isActive: true },
];

function json(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }));
}

beforeEach(() => {
  signIn("test-session");
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/auth/me")) return json({ id: "1", tenantId: "t", email: "owner@example.test", displayName: "Owner", status: "active", roles: [{ role: "OWNER" }] });
    if (url.includes("/tenant/info")) return json({ id: "t", name: "North Works", status: "active" });
    if (url.includes("/onboarding")) return json({ industryType: "GENERAL_MANUFACTURING", completed: true, company: { companyName: "North Works", sites: [], stores: [] } });
    if (url.endsWith("/config/options/categories")) return json([{ code: "PAYMENT_MODES", name: "Payment modes", scope: "tenant", tenantEditable: true, supportsOrdering: true }]);
    if (url.endsWith("/config/categories")) return Promise.resolve(new Response(JSON.stringify({ error: "Not Found" }), { status: 404 }));
    if (url.includes("/config/options/PAYMENT_MODES")) {
      if (init?.method === "PUT" || init?.method === "PATCH" || init?.method === "POST") return json({ status: "updated" });
      return json(options);
    }
    return json({});
  }));
});

afterEach(() => {
  signOut();
  vi.unstubAllGlobals();
});

function renderPage() {
  return render(
    <MemoryRouter>
      <CustomerProvider><ConfigOptionsAdmin /></CustomerProvider>
    </MemoryRouter>,
  );
}

describe("configuration options admin", () => {
  it("shows a retryable error instead of an empty state when categories fail to load", async () => {
    const fetcher = vi.mocked(fetch);
    const original = fetcher.getMockImplementation();
    let failed = false;
    fetcher.mockImplementation((input, init) => {
      if (String(input).endsWith("/config/options/categories") && !failed) {
        failed = true;
        return Promise.resolve(new Response(JSON.stringify({ error: "Not Found" }), { status: 404 }));
      }
      if (!original) throw new Error("Missing fetch mock");
      return original(input, init);
    });

    renderPage();
    expect(await screen.findByRole("alert")).toHaveTextContent("Not Found");
    expect(screen.queryByText("No configuration categories are available.")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    expect(await screen.findByText("Bank transfer")).toBeInTheDocument();
  });

  it("shows protected values as locked and edits tenant-managed labels", async () => {
    renderPage();
    expect(await screen.findByText("Bank transfer")).toBeInTheDocument();
    expect(screen.getByText("Protected")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Edit Cash" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Deactivate Cash" })).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Edit Bank transfer" }));
    const labelInput = screen.getByRole("textbox", { name: "Display value" });
    await userEvent.clear(labelInput);
    await userEvent.type(labelInput, "Bank / UPI");
    await userEvent.click(screen.getByRole("button", { name: "Save changes" }));

    await waitFor(() => {
      const requests = vi.mocked(fetch).mock.calls;
      expect(requests.some(([input, init]) => String(input).includes("/config/options/PAYMENT_MODES/bank")
        && init?.method === "PUT"
        && JSON.parse(String(init.body)).label === "Bank / UPI")).toBe(true);
    });
  });

  it("confirms deactivation and submits tenant-managed order changes", async () => {
    renderPage();
    expect(await screen.findAllByText("UPI")).not.toHaveLength(0);

    await userEvent.click(screen.getByRole("button", { name: "Deactivate Bank transfer" }));
    const dialog = screen.getByRole("dialog");
    expect(screen.getByRole("combobox", { name: "Reference data category" })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole("button", { name: "Deactivate value" }));
    await waitFor(() => {
      expect(vi.mocked(fetch).mock.calls.some(([input, init]) => String(input).includes("/bank/status") && init?.method === "PATCH")).toBe(true);
    });
    await waitFor(() => expect(screen.getByRole("combobox", { name: "Reference data category" })).toBeEnabled());

    fireEvent.click(screen.getByRole("button", { name: "Move UPI up" }));
    await waitFor(() => {
      expect(vi.mocked(fetch).mock.calls.some(([input, init]) => String(input).endsWith("/config/options/PAYMENT_MODES/order")
        && init?.method === "PUT")).toBe(true);
    });
  });

  it("shows dependency details when deactivation is rejected", async () => {
    const fetcher = vi.mocked(fetch);
    const original = fetcher.getMockImplementation();
    fetcher.mockImplementation((input, init) => {
      if (String(input).includes("/bank/status") && init?.method === "PATCH") {
        return Promise.resolve(new Response(JSON.stringify({ detail: "Cannot deactivate category used by 1 active item" }), { status: 409 }));
      }
      if (!original) throw new Error("Missing fetch mock");
      return original(input, init);
    });
    renderPage();
    await userEvent.click(await screen.findByRole("button", { name: "Deactivate Bank transfer" }));
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Deactivate value" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("1 active item");
  });
});
