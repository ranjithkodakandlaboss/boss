import { useState } from "react";
import { Link, useNavigate } from "react-router-dom";
import { ApiError, firebaseSignIn } from "../auth/api";
import { signIn } from "../auth/session";
import Button from "../components/shared/Button";
import { TextField } from "../components/shared/Fields";

export default function LoginPage() {
  const navigate = useNavigate();
  const [error, setError] = useState("");
  const [pending, setPending] = useState(false);

  async function submit(event: React.FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const data = new FormData(event.currentTarget);
    setPending(true);
    setError("");
    try {
      const token = await firebaseSignIn(String(data.get("email") ?? ""), String(data.get("password") ?? ""));
      signIn(token);
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
