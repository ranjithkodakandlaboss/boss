import { currentToken } from "./session";

const base = import.meta.env.VITE_CUSTOMER_API_URL ?? "http://127.0.0.1:8080";

export class ApiError extends Error {
  constructor(readonly status: number, message: string) {
    super(message);
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  if (init.body && !headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  const token = currentToken();
  if (token && path !== "/api/v1/auth/activate") headers.set("Authorization", `Bearer ${token}`);
  const response = await fetch(`${base}${path}`, { ...init, headers });
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  const body = text ? JSON.parse(text) as { detail?: string; message?: string; error?: string } : {};
  if (!response.ok) throw new ApiError(response.status, body.detail || body.message || body.error || response.statusText || "The request was rejected");
  return body as T;
}

export async function firebaseSignIn(email: string, password: string): Promise<string> {
  const apiKey = import.meta.env.VITE_FIREBASE_API_KEY;
  if (!apiKey) throw new Error("Customer sign-in is not configured. Set VITE_FIREBASE_API_KEY.");
  const response = await fetch(`https://identitytoolkit.googleapis.com/v1/accounts:signInWithPassword?key=${apiKey}`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({ email, password, returnSecureToken: true }),
  });
  const body = await response.json() as { idToken?: string; error?: { message?: string } };
  if (!response.ok || !body.idToken) throw new Error(body.error?.message || "Sign-in failed");
  return body.idToken;
}
