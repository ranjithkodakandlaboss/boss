import { describe, expect, it, vi } from "vitest";
import {
  PlatformAdminApiError,
  PlatformAdminConfigurationError,
  createPlatformAdminApi,
} from "./platformAdminApi";
import {
  TenantProvisionRequestEntityType,
  TenantProvisionRequestIndustryType,
} from "./generated/platform-admin";

const apiBase = "https://admin-api-dev.boss.app/admin/api/v1";

describe("Platform Admin API client", () => {
  it("uses the dedicated API path, admin bearer token, and required idempotency key", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({
        tenantId: "tenant-123",
        customerGroupId: "group-123",
        legalEntityId: "entity-123",
        siteId: "site-123",
        seedingApplied: true,
        invitationStatus: "NOT_REQUESTED",
      }),
      { status: 201, headers: { "Content-Type": "application/json" } },
    ));
    const getIdToken = vi.fn().mockResolvedValue("platform-admin-id-token");
    const api = createPlatformAdminApi(apiBase, getIdToken, fetcher);

    await api.provisionTenant({
      customerGroupName: "Example Group",
      customerGroupSlug: "example-group",
      tenantName: "Example Tenant",
      tenantSlug: "example-tenant",
      planTier: "pilot",
      legalEntityName: "Example Manufacturing",
      pan: "ABCDE1234F",
      entityType: TenantProvisionRequestEntityType.PRIVATE_LIMITED,
      siteCode: "SITE-01",
      siteName: "Factory",
      industryType: TenantProvisionRequestIndustryType.FABRICATION_STEEL,
    }, "request-key");

    expect(fetcher).toHaveBeenCalledOnce();
    const [url, init] = fetcher.mock.calls[0];
    expect(url).toBeInstanceOf(URL);
    expect((url as URL).href).toBe(`${apiBase}/tenants`);
    const headers = new Headers(init?.headers);
    expect(headers.get("Authorization")).toBe("Bearer platform-admin-id-token");
    expect(headers.get("Idempotency-Key")).toBe("request-key");
  });

  it("does not send an admin token to a base URL outside the dedicated service path", async () => {
    const fetcher = vi.fn<typeof fetch>();
    const getIdToken = vi.fn().mockResolvedValue("platform-admin-id-token");
    const api = createPlatformAdminApi("https://boss-api.example.com/api/v1", getIdToken, fetcher);

    await expect(api.listTenants({ page: 0, size: 20 })).rejects.toBeInstanceOf(
      PlatformAdminConfigurationError,
    );
    expect(getIdToken).not.toHaveBeenCalled();
    expect(fetcher).not.toHaveBeenCalled();
  });

  it("preserves API conflict status and message for the UI", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({ error: "CONFLICT", message: "Tenant slug already exists" }),
      { status: 409, headers: { "Content-Type": "application/json" } },
    ));
    const api = createPlatformAdminApi(apiBase, async () => "platform-admin-id-token", fetcher);

    await expect(api.activateTenant("tenant-123")).rejects.toEqual(
      new PlatformAdminApiError(409, "Tenant slug already exists"),
    );
  });

  it("does not consume the undocumented 424 provisioning response body", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({
        tenantId: "undocumented-tenant-id",
        seedingApplied: true,
        invitationStatus: "RETRY_REQUIRED",
        adminInviteToken: "must-not-be-consumed",
      }),
      { status: 424, headers: { "Content-Type": "application/json" } },
    ));
    const api = createPlatformAdminApi(apiBase, async () => "platform-admin-id-token", fetcher);

    const response = await api.provisionTenant({
      customerGroupName: "Example Group",
      tenantName: "Example Tenant",
      tenantSlug: "example-tenant",
      planTier: "pilot",
      legalEntityName: "Example Manufacturing",
      pan: "ABCDE1234F",
      entityType: TenantProvisionRequestEntityType.PRIVATE_LIMITED,
      siteCode: "SITE-01",
      siteName: "Factory",
      industryType: TenantProvisionRequestIndustryType.FABRICATION_STEEL,
    }, "request-key");

    expect(response).toEqual({ status: 424, body: undefined });
  });

  it("sends an idempotency key when retrying the initial administrator invitation", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({
        tenantId: "tenant-123",
        inviteId: "invite-123",
        invitationStatus: "CREATED",
        expiresAt: "2026-11-17T00:00:00Z",
      }),
      { status: 201, headers: { "Content-Type": "application/json" } },
    ));
    const api = createPlatformAdminApi(apiBase, async () => "platform-admin-id-token", fetcher);

    await api.retryInitialAdminInvite(
      "tenant-123",
      { email: "owner@example.com" },
      "invite-request-key",
    );

    const [url, init] = fetcher.mock.calls[0];
    expect(String(url)).toBe(`${apiBase}/tenants/tenant-123/admin-invite`);
    expect(new Headers(init?.headers).get("Idempotency-Key")).toBe("invite-request-key");
  });

  it("supports relative service paths to avoid cross-origin mismatch during local development", async () => {
    const fetcher = vi.fn<typeof fetch>().mockResolvedValue(new Response(
      JSON.stringify({ content: [], page: 0, totalElements: 0 }),
      { status: 200, headers: { "Content-Type": "application/json" } },
    ));
    const api = createPlatformAdminApi("/admin/api/v1", async () => "token-1", fetcher);

    await api.listTenants({ page: 0, size: 20 });

    const [url] = fetcher.mock.calls[0];
    expect(String(url)).toContain("/admin/api/v1/tenants");
  });
});
