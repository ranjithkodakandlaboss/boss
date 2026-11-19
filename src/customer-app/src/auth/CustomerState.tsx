import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { api, ApiError } from "./api";
import { currentToken, signOut } from "./session";
import type { OnboardingState, SessionUser, TenantInfo } from "./types";

type CustomerValue = {
  ready: boolean;
  user: SessionUser | null;
  tenant: TenantInfo | null;
  onboarding: OnboardingState | null;
  refresh: () => Promise<void>;
};

const CustomerContext = createContext<CustomerValue>({
  ready: false,
  user: null,
  tenant: null,
  onboarding: null,
  refresh: async () => undefined,
});

export function CustomerProvider({ children }: { children: ReactNode }) {
  const [ready, setReady] = useState(false);
  const [user, setUser] = useState<SessionUser | null>(null);
  const [tenant, setTenant] = useState<TenantInfo | null>(null);
  const [onboarding, setOnboarding] = useState<OnboardingState | null>(null);

  async function refresh() {
    if (!currentToken()) {
      setUser(null);
      setTenant(null);
      setOnboarding(null);
      setReady(true);
      return;
    }
    try {
      const [nextUser, nextTenant, nextOnboarding] = await Promise.all([
        api<SessionUser>("/api/v1/auth/me"),
        api<TenantInfo>("/api/v1/tenant/info"),
        api<OnboardingState>("/api/v1/onboarding"),
      ]);
      setUser(nextUser);
      setTenant(nextTenant);
      setOnboarding(nextOnboarding);
    } catch (error) {
      if (error instanceof ApiError && (error.status === 401 || error.status === 403)) {
        signOut();
        try { sessionStorage.setItem("boss.session.expired", "1"); } catch { /* ignore */ }
      }
      setUser(null);
      setTenant(null);
      setOnboarding(null);
    } finally {
      setReady(true);
    }
  }

  useEffect(() => { void refresh(); }, []);

  return <CustomerContext.Provider value={{ ready, user, tenant, onboarding, refresh }}>{children}</CustomerContext.Provider>;
}

export function useCustomer() {
  return useContext(CustomerContext);
}
