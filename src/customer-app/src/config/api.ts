import { api, safeRandomUUID } from "../auth/api";

export type ConfigCategory = {
  code: string;
  name: string;
  scope: string;
  tenantEditable: boolean;
  supportsOrdering: boolean;
};

export type ConfigOption = {
  id: string;
  categoryCode: string;
  code: string;
  label: string;
  sortOrder: number;
  isSystem: boolean;
  isActive: boolean;
};

function send<T>(method: string, path: string, body?: unknown) {
  return api<T>(path, {
    method,
    headers: { "Idempotency-Key": safeRandomUUID() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
}

export const listConfigCategories = () => api<ConfigCategory[]>("/api/v1/config/options/categories");
export const listConfigOptions = (category: string) =>
  api<ConfigOption[]>(`/api/v1/config/options/${encodeURIComponent(category)}?includeInactive=true`);
export const createConfigOption = (category: string, body: { code: string; label: string; sortOrder: number }) =>
  send<ConfigOption>("POST", `/api/v1/config/options/${encodeURIComponent(category)}`, body);
export const updateConfigOption = (category: string, optionId: string, body: { label: string }) =>
  send<ConfigOption>("PUT", `/api/v1/config/options/${encodeURIComponent(category)}/${optionId}`, body);
export const setConfigOptionActive = (category: string, optionId: string, active: boolean) =>
  send<{ status: string }>("PATCH", `/api/v1/config/options/${encodeURIComponent(category)}/${optionId}/status`, { active });
export const reorderConfigOptions = (category: string, optionIds: string[]) =>
  send<{ status: string }>("PUT", `/api/v1/config/options/${encodeURIComponent(category)}/order`, { optionIds });
