import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { CustomerProvider } from "../auth/CustomerState";
import { signIn } from "../auth/session";
import AssetsPage from "./AssetsPage";
import { AssetDetailPage, MaintenanceEventDetailPage } from "./AssetDetailPage";

const siteId = "11111111-1111-4111-8111-111111111111";
const asset = {
  id: "22222222-2222-4222-8222-222222222222", assetCode: "CNC-01", name: "CNC Lathe", category: "CNC Machine", siteId, siteName: "Plant",
  workCentre: "Machining", criticality: "high", meterType: "hours", currentReading: 100, lastServiceReading: 50, pmFrequencyHours: 100,
  status: "operational", openEvents: 0,
};
const event = {
  id: "ev1", eventNumber: "ME-1", assetId: asset.id, assetCode: "CNC-01", assetName: "CNC Lathe",
  eventType: "Breakdown", status: "open", symptom: "Spindle failure", technicianName: "Ravi",
  affectedJobs: [{ id: "job1", jobNumber: "JOB-1" }],
  affectedStages: [{
    id: "stage1", jobId: "job1", jobNumber: "JOB-1", sequence: 1, name: "Cutting", status: "pending",
    customerOrderNumber: "PO-1", requiredByDate: "2026-12-01",
    alternatives: [{ id: "asset2", assetCode: "CNC-02", name: "Backup lathe", workCentre: "Machining" }],
  }],
  responsibleOwner: "Ravi Tech",
};
const options: Record<string, { id: string; code: string; label: string }[]> = {
  ASSET_CATEGORY: [{ id: "c1", code: "CNC", label: "CNC Machine" }],
  WORK_CENTRE: [{ id: "w1", code: "MACH", label: "Machining" }],
  ASSET_CRITICALITY: [{ id: "k1", code: "high", label: "High" }],
  ASSET_STATUS: [{ id: "s1", code: "operational", label: "Operational" }, { id: "s2", code: "breakdown", label: "Breakdown" }],
  METER_TYPE: [{ id: "m1", code: "hours", label: "Hours" }],
  MAINTENANCE_EVENT_TYPE: [{ id: "t1", code: "Breakdown", label: "Breakdown" }],
  MAINTENANCE_SEVERITY: [{ id: "v1", code: "high", label: "High" }],
  MAINTENANCE_EVENT_STATUS: [{ id: "e1", code: "open", label: "Open" }],
};
const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
let posted: { url: string; body: unknown }[] = [];
let failCreate = false;
let failOptions = false;
let emptyWorkCentres = false;
let configPosts: { url: string; body: any }[] = [];
let repairs: unknown[] = [];

beforeEach(() => {
  posted = []; failCreate = false; failOptions = false; emptyWorkCentres = false; configPosts = []; repairs = [];
  signIn("test-session");
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/auth/me")) return Promise.resolve(json({ id: "1", tenantId: "t", email: "o@x.test", displayName: "Owner", status: "active", roles: [{ role: "OWNER" }] }));
    if (url.includes("/tenant/info")) return Promise.resolve(json({ id: "t", name: "Works", status: "active" }));
    if (url.includes("/onboarding")) return Promise.resolve(json({ industryType: "GENERAL_MANUFACTURING", completed: true, company: { sites: [], stores: [] } }));
    const cat = url.match(/config\/options\/(\w+)/);
    if (cat && init?.method === "POST") {
      const body = JSON.parse(String(init.body));
      configPosts.push({ url, body });
      const created = { id: "new-" + body.code, code: body.code, label: body.label };
      options[cat[1]] = [...(options[cat[1]] ?? []), created];
      emptyWorkCentres = false;
      return Promise.resolve(json(created, 201));
    }
    if (cat) {
      if (failOptions) return Promise.resolve(json({ message: "Config unavailable" }, 500));
      if (cat[1] === "WORK_CENTRE" && emptyWorkCentres) return Promise.resolve(json([]));
      return Promise.resolve(json(options[cat[1]] ?? []));
    }
    if (url.includes("/maintenance/assets/") && url.endsWith("/repair")) { repairs.push(JSON.parse(String(init?.body))); return Promise.resolve(json({}, 200)); }
    if (url.includes("/maintenance/assets/") && url.endsWith("/readings")) return Promise.resolve(json([]));
    if (url.includes("/maintenance/assets/") && url.endsWith("/activity")) return Promise.resolve(json([{ action: "CREATE", actor: "Owner", at: "2026-01-01T00:00:00Z" }]));
    if (url.includes("/maintenance/events/") && url.endsWith("/activity")) return Promise.resolve(json([]));
    if (url.includes("/maintenance/events/")) return Promise.resolve(json(event));
    if (/\/maintenance\/assets\/[\w-]+$/.test(url)) return Promise.resolve(json({ ...asset, status: "breakdown", openEvents: 1 }));
    if (url.includes("/maintenance/lookups")) return Promise.resolve(json({ sites: [{ id: siteId, name: "Plant", code: "P1" }], technicians: [], jobs: [] }));
    if (init?.method === "POST" && url.includes("/maintenance/assets")) {
      expect(new Headers(init.headers).get("Idempotency-Key")).toBeTruthy();
      if (failCreate) return Promise.resolve(json({ message: "Asset code already exists" }, 409));
      posted.push({ url, body: JSON.parse(String(init.body)) });
      return Promise.resolve(json(asset, 201));
    }
    if (url.includes("/maintenance/assets")) return Promise.resolve(json([asset]));
    if (url.includes("/maintenance/events")) return Promise.resolve(json([event]));
    return Promise.resolve(json({}));
  }));
});

function renderPage() {
  return render(<MemoryRouter initialEntries={["/assets"]}><CustomerProvider><Routes><Route path="/assets" element={<AssetsPage />} /></Routes></CustomerProvider></MemoryRouter>);
}

it("lists assets and takes dropdown options from the config API", async () => {
  renderPage();
  expect(await screen.findByText("CNC-01")).toBeTruthy();
  await userEvent.click(await screen.findByRole("button", { name: /Add Asset/ }));
  const dialog = await screen.findByRole("dialog");
  await waitFor(() => expect(within(dialog).getByRole("option", { name: "CNC Machine" })).toBeTruthy());
  expect(within(dialog).getByRole("option", { name: "Machining" })).toBeTruthy();
});

it("creates an asset and shows API errors", async () => {
  renderPage();
  await userEvent.click(await screen.findByRole("button", { name: /Add Asset/ }));
  const dialog = await screen.findByRole("dialog");
  await userEvent.type(within(dialog).getByLabelText("Asset code"), "PRESS-9");
  await userEvent.type(within(dialog).getByLabelText("Asset name"), "Press");
  await userEvent.type(within(dialog).getByLabelText("Manufacturer"), "Acme");
  await waitFor(() => expect(within(dialog).getByRole("option", { name: "Plant" })).toBeTruthy());
  await userEvent.selectOptions(within(dialog).getByLabelText("Site"), siteId);
  failCreate = true;
  await userEvent.click(within(dialog).getByRole("button", { name: "Save Asset" }));
  expect((await within(dialog).findByRole("alert")).textContent).toContain("Asset code already exists");
  failCreate = false;
  await userEvent.click(within(dialog).getByRole("button", { name: "Save Asset" }));
  await waitFor(() => expect(posted).toHaveLength(1));
  expect(posted[0].body).toMatchObject({ assetCode: "PRESS-9", siteId });
});

async function openAddAsset() {
  renderPage();
  await userEvent.click(await screen.findByRole("button", { name: /Add Asset/ }));
  return screen.findByRole("dialog");
}

it("offers Add New for an empty tenant dropdown, then refreshes and selects the new value", async () => {
  emptyWorkCentres = true;
  const dialog = await openAddAsset();
  await userEvent.click(await within(dialog).findByRole("button", { name: /Add New Work centre/ }));
  await userEvent.type(within(dialog).getByLabelText("New Work centre"), "Paint Booth");
  await userEvent.click(within(dialog).getByRole("button", { name: "Add" }));
  await waitFor(() => expect(configPosts).toHaveLength(1));
  expect(configPosts[0].url).toContain("/config/options/WORK_CENTRE");
  expect(configPosts[0].body).toMatchObject({ code: "PAINT_BOOTH", label: "Paint Booth" });
  await waitFor(() => expect((within(dialog).getByRole("combobox", { name: "Work centre" }) as HTMLSelectElement).value).toBe("Paint Booth"));
});

it("offers Add New when a search matches nothing", async () => {
  const dialog = await openAddAsset();
  await userEvent.type(await within(dialog).findByLabelText("Search Category"), "Grinder");
  expect(within(dialog).getByText(/No match for/)).toBeTruthy();
  expect(within(dialog).getByRole("button", { name: /Add New Category/ })).toBeTruthy();
});

it("shows Retry instead of Add New when options fail to load, and never offers Add New for system categories", async () => {
  failOptions = true;
  await openAddAsset();
  await waitFor(() => expect(screen.getAllByRole("button", { name: "Retry" }).length).toBeGreaterThan(0));
  expect(screen.queryByRole("button", { name: /Add New/ })).toBeNull();
  failOptions = false;
  await userEvent.click(screen.getAllByRole("button", { name: "Retry" })[0]);
  await waitFor(() => expect(screen.getByRole("option", { name: "CNC Machine" })).toBeTruthy());
  expect(screen.queryByRole("button", { name: /Add New (Status|Meter type)/ })).toBeNull();
  expect(configPosts).toHaveLength(0);
});

it("Mark Repaired sends the completion time, root cause and work done entered by the user", async () => {
  render(<MemoryRouter initialEntries={[`/assets/${asset.id}`]}><CustomerProvider><Routes><Route path="/assets/:id" element={<AssetDetailPage />} /></Routes></CustomerProvider></MemoryRouter>);
  await userEvent.click(await screen.findByRole("button", { name: "Mark Repaired" }));
  const dialog = await screen.findByRole("dialog");
  await userEvent.click(within(dialog).getByRole("button", { name: "Confirm repaired" }));
  expect((await within(dialog).findByRole("alert")).textContent).toContain("required");
  expect(repairs).toHaveLength(0);
  await userEvent.type(within(dialog).getByLabelText("Repair root cause"), "Worn bearing");
  await userEvent.type(within(dialog).getByLabelText("Repair work done"), "Replaced bearing");
  await userEvent.click(within(dialog).getByRole("button", { name: "Confirm repaired" }));
  await waitFor(() => expect(repairs).toHaveLength(1));
  expect(repairs[0]).toMatchObject({ rootCause: "Worn bearing", workDone: "Replaced bearing" });
  expect((repairs[0] as { completionDate: string }).completionDate).toMatch(/^\d{4}-\d\d-\d\dT/);
});

it("shows affected stages, customer due dates, capable alternatives and the responsible owner", async () => {
  render(<MemoryRouter initialEntries={["/assets/me/ev1"]}><CustomerProvider><Routes><Route path="/assets/me/:id" element={<MaintenanceEventDetailPage />} /></Routes></CustomerProvider></MemoryRouter>);
  expect(await screen.findByRole("heading", { name: "Affected production stages" })).toBeTruthy();
  expect(screen.getByText("Job JOB-1 · 1. Cutting · pending")).toBeTruthy();
  expect(screen.getByText(/Customer order PO-1 · Required by/)).toBeTruthy();
  expect(screen.getByText(/CNC-02 — Backup lathe/)).toBeTruthy();
  expect(screen.getByText("Ravi Tech")).toBeTruthy();
  expect(screen.getByText(/the whole job is not put on hold/)).toBeTruthy();
  expect(screen.getByText(/responsible owner is not notified automatically/)).toBeTruthy();
});
