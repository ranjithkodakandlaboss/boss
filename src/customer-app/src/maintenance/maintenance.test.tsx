import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { CustomerProvider } from "../auth/CustomerState";
import { signIn } from "../auth/session";
import AssetsPage from "./AssetsPage";

const siteId = "11111111-1111-4111-8111-111111111111";
const asset = {
  id: "22222222-2222-4222-8222-222222222222", assetCode: "CNC-01", name: "CNC Lathe", category: "CNC Machine", siteId, siteName: "Plant",
  workCentre: "Machining", criticality: "high", meterType: "hours", currentReading: 100, lastServiceReading: 50, pmFrequencyHours: 100,
  status: "operational", openEvents: 0,
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

beforeEach(() => {
  posted = []; failCreate = false;
  signIn("test-session");
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/auth/me")) return Promise.resolve(json({ id: "1", tenantId: "t", email: "o@x.test", displayName: "Owner", status: "active", roles: [{ role: "OWNER" }] }));
    if (url.includes("/tenant/info")) return Promise.resolve(json({ id: "t", name: "Works", status: "active" }));
    if (url.includes("/onboarding")) return Promise.resolve(json({ industryType: "GENERAL_MANUFACTURING", completed: true, company: { sites: [], stores: [] } }));
    const cat = url.match(/config\/options\/(\w+)/);
    if (cat) return Promise.resolve(json(options[cat[1]] ?? []));
    if (url.includes("/maintenance/lookups")) return Promise.resolve(json({ sites: [{ id: siteId, name: "Plant", code: "P1" }], technicians: [], jobs: [] }));
    if (init?.method === "POST" && url.includes("/maintenance/assets")) {
      expect(new Headers(init.headers).get("Idempotency-Key")).toBeTruthy();
      if (failCreate) return Promise.resolve(json({ message: "Asset code already exists" }, 409));
      posted.push({ url, body: JSON.parse(String(init.body)) });
      return Promise.resolve(json(asset, 201));
    }
    if (url.includes("/maintenance/assets")) return Promise.resolve(json([asset]));
    if (url.includes("/maintenance/events")) return Promise.resolve(json([]));
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
  await waitFor(() => expect(within(dialog).getByRole("option", { name: "Plant" })).toBeTruthy());
  await userEvent.selectOptions(within(dialog).getByLabelText("Site"), siteId);
  failCreate = true;
  await userEvent.click(within(dialog).getByRole("button", { name: "Save asset" }));
  expect((await within(dialog).findByRole("alert")).textContent).toContain("Asset code already exists");
  failCreate = false;
  await userEvent.click(within(dialog).getByRole("button", { name: "Save asset" }));
  await waitFor(() => expect(posted).toHaveLength(1));
  expect(posted[0].body).toMatchObject({ assetCode: "PRESS-9", siteId });
});
