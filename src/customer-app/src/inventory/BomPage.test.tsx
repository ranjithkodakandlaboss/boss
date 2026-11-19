import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import BomPage from "./BomPage";

const { apiMock, allPagesMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  allPagesMock: vi.fn(),
}));

vi.mock("../auth/api", () => ({ api: apiMock, safeRandomUUID: () => "test-uuid" }));
vi.mock("./api", () => ({ allPages: allPagesMock }));
vi.mock("../auth/CustomerState", () => ({
  useCustomer: () => ({ user: { roles: [{ role: "OWNER" }] } }),
}));
vi.mock("./stockNav", () => ({ default: () => null }));

describe("BOM specification calculator", () => {
  beforeEach(() => {
    apiMock.mockReset();
    allPagesMock.mockReset().mockResolvedValue([]);
    apiMock.mockImplementation((path: string, options?: RequestInit) => {
      if (path === "/api/v1/boms") {
        return Promise.resolve([{
          id: "bom-1",
          itemId: "item-1",
          itemCode: "FG-01",
          itemName: "Countertop",
          version: 3,
          status: "approved",
          lines: [{
            componentItemId: "material-1",
            componentItemCode: "SLAB-01",
            componentItemName: "Stone slab",
            qty: 1,
            uomSymbol: "M2",
            quantityFormula: "length * width",
          }],
        }]);
      }
      if (path === "/api/v1/items/item-1/bom/specifications") {
        return Promise.resolve(["length", "width"]);
      }
      if (options?.method === "POST") {
        return Promise.resolve({
          bomVersion: "v3",
          flatMaterials: [{
            itemId: "material-1",
            itemCode: "SLAB-01",
            itemName: "Stone slab",
            qty: 6,
            uomSymbol: "M2",
          }],
        });
      }
      return Promise.resolve([]);
    });
  });

  it("submits specification values and displays calculated materials", async () => {
    render(<BomPage />);
    fireEvent.click(await screen.findByText(/FG-01 Countertop/));
    fireEvent.change(await screen.findByLabelText("Specification length"), { target: { value: "2" } });
    fireEvent.change(screen.getByLabelText("Specification width"), { target: { value: "3" } });
    fireEvent.click(screen.getByRole("button", { name: "Calculate" }));

    expect(await screen.findByText(/Calculated from BOM version v3/)).toBeInTheDocument();
    expect(screen.getAllByText(/Stone slab/)).toHaveLength(2);
    await waitFor(() => expect(apiMock).toHaveBeenCalledWith(
      "/api/v1/items/item-1/bom/explode",
      expect.objectContaining({
        method: "POST",
        body: JSON.stringify({ quantity: 1, specifications: { length: 2, width: 3 } }),
      }),
    ));
  });

  it("lists FINISHED_GOOD and SEMI_FINISHED items in the Create BOM selector and excludes them from components", async () => {
    allPagesMock.mockResolvedValue([
      { id: "item-fg", code: "FG-01", name: "Steel Spoon", itemType: "FINISHED_GOOD", status: "active" },
      { id: "item-sf", code: "SF-01", name: "Spoon Blank", itemType: "SEMI_FINISHED", status: "active" },
      { id: "item-rm", code: "RM-01", name: "Steel Coil", itemType: "RAW_MATERIAL", status: "active" },
      { id: "item-srv", code: "SRV-01", name: "Polishing Service", itemType: "SERVICE", status: "active" },
    ]);
    apiMock.mockImplementation((path: string) => {
      if (path === "/api/v1/boms") return Promise.resolve([]);
      if (path === "/api/v1/uoms") return Promise.resolve([{ id: "uom-1", name: "Piece", symbol: "PCS" }]);
      return Promise.resolve([]);
    });

    render(<BomPage />);
    const createBtn = await screen.findByRole("button", { name: "Create BOM" });
    fireEvent.click(createBtn);

    expect(await screen.findByRole("heading", { name: "Create bill of materials" })).toBeInTheDocument();
    expect(screen.queryByText(/No finished goods or semi-finished items found/)).not.toBeInTheDocument();

    const parentSelect = screen.getByLabelText("Finished item");
    expect(within(parentSelect).getByRole("option", { name: "FG-01 — Steel Spoon" })).toBeInTheDocument();
    expect(within(parentSelect).getByRole("option", { name: "SF-01 — Spoon Blank" })).toBeInTheDocument();
    expect(within(parentSelect).queryByRole("option", { name: "RM-01 — Steel Coil" })).not.toBeInTheDocument();

    const componentSelect = screen.getByLabelText("Component 1");
    expect(within(componentSelect).getByRole("option", { name: "RM-01 — Steel Coil" })).toBeInTheDocument();
    expect(within(componentSelect).getByRole("option", { name: "SF-01 — Spoon Blank" })).toBeInTheDocument();
    expect(within(componentSelect).queryByRole("option", { name: "FG-01 — Steel Spoon" })).not.toBeInTheDocument();
    expect(within(componentSelect).queryByRole("option", { name: "SRV-01 — Polishing Service" })).not.toBeInTheDocument();
  });

  it("shows warning message when no finished goods or semi-finished items exist", async () => {
    allPagesMock.mockResolvedValue([
      { id: "item-rm", code: "RM-01", name: "Steel Coil", itemType: "RAW_MATERIAL", status: "active" },
    ]);
    apiMock.mockImplementation((path: string) => {
      if (path === "/api/v1/boms") return Promise.resolve([]);
      if (path === "/api/v1/uoms") return Promise.resolve([{ id: "uom-1", name: "Piece", symbol: "PCS" }]);
      return Promise.resolve([]);
    });

    render(<BomPage />);
    const createBtn = await screen.findByRole("button", { name: "Create BOM" });
    fireEvent.click(createBtn);

    expect(await screen.findByText(/No finished goods or semi-finished items found/)).toBeInTheDocument();
  });
});
