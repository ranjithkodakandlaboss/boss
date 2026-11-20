import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, firebaseSignIn } from "../auth/api";
import { signIn } from "../auth/session";
import Button from "../components/shared/Button";
import { TextField } from "../components/shared/Fields";

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

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    try {
      const session = await firebaseSignIn(String(data.get("email") ?? ""), String(data.get("password") ?? ""));
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
        <p className="text-sm text-[var(--text-2)]">Use the customer administrator account for your workspace.</p>
        <TextField label="Email" name="email" type="email" required />
        <TextField label="Password" name="password" type="password" required />
        {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
        <Button type="submit" disabled={pending}>{pending ? "Signing in" : "Sign in"}</Button>
        <p className="text-xs text-[var(--text-3)]">Have an invitation? <Link className="underline" to="/activate">Activate your account</Link></p>
      </form>
    </main>
  );
}
