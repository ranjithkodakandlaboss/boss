import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { CustomerProvider } from "../auth/CustomerState";
import { signIn } from "../auth/session";
import DispatchPage from "./DispatchPage";
import DispatchDetailPage from "./DispatchDetailPage";

const mockDispatches = [
  {
    id: "dsp-1",
    dispatchNumber: "DSP-2024-101",
    customerId: "cust-1",
    customerName: "Tata Motors",
    cpoId: "cpo-1",
    cpoNumber: "PO-9901",
    jobId: "job-1",
    jobNumber: "JOB-101",
    jobs: ["JOB-101"],
    sourceSiteId: "site-1",
    sourceSiteName: "Main Plant",
    sourceStoreId: "store-1",
    sourceStoreName: "FG Store",
    carrier: "Blue Dart",
    dispatchMethodOptionId: "method-1",
    dispatchMethodLabel: "Road Freight (historical)",
    vehicleNumber: "MH12AB1234",
    driverName: "Raju",
    lrNumber: "LR-101",
    dispatchDate: "2026-11-09",
    expectedDelivery: "2026-11-12",
    totalQty: 50,
    lineCount: 1,
    status: "pending_docs",
    hasPoD: false,
    createdAt: "2026-11-09T10:00:00Z",
  },
  {
    id: "dsp-2",
    dispatchNumber: "DSP-2024-102",
    customerId: "cust-2",
    customerName: "Mahindra & Mahindra",
    cpoId: "cpo-2",
    cpoNumber: "PO-9902",
    jobId: "job-2",
    jobNumber: "JOB-102",
    jobs: ["JOB-102"],
    sourceSiteId: "site-1",
    sourceSiteName: "Main Plant",
    carrier: "VRL Logistics",
    vehicleNumber: "KA01CD5678",
    dispatchDate: "2026-11-10",
    expectedDelivery: "2026-11-13",
    totalQty: 100,
    lineCount: 1,
    status: "dispatched",
    hasPoD: false,
    createdAt: "2026-11-10T10:00:00Z",
  },
];

const mockDetail = {
  ...mockDispatches[0],
  status: "dispatched",
  destination: "Pune Dock 3",
  actualDeliveryDate: "",
  deliveryCondition: "good",
  podReference: "",
  deliveryNotes: "",
  lines: [
    {
      id: "dl-1",
      dispatchId: "dsp-1",
      cpoLineId: "cpol-1",
      jobId: "job-1",
      jobNumber: "JOB-101",
      itemId: "item-1",
      itemCode: "SH-01",
      itemName: "Shaft",
      quantity: 50,
      uomId: "uom-1",
      uomSymbol: "pcs",
      lineNo: 1,
    },
  ],
  packages: [
    {
      id: "pkg-1",
      dispatchId: "dsp-1",
      packageCode: "PKG-01",
      packageType: "Wooden Crate",
      productDescription: "Machined Shafts Batch 1",
      quantity: 50,
      uom: "pcs",
      weightKg: 85,
      lengthMm: 1200,
      widthMm: 800,
      heightMm: 600,
      packingNotes: "Fragile",
      createdAt: "2026-11-09T10:30:00Z",
    },
  ],
  history: [
    {
      id: "h-1",
      action: "create",
      title: "Dispatch Created",
      description: "Dispatch created by Owner",
      timestamp: "2026-11-09T10:00:00Z",
    },
  ],
  linkedInvoices: [],
  updatedAt: "2026-11-09T10:30:00Z",
};

const mockCompany = {
  sites: [{ id: "site-1", name: "Main Plant", code: "MP-01" }],
  stores: [{ id: "store-1", siteId: "site-1", name: "FG Store", code: "FGS", storeType: "finished_goods" }],
};

const mockCustomers = [
  { id: "cust-1", name: "Tata Motors", tradingName: "Tata Motors" },
  { id: "cust-2", name: "Mahindra & Mahindra", tradingName: "Mahindra & Mahindra" },
];

const mockJobs = [
  {
    id: "job-1",
    jobNumber: "JOB-101",
    cpoId: "cpo-1",
    cpoLineId: "cpo-line-1",
    cpoNumber: "PO-9901",
    customerId: "cust-1",
    customerName: "Tata Motors",
    itemId: "item-1",
    itemCode: "SH-01",
    itemName: "Shaft",
    qty: 10,
    uomSymbol: "pcs",
    siteId: "site-1",
    status: "completed",
  },
];

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });
let postedReqs: { url: string; body: any }[] = [];
let dispatchMethodsFail = false;
let qualityReleaseMissing = false;

beforeEach(() => {
  postedReqs = [];
  dispatchMethodsFail = false;
  qualityReleaseMissing = false;
  signIn("test-session");
  vi.stubGlobal(
    "fetch",
    vi.fn((input: RequestInfo, init?: RequestInit) => {
      const url = String(input);
      if (url.includes("/auth/me")) {
        return Promise.resolve(
          json({
            id: "1",
            tenantId: "t",
            email: "owner@example.test",
            displayName: "Owner",
            status: "active",
            roles: [{ role: "OWNER" }],
          })
        );
      }
      if (url.includes("/tenant/info")) {
        return Promise.resolve(json({ id: "t", name: "BOSS Works", status: "active" }));
      }
      if (url.includes("/onboarding")) {
        return Promise.resolve(json({ completed: true, company: mockCompany }));
      }
      if (url.includes("/api/v1/company")) {
        return Promise.resolve(json(mockCompany));
      }
      if (url.includes("/api/v1/customers")) {
        return Promise.resolve(json(mockCustomers));
      }
      if (url.includes("/api/v1/jobs")) {
        return Promise.resolve(json(mockJobs));
      }
      if (url.includes("/api/v1/inventory/lots")) {
        return Promise.resolve(json({
          content: [{
            id: "lot-1",
            itemId: "item-1",
            itemCode: "SH-01",
            itemName: "Shaft",
            storeId: "store-1",
            storeName: "FG Store",
            siteId: "site-1",
            lotNumber: "FG-LOT-1",
            qty: 10,
            reservedQty: 0,
            availableQty: 10,
            uomSymbol: "pcs",
            status: "available",
          }],
          totalElements: 1,
        }));
      }
      if (url.includes("/api/v1/config/options/CARRIER")) {
        if (init?.method === "POST") {
          const body = JSON.parse(String(init.body));
          postedReqs.push({ url, body });
          return Promise.resolve(json({ id: "carrier-new", code: body.code, label: body.label }, 201));
        }
        return Promise.resolve(json([{ id: "c-1", code: "BLUE_DART", label: "Blue Dart" }]));
      }
      if (url.includes("/api/v1/config/options/VEHICLE")) {
        return Promise.resolve(json([{ id: "v-1", code: "MH12", label: "MH12AB1234" }]));
      }
      if (url.includes("/api/v1/config/options/DISPATCH_METHOD")) {
        if (dispatchMethodsFail) return Promise.resolve(json({ message: "Unavailable" }, 500));
        return Promise.resolve(json([{ id: "method-1", code: "ROAD", label: "Road Freight" }]));
      }
      if (url.includes("/api/v1/quality/jobs/job-1/dispatch-eligibility")) {
        return Promise.resolve(
          json({
            jobId: "job-1",
            eligible: !qualityReleaseMissing,
            reason: qualityReleaseMissing
              ? "Customer clearance certificate has not been issued"
              : "All quality conditions satisfied",
            blockers: qualityReleaseMissing ? ["Customer clearance certificate has not been issued."] : [],
            inspectionStatus: "passed",
            clearanceCertificateId: qualityReleaseMissing ? undefined : "cert-1",
            clearanceCertificateStatus: qualityReleaseMissing ? "not_required" : "issued",
          })
        );
      }
      if (url.includes("/api/v1/dispatches/cpo-lines/cpo-line-1/availability")) {
        return Promise.resolve(json({
          cpoLineId: "cpo-line-1",
          orderedQty: 20,
          deliveredQty: 0,
          pendingQty: 0,
          remainingQty: 20,
        }));
      }
      if (url.endsWith("/api/v1/quality/clearance-certificates") && init?.method === "POST") {
        const body = JSON.parse(String(init.body));
        postedReqs.push({ url, body });
        qualityReleaseMissing = false;
        return Promise.resolve(json({ id: "cert-1", jobId: body.jobId, status: "issued" }, 201));
      }
      if (url.endsWith("/api/v1/dispatches") && init?.method === "POST") {
        const body = JSON.parse(String(init.body));
        postedReqs.push({ url, body });
        return Promise.resolve(
          json({
            ...mockDetail,
            id: "dsp-created",
            dispatchNumber: "DSP-2024-103",
            customerId: body.customerId,
            customerName: body.customerName,
            status: "pending_docs",
          }, 201)
        );
      }
      if (url.includes("/api/v1/dispatches/dsp-1/pod") && init?.method === "POST") {
        const body = JSON.parse(String(init.body));
        postedReqs.push({ url, body });
        return Promise.resolve(
          json({
            ...mockDetail,
            status: "delivered",
            hasPoD: true,
            receivedByName: body.receivedBy,
            actualDeliveryDate: body.deliveredDate,
          })
        );
      }
      if (url.includes("/api/v1/dispatches/dsp-1")) {
        return Promise.resolve(json(mockDetail));
      }
      if (url.includes("/api/v1/dispatches")) {
        return Promise.resolve(json(mockDispatches));
      }
      return Promise.resolve(json({}));
    })
  );
});

function renderWithCustomer(ui: React.ReactElement, initialPath = "/dispatch") {
  return render(
    <MemoryRouter initialEntries={[initialPath]}>
      <CustomerProvider>
        <Routes>
          <Route path="/dispatch" element={ui} />
          <Route path="/dispatch/:id" element={<DispatchDetailPage />} />
        </Routes>
      </CustomerProvider>
    </MemoryRouter>
  );
}

it("renders dispatch landing page with KPI pills and table rows", async () => {
  renderWithCustomer(<DispatchPage />);

  await waitFor(() => {
    expect(screen.getAllByText("DSP-2024-101").length).toBeGreaterThan(0);
  });
  expect(screen.getAllByText("DSP-2024-102").length).toBeGreaterThan(0);
  expect(screen.getAllByText("Tata Motors").length).toBeGreaterThan(0);
  expect(screen.getAllByText("Mahindra & Mahindra").length).toBeGreaterThan(0);
  expect(screen.getByText("Road Freight (historical)")).toBeInTheDocument();
  expect(screen.getByRole("columnheader", { name: /Ship from/i })).toBeInTheDocument();
  expect(screen.getByRole("columnheader", { name: "Customer" })).toBeInTheDocument();

  // KPI buttons
  expect(screen.getByText("1 pending docs")).toBeInTheDocument();
  expect(screen.getByText("1 in transit")).toBeInTheDocument();
});

it("reports an error when dispatch methods cannot be loaded", async () => {
  dispatchMethodsFail = true;
  renderWithCustomer(<DispatchPage />);

  expect(await screen.findByText("Error loading dispatch records")).toBeInTheDocument();
});

it("filters dispatch list by search input", async () => {
  const user = userEvent.setup();
  renderWithCustomer(<DispatchPage />);

  await waitFor(() => {
    expect(screen.getAllByText("DSP-2024-101").length).toBeGreaterThan(0);
  });

  const searchInput = screen.getByPlaceholderText("Search dispatches…");
  await user.type(searchInput, "Tata");

  expect(screen.getAllByText("DSP-2024-101").length).toBeGreaterThan(0);
  expect(screen.queryByText("DSP-2024-102")).not.toBeInTheDocument();
});

it("opens New Dispatch modal and creates dispatch", async () => {
  const user = userEvent.setup();
  renderWithCustomer(<DispatchPage />);

  await waitFor(() => {
    expect(screen.getByText("New Dispatch")).toBeInTheDocument();
  });

  await user.click(screen.getByText("New Dispatch"));

  await waitFor(() => {
    expect(screen.getByRole("heading", { name: "New Dispatch" })).toBeInTheDocument();
  });

  // Select customer
  const custSelect = screen.getByRole("combobox", { name: /^Customer$/i });
  await user.selectOptions(custSelect, "cust-1");
  await user.selectOptions(screen.getByRole("combobox", { name: /Customer PO/i }), "cpo-1");
  await user.selectOptions(screen.getByRole("combobox", { name: /PO line/i }), "job-1");
  await user.selectOptions(screen.getByRole("combobox", { name: /Finished-goods lot/i }), "lot-1");
  await user.selectOptions(
    screen.getByRole("combobox", { name: /Dispatch Method/i }),
    "method-1"
  );

  // Create dispatch
  const createBtn = screen.getByRole("button", { name: "Create Dispatch" });
  expect(await screen.findByText("Remaining Customer PO quantity: 20 pcs")).toBeInTheDocument();
  expect(createBtn).toBeVisible();
  expect(screen.getByRole("spinbutton", { name: /Quantity/i })).toHaveValue(10);
  await user.click(createBtn);

  await waitFor(() => {
    expect(postedReqs.some((r) => r.url.endsWith("/api/v1/dispatches"))).toBe(true);
  });
  const body = postedReqs.find((r) => r.url.endsWith("/api/v1/dispatches"))?.body;
  expect(body?.dispatchMethodOptionId).toBe("method-1");
  expect(body?.cpoId).toBe("cpo-1");
  expect(body?.lines).toEqual([{
    cpoLineId: "cpo-line-1",
    jobId: "job-1",
    itemId: "item-1",
    quantity: 10,
    lotId: "lot-1",
  }]);
});

it("requires a Customer PO line, finished-goods lot, and explicit quantity", async () => {
  const user = userEvent.setup();
  renderWithCustomer(<DispatchPage />);

  await user.click(await screen.findByText("New Dispatch"));

  expect(await screen.findByRole("combobox", { name: /Customer PO/i })).toBeInTheDocument();
  expect(screen.getByRole("combobox", { name: /PO line/i })).toBeInTheDocument();
  expect(screen.getByRole("combobox", { name: /Finished-goods lot/i })).toBeInTheDocument();
  expect(screen.getByRole("spinbutton", { name: /Quantity/i })).toBeInTheDocument();
});

it("issues a quality release after a passed final inspection", async () => {
  qualityReleaseMissing = true;
  const user = userEvent.setup();
  renderWithCustomer(<DispatchPage />);

  await user.click(await screen.findByText("New Dispatch"));
  await user.selectOptions(await screen.findByRole("combobox", { name: /^Customer$/i }), "cust-1");
  await user.selectOptions(screen.getByRole("combobox", { name: /Customer PO/i }), "cpo-1");
  await user.selectOptions(screen.getByRole("combobox", { name: /PO line/i }), "job-1");

  await user.click(await screen.findByRole("button", { name: "Issue quality release" }));

  await waitFor(() => {
    expect(postedReqs.some((request) =>
      request.url.endsWith("/api/v1/quality/clearance-certificates")
    )).toBe(true);
  });
  expect(await screen.findByText(/Dispatch-eligible/)).toBeInTheDocument();
});

it("renders Dispatch Detail page with details, shipment summary and packages", async () => {
  renderWithCustomer(<DispatchDetailPage />, "/dispatch/dsp-1");

  await waitFor(() => {
    expect(screen.getByText("DSP-2024-101")).toBeInTheDocument();
  });

  expect(screen.getByText("Tata Motors")).toBeInTheDocument();
  expect(screen.getByText("Dispatch Details")).toBeInTheDocument();
  expect(screen.getByText("Shipment Summary")).toBeInTheDocument();
  expect(screen.getByText("Blue Dart")).toBeInTheDocument();
  expect(screen.getByText("Road Freight (historical)")).toBeInTheDocument();
  expect(screen.getByText("MH12AB1234")).toBeInTheDocument();
  expect(screen.getByText(/Packages/)).toBeInTheDocument();
  expect(screen.getByText("PKG-01")).toBeInTheDocument();
});

it("records Proof of Delivery from Detail page", async () => {
  const user = userEvent.setup();
  renderWithCustomer(<DispatchDetailPage />, "/dispatch/dsp-1");

  await waitFor(() => {
    expect(screen.getByText("DSP-2024-101")).toBeInTheDocument();
  });

  const receivedByInput = screen.getByPlaceholderText(/Customer receiver name/i);
  await user.type(receivedByInput, "Suresh Patil");

  const confirmBtn = screen.getByRole("button", { name: /Confirm Delivery & Record PoD/i });
  await user.click(confirmBtn);

  await waitFor(() => {
    expect(postedReqs.some((r) => r.url.includes("/api/v1/dispatches/dsp-1/pod"))).toBe(true);
  });
});
