import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import BomPage from "./BomPage";

const { apiMock, allPagesMock } = vi.hoisted(() => ({
  apiMock: vi.fn(),
  allPagesMock: vi.fn(),
}));

vi.mock("../auth/api", () => ({ api: apiMock }));
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
});
