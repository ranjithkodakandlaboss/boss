import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError, firebaseSignIn, validateBossAccount } from "../auth/api";
import { signIn } from "../auth/session";
import Button from "../components/shared/Button";
import { TextField } from "../components/shared/Fields";

const LOCAL_USERS = [
  { role: "Owner", email: "owner@boss.local.test", token: "local-seed-owner" },
  { role: "Production Supervisor", email: "production-supervisor@boss.local.test", token: "local-seed-production-supervisor" },
  { role: "Operations Manager", email: "ops-manager@boss.local.test", token: "local-seed-ops-manager" },
];

const isLocalEnv = typeof window !== "undefined" &&
  (window.location.hostname === "localhost" || window.location.hostname === "127.0.0.1");

export default function LoginPage() {
  const navigate = useNavigate();
  const [expired] = useState(() => {
    try {
      const val = sessionStorage.getItem("boss.session.expired");
      if (val) sessionStorage.removeItem("boss.session.expired");
      return !!val;
    } catch { return false; }
  });
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  function loginWithToken(token: string) {
    signIn(token);
    window.location.assign("/");
  }

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    const email = String(data.get("email") ?? "").trim();
    const password = String(data.get("password") ?? "");

    // Quick match for local demo users
    const matched = LOCAL_USERS.find((u) => u.email.toLowerCase() === email.toLowerCase());
    if (matched) {
      loginWithToken(matched.token);
      return;
    }
    if (email.endsWith("@boss.local.test")) {
      const prefix = email.split("@")[0];
      loginWithToken(`local-seed-${prefix}`);
      return;
    }

    try {
      const session = await firebaseSignIn(email, password);
      // Validate tenant membership with the backend BEFORE storing the session.
      // Firebase authentication alone is not sufficient — the user must have an
      // active BOSS account associated with this workspace.
      await validateBossAccount(session.idToken);
      signIn(session.idToken, session.refreshToken, session.expiresIn);
      navigate("/");
      window.location.reload();
    } catch (caught) {
      setError(caught instanceof ApiError || caught instanceof Error ? caught.message : "Sign-in failed");
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="min-h-screen flex items-center justify-center px-4" style={{ background: "var(--bg)" }}>
      <form onSubmit={submit} className="w-full max-w-md rounded-2xl border p-6 space-y-4" style={{ background: "var(--surface)", borderColor: "var(--border)" }}>
        <h1 className="text-2xl font-extrabold">Sign in to BOSS</h1>
        {expired && (
          <p role="alert" className="text-sm rounded-lg px-3 py-2" style={{ background: "var(--surface-2)", color: "var(--text-2)", border: "1px solid var(--border)" }}>
            Your session has expired. Please sign in again.
          </p>
        )}
        <p className="text-sm text-[var(--text-2)]">Sign in with your BOSS account credentials.</p>
        <TextField label="Email" name="email" type="email" required defaultValue={isLocalEnv ? "owner@boss.local.test" : ""} />
        <TextField label="Password" name="password" type="password" required defaultValue={isLocalEnv ? "password" : ""} />
        {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
        <Button type="submit" disabled={pending}>{pending ? "Signing in" : "Sign in"}</Button>

        {isLocalEnv && (
          <div className="pt-3 border-t space-y-2" style={{ borderColor: "var(--border)" }}>
            <p className="text-xs font-semibold text-[var(--text-3)] uppercase tracking-wider">Quick Sign-in (Local Demo)</p>
            <div className="flex flex-col gap-1.5">
              {LOCAL_USERS.map((u) => (
                <button
                  key={u.token}
                  type="button"
                  onClick={() => loginWithToken(u.token)}
                  className="w-full text-left px-3 py-2 rounded-lg border text-xs flex justify-between items-center hover:bg-[var(--surface-2)] transition-colors cursor-pointer"
                  style={{ borderColor: "var(--border)" }}
                >
                  <span className="font-medium text-[var(--text)]">{u.role}</span>
                  <span className="font-mono text-[11px] text-[var(--text-3)]">{u.email}</span>
                </button>
              ))}
            </div>
          </div>
        )}

        <p className="text-center text-xs text-[var(--text-3)] pt-2">
          New to BOSS? Contact your workspace administrator to be invited.
        </p>
      </form>
    </main>
  );
}
