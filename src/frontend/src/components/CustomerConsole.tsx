import { useEffect, useMemo, useState, type FormEvent } from "react";
import {
  ArrowLeft,
  ArrowRight,
  Building2,
  Check,
  ChevronDown,
  CircleAlert,
  Copy,
  LoaderCircle,
  LogOut,
  Plus,
  RefreshCw,
  ShieldCheck,
  Users,
  X,
} from "lucide-react";
import { PathsTenantsGetParametersQueryStatus } from "../api/generated/platform-admin";
import {
  PlatformAdminApiError,
  type PlatformAdminApi,
  type ProvisionResult,
  type ApiResponse,
  type TenantProvisionRequest,
  type TenantStatusFilter,
  type TenantSummary,
} from "../api/platformAdminApi";
import { ProvisionCustomerForm } from "./ProvisionCustomerForm";

const PAGE_SIZE = 20;

type ConsolePage = "list" | "create" | "result";

interface ProvisionOutcome {
  status: number;
  body?: ProvisionResult;
  initialAdminEmail: string;
  tenantName: string;
}

interface InviteHandoff {
  tenantName: string;
  email: string;
  inviteId?: string;
  token?: string;
  status?: string;
  expiresAt?: string;
}

interface InviteTarget {
  id: string;
  name: string;
}

type TenantAction =
  | { kind: "activate"; tenant: TenantSummary }
  | { kind: "suspend"; tenant: TenantSummary }
  | { kind: "reactivate"; tenant: TenantSummary };

interface CustomerConsoleProps {
  api: PlatformAdminApi;
  adminEmail: string;
  onSignOut: () => void;
}

export function CustomerConsole({ api, adminEmail, onSignOut }: CustomerConsoleProps) {
  const [page, setPage] = useState<ConsolePage>("list");
  const [filter, setFilter] = useState<TenantStatusFilter | "all">("all");
  const [currentPage, setCurrentPage] = useState(0);
  const [reloadId, setReloadId] = useState(0);
  const [tenants, setTenants] = useState<TenantSummary[]>([]);
  const [total, setTotal] = useState(0);
  const [loading, setLoading] = useState(true);
  const [listError, setListError] = useState("");
  const [notice, setNotice] = useState("");
  const [provisionOutcome, setProvisionOutcome] = useState<ProvisionOutcome | null>(null);
  const [action, setAction] = useState<TenantAction | null>(null);
  const [actionBusy, setActionBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [suspendReason, setSuspendReason] = useState("");
  const [inviteTarget, setInviteTarget] = useState<InviteTarget | null>(null);
  const [inviteEmail, setInviteEmail] = useState("");
  const [inviteBusy, setInviteBusy] = useState(false);
  const [inviteError, setInviteError] = useState("");
  const [inviteHandoff, setInviteHandoff] = useState<InviteHandoff | null>(null);
  const [copyError, setCopyError] = useState("");
  const [copied, setCopied] = useState(false);

  const statuses = useMemo(() => Object.values(PathsTenantsGetParametersQueryStatus), []);
  const pageCount = Math.max(1, Math.ceil(total / PAGE_SIZE));

  useEffect(() => {
    let active = true;
    setLoading(true);
    setListError("");
    const request = {
      page: currentPage,
      size: PAGE_SIZE,
      ...(filter === "all" ? {} : { status: filter }),
    };

    void api.listTenants(request).then((result) => {
      if (!active) return;
      setTenants(result.content);
      setTotal(result.totalElements);
    }).catch((error: unknown) => {
      if (!active) return;
      setListError(errorMessage(error));
    }).finally(() => {
      if (active) setLoading(false);
    });

    return () => {
      active = false;
    };
  }, [api, currentPage, filter, reloadId]);

  async function submitProvision(request: TenantProvisionRequest) {
    return api.provisionTenant(request, crypto.randomUUID());
  }

  function showProvisionResult(
    response: ApiResponse<ProvisionResult | undefined>,
    initialAdminEmail: string,
    tenantName: string,
  ) {
    setProvisionOutcome({ ...response, initialAdminEmail, tenantName });
    setPage("result");
    setReloadId((current) => current + 1);
  }

  function leaveProvisionResult() {
    setProvisionOutcome(null);
    setPage("list");
    setReloadId((current) => current + 1);
  }

  function openInvite(tenant: InviteTarget, defaultEmail?: string) {
    setInviteTarget(tenant);
    setInviteEmail(defaultEmail || "");
    setInviteError("");
  }

  async function sendInvite(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!inviteTarget || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(inviteEmail.trim())) {
      setInviteError("Enter a valid email address.");
      return;
    }

    setInviteBusy(true);
    setInviteError("");
    try {
      const result = await api.retryInitialAdminInvite(
        inviteTarget.id,
        { email: inviteEmail.trim() },
        crypto.randomUUID(),
      );
      setInviteHandoff({
        tenantName: inviteTarget.name,
        email: inviteEmail.trim(),
        inviteId: result.inviteId,
        token: result.adminInviteToken,
        status: result.invitationStatus,
        expiresAt: result.expiresAt,
      });
      setInviteTarget(null);
    } catch (error) {
      setInviteError(errorMessage(error));
    } finally {
      setInviteBusy(false);
    }
  }

  async function performAction(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!action) return;
    if (action.kind === "suspend" && !suspendReason.trim()) {
      setActionError("A suspension reason is required.");
      return;
    }

    setActionBusy(true);
    setActionError("");
    try {
      await (action.kind === "activate"
        ? api.activateTenant(action.tenant.id)
        : action.kind === "suspend"
          ? api.suspendTenant(action.tenant.id, suspendReason.trim())
          : api.reactivateTenant(action.tenant.id));
      const actionLabel = action.kind === "activate"
        ? "Activation"
        : action.kind === "suspend"
          ? "Suspension"
          : "Reactivation";
      setNotice(`${actionLabel} request succeeded. The directory is reloading the current status from the API.`);
      setAction(null);
      setSuspendReason("");
      setReloadId((current) => current + 1);
    } catch (error) {
      setActionError(errorMessage(error));
    } finally {
      setActionBusy(false);
    }
  }

  async function copyInviteToken() {
    if (!inviteHandoff?.token) return;
    setCopyError("");
    try {
      await navigator.clipboard.writeText(inviteHandoff.token);
      setCopied(true);
    } catch {
      setCopyError("Clipboard access is unavailable. Select and copy the token manually.");
    }
  }

  function closeInviteHandoff() {
    setInviteHandoff(null);
    setCopied(false);
    setCopyError("");
  }

  const resultTenantId = provisionOutcome?.body?.tenantId;
  const resultTenant = resultTenantId ? tenants.find((tenant) => tenant.id === resultTenantId) : undefined;

  if (page === "create") {
    return (
      <AppShell adminEmail={adminEmail} onSignOut={onSignOut}>
        <ProvisionCustomerForm
          onCancel={() => setPage("list")}
          onSubmit={submitProvision}
          onCreated={showProvisionResult}
        />
      </AppShell>
    );
  }

  if (page === "result" && provisionOutcome) {
    return (
      <AppShell adminEmail={adminEmail} onSignOut={onSignOut}>
        <ProvisioningResult
          outcome={provisionOutcome}
          tenantStatus={resultTenant?.status}
          onBack={leaveProvisionResult}
          onRetryInvite={() => {
            if (!provisionOutcome.body?.tenantId) return;
            setInviteTarget({
              id: provisionOutcome.body.tenantId,
              name: provisionOutcome.tenantName,
            });
            setInviteEmail(provisionOutcome.initialAdminEmail);
            setInviteError("");
          }}
        />
        {inviteTarget && (
          <InviteDialog
            email={inviteEmail}
            error={inviteError}
            busy={inviteBusy}
            tenantName={inviteTarget.name}
            onClose={() => setInviteTarget(null)}
            onEmailChange={setInviteEmail}
            onSubmit={sendInvite}
          />
        )}
        {inviteHandoff && (
          <InviteHandoffDialog
            handoff={inviteHandoff}
            copied={copied}
            copyError={copyError}
            onCopy={() => void copyInviteToken()}
            onClose={closeInviteHandoff}
          />
        )}
      </AppShell>
    );
  }

  return (
    <AppShell adminEmail={adminEmail} onSignOut={onSignOut}>
      <main className="console-page">
        <div className="page-heading console-heading">
          <div>
            <p className="eyebrow">PLATFORM OPERATIONS</p>
            <h1>Customers</h1>
            <p className="subheading">Manage customer workspaces and their onboarding status.</p>
          </div>
          <button className="button button-primary" onClick={() => setPage("create")}>
            <Plus size={17} aria-hidden="true" />
            Create customer
          </button>
        </div>

        <section className="metric-row" aria-label="Customer overview">
          <div className="metric-card">
            <span className="metric-icon"><Building2 size={18} aria-hidden="true" /></span>
            <span className="metric-label">Total tenants</span>
            <strong>{loading ? "—" : total.toLocaleString()}</strong>
          </div>
          <div className="metric-card">
            <span className="metric-icon metric-icon-blue"><Users size={18} aria-hidden="true" /></span>
            <span className="metric-label">Current page</span>
            <strong>{loading ? "—" : `${tenants.length} / ${PAGE_SIZE}`}</strong>
          </div>
          <div className="metric-card metric-secure">
            <span className="metric-icon metric-icon-green"><ShieldCheck size={18} aria-hidden="true" /></span>
            <span className="metric-label">Admin boundary</span>
            <strong>Dedicated service</strong>
          </div>
        </section>

        <section className="table-card" aria-labelledby="tenant-list-heading">
          <div className="table-toolbar">
            <div>
              <h2 id="tenant-list-heading">Tenant directory</h2>
              <p>Customer details are read directly from the Platform Admin API.</p>
            </div>
            <div className="toolbar-controls">
              <label className="filter-control">
                <span>Status</span>
                <span className="select-wrap">
                  <select
                    aria-label="Filter by status"
                    value={filter}
                    onChange={(event) => {
                      const value = event.currentTarget.value;
                      setFilter(value === "all" ? "all" : statuses.find((status) => status === value) ?? "all");
                      setCurrentPage(0);
                    }}
                  >
                    <option value="all">All statuses</option>
                    {statuses.map((status) => (
                      <option key={status} value={status}>{formatStatus(status)}</option>
                    ))}
                  </select>
                  <ChevronDown size={14} aria-hidden="true" />
                </span>
              </label>
              <button
                className="icon-button"
                aria-label="Refresh tenant list"
                onClick={() => setReloadId((current) => current + 1)}
              >
                <RefreshCw size={16} aria-hidden="true" />
              </button>
            </div>
          </div>

          {notice && (
            <div className="banner banner-success" role="status">
              <Check size={16} aria-hidden="true" />
              <span>{notice}</span>
              <button className="banner-close" aria-label="Dismiss message" onClick={() => setNotice("")}>
                <X size={15} aria-hidden="true" />
              </button>
            </div>
          )}

          {loading ? (
            <div className="state-panel" role="status">
              <LoaderCircle className="spinner" size={24} aria-hidden="true" />
              <strong>Loading customer directory</strong>
              <span>Connecting to the Platform Admin API…</span>
            </div>
          ) : listError ? (
            <div className="state-panel state-error" role="alert">
              <CircleAlert size={25} aria-hidden="true" />
              <strong>Could not load customers</strong>
              <span>{listError}</span>
              <button className="button button-secondary" onClick={() => setReloadId((current) => current + 1)}>
                Try again
              </button>
            </div>
          ) : tenants.length === 0 ? (
            <div className="state-panel state-empty">
              <span className="empty-icon"><Building2 size={24} aria-hidden="true" /></span>
              <strong>{filter === "all" ? "No customers yet" : "No tenants match this status"}</strong>
              <span>
                {filter === "all"
                  ? "Create a customer to start provisioning its first tenant."
                  : "Choose another status filter or clear the filter to see all tenants."}
              </span>
              {filter === "all" && (
                <button className="button button-secondary" onClick={() => setPage("create")}>
                  <Plus size={16} aria-hidden="true" /> Create customer
                </button>
              )}
            </div>
          ) : (
            <>
              <div className="table-scroll">
                <table className="tenant-table">
                  <thead>
                    <tr>
                      <th scope="col">Customer / tenant</th>
                      <th scope="col">Tenant slug</th>
                      <th scope="col">Status</th>
                      <th scope="col">Industry</th>
                      <th scope="col">Admin / Invitation</th>
                      <th scope="col"><span className="visually-hidden">Actions</span></th>
                    </tr>
                  </thead>
                  <tbody>
                    {tenants.map((tenant) => (
                      <tr key={tenant.id}>
                        <td>
                          <div className="tenant-name">{tenant.name}</div>
                          <div className="tenant-id">{tenant.id}</div>
                        </td>
                        <td><code>{tenant.slug}</code></td>
                        <td><StatusBadge status={tenant.status} /></td>
                        <td>{formatStatus(tenant.industryType || "") || "—"}</td>
                        <td>
                          <div className="tenant-admin-email">{tenant.inviteEmail || "—"}</div>
                          <div style={{ marginTop: "4px" }}><InviteStatusBadge status={tenant.inviteStatus} /></div>
                        </td>
                        <td>
                          <div className="row-actions">
                            {tenant.status === "provisioning" && (
                              <button className="text-button" onClick={() => setAction({ kind: "activate", tenant })}>
                                Activate
                              </button>
                            )}
                            {tenant.status === "active" && (
                              <button className="text-button text-danger" onClick={() => {
                                setAction({ kind: "suspend", tenant });
                                setSuspendReason("");
                              }}>
                                Suspend
                              </button>
                            )}
                            {tenant.status === "suspended" && (
                              <button className="text-button" onClick={() => setAction({ kind: "reactivate", tenant })}>
                                Reactivate
                              </button>
                            )}
                            {tenant.status !== "archived" && tenant.inviteStatus !== "accepted" && (
                              <button className="text-button text-muted" onClick={() => openInvite(tenant, tenant.inviteEmail)}>
                                {tenant.inviteStatus ? "Resend invite" : "Invite admin"}
                              </button>
                            )}
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <div className="pagination">
                <span>
                  {total === 0 ? "0 tenants" : `Showing ${currentPage * PAGE_SIZE + 1}–${Math.min((currentPage + 1) * PAGE_SIZE, total)} of ${total}`}
                </span>
                <div className="pagination-actions">
                  <button
                    className="button button-small button-secondary"
                    onClick={() => setCurrentPage((current) => Math.max(0, current - 1))}
                    disabled={currentPage === 0}
                  >
                    <ArrowLeft size={14} aria-hidden="true" /> Previous
                  </button>
                  <span>Page {currentPage + 1} of {pageCount}</span>
                  <button
                    className="button button-small button-secondary"
                    onClick={() => setCurrentPage((current) => Math.min(pageCount - 1, current + 1))}
                    disabled={currentPage >= pageCount - 1}
                  >
                    Next <ArrowRight size={14} aria-hidden="true" />
                  </button>
                </div>
              </div>
            </>
          )}
        </section>
      </main>

      {action && (
        <ActionDialog
          action={action}
          reason={suspendReason}
          error={actionError}
          busy={actionBusy}
          onReasonChange={setSuspendReason}
          onClose={() => {
            setAction(null);
            setActionError("");
          }}
          onSubmit={performAction}
        />
      )}

      {inviteTarget && (
        <InviteDialog
          email={inviteEmail}
          error={inviteError}
          busy={inviteBusy}
          tenantName={inviteTarget.name}
          onClose={() => setInviteTarget(null)}
          onEmailChange={setInviteEmail}
          onSubmit={sendInvite}
        />
      )}

      {inviteHandoff && (
        <InviteHandoffDialog
          handoff={inviteHandoff}
          copied={copied}
          copyError={copyError}
          onCopy={() => void copyInviteToken()}
          onClose={closeInviteHandoff}
        />
      )}
    </AppShell>
  );
}

function AppShell({
  adminEmail,
  onSignOut,
  children,
}: {
  adminEmail: string;
  onSignOut: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand-lockup">
          <span className="brand-mark">B</span>
          <div>
            <strong>BOSS</strong>
            <span>PLATFORM ADMIN</span>
          </div>
        </div>
        <div className="sidebar-divider" />
        <div className="sidebar-caption">WORKSPACE</div>
        <div className="sidebar-link active">
          <Users size={17} aria-hidden="true" />
          <span>Customers</span>
        </div>
        <div className="sidebar-footer">
          <span className="secure-dot" />
          <div>
            <strong>Secure admin session</strong>
            <span>Separate Firebase project</span>
          </div>
        </div>
      </aside>
      <div className="main-column">
        <header className="topbar">
          <div className="topbar-context"><span>Platform Admin</span><span className="crumb-divider">/</span><strong>Customer management</strong></div>
          <div className="admin-account">
            <span className="admin-avatar">{adminEmail.charAt(0).toUpperCase() || "A"}</span>
            <span className="admin-email">{adminEmail}</span>
            <button className="signout-button" onClick={onSignOut} aria-label="Sign out">
              <LogOut size={16} aria-hidden="true" />
            </button>
          </div>
        </header>
        {children}
        <footer className="app-footer">
          <span>BOSS Platform Admin</span>
          <span>Internal operations · Authorized access only</span>
        </footer>
      </div>
    </div>
  );
}

function StatusBadge({ status }: { status: string }) {
  return <span className={`status-badge status-${status.toLowerCase()}`}>{formatStatus(status)}</span>;
}

function InviteStatusBadge({ status }: { status?: string }) {
  if (!status) return <span className="status-badge status-secondary">Not requested</span>;
  const s = status.toLowerCase();
  let badgeClass = "status-secondary";
  if (s === "accepted" || s === "sent") badgeClass = "status-active";
  else if (s === "pending" || s === "created") badgeClass = "status-provisioning";
  else if (s === "failed" || s === "retry_required" || s === "expired") badgeClass = "status-suspended";
  return <span className={`status-badge ${badgeClass}`}>{formatStatus(status)}</span>;
}

function ProvisioningResult({
  outcome,
  tenantStatus,
  onBack,
  onRetryInvite,
}: {
  outcome: ProvisionOutcome;
  tenantStatus?: string;
  onBack: () => void;
  onRetryInvite: () => void;
}) {
  const { body, status, initialAdminEmail } = outcome;
  const contractFailure = status === 424;
  const invitationStatus = body?.invitationStatus;
  const token = body?.adminInviteToken;
  const identifiers = [
    ["Tenant ID", body?.tenantId],
    ["Customer group ID", body?.customerGroupId],
    ["Legal entity ID", body?.legalEntityId],
    ["Site ID", body?.siteId],
  ] as const;

  return (
    <main className="result-page">
      <button className="back-link" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Customer directory</button>
      <div className={`result-hero ${contractFailure ? "result-warning" : ""}`}>
        <span className="result-icon">{contractFailure ? <CircleAlert size={24} /> : <Check size={24} />}</span>
        <div>
          <p className="eyebrow">{contractFailure ? "PROVISIONING NEEDS ATTENTION" : "PROVISIONING RESPONSE"}</p>
          <h1>{contractFailure ? "Customer created; invitation was not created" : "Customer provisioning accepted"}</h1>
          <p>HTTP {status}{body && ` · Seeding ${body.seedingApplied === true ? "applied" : body.seedingApplied === false ? "not applied" : "not reported"}.`}</p>
        </div>
      </div>

      <section className="result-card">
        <div className="result-card-heading">
          <div>
            <h2>Provisioning details</h2>
            <p>Identifiers and statuses returned by the Platform Admin API.</p>
          </div>
          {tenantStatus && <StatusBadge status={tenantStatus} />}
        </div>
        <dl className="identifier-list">
          {identifiers.map(([label, value]) => (
            <div key={label}>
              <dt>{label}</dt>
              <dd>{value || "Not returned"}</dd>
            </div>
          ))}
        </dl>
        <div className="result-status-row">
          <div>
            <span className="metric-label">Invitation status</span>
            <strong>{invitationStatus || (contractFailure ? "Not specified in the 424 response schema" : "Not returned")}</strong>
          </div>
          <div>
            <span className="metric-label">Tenant status</span>
            <strong>{tenantStatus ? formatStatus(tenantStatus) : "Not included in create response"}</strong>
          </div>
        </div>
        {!tenantStatus && (
          <p className="subtle-note">
            The create response does not contain a tenant status. Refresh the directory to read the current value from the list API.
          </p>
        )}
        {contractFailure && (
          <div className="banner banner-warning" role="status">
            The API contract says the tenant was created and seeded, but the initial administrator invitation was not created. It does not define a 424 response body, so this page cannot show identifiers or retry against the tenant. Return to the directory, refresh, and retry the invitation from the tenant row.
          </div>
        )}
        {token && (
          <div className="token-handoff">
            <div>
              <strong>One-time invitation token</strong>
              <p>Copy it now to complete the administrator handoff. It is held only in this page’s memory.</p>
            </div>
            <code>{token}</code>
            <p className="token-email">For {initialAdminEmail || "the initial administrator"}</p>
          </div>
        )}
        <div className="result-actions">
          {invitationStatus === "RETRY_REQUIRED" && body?.tenantId && (
            <button className="button button-primary" onClick={onRetryInvite}>
              Retry administrator invitation
            </button>
          )}
          <button className="button button-secondary" onClick={onBack}>Return to customers</button>
        </div>
        {invitationStatus === "CREATED" && !token && (
          <p className="subtle-note">
            The API confirms an invitation exists but did not return its one-time token in this response.
          </p>
        )}
      </section>
    </main>
  );
}

function ActionDialog({
  action,
  reason,
  error,
  busy,
  onReasonChange,
  onClose,
  onSubmit,
}: {
  action: TenantAction;
  reason: string;
  error: string;
  busy: boolean;
  onReasonChange: (value: string) => void;
  onClose: () => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  const label = action.kind === "activate" ? "Activate" : action.kind === "suspend" ? "Suspend" : "Reactivate";
  const description = action.kind === "activate"
    ? "The API will activate this tenant if seeding is complete and no requested invitation is outstanding."
    : action.kind === "suspend"
      ? "Suspending this tenant will immediately block its customer API requests."
      : "This will restore the tenant to active status.";

  return (
    <Modal title={`${label} tenant?`} onClose={onClose}>
      <form onSubmit={onSubmit}>
        <p className="dialog-copy"><strong>{action.tenant.name}</strong> · {description}</p>
        {action.kind === "suspend" && (
          <div className="field dialog-field">
            <label htmlFor="suspend-reason">Reason <span className="required-mark" aria-hidden="true">*</span></label>
            <textarea
              id="suspend-reason"
              value={reason}
              onChange={(event) => onReasonChange(event.currentTarget.value)}
              required
              rows={3}
              autoFocus
            />
          </div>
        )}
        {error && <div className="banner banner-error" role="alert">{error}</div>}
        <div className="dialog-actions">
          <button className="button button-quiet" type="button" onClick={onClose} disabled={busy}>Cancel</button>
          <button
            className={`button ${action.kind === "suspend" ? "button-danger" : "button-primary"}`}
            type="submit"
            disabled={busy || (action.kind === "suspend" && !reason.trim())}
          >
            {busy ? "Updating…" : `Confirm ${label.toLowerCase()}`}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function InviteDialog({
  email,
  error,
  busy,
  tenantName,
  onClose,
  onEmailChange,
  onSubmit,
}: {
  email: string;
  error: string;
  busy: boolean;
  tenantName: string;
  onClose: () => void;
  onEmailChange: (value: string) => void;
  onSubmit: (event: FormEvent<HTMLFormElement>) => void;
}) {
  return (
    <Modal title="Initial administrator invitation" onClose={onClose}>
      <form onSubmit={onSubmit}>
        <p className="dialog-copy">Create or retry the first administrator invitation for <strong>{tenantName}</strong>.</p>
        <div className="field dialog-field">
          <label htmlFor="invite-email">Administrator email</label>
          <input
            id="invite-email"
            type="email"
            required
            value={email}
            onChange={(event) => onEmailChange(event.currentTarget.value)}
            autoFocus
          />
        </div>
        <p className="field-hint">The API returns a one-time token only when it creates the invitation.</p>
        {error && <div className="banner banner-error" role="alert">{error}</div>}
        <div className="dialog-actions">
          <button className="button button-quiet" type="button" onClick={onClose} disabled={busy}>Cancel</button>
          <button className="button button-primary" type="submit" disabled={busy}>
            {busy ? "Sending…" : "Create invitation"}
          </button>
        </div>
      </form>
    </Modal>
  );
}

function InviteHandoffDialog({
  handoff,
  copied,
  copyError,
  onCopy,
  onClose,
}: {
  handoff: InviteHandoff;
  copied: boolean;
  copyError: string;
  onCopy: () => void;
  onClose: () => void;
}) {
  return (
    <Modal title="Invitation response" onClose={onClose}>
      <div className="handoff-content" role="status">
        <span className="result-icon"><Check size={22} aria-hidden="true" /></span>
        <p><strong>{handoff.status || "Status not returned"}</strong> for {handoff.email} at {handoff.tenantName}.</p>
        {handoff.inviteId && <p className="field-hint">Invitation ID: <code>{handoff.inviteId}</code></p>}
        {handoff.expiresAt && <p className="field-hint">Expires: {new Date(handoff.expiresAt).toLocaleString()}</p>}
        {handoff.token ? (
          <div className="token-handoff">
            <strong>One-time invitation token</strong>
            <code>{handoff.token}</code>
            <button className="button button-secondary" onClick={onCopy}>
              {copied ? <Check size={15} /> : <Copy size={15} aria-hidden="true" />}
              {copied ? "Copied" : "Copy token"}
            </button>
            {copyError && <p className="field-error" role="alert">{copyError}</p>}
          </div>
        ) : (
          <div className="banner banner-warning">
            No token was returned in this API response. A replayed invitation token is not available.
          </div>
        )}
        <p className="token-security-note">This token is not saved in browser storage. Close this message to clear it from the page.</p>
        <div className="dialog-actions">
          <button className="button button-primary" onClick={onClose}>Clear and close</button>
        </div>
      </div>
    </Modal>
  );
}

function Modal({
  title,
  onClose,
  children,
}: {
  title: string;
  onClose: () => void;
  children: React.ReactNode;
}) {
  return (
    <div className="modal-backdrop" onMouseDown={(event) => {
      if (event.target === event.currentTarget) onClose();
    }}>
      <section className="modal" role="dialog" aria-modal="true" aria-labelledby="modal-heading">
        <header className="modal-heading">
          <h2 id="modal-heading">{title}</h2>
          <button className="icon-button" onClick={onClose} aria-label="Close dialog">
            <X size={17} aria-hidden="true" />
          </button>
        </header>
        {children}
      </section>
    </div>
  );
}

function formatStatus(status: string): string {
  if (!status) return "";
  return status
    .replaceAll("_", " ")
    .toLowerCase()
    .replace(/\b\w/g, (letter) => letter.toUpperCase());
}

function errorMessage(error: unknown): string {
  if (error instanceof PlatformAdminApiError) return `${error.message} (HTTP ${error.status})`;
  if (error instanceof Error) return error.message;
  return "The request could not be completed.";
}
