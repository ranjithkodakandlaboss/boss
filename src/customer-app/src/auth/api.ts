import {
  currentRefreshToken,
  currentToken,
  currentTokenExpiresAt,
  storeRefreshedToken,
} from "./session";

const base = import.meta.env.VITE_CUSTOMER_API_URL ?? "http://127.0.0.1:8080";
const TOKEN_REFRESH_WINDOW_MS = 5 * 60 * 1000;
let refreshInProgress: { refreshToken: string; promise: Promise<string> } | null = null;

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export function safeRandomUUID(): string {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  const bytes = new Uint8Array(16);
  if (typeof crypto !== "undefined" && typeof crypto.getRandomValues === "function") {
    crypto.getRandomValues(bytes);
  } else {
    for (let i = 0; i < bytes.length; i++) bytes[i] = Math.floor(Math.random() * 256);
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, "0")).join("");
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

async function refreshFirebaseToken(refreshToken: string): Promise<string> {
  const apiKey = import.meta.env.VITE_FIREBASE_API_KEY;
  if (!apiKey) throw new ApiError(401, "Session refresh is not configured. Please sign in again.");

  const response = await fetch(`https://securetoken.googleapis.com/v1/token?key=${encodeURIComponent(apiKey)}`, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({ grant_type: "refresh_token", refresh_token: refreshToken }),
  });
  const text = await response.text();
  let body: { id_token?: string; refresh_token?: string; expires_in?: string; error?: { message?: string } } = {};
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = {};
    }
  }
  const expiresIn = Number(body.expires_in);
  if (!response.ok || !body.id_token || !Number.isFinite(expiresIn) || expiresIn <= 0) {
    throw new ApiError(401, body.error?.message || "Your session has expired. Please sign in again.");
  }
  if (currentRefreshToken() !== refreshToken) {
    throw new ApiError(401, "Your session changed. Please retry the request.");
  }

  storeRefreshedToken(body.id_token, body.refresh_token || refreshToken, expiresIn);
  return body.id_token;
}

export async function getAccessToken(forceRefresh = false): Promise<string | null> {
  const token = currentToken();
  const refreshToken = currentRefreshToken();
  if (!token || !refreshToken) return token;

  const expiresAt = currentTokenExpiresAt();
  if (!forceRefresh && expiresAt !== null && expiresAt - Date.now() > TOKEN_REFRESH_WINDOW_MS) {
    return token;
  }

  if (!refreshInProgress || refreshInProgress.refreshToken !== refreshToken) {
    const promise = refreshFirebaseToken(refreshToken).finally(() => {
      if (refreshInProgress?.promise === promise) refreshInProgress = null;
    });
    refreshInProgress = { refreshToken, promise };
  }
  return refreshInProgress.promise;
}

export async function authorizedFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers);
  const token = await getAccessToken();
  if (token) headers.set("Authorization", `Bearer ${token}`);

  const request = { ...init, headers };
  const response = await fetch(input, request);
  if (response.status !== 401 || !currentRefreshToken()) return response;

  const refreshedToken = await getAccessToken(true);
  if (refreshedToken) headers.set("Authorization", `Bearer ${refreshedToken}`);
  return fetch(input, request);
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !(init.body instanceof FormData) && !headers.has("Content-Type")) {
    headers.set("Content-Type", "application/json");
  }
  if (path === "/api/v1/auth/activate") {
    const response = await fetch(`${base}${path}`, { ...init, headers });
    return parseApiResponse<T>(response);
  }
  const response = await authorizedFetch(`${base}${path}`, { ...init, headers });
  return parseApiResponse<T>(response);
}

async function parseApiResponse<T>(response: Response): Promise<T> {
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  let body: { detail?: string; message?: string; error?: string } = {};
  if (text) {
    try {
      body = JSON.parse(text);
    } catch {
      body = { message: text.length > 200 ? response.statusText || `Request failed with status ${response.status}` : text };
    }
  }
  if (!response.ok) throw new ApiError(response.status, body.detail || body.message || body.error || response.statusText || "The request was rejected");
  return body as T;
}

/**
 * Validates that the given Firebase ID token is associated with an active BOSS account.
 * Throws ApiError(401) if the token is valid Firebase-wise but has no BOSS tenant membership.
 * Must be called BEFORE storing the session client-side.
 */
export async function validateBossAccount(idToken: string): Promise<void> {
  const response = await fetch(`${base}/api/v1/auth/me`, {
    headers: {
      "Authorization": `Bearer ${idToken}`,
      "Content-Type": "application/json",
    },
  });
  if (response.status === 401) {
    throw new ApiError(401, "This account is not registered with BOSS. Please use your invitation link to activate your account.");
  }
  if (response.status === 403) {
    const text = await response.text().catch(() => "");
    let detail = "Your account has been suspended or deactivated.";
    try { detail = JSON.parse(text).detail || detail; } catch { /* ignore */ }
    throw new ApiError(403, detail);
  }
  if (!response.ok) {
    throw new ApiError(response.status, "Sign-in failed. Please try again.");
  }
}

export async function firebaseSignIn(email: string, password: string): Promise<{ idToken: string; refreshToken: string; expiresIn: string }> {
  const apiKey = import.meta.env.VITE_FIREBASE_API_KEY;
  if (!apiKey) throw new Error("Customer sign-in is not configured. Set VITE_FIREBASE_API_KEY.");
  const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${encodeURIComponent(apiKey)}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  const body = await response.json() as { idToken?: string; refreshToken?: string; expiresIn?: string; error?: { message?: string } };
  const expiresIn = Number(body.expiresIn);
  if (!response.ok || !body.idToken || !body.refreshToken || !Number.isFinite(expiresIn) || expiresIn <= 0) {
    throw new Error(body.error?.message || "Sign-in failed");
  }
  return { idToken: body.idToken, refreshToken: body.refreshToken, expiresIn: String(expiresIn) };
}
