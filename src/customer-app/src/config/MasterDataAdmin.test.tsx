import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { CustomerProvider } from "../auth/CustomerState";
import { signIn, signOut } from "../auth/session";
import MasterDataAdmin from "./MasterDataAdmin";

const config = {
  labourRates: [{ name: "Cutting", ratePerHour: 120 }],
  machineRates: [],
  overheadPerUnit: 1,
  consumablesPerUnit: 2,
  packagingPerUnit: 3,
  transportPerUnit: 4,
  targetMarginPct: 20,
  minimumMarginPct: 10,
  approvalThresholdPct: 12,
};

function json(body: unknown, status = 200) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));
}

let role: string;
let failCosting: boolean;

beforeEach(() => {
  role = "OWNER";
  failCosting = false;
  signIn("test-session");
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/auth/me")) return json({ id: "1", roles: [{ role }] });
    if (url.includes("/tenant/info") || url.includes("/onboarding")) return json({});
    if (url.includes("/quotations/costing-config")) {
      if (failCosting) return json({ message: "Unavailable" }, 500);
      return json(init?.method === "PUT" ? JSON.parse(String(init.body)) : config);
    }
    if (url.includes("/uoms")) return json([{ id: "unit-1", symbol: "m", name: "Metre" }]);
    if (url.includes("/boms")) return json([]);
    if (url.includes("/items")) return json({ content: [], totalElements: 0 });
    if (url.includes("/config/categories")) return json([]);
    return json([]);
  }));
});

afterEach(() => {
  signOut();
  vi.unstubAllGlobals();
});

function renderAdmin(section: string) {
  return render(<MemoryRouter initialEntries={[`/admin?tab=master-data&section=${section}`]}>
    <CustomerProvider><MasterDataAdmin /></CustomerProvider>
  </MemoryRouter>);
}

describe("master data administration", () => {
  it("opens the existing item and BOM workflows within admin", async () => {
    renderAdmin("items");
    expect(await screen.findByRole("heading", { name: "Item Master" })).toBeInTheDocument();
    expect(await screen.findByText("No item master records match this filter.")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Bill of Materials" }));
    expect(await screen.findByRole("heading", { name: "Bills of materials" })).toBeInTheDocument();
    expect(await screen.findByText(/No bills of materials yet/)).toBeInTheDocument();
    expect(vi.mocked(fetch).mock.calls.some(([input]) => String(input).includes("/api/v1/boms"))).toBe(true);
  });

  it("creates a finished product with numeric specification definitions", async () => {
    renderAdmin("items");
    await screen.findByRole("heading", { name: "Item Master" });
    await userEvent.click(await screen.findByRole("button", { name: "Add item" }));
    await userEvent.type(screen.getByLabelText("Item code"), "TOP");
    await userEvent.type(screen.getByLabelText("Item name *"), "Countertop");
    await userEvent.selectOptions(screen.getByLabelText("Item type"), "FINISHED_GOOD");
    await userEvent.selectOptions(screen.getByLabelText("Product kind"), "MANUFACTURED");
    await userEvent.click(screen.getByRole("button", { name: "Add specification" }));
    await userEvent.type(screen.getByLabelText("Specification 1 key"), "length");
    await userEvent.type(screen.getByLabelText("Specification 1 label"), "Length");
    await userEvent.selectOptions(screen.getByLabelText("Specification 1 unit"), "unit-1");
    await userEvent.type(screen.getByLabelText("Specification 1 minValue"), "1");
    await userEvent.type(screen.getByLabelText("Specification 1 defaultValue"), "5");
    await userEvent.click(screen.getByRole("button", { name: "Create item" }));
    await waitFor(() => {
      const request = vi.mocked(fetch).mock.calls.find(([input, init]) => String(input).endsWith("/api/v1/items") && init?.method === "POST");
      expect(request).toBeDefined();
      expect(JSON.parse(String(request?.[1]?.body))).toMatchObject({
        productKind: "MANUFACTURED",
        specifications: [{ key: "length", label: "Length", uomId: "unit-1", minValue: 1, maxValue: null, defaultValue: 5 }],
      });
    });
  });

  it("loads, validates and saves the real costing configuration", async () => {
    renderAdmin("costing");
    const labour = await screen.findByRole("textbox", { name: /Labour rates/ });
    expect(labour).toHaveValue("Cutting, 120");
    await userEvent.clear(labour);
    await userEvent.type(labour, "Cutting, invalid");
    await userEvent.click(screen.getByRole("button", { name: "Save costing controls" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Enter rates one per line");
    expect(vi.mocked(fetch).mock.calls.some(([, init]) => init?.method === "PUT")).toBe(false);
    await userEvent.clear(labour);
    await userEvent.type(labour, "Cutting, 150");
    await userEvent.click(screen.getByRole("button", { name: "Save costing controls" }));
    expect(await screen.findByText("Costing configuration saved.")).toBeInTheDocument();
    const request = vi.mocked(fetch).mock.calls.find(([input, init]) => String(input).includes("/quotations/costing-config") && init?.method === "PUT");
    expect(JSON.parse(String(request?.[1]?.body))).toEqual({ ...config, labourRates: [{ name: "Cutting", ratePerHour: 150 }] });
  });

  it("retries a failed load and restricts editing to owners", async () => {
    role = "SALES_MANAGER";
    failCosting = true;
    renderAdmin("costing");
    expect(await screen.findByRole("alert")).toBeInTheDocument();
    failCosting = false;
    await userEvent.click(screen.getByRole("button", { name: "Retry" }));
    await waitFor(() => expect(screen.getByText("Only owners can edit costing configuration.")).toBeInTheDocument());
    expect(screen.queryByRole("button", { name: "Save costing controls" })).not.toBeInTheDocument();
  });
});
