import { useEffect, useState, type FormEvent } from "react";
import { Check, CircleAlert, LoaderCircle, ShieldCheck } from "lucide-react";
import {
  activateCustomerAccount,
  validateInviteToken,
  type ValidateInviteResponse,
} from "../api/platformAdminApi";

interface AccountActivationScreenProps {
  token?: string;
  onBackToLogin?: () => void;
}

export function AccountActivationScreen({ token: initialToken, onBackToLogin }: AccountActivationScreenProps) {
  const [token] = useState(() => {
    if (initialToken) return initialToken;
    const params = new URLSearchParams(window.location.search);
    return params.get("token") ?? "";
  });

  const [loading, setLoading] = useState(true);
  const [validation, setValidation] = useState<ValidateInviteResponse | null>(null);
  const [validateError, setValidateError] = useState("");

  const [displayName, setDisplayName] = useState("");
  const [phone, setPhone] = useState("");
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [submitError, setSubmitError] = useState("");
  const [activatedTenantSlug, setActivatedTenantSlug] = useState("");

  useEffect(() => {
    if (!token) {
      setLoading(false);
      setValidateError("No invitation token was provided. Check the link in your invitation email.");
      return;
    }

    let active = true;
    setLoading(true);
    setValidateError("");

    validateInviteToken(import.meta.env.VITE_PLATFORM_ADMIN_API_BASE_URL, token)
      .then((res) => {
        if (!active) return;
        setValidation(res);
      })
      .catch((err: unknown) => {
        if (!active) return;
        setValidateError(err instanceof Error ? err.message : "The invitation token could not be verified.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });

    return () => {
      active = false;
    };
  }, [token]);

  async function handleActivate(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    if (!token) return;

    if (password.length < 8) {
      setSubmitError("Password must be at least 8 characters long.");
      return;
    }
    if (password !== confirmPassword) {
      setSubmitError("Passwords do not match.");
      return;
    }

    setSubmitting(true);
    setSubmitError("");

    try {
      const firebaseUid = "cust_" + crypto.randomUUID().replace(/-/g, "").slice(0, 20);

      const res = await activateCustomerAccount(import.meta.env.VITE_PLATFORM_ADMIN_API_BASE_URL, {
        token,
        firebaseUid,
        displayName: displayName.trim() || undefined,
        phone: phone.trim() || undefined,
      });

      setActivatedTenantSlug(res.tenantSlug);
    } catch (err: unknown) {
      setSubmitError(err instanceof Error ? err.message : "Account activation failed.");
    } finally {
      setSubmitting(false);
    }
  }

  if (loading) {
    return (
      <main className="auth-page">
        <div className="auth-card auth-loading">
          <LoaderCircle className="spinner" size={28} aria-hidden="true" />
          <p>Verifying invitation token…</p>
        </div>
      </main>
    );
  }

  if (validateError || !validation) {
    return (
      <main className="auth-page">
        <section className="auth-card">
          <div className="auth-brand">
            <span className="brand-mark">B</span>
            <span>BOSS <small>ACCOUNT ACTIVATION</small></span>
          </div>
          <p className="eyebrow">INVITATION ERROR</p>
          <h1>Cannot activate account</h1>
          <div className="banner banner-error" role="alert">
            <CircleAlert size={18} aria-hidden="true" />
            <span>{validateError || "Invalid invitation token."}</span>
          </div>
          <p className="auth-description">
            This invitation link may be invalid, expired, or already accepted. Contact your administrator to request a new invitation.
          </p>
          {onBackToLogin && (
            <button className="button button-secondary auth-submit" onClick={onBackToLogin}>
              Return to sign in
            </button>
          )}
        </section>
      </main>
    );
  }

  if (activatedTenantSlug) {
    return (
      <main className="auth-page">
        <section className="auth-card">
          <div className="auth-brand">
            <span className="brand-mark">B</span>
            <span>BOSS <small>WELCOME</small></span>
          </div>
          <div className="banner banner-success" role="status" style={{ marginTop: "1rem" }}>
            <Check size={18} aria-hidden="true" />
            <span>Account activated successfully!</span>
          </div>
          <h1 style={{ marginTop: "1.25rem" }}>Welcome to {validation.tenantName}</h1>
          <p className="auth-description">
            Your initial administrator account (<strong>{validation.email}</strong>) has been configured with the Owner role.
            Your tenant workspace <code>{activatedTenantSlug}</code> is now active.
          </p>
          <div style={{ marginTop: "1.5rem" }}>
            <a
              href="/"
              className="button button-primary auth-submit"
              style={{ display: "inline-block", textAlign: "center", textDecoration: "none" }}
            >
              Go to sign in
            </a>
          </div>
        </section>
      </main>
    );
  }

  return (
    <main className="auth-page">
      <section className="auth-card" style={{ maxWidth: "480px" }}>
        <div className="auth-brand">
          <span className="brand-mark">B</span>
          <span>BOSS <small>ACCOUNT ACTIVATION</small></span>
        </div>
        <p className="eyebrow">COMPANY ONBOARDING</p>
        <h1>Activate your account</h1>
        <p className="auth-description">
          Set up credentials for <strong>{validation.tenantName}</strong> (<code>{validation.tenantSlug}</code>).
        </p>

        {submitError && (
          <div className="banner banner-error" role="alert">
            <CircleAlert size={18} aria-hidden="true" />
            <span>{submitError}</span>
          </div>
        )}

        <form onSubmit={handleActivate} style={{ marginTop: "1rem" }}>
          <div className="field">
            <label htmlFor="activate-email">Email address</label>
            <input
              id="activate-email"
              type="email"
              readOnly
              disabled
              value={validation.email}
              style={{ background: "var(--surface-2)", color: "var(--text-3)", cursor: "not-allowed" }}
            />
          </div>

          <div className="field">
            <label htmlFor="activate-name">Full name</label>
            <input
              id="activate-name"
              type="text"
              placeholder="e.g. Jane Doe"
              value={displayName}
              onChange={(e) => setDisplayName(e.target.value)}
            />
          </div>

          <div className="field">
            <label htmlFor="activate-phone">Contact number</label>
            <input
              id="activate-phone"
              type="tel"
              placeholder="+91 9876543210"
              value={phone}
              onChange={(e) => setPhone(e.target.value)}
            />
          </div>

          <div className="field">
            <label htmlFor="activate-password">Create password <span className="required-mark">*</span></label>
            <input
              id="activate-password"
              type="password"
              required
              minLength={8}
              autoComplete="new-password"
              placeholder="At least 8 characters"
              value={password}
              onChange={(e) => setPassword(e.target.value)}
            />
          </div>

          <div className="field">
            <label htmlFor="activate-confirm-password">Confirm password <span className="required-mark">*</span></label>
            <input
              id="activate-confirm-password"
              type="password"
              required
              minLength={8}
              autoComplete="new-password"
              placeholder="Re-enter password"
              value={confirmPassword}
              onChange={(e) => setConfirmPassword(e.target.value)}
            />
          </div>

          <button
            className="button button-primary auth-submit"
            type="submit"
            disabled={submitting}
            style={{ marginTop: "1rem" }}
          >
            {submitting ? "Activating account…" : "Activate account & join workspace"}
          </button>
        </form>

        <p className="auth-security" style={{ marginTop: "1.25rem" }}>
          <ShieldCheck size={16} aria-hidden="true" />
          <span>Single-use, expiring invitation token verified</span>
        </p>
      </section>
    </main>
  );
}
