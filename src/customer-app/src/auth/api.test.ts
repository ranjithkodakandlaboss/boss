import { afterEach, expect, it, vi } from "vitest";
import { api, authorizedFetch, firebaseSignIn } from "./api";
import { currentRefreshToken, currentToken, currentTokenExpiresAt, signIn, signOut } from "./session";

afterEach(() => {
  signOut();
  sessionStorage.removeItem("boss.customer.refreshToken");
  sessionStorage.removeItem("boss.customer.tokenExpiresAt");
  vi.unstubAllGlobals();
  vi.unstubAllEnvs();
});

it("stores Firebase refresh credentials when signing in", async () => {
  vi.stubEnv("VITE_FIREBASE_API_KEY", "test-api-key");
  vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(JSON.stringify({
    idToken: "firebase-id-token",
    refreshToken: "firebase-refresh-token",
    expiresIn: "3600",
  }), { status: 200 })));

  const credentials = await firebaseSignIn("owner@example.test", "password");
  signIn(credentials.idToken, credentials.refreshToken, credentials.expiresIn);

  expect(currentRefreshToken()).toBe("firebase-refresh-token");
  expect(currentTokenExpiresAt()).toBeGreaterThan(Date.now());
});

it("refreshes an expired Firebase token before sending a protected request", async () => {
  vi.stubEnv("VITE_FIREBASE_API_KEY", "test-api-key");
  signIn("expired-id-token");
  sessionStorage.setItem("boss.customer.refreshToken", "test-refresh-token");
  sessionStorage.setItem("boss.customer.tokenExpiresAt", "0");

  const protectedRequests: Array<{ authorization: string | null }> = [];
  const refreshRequests: RequestInit[] = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = String(input);
    if (url.includes("securetoken.googleapis.com")) {
      refreshRequests.push(init ?? {});
      return new Response(JSON.stringify({
        id_token: "refreshed-id-token",
        refresh_token: "rotated-refresh-token",
        expires_in: "3600",
      }), { status: 200 });
    }

    protectedRequests.push({
      authorization: new Headers(init?.headers).get("Authorization"),
    });
    const status = protectedRequests.at(-1)?.authorization === "Bearer refreshed-id-token" ? 200 : 401;
    return new Response(JSON.stringify({ ok: true }), { status });
  }));

  await expect(api<{ ok: boolean }>("/api/v1/test")).resolves.toEqual({ ok: true });
  expect(protectedRequests).toEqual([{ authorization: "Bearer refreshed-id-token" }]);
  expect(new URLSearchParams(String(refreshRequests[0].body)).get("refresh_token")).toBe("test-refresh-token");
  expect(sessionStorage.getItem("boss.customer.refreshToken")).toBe("rotated-refresh-token");
});

it("refreshes and retries when the API rejects a locally unexpired token", async () => {
  vi.stubEnv("VITE_FIREBASE_API_KEY", "test-api-key");
  signIn("rejected-id-token");
  sessionStorage.setItem("boss.customer.refreshToken", "test-refresh-token");
  sessionStorage.setItem("boss.customer.tokenExpiresAt", String(Date.now() + 60 * 60 * 1000));

  const protectedRequests: Array<string | null> = [];
  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    if (String(input).includes("securetoken.googleapis.com")) {
      return new Response(JSON.stringify({
        id_token: "refreshed-id-token",
        refresh_token: "rotated-refresh-token",
        expires_in: "3600",
      }), { status: 200 });
    }

    const authorization = new Headers(init?.headers).get("Authorization");
    protectedRequests.push(authorization);
    return new Response(JSON.stringify({ ok: true }), {
      status: authorization === "Bearer refreshed-id-token" ? 200 : 401,
    });
  }));

  await expect(api<{ ok: boolean }>("/api/v1/test")).resolves.toEqual({ ok: true });
  expect(protectedRequests).toEqual([
    "Bearer rejected-id-token",
    "Bearer refreshed-id-token",
  ]);
});

it("signs out when Firebase token refresh fails mid-session", async () => {
  vi.stubEnv("VITE_FIREBASE_API_KEY", "test-api-key");
  signIn("expired-token");
  sessionStorage.setItem("boss.customer.refreshToken", "expired-refresh-token");
  sessionStorage.setItem("boss.customer.tokenExpiresAt", "0");

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).includes("securetoken.googleapis.com")) {
      return new Response(JSON.stringify({ error: { message: "TOKEN_EXPIRED" } }), { status: 400 });
    }
    return new Response("{}", { status: 401 });
  }));

  const response = await authorizedFetch("http://localhost/api/v1/test");
  // After a failed refresh, the session is cleared.
  expect(currentToken()).toBeNull();
  expect(response.status).toBe(401);
});

it("signs out when backend returns 401 even after a successful token refresh", async () => {
  vi.stubEnv("VITE_FIREBASE_API_KEY", "test-api-key");
  signIn("old-token");
  sessionStorage.setItem("boss.customer.refreshToken", "valid-refresh-token");
  sessionStorage.setItem("boss.customer.tokenExpiresAt", "0");

  vi.stubGlobal("fetch", vi.fn(async (input: RequestInfo | URL) => {
    if (String(input).includes("securetoken.googleapis.com")) {
      return new Response(JSON.stringify({ id_token: "new-token", refresh_token: "rotated", expires_in: "3600" }), { status: 200 });
    }
    // Even the new token is rejected — account was deactivated server-side.
    return new Response(JSON.stringify({ detail: "Account suspended" }), { status: 401 });
  }));

  const response = await authorizedFetch("http://localhost/api/v1/test");
  expect(currentToken()).toBeNull();
  expect(response.status).toBe(401);
});
