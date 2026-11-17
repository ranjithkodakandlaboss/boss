import type { components, paths } from "./generated/platform-admin";

export type TenantProvisionRequest = components["schemas"]["TenantProvisionRequest"] & {
  gstin?: string;
  registeredAddress?: string;
  siteAddress?: string;
  adminFullName?: string;
  adminPhone?: string;
};
export type ProvisionResult = components["schemas"]["ProvisionResult"];
export type AdminInviteResult =
  paths["/tenants/{tenantId}/admin-invite"]["post"]["responses"][201]["content"]["application/json"];
type ContractTenantPage = paths["/tenants"]["get"]["responses"][200]["content"]["application/json"];
type ContractTenant = NonNullable<ContractTenantPage["content"]>[number];
export type TenantSummary = {
  [K in keyof ContractTenant]-?: NonNullable<ContractTenant[K]>;
} & {
  inviteStatus?: string;
  inviteEmail?: string;
  workspaceUrl?: string;
};
export interface TenantPage {
  content: TenantSummary[];
  page: number;
  totalElements: number;
}
export type TenantStatusFilter = NonNullable<
  NonNullable<paths["/tenants"]["get"]["parameters"]["query"]>["status"]
>;

export interface ValidateInviteResponse {
  valid: boolean;
  inviteId: string;
  tenantId: string;
  tenantName: string;
  tenantSlug: string;
  email: string;
  role: string;
  status: string;
  expiresAt: string;
  isExpired: boolean;
}

export interface CustomerActivationRequest {
  token: string;
  firebaseUid: string;
  displayName?: string;
  phone?: string;
}

export interface CustomerActivationResponse {
  activated: boolean;
  userId: string;
  tenantId: string;
  tenantSlug: string;
  status: string;
}

export interface AdminInviteRequest {
  email: string;
}

export interface SuspendRequest {
  reason: string;
}

export interface ApiResponse<T> {
  status: number;
  body: T;
}

export interface PlatformAdminApi {
  listTenants(params: {
    status?: TenantStatusFilter;
    page: number;
    size: number;
  }): Promise<TenantPage>;
  provisionTenant(
    request: TenantProvisionRequest,
    idempotencyKey: string,
  ): Promise<ApiResponse<ProvisionResult | undefined>>;
  activateTenant(tenantId: string): Promise<void>;
  suspendTenant(tenantId: string, reason: string): Promise<void>;
  reactivateTenant(tenantId: string): Promise<void>;
  retryInitialAdminInvite(
    tenantId: string,
    request: AdminInviteRequest,
    idempotencyKey: string,
  ): Promise<AdminInviteResult>;
}

export class PlatformAdminApiError extends Error {
  constructor(
    readonly status: number,
    message: string,
  ) {
    super(message);
    this.name = "PlatformAdminApiError";
  }
}

export class PlatformAdminConfigurationError extends Error {
  constructor(message: string) {
    super(message);
    this.name = "PlatformAdminConfigurationError";
  }
}

export function createPlatformAdminApi(
  baseUrl: string | undefined,
  getIdToken: () => Promise<string>,
  fetcher: typeof fetch = fetch,
): PlatformAdminApi {
  async function request<T>(
    path: string,
    init: RequestInit = {},
    acceptedStatuses: number[] = [],
    requireBody = true,
    ignoredBodyStatuses: number[] = [],
  ): Promise<ApiResponse<T>> {
    if (!baseUrl?.trim()) {
      throw new PlatformAdminConfigurationError(
        "Set VITE_PLATFORM_ADMIN_API_BASE_URL to the dedicated Platform Admin API base URL.",
      );
    }

    let apiBase: URL;
    try {
      apiBase = baseUrl.startsWith("/")
        ? new URL(baseUrl, typeof window !== "undefined" && window.location?.origin ? window.location.origin : "http://localhost")
        : new URL(baseUrl);
    } catch {
      throw new PlatformAdminConfigurationError("VITE_PLATFORM_ADMIN_API_BASE_URL must be a valid URL or path.");
    }
    if (!apiBase.pathname.replace(/\/+$/, "").endsWith("/admin/api/v1") || apiBase.search || apiBase.hash) {
      throw new PlatformAdminConfigurationError(
        "VITE_PLATFORM_ADMIN_API_BASE_URL must target the dedicated /admin/api/v1 service path.",
      );
    }

    const token = await getIdToken();
    if (!token) {
      throw new PlatformAdminApiError(401, "Your Platform Admin session expired. Sign in again.");
    }

    const url = new URL(path.replace(/^\/+/, ""), `${apiBase.toString().replace(/\/+$/, "")}/`);
    const headers = new Headers(init.headers);
    headers.set("Accept", "application/json");
    headers.set("Authorization", `Bearer ${token}`);
    if (init.body) headers.set("Content-Type", "application/json");

    const response = await fetcher(url, { ...init, headers });
    const text = ignoredBodyStatuses.includes(response.status) ? "" : await response.text();
    let body: unknown;
    if (text) {
      try {
        body = JSON.parse(text) as unknown;
      } catch {
        body = undefined;
      }
    }

    if (!response.ok && !acceptedStatuses.includes(response.status)) {
      throw new PlatformAdminApiError(
        response.status,
        errorMessage(body, response.statusText),
      );
    }

    if (requireBody && (body === undefined || body === null)) {
      throw new PlatformAdminApiError(
        response.status,
        "The Platform Admin API returned an empty response.",
      );
    }

    return { status: response.status, body: body as T };
  }

  return {
    async listTenants({ status, page, size }) {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (status) params.set("status", status);
      const response = await request<ContractTenantPage>(`/tenants?${params.toString()}`);
      return normalizeTenantPage(response.body);
    },
    provisionTenant(requestBody, idempotencyKey) {
      return request<ProvisionResult | undefined>(
        "/tenants",
        {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKey },
          body: JSON.stringify(requestBody),
        },
        [424],
        false,
        [424],
      ).then((response) => {
        if (response.status === 201 && !response.body) {
          throw new PlatformAdminApiError(201, "The Platform Admin API returned an empty provisioning result.");
        }
        if (response.status === 424) return { status: 424, body: undefined };
        return response;
      });
    },
    async activateTenant(tenantId) {
      await request<unknown>(
        `/tenants/${encodeURIComponent(tenantId)}/activate`,
        { method: "POST" },
        [],
        false,
      );
    },
    async suspendTenant(tenantId, reason) {
      await request<unknown>(
        `/tenants/${encodeURIComponent(tenantId)}/suspend`,
        { method: "POST", body: JSON.stringify({ reason } satisfies SuspendRequest) },
        [],
        false,
      );
    },
    async reactivateTenant(tenantId) {
      await request<unknown>(
        `/tenants/${encodeURIComponent(tenantId)}/reactivate`,
        { method: "POST" },
        [],
        false,
      );
    },
    async retryInitialAdminInvite(tenantId, requestBody, idempotencyKey) {
      const response = await request<AdminInviteResult>(
        `/tenants/${encodeURIComponent(tenantId)}/admin-invite`,
        {
          method: "POST",
          headers: { "Idempotency-Key": idempotencyKey },
          body: JSON.stringify(requestBody),
        },
      );
      return response.body;
    },
  };
}

function errorMessage(body: unknown, fallback: string): string {
  if (typeof body !== "object" || body === null) return fallback || "The request failed.";
  const record = body as Record<string, unknown>;
  for (const key of ["message", "detail", "error"]) {
    const value = record[key];
    if (typeof value === "string" && value.trim()) return value.slice(0, 400);
  }
  return fallback || "The request failed.";
}

function normalizeTenantPage(body: ContractTenantPage | undefined): TenantPage {
  if (
    typeof body !== "object" ||
    body === null ||
    !Array.isArray(body.content) ||
    typeof body.page !== "number" ||
    typeof body.totalElements !== "number"
  ) {
    throw new PlatformAdminApiError(200, "The Platform Admin API returned an invalid tenant list response.");
  }

  const content = body.content.map((tenant): TenantSummary => {
    if (typeof tenant !== "object" || tenant === null) {
      throw new PlatformAdminApiError(200, "The Platform Admin API returned an invalid tenant record.");
    }
    const { id, name, slug, status, planTier, industryType } = tenant;
    const raw = tenant as Record<string, unknown>;
    if (
      typeof id !== "string" ||
      typeof name !== "string" ||
      typeof slug !== "string" ||
      typeof status !== "string" ||
      typeof planTier !== "string"
    ) {
      throw new PlatformAdminApiError(200, "The Platform Admin API returned an incomplete tenant record.");
    }
    return {
      id,
      name,
      slug,
      status,
      planTier,
      industryType: typeof industryType === "string" ? industryType : "",
      inviteStatus: typeof raw.inviteStatus === "string" ? raw.inviteStatus : undefined,
      inviteEmail: typeof raw.inviteEmail === "string" ? raw.inviteEmail : undefined,
      workspaceUrl: typeof raw.workspaceUrl === "string" ? raw.workspaceUrl : undefined,
    };
  });
  return { content, page: body.page, totalElements: body.totalElements };
}

export async function validateInviteToken(
  baseUrl: string | undefined,
  token: string,
  tenantSlug?: string,
): Promise<ValidateInviteResponse> {
  const root = (baseUrl || "").replace(/\/+$/, "");
  let endpoint = root ? `${root}/invites/validate?token=${encodeURIComponent(token)}` : `/admin/api/v1/invites/validate?token=${encodeURIComponent(token)}`;
  if (tenantSlug) {
    endpoint += `&tenant=${encodeURIComponent(tenantSlug)}`;
  }
  const response = await fetch(endpoint, {
    method: "GET",
    headers: { Accept: "application/json" },
  });
  const text = await response.text();
  let json: Record<string, unknown> | null = null;
  try {
    json = JSON.parse(text);
  } catch {}

  if (!response.ok) {
    const msg = (json && (json.message || json.detail || json.error)) ? String(json.message || json.detail || json.error) : `Validation failed (${response.status})`;
    throw new PlatformAdminApiError(response.status, msg);
  }
  return json as unknown as ValidateInviteResponse;
}

export async function activateCustomerAccount(baseUrl: string | undefined, req: CustomerActivationRequest): Promise<CustomerActivationResponse> {
  const root = (baseUrl || "").replace(/\/+$/, "");
  const endpoint = root ? `${root}/invites/activate` : `/admin/api/v1/invites/activate`;
  const response = await fetch(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/json", Accept: "application/json" },
    body: JSON.stringify(req),
  });
  const text = await response.text();
  let json: Record<string, unknown> | null = null;
  try {
    json = JSON.parse(text);
  } catch {}

  if (!response.ok) {
    const msg = (json && (json.message || json.detail || json.error)) ? String(json.message || json.detail || json.error) : `Activation failed (${response.status})`;
    throw new PlatformAdminApiError(response.status, msg);
  }
  return json as unknown as CustomerActivationResponse;
}
