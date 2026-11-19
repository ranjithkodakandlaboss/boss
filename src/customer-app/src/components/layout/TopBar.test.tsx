import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { MemoryRouter, Route, Routes, useParams } from "react-router-dom";
import { afterEach, describe, expect, it, vi } from "vitest";
import TopBar from "./TopBar";

vi.mock("../../auth/CustomerState", () => ({
  useCustomer: () => ({ tenant: { name: "North Works" }, user: { displayName: "Owner", email: "owner@example.test", roles: [] } }),
}));
vi.mock("../../shell/ShellContext", () => ({
  useShell: () => ({ collapsed: false, palette: "indigo", mode: "light", setPalette: vi.fn(), setMode: vi.fn() }),
}));
vi.mock("./QuickNewMenu", () => ({ default: () => null }));
vi.mock("../search/GlobalSearchModal", () => ({ default: () => null }));

const notification = {
  id: "notification-1",
  notificationType: "maintenance_asset_impact",
  entityType: "maintenance_event",
  entityId: "event-1",
  title: "Asset LATHE-1 unavailable",
  body: "Breakdown may affect production stages.",
  isRead: false,
  createdAt: "2026-01-01T10:00:00Z",
};

function EventDestination() {
  const { id } = useParams();
  return <p>Maintenance event {id}</p>;
}

describe("TopBar notifications", () => {
  afterEach(() => vi.unstubAllGlobals());

  it("loads the owner feed, marks an alert read, and opens its maintenance event", async () => {
    let readRequest: RequestInit | undefined;
    const fetchMock = vi.fn((input: RequestInfo | URL, init?: RequestInit) => {
      const url = String(input);
      if (url.endsWith("/api/v1/notifications")) {
        return Promise.resolve(new Response(JSON.stringify([notification]), { status: 200 }));
      }
      if (url.endsWith(`/api/v1/notifications/${notification.id}/read`)) {
        readRequest = init;
        return Promise.resolve(new Response(JSON.stringify({ ...notification, isRead: true }), { status: 200 }));
      }
      return Promise.reject(new Error(`Unexpected request: ${url}`));
    });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <MemoryRouter>
        <Routes>
          <Route path="/assets/me/:id" element={<EventDestination />} />
          <Route path="*" element={<TopBar />} />
        </Routes>
      </MemoryRouter>,
    );

    fireEvent.click(screen.getByRole("button", { name: "Notifications" }));
    fireEvent.click(await screen.findByRole("button", { name: /Asset LATHE-1 unavailable/ }));

    expect(await screen.findByText("Maintenance event event-1")).toBeInTheDocument();
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(readRequest?.method).toBe("POST");
    expect(new Headers(readRequest?.headers).get("Idempotency-Key")).toBeTruthy();
  });
});
