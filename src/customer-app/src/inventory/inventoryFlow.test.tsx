import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import InventoryHome from "./InventoryHome";

function json(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), {
    status: 200,
    headers: { "Content-Type": "application/json" },
  }));
}

describe("inventory workspace", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("loads positions and filters the stock list by material or location", async () => {
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo) => {
      const url = String(input);
      if (url.includes("/inventory/positions")) {
        return json({
          content: [{
            itemId: "item-1",
            itemCode: "RM-01",
            itemName: "Copper Wire",
            storeId: "store-1",
            storeName: "Raw Store",
            totalQty: 12,
            reservedQty: 3,
            availableQty: 9,
            inTransitQty: 4,
            reorderPoint: 10,
            uomSymbol: "KG",
          }],
          page: 0,
          totalElements: 1,
        });
      }
      if (url.includes("/inventory/lots")) return json({ content: [], page: 0, totalElements: 0 });
      return json({});
    }));

    render(<MemoryRouter><InventoryHome /></MemoryRouter>);

    expect(await screen.findByText("Copper Wire")).toBeInTheDocument();
    expect(screen.getByText("12 KG")).toBeInTheDocument();
    expect(screen.getByText("9 KG")).toBeInTheDocument();
    expect(screen.getByText("4 KG")).toBeInTheDocument();
    await userEvent.type(screen.getByRole("textbox", { name: "Search material or location" }), "finished");
    expect(screen.getByText("No stock positions")).toBeInTheDocument();
  });
});
