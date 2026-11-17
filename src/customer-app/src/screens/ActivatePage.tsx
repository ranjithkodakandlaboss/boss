import { useState } from "react";
import { Link, useSearchParams } from "react-router-dom";
import { api, ApiError, firebaseSignIn } from "../auth/api";
import { signIn } from "../auth/session";
import Button from "../components/shared/Button";
import { TextField } from "../components/shared/Fields";

export default function ActivatePage() {
  const [params] = useSearchParams();
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    const password = String(data.get("password") ?? "");
    const token = String(data.get("token") ?? "");
    setPending(true);
    setError("");
    try {
      const user = await api<{ email: string }>("/api/v1/auth/activate", {
        method: "POST",
        headers: { "Idempotency-Key": crypto.randomUUID() },
        body: JSON.stringify({ token, password, displayName: String(data.get("displayName") ?? "") }),
      });
      signIn(await firebaseSignIn(user.email, password));
      window.location.assign("/onboarding");
    } catch (caught) {
      setError(caught instanceof ApiError || caught instanceof Error ? caught.message : "Activation failed");
    } finally {
      setPending(false);
    }
  }

  return (
    <main className="min-h-screen flex items-center justify-center px-4" style={{ background: "var(--bg)" }}>
      <form onSubmit={submit} className="w-full max-w-md rounded-2xl border p-6 space-y-4" style={{ background: "var(--surface)", borderColor: "var(--border)" }}>
        <h1 className="text-2xl font-extrabold">Activate your account</h1>
        <p className="text-sm text-[var(--text-2)]">Use the invitation token from your workspace administrator.</p>
        <TextField label="Invitation token" name="token" required defaultValue={params.get("token") ?? ""} />
        <TextField label="Display name" name="displayName" />
        <TextField label="Password" name="password" type="password" required minLength={6} />
        <p className="text-xs text-[var(--text-3)]">The password is created for the email address on the invitation.</p>
        {error && <p role="alert" className="text-sm text-[var(--error)]">{error}</p>}
        <Button type="submit" disabled={pending}>{pending ? "Activating" : "Activate"}</Button>
        <p className="text-xs text-[var(--text-3)]"><Link className="underline" to="/login">Back to sign in</Link></p>
      </form>
    </main>
  );
}
