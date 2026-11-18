import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import { api } from "../auth/api";
import InventoryTransfers from "./InventoryTransfers";

vi.mock("../auth/api", () => ({
  api: vi.fn(),
  safeRandomUUID: () => "idempotency-key",
}));

describe("inventory transfers", () => {
  afterEach(() => vi.clearAllMocks());

  it("lists in-transit transfers and receives them through the API", async () => {
    vi.mocked(api).mockImplementation(async (path) => {
      if (path === "/api/v1/inter-site-transfers?page=0&size=50") {
        return {
          content: [{
            id: "transfer-1",
            transferNumber: "TRF-0001",
            fromStoreName: "Raw Store",
            toStoreName: "Destination Store",
            status: vi.mocked(api).mock.calls.some(([calledPath]) =>
              calledPath === "/api/v1/inter-site-transfers/transfer-1/receive") ? "received" : "in_transit",
            transferDate: "2026-11-18",
            lines: [{ id: "line-1", itemCode: "RM-01", itemName: "Copper", qty: 4, uomSymbol: "KG" }],
          }],
          totalElements: 1,
        } as never;
      }
      return {} as never;
    });

    render(<MemoryRouter><InventoryTransfers /></MemoryRouter>);
    expect(await screen.findByText("TRF-0001")).toBeInTheDocument();
    expect(screen.getByText("in transit")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Receive" }));

    await waitFor(() => expect(api).toHaveBeenCalledWith(
      "/api/v1/inter-site-transfers/transfer-1/receive",
      expect.objectContaining({
        method: "POST",
        headers: { "Idempotency-Key": "idempotency-key" },
      }),
    ));
    expect(await screen.findByText("received")).toBeInTheDocument();
  });
});
