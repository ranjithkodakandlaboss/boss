import { useEffect, useMemo, useState } from "react";
import {
  getAdminAuth,
  getMissingFirebaseConfig,
  observeAdminUser,
  signInErrorMessage,
  signInWithEmail,
  signInWithGoogle,
  signOutAdmin,
} from "./auth/adminAuth";
import { createPlatformAdminApi, platformAdminApiBaseUrl } from "./api/platformAdminApi";
import { CustomerConsole } from "./components/CustomerConsole";
import { AccountActivationScreen } from "./screens/AccountActivationScreen";
import type { Auth, User } from "@firebase/auth";

type SessionState =
  | { status: "loading" }
  | { status: "signed-out" }
  | { status: "signed-in"; user: User }
  | { status: "unavailable"; message: string }
  | { status: "error"; message: string };

export default function App() {
  const [authSetup] = useState(() => resolveAuthSetup());
  const [session, setSession] = useState<SessionState>(
    authSetup.auth ? { status: "loading" } : { status: "unavailable", message: authSetup.message },
  );
  const [loginError, setLoginError] = useState("");
  const [loginBusy, setLoginBusy] = useState(false);

  useEffect(() => {
    if (!authSetup.auth) return;
    return observeAdminUser(
      authSetup.auth,
      (user) => {
        setSession(user ? { status: "signed-in", user } : { status: "signed-out" });
        setLoginError("");
      },
      () => setSession({
        status: "error",
        message: "Platform Admin authentication could not be verified. Reload the page or contact your administrator.",
      }),
    );
  }, [authSetup.auth]);

  const api = useMemo(() => {
    if (session.status !== "signed-in") return undefined;
    return createPlatformAdminApi(
      platformAdminApiBaseUrl(),
      () => session.user.getIdToken(),
    );
  }, [session]);

  async function submitEmailSignIn(email: string, password: string) {
    if (!authSetup.auth) return;
    setLoginBusy(true);
    setLoginError("");
    try {
      await signInWithEmail(authSetup.auth, email, password);
    } catch (error) {
      setLoginError(signInErrorMessage(firebaseErrorCode(error)));
    } finally {
      setLoginBusy(false);
    }
  }

  async function submitGoogleSignIn() {
    if (!authSetup.auth) return;
    setLoginBusy(true);
    setLoginError("");
    try {
      await signInWithGoogle(authSetup.auth);
    } catch (error) {
      setLoginError(signInErrorMessage(firebaseErrorCode(error)));
    } finally {
      setLoginBusy(false);
    }
  }

  async function signOut() {
    if (!authSetup.auth) return;
    try {
      await signOutAdmin(authSetup.auth);
    } catch {
      setSession({
        status: "error",
        message: "Sign-out could not be completed. Reload the page to end this session.",
      });
    }
  }

  const isActivationRoute =
    typeof window !== "undefined" &&
    (window.location.pathname.startsWith("/activate") ||
      Boolean(new URLSearchParams(window.location.search).get("token")));

  if (isActivationRoute) {
    return <AccountActivationScreen onBackToLogin={() => { window.location.assign("/"); }} />;
  }

  if (session.status === "loading") {
    return <main className="auth-page"><div className="auth-card auth-loading"><div className="spinner" /><p>Checking Platform Admin session…</p></div></main>;
  }
  if (session.status === "unavailable" || session.status === "error") {
    return <ConfigurationScreen message={session.message} />;
  }
  if (session.status === "signed-out") {
    return (
      <SignInScreen
        busy={loginBusy}
        error={loginError}
        onEmailSignIn={submitEmailSignIn}
        onGoogleSignIn={submitGoogleSignIn}
      />
    );
  }
  if (!api) return <ConfigurationScreen message="Platform Admin API configuration is unavailable." />;

  return (
    <CustomerConsole
      api={api}
      adminEmail={session.user.email ?? "Platform administrator"}
      onSignOut={() => void signOut()}
    />
  );
}

function SignInScreen({
  busy,
  error,
  onEmailSignIn,
  onGoogleSignIn,
}: {
  busy: boolean;
  error: string;
  onEmailSignIn: (email: string, password: string) => Promise<void>;
  onGoogleSignIn: () => Promise<void>;
}) {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");

  return (
    <main className="auth-page">
      <section className="auth-card">
        <div className="auth-brand"><span className="brand-mark">B</span><span>BOSS <small>PLATFORM ADMIN</small></span></div>
        <p className="eyebrow">INTERNAL OPERATIONS</p>
        <h1>Sign in to Platform Admin</h1>
        <p className="auth-description">Use your authorized Platform Admin account. Customer-app sign-in is not accepted here.</p>
        {error && <div className="banner banner-error" role="alert">{error}</div>}
        <form onSubmit={(event) => {
          event.preventDefault();
          void onEmailSignIn(email, password);
        }}>
          <div className="field">
            <label htmlFor="admin-login-email">Email</label>
            <input id="admin-login-email" type="email" autoComplete="username" required value={email} onChange={(event) => setEmail(event.currentTarget.value)} />
          </div>
          <div className="field">
            <label htmlFor="admin-login-password">Password</label>
            <input id="admin-login-password" type="password" autoComplete="current-password" required value={password} onChange={(event) => setPassword(event.currentTarget.value)} />
          </div>
          <button className="button button-primary auth-submit" type="submit" disabled={busy}>
            {busy ? "Signing in…" : "Sign in"}
          </button>
        </form>
        <div className="auth-divider"><span>or</span></div>
        <button className="button button-secondary auth-submit" onClick={() => void onGoogleSignIn()} disabled={busy}>
          Continue with Google
        </button>
        <p className="auth-security"><span className="secure-dot" /> Separate Platform Admin Firebase authentication</p>
      </section>
    </main>
  );
}

function ConfigurationScreen({ message }: { message: string }) {
  return (
    <main className="auth-page">
      <section className="auth-card config-card">
        <div className="auth-brand"><span className="brand-mark">B</span><span>BOSS <small>PLATFORM ADMIN</small></span></div>
        <p className="eyebrow">SETUP REQUIRED</p>
        <h1>Platform Admin is not configured</h1>
        <div className="banner banner-error" role="alert">{message}</div>
        {getMissingFirebaseConfig().length > 0 && (
          <p className="field-hint">
            Missing Firebase settings: {getMissingFirebaseConfig().join(", ")}. See <code>.env.example</code>.
          </p>
        )}
      </section>
    </main>
  );
}

function resolveAuthSetup(): { auth?: Auth; message: string } {
  const missing = getMissingFirebaseConfig();
  if (missing.length > 0) {
    return {
      message: "Provide the dedicated Platform Admin Firebase web configuration before sign-in.",
    };
  }
  try {
    const auth = getAdminAuth();
    return auth
      ? { auth, message: "" }
      : { message: "The dedicated Platform Admin Firebase configuration is unavailable." };
  } catch {
    return { message: "The dedicated Platform Admin Firebase configuration could not be initialized." };
  }
}

function firebaseErrorCode(error: unknown): string | undefined {
  if (typeof error === "object" && error !== null && "code" in error && typeof error.code === "string") {
    return error.code;
  }
  return undefined;
}
