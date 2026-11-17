import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  PathsTenantsTenantIdAdminInvitePostResponses201ContentApplicationJsonInvitationStatus,
  PathsTenantsGetParametersQueryStatus,
  ProvisionResultInvitationStatus,
  TenantActivationResultInvitationStatus,
  TenantActivationResultTenantStatus,
  TenantDetailsTenantStatus,
  TenantProvisionRequestEntityType,
  TenantProvisionRequestIndustryType,
} from "../api/generated/platform-admin";
import {
  PlatformAdminApiError,
  type PlatformAdminApi,
  type TenantDetails,
  type TenantSummary,
} from "../api/platformAdminApi";
import { CustomerConsole } from "./CustomerConsole";

const sampleTenant: TenantSummary = {
  id: "tenant-123",
  name: "Acme Manufacturing",
  slug: "acme-manufacturing",
  status: PathsTenantsGetParametersQueryStatus.active,
  planTier: "pilot",
  industryType: TenantProvisionRequestIndustryType.FABRICATION_STEEL,
  invitationStatus: "NOT_REQUESTED",
  adminEmail: "owner@acme.example",
  workspaceUrl: "https://acme.example.com",
};

const sampleDetails: TenantDetails = {
  tenantId: "tenant-123",
  customerGroupId: "group-123",
  customerGroupName: "Acme Group",
  customerGroupSlug: "acme-group",
  tenantName: "Acme Manufacturing",
  tenantSlug: "acme-manufacturing",
  tenantStatus: TenantDetailsTenantStatus.active,
  planTier: "pilot",
  industryType: TenantProvisionRequestIndustryType.FABRICATION_STEEL,
  invitationStatus: "NOT_REQUESTED",
  adminEmail: "owner@acme.example",
  workspaceUrl: "https://acme.example.com",
  legalEntities: [{
    id: "entity-123",
    name: "Acme Manufacturing Pvt Ltd",
    legalName: "Acme Manufacturing Pvt Ltd",
    pan: "ABCDE1234F",
    entityType: "PRIVATE_LIMITED",
    registeredAddress: "1 Factory Road",
    status: "active",
    gstRegistrations: [{
      id: "gst-123",
      gstin: "27ABCDE1234F1Z5",
      tradeName: "Acme Trade",
      registrationType: "regular",
      stateCode: "27",
      status: "active",
      effectiveFrom: "2025-01-01",
      effectiveTo: null,
    }],
  }],
  sites: [{
    id: "site-123",
    legalEntityId: "entity-123",
    gstRegistrationId: "gst-123",
    name: "Factory",
    code: "SITE-01",
    siteType: "factory",
    address: "1 Factory Road",
    status: "active",
  }],
};

function createApi(overrides: Partial<PlatformAdminApi> = {}): PlatformAdminApi {
  return {
    listTenants: vi.fn<PlatformAdminApi["listTenants"]>().mockResolvedValue({
      content: [sampleTenant],
      page: 0,
      totalElements: 1,
    }),
    getTenantDetails: vi.fn<PlatformAdminApi["getTenantDetails"]>().mockResolvedValue(sampleDetails),
    updateTenantDetails: vi.fn<PlatformAdminApi["updateTenantDetails"]>().mockResolvedValue({
      tenantId: "tenant-123",
      updatedFields: ["tenantName"],
    }),
    provisionTenant: vi.fn<PlatformAdminApi["provisionTenant"]>(),
    activateTenant: vi.fn<PlatformAdminApi["activateTenant"]>(),
    suspendTenant: vi.fn<PlatformAdminApi["suspendTenant"]>(),
    reactivateTenant: vi.fn<PlatformAdminApi["reactivateTenant"]>(),
    retryInitialAdminInvite: vi.fn<PlatformAdminApi["retryInitialAdminInvite"]>(),
    ...overrides,
  };
}

function renderConsole(api: PlatformAdminApi) {
  const user = userEvent.setup();
  render(<CustomerConsole api={api} adminEmail="admin@example.com" onSignOut={vi.fn()} />);
  return user;
}

async function openCreateForm(user: ReturnType<typeof userEvent.setup>) {
  await user.click(await screen.findByRole("button", { name: "Create customer" }));
}

async function fillValidForm(user: ReturnType<typeof userEvent.setup>) {
  await user.type(screen.getByLabelText(/Customer group name/), "Acme Group");
  await user.type(screen.getByLabelText(/Customer group slug/), "acme-group");
  await user.type(screen.getByLabelText(/Tenant name/), "Acme Manufacturing");
  await user.type(screen.getByLabelText(/Tenant slug/), "acme-manufacturing");
  await user.type(screen.getByLabelText(/Legal entity name/), "Acme Manufacturing Pvt Ltd");
  await user.type(screen.getByLabelText(/^PAN/), "ABCDE1234F");
  await user.selectOptions(screen.getByLabelText(/Entity type/), TenantProvisionRequestEntityType.PRIVATE_LIMITED);
  await user.type(screen.getByLabelText(/Site code/), "SITE-01");
  await user.type(screen.getByLabelText(/Site name/), "Factory");
  await user.selectOptions(screen.getByLabelText(/Industry type/), TenantProvisionRequestIndustryType.FABRICATION_STEEL);
}

describe("customer directory and provisioning flow", () => {
  beforeEach(() => {
    vi.stubGlobal("crypto", { randomUUID: () => "idempotency-key-1" });
    vi.stubGlobal("navigator", {
      ...navigator,
      clipboard: { writeText: vi.fn().mockResolvedValue(undefined) },
    });
  });

  it("loads tenants, filters by API status, and paginates", async () => {
    const api = createApi({
      listTenants: vi.fn<PlatformAdminApi["listTenants"]>()
        .mockResolvedValueOnce({ content: [sampleTenant], page: 0, totalElements: 41 })
        .mockResolvedValue({ content: [sampleTenant], page: 1, totalElements: 41 }),
    });

    const user = renderConsole(api);

    expect(await screen.findByText("Acme Manufacturing")).toBeInTheDocument();
    expect(screen.getByText("Acme Manufacturing")).toBeInTheDocument();
    await user.selectOptions(screen.getByRole("combobox", { name: "Filter by status" }), "suspended");
    await waitFor(() => {
      expect(api.listTenants).toHaveBeenLastCalledWith({
        page: 0,
        size: 20,
        status: PathsTenantsGetParametersQueryStatus.suspended,
      });
    });
    await user.click(screen.getByRole("button", { name: "Next" }));
    await waitFor(() => {
      expect(api.listTenants).toHaveBeenLastCalledWith({
        page: 1,
        size: 20,
        status: PathsTenantsGetParametersQueryStatus.suspended,
      });
    });
  });

  it("loads tenant, company, and site details from the API", async () => {
    const api = createApi();
    const user = renderConsole(api);

    await screen.findByText("Acme Manufacturing");
    await user.click(screen.getByRole("button", { name: "Details" }));

    const details = screen.getByRole("dialog", { name: "Customer details" });
    expect(within(details).getByText("tenant-123")).toBeInTheDocument();
    expect(within(details).getByText("Acme Manufacturing")).toBeInTheDocument();
    expect(within(details).getByText("Acme Group")).toBeInTheDocument();
    expect(within(details).getAllByText("Acme Manufacturing Pvt Ltd")).toHaveLength(2);
    expect(within(details).getAllByText("1 Factory Road")).toHaveLength(2);
    expect(within(details).getByText("ABCDE1234F")).toBeInTheDocument();
    expect(within(details).getByText("27ABCDE1234F1Z5")).toBeInTheDocument();
    expect(api.getTenantDetails).toHaveBeenCalledWith("tenant-123");
    expect(within(details).getByRole("button", { name: "Change email & send invite" })).toBeInTheDocument();

    await user.click(within(details).getByRole("button", { name: "Change email & send invite" }));
    expect(screen.getByRole("dialog", { name: "Initial administrator invitation" })).toBeInTheDocument();
    expect(screen.getByLabelText("Administrator email")).toHaveValue("owner@acme.example");
  });

  it("saves permitted customer details and shows the persisted API response", async () => {
    const api = createApi({
      updateTenantDetails: vi.fn<PlatformAdminApi["updateTenantDetails"]>().mockResolvedValue({
        tenantId: "tenant-123",
        updatedFields: ["tenantName", "legalEntity:entity-123"],
      }),
    });
    const user = renderConsole(api);

    await screen.findByText("Acme Manufacturing");
    await user.click(screen.getByRole("button", { name: "Details" }));
    const details = screen.getByRole("dialog", { name: "Customer details" });
    await user.click(await within(details).findByRole("button", { name: "Edit details" }));
    await user.clear(within(details).getByLabelText("Tenant name"));
    await user.type(within(details).getByLabelText("Tenant name"), "Acme Manufacturing West");
    await user.clear(within(details).getByLabelText("Legal name"));
    await user.type(within(details).getByLabelText("Legal name"), "Acme Manufacturing West Pvt Ltd");
    await user.click(within(details).getByRole("button", { name: "Save details" }));

    expect(await within(details).findByText("Customer details saved.")).toBeInTheDocument();
    expect(within(details).getByText("Acme Manufacturing West")).toBeInTheDocument();
    expect(within(details).getByText("Acme Manufacturing West Pvt Ltd")).toBeInTheDocument();
    expect(api.updateTenantDetails).toHaveBeenCalledWith(
      "tenant-123",
      {
        tenantName: "Acme Manufacturing West",
        legalEntities: [{ id: "entity-123", legalName: "Acme Manufacturing West Pvt Ltd" }],
      },
      "idempotency-key-1",
    );
  });

  it("shows detail loading and API error states", async () => {
    const failed = createApi({
      getTenantDetails: vi.fn<PlatformAdminApi["getTenantDetails"]>().mockRejectedValue(
        new PlatformAdminApiError(503, "Service unavailable"),
      ),
    });
    const user = renderConsole(failed);
    await screen.findByText("Acme Manufacturing");
    await user.click(screen.getByRole("button", { name: "Details" }));
    expect(await screen.findByText("Could not load customer details")).toBeInTheDocument();
    expect(screen.getByText("Service unavailable (HTTP 503)")).toBeInTheDocument();
  });

  it("shows loading, empty, and API error states without inventing customer data", async () => {
    const pending = createApi({
      listTenants: vi.fn<PlatformAdminApi["listTenants"]>().mockImplementation(
        () => new Promise(() => undefined),
      ),
    });
    renderConsole(pending);
    expect(screen.getByText("Loading customer directory")).toBeInTheDocument();

    cleanup();
    const empty = createApi({
      listTenants: vi.fn<PlatformAdminApi["listTenants"]>().mockResolvedValue({
        content: [],
        page: 0,
        totalElements: 0,
      }),
    });
    render(<CustomerConsole api={empty} adminEmail="admin@example.com" onSignOut={vi.fn()} />);
    expect(await screen.findByText("No customers yet")).toBeInTheDocument();

    cleanup();
    const failed = createApi({
      listTenants: vi.fn<PlatformAdminApi["listTenants"]>().mockRejectedValue(
        new PlatformAdminApiError(503, "Service unavailable"),
      ),
    });
    render(<CustomerConsole api={failed} adminEmail="admin@example.com" onSignOut={vi.fn()} />);
    expect(await screen.findByText("Could not load customers")).toBeInTheDocument();
    expect(screen.getByText("Service unavailable (HTTP 503)")).toBeInTheDocument();
  });

  it("marks every required field before making a create request", async () => {
    const api = createApi();
    const user = renderConsole(api);
    await openCreateForm(user);
    await user.click(screen.getByRole("button", { name: "Create customer" }));

    expect(screen.getByLabelText(/Customer group name/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/Tenant name/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/Tenant slug/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/Legal entity name/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/^PAN/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/Site code/)).toHaveAttribute("aria-invalid", "true");
    expect(screen.getByLabelText(/Site name/)).toHaveAttribute("aria-invalid", "true");
    expect(api.provisionTenant).not.toHaveBeenCalled();
  });

  it("validates the contract fields before submitting and shows provisioning identifiers and statuses", async () => {
    const api = createApi({
      provisionTenant: vi.fn<PlatformAdminApi["provisionTenant"]>().mockResolvedValue({
        status: 201,
        body: {
          tenantId: "tenant-created",
          customerGroupId: "group-created",
          legalEntityId: "entity-created",
          siteId: "site-created",
          seedingApplied: true,
          invitationStatus: ProvisionResultInvitationStatus.PENDING_ACTIVATION,
        },
      }),
    });
    const user = renderConsole(api);
    await openCreateForm(user);
    await fillValidForm(user);
    await user.type(screen.getByLabelText(/Administrator email/), "bad-email");
    await user.clear(screen.getByLabelText(/^PAN/));
    await user.type(screen.getByLabelText(/^PAN/), "ABCDE123");
    await user.click(screen.getByRole("button", { name: "Create customer" }));

    expect(screen.getByText(/valid 10-character PAN/)).toBeInTheDocument();
    expect(screen.getByText("Enter a valid email address.")).toBeInTheDocument();
    expect(api.provisionTenant).not.toHaveBeenCalled();

    await user.clear(screen.getByLabelText(/^PAN/));
    await user.type(screen.getByLabelText(/^PAN/), "ABCDE1234F");
    await user.clear(screen.getByLabelText(/Administrator email/));
    await user.type(screen.getByLabelText(/Administrator email/), "owner@acme.example");
    await user.click(screen.getByRole("button", { name: "Create customer" }));

    expect(await screen.findByText("Customer provisioning accepted")).toBeInTheDocument();
    expect(screen.getByText("tenant-created")).toBeInTheDocument();
    expect(screen.getByText("group-created")).toBeInTheDocument();
    expect(screen.getByText("entity-created")).toBeInTheDocument();
    expect(screen.getByText("site-created")).toBeInTheDocument();
    expect(screen.getByText("PENDING_ACTIVATION")).toBeInTheDocument();
    expect(screen.getByText(/No invitation has been created or sent/)).toBeInTheDocument();
    expect(screen.queryByText("one-time-token")).not.toBeInTheDocument();
    expect(api.provisionTenant).toHaveBeenCalledWith(
      expect.objectContaining({
        customerGroupName: "Acme Group",
        customerGroupSlug: "acme-group",
        tenantName: "Acme Manufacturing",
        tenantSlug: "acme-manufacturing",
        pan: "ABCDE1234F",
        adminEmail: "owner@acme.example",
      }),
      "idempotency-key-1",
    );
  });

  it("displays server conflict and validation errors in the create flow", async () => {
    const api = createApi({
      provisionTenant: vi.fn<PlatformAdminApi["provisionTenant"]>().mockRejectedValue(
        new PlatformAdminApiError(409, "Tenant or customer group slug already exists"),
      ),
    });
    const user = renderConsole(api);
    await openCreateForm(user);
    await fillValidForm(user);
    await user.click(screen.getByRole("button", { name: "Create customer" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Tenant or customer group slug already exists (HTTP 409)");
  });

  it("confirms lifecycle actions, requires a suspension reason, and displays the returned state", async () => {
    const api = createApi({
      listTenants: vi.fn<PlatformAdminApi["listTenants"]>()
        .mockResolvedValueOnce({ content: [sampleTenant], page: 0, totalElements: 1 })
        .mockResolvedValue({
          content: [{ ...sampleTenant, status: PathsTenantsGetParametersQueryStatus.suspended }],
          page: 0,
          totalElements: 1,
        }),
      suspendTenant: vi.fn<PlatformAdminApi["suspendTenant"]>().mockResolvedValue(undefined),
    });
    const user = renderConsole(api);
    await screen.findByText("Acme Manufacturing");
    await user.click(screen.getByRole("button", { name: "Suspend" }));
    expect(screen.getByRole("dialog", { name: "Suspend tenant?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Confirm suspend" })).toBeDisabled();
    await user.type(screen.getByLabelText("Reason *"), "Contract review");
    await user.click(screen.getByRole("button", { name: "Confirm suspend" }));

    const row = screen.getByRole("row", { name: /Acme Manufacturing/ });
    expect(await within(row).findByText("Suspended")).toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent(
      "Suspension request succeeded. The directory is reloading the current status from the API.",
    );
    expect(api.suspendTenant).toHaveBeenCalledWith("tenant-123", "Contract review");
  });

  it.each([
    {
      status: PathsTenantsGetParametersQueryStatus.provisioning,
      action: "Activate",
      confirm: "Confirm activate",
      method: "activateTenant" as const,
      result: PathsTenantsGetParametersQueryStatus.active,
    },
    {
      status: PathsTenantsGetParametersQueryStatus.suspended,
      action: "Reactivate",
      confirm: "Confirm reactivate",
      method: "reactivateTenant" as const,
      result: PathsTenantsGetParametersQueryStatus.active,
    },
  ])("$action tenant uses the refreshed API status", async (scenario) => {
    const listTenants = vi.fn<PlatformAdminApi["listTenants"]>()
      .mockResolvedValueOnce({
        content: [{ ...sampleTenant, status: scenario.status }],
        page: 0,
        totalElements: 1,
      })
      .mockResolvedValue({
        content: [{ ...sampleTenant, status: scenario.result }],
        page: 0,
        totalElements: 1,
      });
    const overrides: Partial<PlatformAdminApi> = { listTenants };
    if (scenario.method === "activateTenant") {
      overrides.activateTenant = vi.fn<PlatformAdminApi["activateTenant"]>().mockResolvedValue({
        tenantId: "tenant-123",
        tenantStatus: TenantActivationResultTenantStatus.active,
        invitationStatus: TenantActivationResultInvitationStatus.NOT_REQUESTED,
      });
    } else {
      overrides.reactivateTenant = vi.fn<PlatformAdminApi["reactivateTenant"]>().mockResolvedValue(undefined);
    }
    const api = createApi(overrides);
    const user = renderConsole(api);

    await screen.findByText("Acme Manufacturing");
    await user.click(screen.getByRole("button", { name: scenario.action }));
    await user.click(screen.getByRole("button", { name: scenario.confirm }));

    const row = screen.getByRole("row", { name: /Acme Manufacturing/ });
    expect(await within(row).findByText("Active")).toBeInTheDocument();
    expect(api[scenario.method]).toHaveBeenCalledWith("tenant-123");
  });

  it("reports invitation-delivery failure after activation and offers retry", async () => {
    const api = createApi({
      listTenants: vi.fn<PlatformAdminApi["listTenants"]>()
        .mockResolvedValueOnce({
          content: [{
            ...sampleTenant,
            status: PathsTenantsGetParametersQueryStatus.provisioning,
            adminEmail: "owner@acme.example",
          }],
          page: 0,
          totalElements: 1,
        })
        .mockResolvedValue({
          content: [{
            ...sampleTenant,
            adminEmail: "owner@acme.example",
            invitationStatus: "RETRY_REQUIRED",
          }],
          page: 0,
          totalElements: 1,
        }),
      activateTenant: vi.fn<PlatformAdminApi["activateTenant"]>().mockResolvedValue({
        tenantId: "tenant-123",
        tenantStatus: TenantActivationResultTenantStatus.active,
        invitationStatus: TenantActivationResultInvitationStatus.RETRY_REQUIRED,
      }),
    });
    const user = renderConsole(api);
    await screen.findByText("Acme Manufacturing");
    await user.click(screen.getByRole("button", { name: "Activate" }));
    await user.click(screen.getByRole("button", { name: "Confirm activate" }));

    expect(await screen.findByRole("status")).toHaveTextContent(
      "The tenant is active, but invitation delivery failed.",
    );
    expect(await screen.findByRole("button", { name: "Retry invite" })).toBeInTheDocument();
  });

  it("shows invitation retry errors and clears the one-time token after handoff", async () => {
    const api = createApi({
      retryInitialAdminInvite: vi.fn<PlatformAdminApi["retryInitialAdminInvite"]>()
        .mockRejectedValueOnce(new PlatformAdminApiError(503, "IDENTITY_INVITE_UNAVAILABLE"))
        .mockResolvedValueOnce({
          tenantId: "tenant-123",
          inviteId: "invite-123",
          adminInviteToken: "temporary-invite-token",
          invitationStatus: PathsTenantsTenantIdAdminInvitePostResponses201ContentApplicationJsonInvitationStatus.CREATED,
          expiresAt: "2026-11-17T00:00:00Z",
        }),
    });
    const user = renderConsole(api);
    await screen.findByText("Acme Manufacturing");
    await user.click(screen.getByRole("button", { name: "Retry invite" }));
    expect(screen.getByLabelText("Administrator email")).toHaveValue("owner@acme.example");
    await user.click(screen.getByRole("button", { name: "Create invitation" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("IDENTITY_INVITE_UNAVAILABLE (HTTP 503)");

    await user.click(screen.getByRole("button", { name: "Create invitation" }));
    const handoff = await screen.findByRole("dialog", { name: "Invitation response" });
    expect(within(handoff).getByText("temporary-invite-token")).toBeInTheDocument();
    expect(within(handoff).getByText(/CREATED/)).toBeInTheDocument();
    expect(api.retryInitialAdminInvite).toHaveBeenCalledWith(
      "tenant-123",
      { email: "owner@acme.example" },
      "idempotency-key-1",
    );
    await user.click(within(handoff).getByRole("button", { name: "Clear and close" }));
    expect(screen.queryByText("temporary-invite-token")).not.toBeInTheDocument();
  });
});
