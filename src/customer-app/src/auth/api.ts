import { currentToken } from "./session";

const base = import.meta.env.VITE_CUSTOMER_API_URL ?? "http://127.0.0.1:8080";

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
