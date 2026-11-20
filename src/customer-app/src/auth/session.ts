const KEY = "boss.customer.idToken";
const REFRESH_TOKEN_KEY = "boss.customer.refreshToken";
const EXPIRES_AT_KEY = "boss.customer.tokenExpiresAt";
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

export function currentRefreshToken(): string | null {
  return browserStorage()?.getItem(REFRESH_TOKEN_KEY) ?? null;
}

export function currentTokenExpiresAt(): number | null {
  const value = browserStorage()?.getItem(EXPIRES_AT_KEY);
  if (!value) return null;
  const expiresAt = Number(value);
  return Number.isFinite(expiresAt) ? expiresAt : null;
}

export function storeRefreshedToken(token: string, refreshToken: string, expiresIn: string | number) {
  memoryToken = token;
  const storage = browserStorage();
  storage?.setItem(KEY, token);
  storage?.setItem(REFRESH_TOKEN_KEY, refreshToken);
  storage?.setItem(EXPIRES_AT_KEY, String(Date.now() + Number(expiresIn) * 1000));
}

export function signIn(token: string, refreshToken?: string, expiresIn?: string | number) {
  memoryToken = token;
  const storage = browserStorage();
  storage?.setItem(KEY, token);
  if (refreshToken && expiresIn) {
    storeRefreshedToken(token, refreshToken, expiresIn);
  } else {
    storage?.removeItem(REFRESH_TOKEN_KEY);
    storage?.removeItem(EXPIRES_AT_KEY);
  }
}

export function signOut() {
  memoryToken = null;
  const storage = browserStorage();
  storage?.removeItem(KEY);
  storage?.removeItem(REFRESH_TOKEN_KEY);
  storage?.removeItem(EXPIRES_AT_KEY);
}
