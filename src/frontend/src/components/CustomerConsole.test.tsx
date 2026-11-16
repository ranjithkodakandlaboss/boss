import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it, vi } from "vitest";
import {
  PathsTenantsTenantIdAdminInvitePostResponses201ContentApplicationJsonInvitationStatus,
  PathsTenantsGetParametersQueryStatus,
  ProvisionResultInvitationStatus,
  TenantProvisionRequestEntityType,
  TenantProvisionRequestIndustryType,
} from "../api/generated/platform-admin";
import { PlatformAdminApiError, type PlatformAdminApi, type TenantSummary } from "../api/platformAdminApi";
import { CustomerConsole } from "./CustomerConsole";

const sampleTenant: TenantSummary = {
  id: "tenant-123",
  name: "Acme Manufacturing",
  slug: "acme-manufacturing",
  status: PathsTenantsGetParametersQueryStatus.active,
  planTier: "pilot",
  industryType: TenantProvisionRequestIndustryType.FABRICATION_STEEL,
};

function createApi(overrides: Partial<PlatformAdminApi> = {}): PlatformAdminApi {
  return {
    listTenants: vi.fn<PlatformAdminApi["listTenants"]>().mockResolvedValue({
      content: [sampleTenant],
      page: 0,
      totalElements: 1,
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
          adminInviteToken: "one-time-token",
          seedingApplied: true,
          invitationStatus: ProvisionResultInvitationStatus.CREATED,
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
    expect(screen.getByText("CREATED")).toBeInTheDocument();
    expect(screen.getByText("one-time-token")).toBeInTheDocument();
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
      overrides.activateTenant = vi.fn<PlatformAdminApi["activateTenant"]>().mockResolvedValue(undefined);
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
    await user.click(screen.getByRole("button", { name: "Invite admin" }));
    await user.type(screen.getByLabelText("Administrator email"), "owner@acme.example");
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
