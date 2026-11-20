import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import RoutingTemplatesAdmin from "./RoutingTemplatesAdmin";

const state = vi.hoisted(() => ({ role: "OWNER" }));
vi.mock("../auth/CustomerState", () => ({ useCustomer: () => ({ user: { roles: [{ role: state.role }] } }) }));

const legacy = { name: "Old weld", workCentre: "Bay 1", assetId: null, stageConfigOptionId: null, inspectionTemplateId: "inspection-1", requiresInspection: true, isOptional: false, stageType: "production", setupTimeMin: 5, runTimeMin: 12 };
const configured = { name: "Paint", workCentre: null, assetId: null, stageConfigOptionId: "paint", inspectionTemplateId: null, requiresInspection: false, isOptional: true, stageType: "inspection", setupTimeMin: 0, runTimeMin: 20 };
const routes = [{ id: "route-1", name: "Frame route", version: "v2", finishedItemId: "product-1", stageCount: 2 }];
const detail = { ...routes[0], stages: [legacy, configured] };
const json = (body: unknown, status = 200) => Promise.resolve(new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } }));

beforeEach(() => {
  state.role = "OWNER";
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (init?.method === "POST") return json({ id: "new" });
    if (url.includes("/items?")) return json({ content: [
      { id: "product-1", code: "FG1", name: "Frame", itemType: "FINISHED_GOOD", status: "active" },
      { id: "product-2", code: "FG2", name: "Wheel", itemType: "finished_goods", status: "active" },
      { id: "raw", code: "RAW", name: "Steel", itemType: "raw_material", status: "active" },
      { id: "inactive", code: "OLD", name: "Old product", itemType: "finished_goods", status: "inactive" },
    ], totalElements: 4 });
    if (url.includes("/config/options/PRODUCTION_STAGES")) return json([
      { id: "cut", label: "Cut", sortOrder: 1, isActive: true },
      { id: "paint", label: "Paint", sortOrder: 2, isActive: true },
      { id: "retired", label: "Retired", sortOrder: 3, isActive: false },
    ]);
    if (url.endsWith("/routing-templates/route-1")) return json(detail);
    if (url.endsWith("/routing-templates")) return json(routes);
    return json([]);
  }));
});
afterEach(() => { vi.unstubAllGlobals(); });

describe("manufacturing routes", () => {
  it("creates a product route with ordered active configuration stages", async () => {
    state.role = "OPS_MANAGER";
    render(<RoutingTemplatesAdmin />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Create route" }));
    await user.type(screen.getByLabelText("Route name"), "Wheel route");
    const product = screen.getByLabelText("Product");
    expect(within(product).queryByText("RAW — Steel")).not.toBeInTheDocument();
    expect(within(product).queryByText("OLD — Old product")).not.toBeInTheDocument();
    await user.selectOptions(product, "product-2");
    const selector = screen.getByLabelText(/production stage/i);
    expect(within(selector).queryByText("Retired")).not.toBeInTheDocument();
    await user.selectOptions(selector, "paint");
    await user.click(screen.getByRole("button", { name: "Add stage" }));
    await user.selectOptions(selector, "cut");
    await user.click(screen.getByRole("button", { name: "Add stage" }));
    await user.click(screen.getByRole("button", { name: "Move stage 2 up" }));
    await user.click(screen.getAllByRole("button", { name: "Create route" })[1]);
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) => String(url).endsWith("/routing-templates") && init?.method === "POST" && JSON.stringify(JSON.parse(String(init.body))) === JSON.stringify({
      name: "Wheel route", finishedItemId: "product-2", stages: [{ name: "Cut", stageConfigOptionId: "cut" }, { name: "Paint", stageConfigOptionId: "paint" }],
    }))).toBe(true));
  });

  it("revises without losing retained stage attributes or legacy stages", async () => {
    render(<RoutingTemplatesAdmin />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Inspect Frame route" }));
    expect(await screen.findByText(/Old weld \(legacy stage\)/)).toBeInTheDocument();
    expect(screen.getByText(/Inspection template(:| ID:) inspection-1/i)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Create revision" }));
    await user.click(screen.getByRole("button", { name: "Move stage 2 up" }));
    await user.click(screen.getByRole("button", { name: "Create revision" }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) => String(url).endsWith("/route-1/revisions") && init?.method === "POST" && JSON.stringify(JSON.parse(String(init.body))) === JSON.stringify({ stages: [configured, legacy] }))).toBe(true));
  });

  it("only removes stages explicitly selected for removal", async () => {
    render(<RoutingTemplatesAdmin />);
    const user = userEvent.setup();
    await user.click(await screen.findByRole("button", { name: "Inspect Frame route" }));
    await screen.findByText(/Old weld \(legacy stage\)/);
    await user.click(screen.getByRole("button", { name: "Create revision" }));
    await user.click(screen.getByRole("button", { name: "Remove stage 2" }));
    await user.click(screen.getByRole("button", { name: "Create revision" }));
    await waitFor(() => expect(vi.mocked(fetch).mock.calls.some(([url, init]) => String(url).endsWith("/route-1/revisions") && init?.method === "POST" && JSON.stringify(JSON.parse(String(init.body))) === JSON.stringify({ stages: [legacy] }))).toBe(true));
  });

  it("supports read-only inspection and surfaces server authorization errors", async () => {
    state.role = "PRODUCTION_SUPERVISOR";
    const { unmount } = render(<RoutingTemplatesAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Inspect Frame route" }));
    expect(await screen.findByText(/Old weld \(legacy stage\)/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Create revision" })).not.toBeInTheDocument();
    unmount();
    state.role = "OWNER";
    vi.mocked(fetch).mockImplementation((input: RequestInfo | URL, init?: RequestInit) => {
      if (init?.method === "POST") return json({ detail: "Not authorized" }, 403);
      const url = String(input);
      if (url.includes("/items?")) return json({ content: [], totalElements: 0 });
      if (url.includes("/config/options/")) return json([{ id: "cut", label: "Cut", sortOrder: 1, isActive: true }]);
      return json([]);
    });
    render(<RoutingTemplatesAdmin />);
    await userEvent.click(await screen.findByRole("button", { name: "Create route" }));
    await userEvent.type(screen.getByLabelText("Route name"), "Generic");
    await userEvent.selectOptions(screen.getByLabelText(/production stage/i), "cut");
    await userEvent.click(screen.getByRole("button", { name: "Add stage" }));
    await userEvent.click(screen.getAllByRole("button", { name: "Create route" })[1]);
    expect(await screen.findByRole("alert")).toHaveTextContent("Not authorized");
    const request = vi.mocked(fetch).mock.calls.find(([, init]) => init?.method === "POST");
    expect(JSON.parse(String(request?.[1]?.body)).finishedItemId).toBeNull();
    expect(screen.getByLabelText("Route name")).toHaveValue("Generic");
  });
});
