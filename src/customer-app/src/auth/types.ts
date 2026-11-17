export type SessionUser = {
  id: string;
  tenantId: string;
  email: string;
  displayName: string | null;
  status: string;
  roles: { role: string }[];
};

export type TenantInfo = { id: string; name: string; status: string };

export type CompanyRecord = {
  companyName: string | null;
  tradingName: string | null;
  legalName: string | null;
  entityType: string | null;
  pan: string | null;
  gstin: string | null;
  addressLine: string | null;
  city: string | null;
  state: string | null;
  email: string | null;
  phone: string | null;
  financialYear: string | null;
  currency: string | null;
  sites: { id?: string; name: string; code: string; siteType?: string; city?: string; state?: string; addressLine?: string }[];
  stores: { name: string; code: string; siteCode: string; storeType?: string }[];
};

export type OnboardingState = {
  industryType: string;
  completed: boolean;
  company: CompanyRecord;
};
