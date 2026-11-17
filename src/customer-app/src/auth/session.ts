const KEY = "boss.customer.idToken";
let memoryToken: string | null = null;

function browserStorage(): Storage | null {
  const candidate = globalThis.sessionStorage;
  if (!candidate || typeof candidate.getItem !== "function") return null;
  return candidate;
}

export function currentToken(): string | null {
  if (memoryToken) return memoryToken;
  return browserStorage()?.getItem(KEY) ?? null;
}

export function signIn(token: string) {
  memoryToken = token;
  browserStorage()?.setItem(KEY, token);
}

export function signOut() {
  memoryToken = null;
  browserStorage()?.removeItem(KEY);
}
