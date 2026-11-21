import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../auth/api";
import ItemDetail from "./ItemDetail";
import ItemMaster, { ItemForm } from "./ItemMaster";
import MaterialReturns from "./MaterialReturns";
import SiteNeed from "./SiteNeed";

vi.mock("../auth/api", () => ({
  api: vi.fn(),
  safeRandomUUID: () => "idempotency-key",
}));

vi.mock("../auth/CustomerState", () => ({
  useCustomer: () => ({ user: { roles: [{ role: "OWNER" }] } }),
}));

const item = {
  id: "item-1", code: "RM-01", name: "Copper Wire", itemType: "RAW_MATERIAL",
  categoryLabel: "Conductor", uomSymbol: "KG", status: "active", uomId: "uom-1",
};

afterEach(() => vi.clearAllMocks());

describe("inventory prototype screens", () => {
  it("lists the item master and opens an item", async () => {
    vi.mocked(api).mockImplementation(async (path) => {
      const url = String(path);
      if (url.includes("/api/v1/items") && url.includes("status=active")) return { content: [item], totalElements: 1 };
      return { content: [], totalElements: 0 };
    });
    render(<MemoryRouter><ItemMaster /></MemoryRouter>);
    expect(await screen.findByText("Copper Wire")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Stock on hand" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Returns" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Site need" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Cycle count" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Bills of materials" })).not.toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Remnants" })).not.toBeInTheDocument();
  });

  it("shows lot stock and the jobs that use an item", async () => {
    vi.mocked(api).mockImplementation(async (path) => {
      const url = String(path);
      if (url === "/api/v1/items/item-1") return item;
      if (url.includes("/inventory/positions")) return { content: [{ itemId: "item-1", itemCode: "RM-01", itemName: "Copper Wire", storeId: "store-1", storeName: "Raw Store", totalQty: 12, reservedQty: 2, availableQty: 10, reorderPoint: 4, uomSymbol: "KG" }], totalElements: 1 };
      if (url.includes("/inventory/lots?") && !url.includes("/movements")) return { content: [{ id: "lot-1", itemId: "item-1", itemCode: "RM-01", itemName: "Copper Wire", storeId: "store-1", storeName: "Raw Store", lotNumber: "LOT-1", qty: 12, reservedQty: 2, availableQty: 10, uomSymbol: "KG", status: "available" }], totalElements: 1 };
      if (url.includes("/inventory/reservations")) return [{ jobId: "job-1", jobNumber: "J-1", siteId: "site-1", itemId: "item-1", itemCode: "RM-01", itemName: "Copper Wire", reservedQty: 2 }];
      if (url.includes("/api/v1/jobs")) return { content: [{ id: "job-1", jobNumber: "J-1", status: "in_progress", siteName: "Hyderabad", materials: [{ id: "req-1", itemId: "item-1", itemName: "Copper Wire", requiredQty: 8, issuedQty: 1, returnedQty: 0, netIssuedQty: 1, uomSymbol: "KG" }] }], totalElements: 1 };
      return { content: [], totalElements: 0 };
    });
    render(<MemoryRouter initialEntries={["/inventory/item/item-1"]}><Routes><Route path="/inventory/item/:id" element={<ItemDetail />} /></Routes></MemoryRouter>);
    expect(await screen.findByText("LOT-1")).toBeInTheDocument();
    expect(screen.getByText("J-1")).toBeInTheDocument();
    expect(screen.getByText("Used by jobs")).toBeInTheDocument();
  });

  it("lists posted production returns", async () => {
    vi.mocked(api).mockImplementation(async (path) => {
      if (String(path).includes("/api/v1/jobs")) {
        return {
          content: [{
            id: "job-1", jobNumber: "J-1", status: "in_progress",
            materials: [{ id: "req-1", itemId: "item-1", itemCode: "RM-01", itemName: "Copper Wire", uomSymbol: "KG" }],
            movements: [{ id: "ret-1", requirementId: "req-1", lotId: "lot-1", issueType: "return", quantity: 1.5, createdAt: "2026-11-18T05:00:00Z" }],
          }],
          totalElements: 1,
        };
      }
      return { content: [], totalElements: 0 };
    });
    render(<MemoryRouter><MaterialReturns /></MemoryRouter>);
    expect(await screen.findByText(/Copper Wire/)).toBeInTheDocument();
    expect(screen.getByText("Posted")).toBeInTheDocument();
    expect(screen.getByText("J-1")).toBeInTheDocument();
  });

  it("shows category and unit failures instead of an empty item form", async () => {
    vi.mocked(api).mockImplementation(async (path) => {
      const url = String(path);
      if (url.includes("/api/v1/uoms")) return [{ id: "uom-1", name: "Kilogram", symbol: "KG" }];
      if (url.includes("ITEM_CATEGORY") || url.includes("item-categories")) throw new Error("Categories could not be loaded.");
      return {};
    });
    render(<ItemForm onClose={() => undefined} onSaved={() => undefined} />);
    expect(await screen.findByRole("option", { name: "KG · Kilogram" })).toBeInTheDocument();
    expect(await screen.findByRole("alert")).toHaveTextContent("Categories could not be loaded.");
    expect(screen.queryByRole("button", { name: "Add category" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Retry" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add item type" })).not.toBeInTheDocument();
  });

  it("adds a category from the empty item form and selects the saved record", async () => {
    let categories: { id: string; code: string; name: string; label: string }[] = [];
    vi.mocked(api).mockImplementation(async (path, init) => {
      const url = String(path);
      if (init?.method === "POST" && url.includes("/api/v1/item-categories")) {
        const body = JSON.parse(String(init.body)) as { code: string; name: string };
        if (categories.some((category) => category.code === body.code)) throw new Error("Category code already exists for this tenant");
        const created = { id: "cat-1", code: body.code, name: body.name, label: body.name };
        categories = [created];
        return created;
      }
      if (url.includes("/api/v1/uoms")) return [{ id: "uom-1", name: "Kilogram", symbol: "KG" }];
      if (url.includes("ITEM_CATEGORY") || url.includes("item-categories")) return categories;
      return {};
    });
    render(<ItemForm onClose={() => undefined} onSaved={() => undefined} />);
    expect(await screen.findByText("No options available. Add your first option.")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Add category" }));
    fireEvent.change(screen.getByLabelText("Code"), { target: { value: "COND" } });
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Conductor" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("option", { name: "Conductor" })).toBeInTheDocument();
    expect(api).toHaveBeenCalledWith("/api/v1/item-categories", expect.objectContaining({ method: "POST" }));
    fireEvent.click(screen.getByRole("button", { name: "Add category" }));
    fireEvent.change(screen.getByLabelText("Code"), { target: { value: "COND" } });
    fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Again" } });
    fireEvent.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("This value already exists.");
  });

  it("reserves a short job requirement through the job reservation API", async () => {
    vi.mocked(api).mockImplementation(async (path) => {
      const url = String(path);
      if (url.includes("/api/v1/jobs")) {
        return {
          content: [{
            id: "job-1", jobNumber: "J-1", status: "released", siteId: "site-1", siteName: "Hyderabad", plannedEnd: "2026-12-01",
            materials: [{ id: "req-1", itemId: "item-1", itemName: "Copper Wire", requiredQty: 10, issuedQty: 0, returnedQty: 0, netIssuedQty: 0, uomSymbol: "KG" }],
          }],
          totalElements: 1,
        };
      }
      if (url.includes("/inventory/positions")) return { content: [{ itemId: "item-1", storeId: "store-1", storeName: "Raw", totalQty: 6, reservedQty: 0, availableQty: 6, reorderPoint: null, uomSymbol: "KG", itemCode: "RM-01", itemName: "Copper Wire" }], totalElements: 1 };
      if (url.includes("/api/v1/company")) return { stores: [{ id: "store-1", siteId: "site-1", name: "Raw" }] };
      if (url.includes("/inventory/reservations") && !url.includes("/requirements")) return [];
      return { status: "PARTIALLY_RESERVED" };
    });
    render(<MemoryRouter><SiteNeed /></MemoryRouter>);
    expect(await screen.findByText("Copper Wire")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reserve" }));
    await waitFor(() => expect(api).toHaveBeenCalledWith(
      "/api/v1/inventory/reservations/job-1/requirements?partial=true",
      expect.objectContaining({ method: "POST", headers: { "Idempotency-Key": "idempotency-key" } }),
    ));
  });
});
