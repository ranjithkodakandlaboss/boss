import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import App from "./App";
import { signIn, signOut } from "./auth/session";
import { NAV_LABELS, screens } from "./screens/catalog";
import ConfirmDialog from "./components/shared/ConfirmDialog";
import { getStatusLabel } from "./components/shared/StatusChip";
import EmptyState from "./components/shared/EmptyState";
import ErrorState from "./components/shared/ErrorState";
import LoadingState from "./components/shared/LoadingState";

function json(body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status: 200, headers: { "Content-Type": "application/json" } }));
}

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  signIn("test-session");
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo) => {
    const url = String(input);
    if (url.includes("/auth/me")) return json({ id: "1", tenantId: "t", email: "owner@example.test", displayName: "Owner", status: "active", roles: [{ role: "OWNER" }] });
    if (url.includes("/tenant/info")) return json({ id: "t", name: "North Works", status: "active" });
    if (url.includes("/onboarding")) return json({ industryType: "GENERAL_MANUFACTURING", completed: true, company: { companyName: "North Works", legalName: "North Works Pvt Ltd", sites: [], stores: [] } });
    if (url.includes("/company")) return json({ companyName: "North Works", legalName: "North Works Pvt Ltd", sites: [], stores: [] });
    return json({});
  }));
});

afterEach(() => signOut());

describe("customer app shell", () => {
  it("renders the prototype navigation without a demo company", async () => {
    renderAt("/");
    expect(await screen.findByText("North Works")).toBeInTheDocument();
    const nav = screen.getByRole("navigation", { name: "Primary" });
    for (const label of NAV_LABELS.filter((item) => item !== "Admin & Settings")) {
      expect(within(nav).getByRole("link", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Admin & Settings" })).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Apex Precision|ranjith302@gmail.com/i);
  });

  it("keeps the sales workspace tabs from the prototype", async () => {
    renderAt("/sales");
    expect(await screen.findByRole("heading", { name: "Customer POs" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Customer Directory" }));
    expect(screen.getByRole("heading", { name: "Customer Directory" })).toBeInTheDocument();
  });

  it("shows an empty table instead of seeded rows", async () => {
    renderAt("/sales");
    expect(await screen.findByText("No records")).toBeInTheDocument();
    expect(screen.queryByText(/PO-2026/)).not.toBeInTheDocument();
  });

  it("covers loading and error states", () => {
    const { rerender } = render(<LoadingState label="Loading records" />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading records");
    rerender(<ErrorState body="The customer API did not return records." />);
    expect(screen.getByRole("alert")).toHaveTextContent("did not return records");
    rerender(<EmptyState title="No records" body="Nothing seeded." />);
    expect(screen.getByText("Nothing seeded.")).toBeInTheDocument();
  });

  it("confirms a blocked save without writing a record", async () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog open title="New Job" body="Nothing was saved." confirmLabel="Close" onConfirm={onConfirm} onCancel={vi.fn()} />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("uses prototype status labels", () => {
    expect(getStatusLabel("pending_approval")).toBe("Pending Approval");
    expect(getStatusLabel("qc_hold")).toBe("Quality Hold");
  });

  it("registers every prototype route used by the shell", () => {
    const required = ["", "sales", "procurement", "inventory", "jobs", "quality", "finance", "admin", "onboarding"];
    for (const path of required) {
      expect(screens.some((screen) => screen.path === path)).toBe(true);
    }
    expect(screens.every((screen) => screen.missing.length > 0)).toBe(true);
  });
});
