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
    if (url.includes("/config/options/categories")) return json([{ code: "PAYMENT_MODES", name: "Payment modes", scope: "tenant", tenantEditable: true, supportsOrdering: true }]);
    if (url.includes("/config/options/")) return json([]);
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
    await userEvent.click(screen.getByRole("button", { name: /Customer Directory/ }));
    expect(screen.getByRole("heading", { name: "Customer Directory" })).toBeInTheDocument();
  });

  it("keeps Customer Directory behind the add-customer sheet and on close", async () => {
    renderAt("/sales?tab=customers");
    await userEvent.click(await screen.findByRole("button", { name: "Add New Customer" }));
    expect(await screen.findByRole("dialog")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Customer Directory" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Customer POs" })).not.toBeInTheDocument();
    await userEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Close" }));
    expect(await screen.findByRole("heading", { name: "Customer Directory" })).toBeInTheDocument();
  });

  it("opens supplier quotation entry forms in a side sheet", async () => {
    const inviteView = renderAt("/procurement/supplier-rfq/new?mode=invite");
    const inviteDialog = await screen.findByRole("dialog");
    expect(within(inviteDialog).getByRole("heading", { name: "Invite Suppliers for Quotation" })).toBeInTheDocument();
    inviteView.unmount();

    const quoteView = renderAt("/procurement/supplier-rfq/new?mode=quote");
    const quoteDialog = await screen.findByRole("dialog");
    expect(within(quoteDialog).getByRole("heading", { name: "Record Supplier Quotation" })).toBeInTheDocument();
    quoteView.unmount();
  });

  it("keeps the maintenance events list behind the event detail side sheet", async () => {
    const maintenanceAsset = {
      id: "22222222-2222-4222-8222-222222222222", assetCode: "CNC-01", name: "CNC Lathe",
      siteId: "11111111-1111-4111-8111-111111111111", criticality: "high", meterType: "hours",
      status: "breakdown", openEvents: 1,
    };
    const maintenanceEvent = {
      id: "ev1", eventNumber: "ME-1", assetId: maintenanceAsset.id, assetCode: "CNC-01",
      assetName: "CNC Lathe", eventType: "Breakdown", status: "open", symptom: "Spindle failure",
      technicianName: "Ravi", affectedJobs: [], affectedStages: [],
    };
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo) => {
      const url = String(input);
      if (url.includes("/auth/me")) return json({ id: "1", tenantId: "t", email: "owner@example.test", displayName: "Owner", status: "active", roles: [{ role: "OWNER" }] });
      if (url.includes("/tenant/info")) return json({ id: "t", name: "North Works", status: "active" });
      if (url.includes("/onboarding")) return json({ industryType: "GENERAL_MANUFACTURING", completed: true, company: { sites: [], stores: [] } });
      if (url.includes("/maintenance/lookups")) return json({ sites: [], technicians: [], jobs: [] });
      if (url.includes("/maintenance/events/ev1/activity")) return json([]);
      if (url.endsWith("/maintenance/events/ev1")) return json(maintenanceEvent);
      if (url.endsWith("/maintenance/events")) return json([maintenanceEvent]);
      if (url.includes("/maintenance/assets?")) return json([maintenanceAsset]);
      if (url.includes("/config/options/")) return json([]);
      return json({});
    }));

    renderAt("/assets/me/ev1");
    const dialog = await screen.findByRole("dialog");
    expect(await within(dialog).findByRole("heading", { name: "ME-1" })).toBeInTheDocument();
    expect(await screen.findByRole("tab", { name: "Maintenance Events" })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findAllByText("ME-1")).toHaveLength(2);
    expect(await screen.findAllByText(/CNC-01/)).toHaveLength(2);
    expect(screen.queryByText("No records are available for this account.")).not.toBeInTheDocument();
  });

  it("keeps stage updates and their confirmation in a side sheet", async () => {
    const jobId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
    const stageJob = {
      id: jobId,
      jobNumber: "JOB-100",
      customerName: "North Works",
      status: "released",
      qty: 10,
      uomSymbol: "ea",
      stages: [{ id: "stage-1", seqNo: 1, stageName: "Cutting", status: "pending", requiresInspection: false, inspectionStatus: "not_required", isOptional: false, canSkip: false }],
      materials: [],
      movements: [],
      inspections: [],
    };
    vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/auth/me")) return json({ id: "1", tenantId: "t", email: "owner@example.test", displayName: "Owner", status: "active", roles: [{ role: "OWNER" }] });
      if (url.includes("/tenant/info")) return json({ id: "t", name: "North Works", status: "active" });
      if (url.includes("/onboarding")) return json({ industryType: "GENERAL_MANUFACTURING", completed: true, company: { sites: [], stores: [] } });
      if (url.includes("/jobs/features")) return json({ "production.stage.transition": true });
      if (url.endsWith(`/api/v1/jobs/${jobId}/stages/stage-1/start`) && init?.method === "POST") return json(stageJob);
      if (url.endsWith(`/api/v1/jobs/${jobId}`)) return json(stageJob);
      return json({});
    }));

    renderAt(`/jobs/${jobId}/stage/stage-1`);
    const dialog = await screen.findByRole("dialog");
    expect(screen.getByRole("heading", { name: "North Works" })).toBeInTheDocument();
    expect(within(dialog).getByRole("heading", { name: "Stage Update" })).toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: /complete/i })).toBeDisabled();
    await userEvent.click(within(dialog).getByRole("button", { name: "Save Update" }));
    expect(await within(dialog).findByRole("heading", { name: "Update Recorded" })).toBeInTheDocument();
  });

  it("opens master-data configuration under Admin & Settings", async () => {
    renderAt("/admin?tab=master-data");
    expect(await screen.findByRole("heading", { name: "Master Data & Business Configuration" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "Reference data category" })).toHaveValue("PAYMENT_MODES");
  });

  it("shows an empty table instead of seeded rows", async () => {
    renderAt("/sales");
    expect(await screen.findByText("No POs match the current filters")).toBeInTheDocument();
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
