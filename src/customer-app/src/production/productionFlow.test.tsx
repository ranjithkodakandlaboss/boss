import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { CustomerProvider } from "../auth/CustomerState";
import { signIn, signOut } from "../auth/session";
import JobsPage from "./JobsPage";
import JobWorkspacePage from "./JobWorkspacePage";

const jobId = "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa";
const lineId = "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb";
const cpoId = "cccccccc-cccc-4ccc-8ccc-cccccccccccc";
const itemId = "dddddddd-dddd-4ddd-8ddd-dddddddddddd";
const templateId = "eeeeeeee-eeee-4eee-8eee-eeeeeeeeeeee";
const requirementId = "ffffffff-ffff-4fff-8fff-ffffffffffff";
const lotId = "11111111-1111-4111-8111-111111111111";

const job = {
  id: jobId,
  jobNumber: "JOB-100",
  cpoId,
  cpoNumber: "CPO-100",
  customerName: "North Works",
  itemId,
  itemName: "Frame",
  qty: 10,
  uomSymbol: "ea",
  routingTemplateName: "Frame route",
  routingTemplateVersion: "v3",
  status: "released",
  siteName: "Plant",
  plannedEnd: "2026-12-01",
  currentStage: "Cutting",
  materialReadiness: "partial",
  risk: "on_track",
  stages: [{ id: "stage-1", seqNo: 1, stageName: "Cutting", status: "pending", requiresInspection: false, inspectionStatus: "not_required", isOptional: false, canSkip: false }],
  materials: [{ id: requirementId, itemId, itemCode: "PLATE", itemName: "Plate", requiredQty: 20, issuedQty: 0, returnedQty: 0, netIssuedQty: 0, uomSymbol: "ea", readiness: "available" }],
  movements: [],
  inspections: [],
  actualCost: { sellingPrice: 1000, totalCost: 0 },
  estimatedCost: { totalCost: 50 },
};

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), { status });
}

beforeEach(() => {
  signIn("test-session");
  vi.stubGlobal("fetch", vi.fn((input: RequestInfo, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("/auth/me")) return Promise.resolve(json({ id: "1", tenantId: "t", email: "owner@example.test", displayName: "Owner", status: "active", roles: [{ role: "OWNER" }] }));
    if (url.includes("/tenant/info")) return Promise.resolve(json({ id: "t", name: "North Works", status: "active" }));
    if (url.includes("/onboarding")) return Promise.resolve(json({ industryType: "GENERAL_MANUFACTURING", completed: true, company: { sites: [], stores: [] } }));
    if (url.includes("/jobs/features")) return Promise.resolve(json({ "production.job.create": true, "production.routing.stages": true, "production.material.issue": true, "production.stage.transition": true }));
    if (url.includes("/eligible-cpos")) return Promise.resolve(json([{ cpoId, cpoLineId: lineId, cpoNumber: "CPO-100", customerName: "North Works", itemId, itemName: "Frame", qty: 10, sellingPrice: 1000, siteName: "Plant" }]));
    if (url.includes("/routing-templates")) return Promise.resolve(json([{ id: templateId, name: "Frame route", version: "v3", stageCount: 1 }]));
    if (url.includes("/inventory/lots")) return Promise.resolve(json({ content: [{ id: lotId, itemId, batchRef: "BATCH", availableQty: 20, siteId: "site" }] }));
    if (url.includes("/materials/issues") && init?.method === "POST") {
      expect(new Headers(init.headers).get("Idempotency-Key")).toBeTruthy();
      return Promise.resolve(json({ id: "issue-1", issueType: "issue", quantity: 20 }, 201));
    }
    if (url.endsWith("/api/v1/jobs") && init?.method === "POST") {
      expect(JSON.parse(String(init.body)).cpoLineId).toBe(lineId);
      return Promise.resolve(json({ ...job, id: jobId }, 201));
    }
    if (url.includes(`/jobs/${jobId}`)) return Promise.resolve(json(job));
    if (url.includes("/api/v1/jobs")) return Promise.resolve(json({ content: [job], totalElements: 1 }));
    return Promise.resolve(json({}));
  }));
  return () => signOut();
});

it("opens a job from an eligible customer order and issues material", async () => {
  render(
    <MemoryRouter initialEntries={["/jobs"]}>
      <CustomerProvider>
        <Routes>
          <Route path="/jobs" element={<JobsPage />} />
          <Route path="/jobs/:id" element={<JobWorkspacePage />} />
        </Routes>
      </CustomerProvider>
    </MemoryRouter>,
  );
  expect((await screen.findAllByText("JOB-100")).length).toBeGreaterThan(0);
  await userEvent.click(screen.getByRole("button", { name: "New Job" }));
  await userEvent.selectOptions(await screen.findByLabelText("Customer PO"), lineId);
  expect(screen.getByText(/Selling price stays on the Customer PO/)).toBeInTheDocument();
  await userEvent.selectOptions(screen.getByLabelText("Production workflow"), templateId);
  await userEvent.type(screen.getByLabelText("Planned End"), "2026-12-01");
  await userEvent.click(screen.getByRole("button", { name: "Create job" }));
  expect(await screen.findByRole("heading", { name: "North Works" })).toBeInTheDocument();
  await userEvent.click(screen.getByRole("button", { name: "Materials" }));
  await userEvent.selectOptions(await screen.findByLabelText("Lot for PLATE"), lotId);
  await userEvent.click(screen.getByRole("button", { name: "Issue" }));
  expect(await screen.findByText("Material issued")).toBeInTheDocument();
});
