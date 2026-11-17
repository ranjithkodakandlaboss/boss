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
  type TenantDetails,
  type TenantDetailsUpdateRequest,
  type TenantStatusFilter,
  type TenantSummary,
} from "../api/platformAdminApi";
import { ProvisionCustomerForm } from "./ProvisionCustomerForm";

const PAGE_SIZE = 20;

type ConsolePage = "list" | "create" | "result";

interface ProvisionOutcome {
  status: number;
  body: ProvisionResult;
  tenantName: string;
  tenantSlug: string;
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
  const [detailTenant, setDetailTenant] = useState<TenantSummary | null>(null);
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
    response: ApiResponse<ProvisionResult>,
    tenantName: string,
    tenantSlug: string,
  ) {
    setProvisionOutcome({ ...response, tenantName, tenantSlug });
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
      if (action.kind === "activate") {
        const result = await api.activateTenant(action.tenant.id);
        const invitationMessage = result.invitationStatus === "CREATED"
          ? ` The administrator invitation was sent${action.tenant.adminEmail ? ` to ${action.tenant.adminEmail}` : ""}.`
          : result.invitationStatus === "RETRY_REQUIRED"
            ? " The tenant is active, but invitation delivery failed. Retry the invitation from the customer row."
            : " No administrator invitation was requested.";
        setNotice(`Tenant status confirmed as ${formatStatus(result.tenantStatus)}.${invitationMessage}`);
      } else if (action.kind === "suspend") {
        await api.suspendTenant(action.tenant.id, suspendReason.trim());
        setNotice("Suspension request succeeded. The directory is reloading the current status from the API.");
      } else {
        await api.reactivateTenant(action.tenant.id);
        setNotice("Reactivation request succeeded. The directory is reloading the current status from the API.");
      }
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
        />
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
                        <td>
                          <div><code>{tenant.slug}</code></div>
                          {tenant.workspaceUrl ? (
                            <div style={{ marginTop: "4px" }}>
                              <a
                                href={tenant.workspaceUrl}
                                target="_blank"
                                rel="noopener noreferrer"
                                className="workspace-link"
                                style={{ fontSize: "11px", color: "#2563eb", textDecoration: "underline", display: "inline-flex", alignItems: "center", gap: "2px" }}
                              >
                                Workspace URL ↗
                              </a>
                            </div>
                          ) : null}
                        </td>
                        <td><StatusBadge status={tenant.status} /></td>
                        <td>{formatStatus(tenant.industryType || "") || "—"}</td>
                        <td>
                          <div className="tenant-admin-email">{tenant.adminEmail || "—"}</div>
                          <div style={{ marginTop: "4px" }}><InviteStatusBadge status={tenant.invitationStatus} /></div>
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
                            {tenant.status === "active"
                              && ["NOT_REQUESTED", "RETRY_REQUIRED"].includes(tenant.invitationStatus ?? "") && (
                              <button className="text-button text-muted" onClick={() => openInvite(tenant, tenant.adminEmail)}>
                                {tenant.adminEmail ? "Retry invite" : "Invite admin"}
                              </button>
                            )}
                            <button className="text-button" onClick={() => setDetailTenant(tenant)}>
                              Details
                            </button>
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

      {detailTenant && (
        <CustomerDetailsDialog
          api={api}
          tenant={detailTenant}
          onClose={() => setDetailTenant(null)}
          onSaved={(details) => {
            setTenants((current) => current.map((tenant) => tenant.id === details.tenantId
              ? { ...tenant, name: details.tenantName }
              : tenant));
            setReloadId((current) => current + 1);
          }}
          onEditAdminEmail={() => {
            setDetailTenant(null);
            openInvite(detailTenant, detailTenant.adminEmail);
          }}
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
  const label = s === "created" ? "Invitation sent"
    : s === "retry_required" ? "Delivery failed"
      : s === "pending_activation" ? "Pending activation"
        : formatStatus(status);
  return <span className={`status-badge ${badgeClass}`}>{label}</span>;
}

function ProvisioningResult({
  outcome,
  tenantStatus,
  onBack,
}: {
  outcome: ProvisionOutcome;
  tenantStatus?: string;
  onBack: () => void;
}) {
  const { body, status } = outcome;
  const invitationStatus = body.invitationStatus;
  const identifiers = [
    ["Tenant ID", body.tenantId],
    ["Customer group ID", body.customerGroupId],
    ["Legal entity ID", body.legalEntityId],
    ["Site ID", body.siteId],
  ] as const;

  return (
    <main className="result-page">
      <button className="back-link" onClick={onBack}><ArrowLeft size={15} aria-hidden="true" /> Customer directory</button>
      <div className="result-hero">
        <span className="result-icon"><Check size={24} /></span>
        <div>
          <p className="eyebrow">PROVISIONING RESPONSE</p>
          <h1>Customer provisioning accepted</h1>
          <p>HTTP {status} · Seeding {body.seedingApplied ? "applied" : "not applied"}.</p>
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
        <div style={{ margin: "20px 0", padding: "16px", background: "#f8fafc", border: "1px solid #e2e8f0", borderRadius: "8px" }}>
          <span style={{ fontSize: "11px", fontWeight: 700, textTransform: "uppercase", color: "#64748b", letterSpacing: "0.5px" }}>Company workspace slug</span>
          <div style={{ marginTop: "6px" }}>
            <code style={{ fontSize: "13px", color: "#0f172a", wordBreak: "break-all" }}>{outcome.tenantSlug || "Not returned"}</code>
          </div>
          <p style={{ margin: "8px 0 0", fontSize: "12px", color: "#64748b" }}>
            The invitation uses the company workspace URL configured for this environment. That URL is shown on the customer list.
          </p>
        </div>
        <div className="result-status-row">
          <div>
            <span className="metric-label">Invitation status</span>
            <strong>{invitationStatus || "Not reported"}</strong>
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
        {invitationStatus === "PENDING_ACTIVATION" && (
          <p className="subtle-note">
            The administrator email is saved. No invitation has been created or sent; use Activate from the customer list when ready.
          </p>
        )}
        <div className="result-actions">
          <button className="button button-secondary" onClick={onBack}>Return to customers</button>
        </div>
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
    ? action.tenant.adminEmail
      ? `This enables the customer and sends an administrator invitation to ${action.tenant.adminEmail} after the API confirms activation.`
      : "This enables the customer after the API confirms provisioning and industry seeding are complete. No administrator email is configured."
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

function CustomerDetailsDialog({
  api,
  tenant,
  onClose,
  onSaved,
  onEditAdminEmail,
}: {
  api: PlatformAdminApi;
  tenant: TenantSummary;
  onClose: () => void;
  onSaved: (details: TenantDetails) => void;
  onEditAdminEmail: () => void;
}) {
  const [details, setDetails] = useState<TenantDetails | null>(null);
  const [loading, setLoading] = useState(true);
  const [loadError, setLoadError] = useState("");
  const [reloadId, setReloadId] = useState(0);
  const [editing, setEditing] = useState(false);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState("");
  const [saved, setSaved] = useState(false);
  const [tenantName, setTenantName] = useState("");
  const [entities, setEntities] = useState<TenantDetails["legalEntities"]>([]);
  const [sites, setSites] = useState<TenantDetails["sites"]>([]);

  useEffect(() => {
    let active = true;
    setLoading(true);
    setLoadError("");
    void api.getTenantDetails(tenant.id).then((result) => {
      if (!active) return;
      setDetails(result);
      setTenantName(result.tenantName);
      setEntities(result.legalEntities);
      setSites(result.sites);
    }).catch((error: unknown) => {
      if (active) setLoadError(errorMessage(error));
    }).finally(() => {
      if (active) setLoading(false);
    });
    return () => {
      active = false;
    };
  }, [api, tenant.id, reloadId]);

  const canEditAdminEmail = tenant.status === "active"
    && ["NOT_REQUESTED", "RETRY_REQUIRED"].includes(tenant.invitationStatus ?? "");
  const canEditDetails = details?.tenantStatus !== "archived";

  function startEditing() {
    if (!details) return;
    setTenantName(details.tenantName);
    setEntities(details.legalEntities);
    setSites(details.sites);
    setEditing(true);
    setSaveError("");
    setSaved(false);
  }

  function buildUpdate(): TenantDetailsUpdateRequest {
    if (!details) return {};
    const changedEntities = entities.flatMap((entity, index) => {
      const current = details.legalEntities[index];
      if (!current) return [];
      const update: NonNullable<TenantDetailsUpdateRequest["legalEntities"]>[number] = {
        id: entity.id,
        ...(entity.name !== current.name ? { name: entity.name } : {}),
        ...(entity.legalName !== current.legalName ? { legalName: entity.legalName } : {}),
        ...(entity.registeredAddress !== current.registeredAddress
          ? { registeredAddress: entity.registeredAddress ?? "" }
          : {}),
      };
      return Object.keys(update).length > 1 ? [update] : [];
    });
    const changedSites = sites.flatMap((site, index) => {
      const current = details.sites[index];
      if (!current) return [];
      const update: NonNullable<TenantDetailsUpdateRequest["sites"]>[number] = {
        id: site.id,
        ...(site.name !== current.name ? { name: site.name } : {}),
        ...(site.address !== current.address ? { address: site.address ?? "" } : {}),
      };
      return Object.keys(update).length > 1 ? [update] : [];
    });
    return {
      ...(tenantName !== details.tenantName ? { tenantName } : {}),
      ...(changedEntities.length ? { legalEntities: changedEntities } : {}),
      ...(changedSites.length ? { sites: changedSites } : {}),
    };
  }

  async function saveDetails(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (!details) return;
    const update = buildUpdate();
    if (!Object.keys(update).length) {
      setEditing(false);
      return;
    }
    setSaving(true);
    setSaveError("");
    setSaved(false);
    try {
      await api.updateTenantDetails(tenant.id, update, crypto.randomUUID());
      const result: TenantDetails = {
        ...details,
        tenantName: update.tenantName ?? details.tenantName,
        legalEntities: details.legalEntities.map((entity) => ({
          ...entity,
          ...update.legalEntities?.find((item) => item.id === entity.id),
        })),
        sites: details.sites.map((site) => ({
          ...site,
          ...update.sites?.find((item) => item.id === site.id),
        })),
      };
      setDetails(result);
      setTenantName(result.tenantName);
      setEntities(result.legalEntities);
      setSites(result.sites);
      setEditing(false);
      setSaved(true);
      onSaved(result);
    } catch (error) {
      setSaveError(errorMessage(error));
    } finally {
      setSaving(false);
    }
  }

  return (
    <Modal title="Customer details" onClose={onClose}>
      {loading ? (
        <div className="state-panel" role="status">
          <LoaderCircle className="spinner" size={22} aria-hidden="true" />
          <strong>Loading customer details</strong>
        </div>
      ) : loadError ? (
        <div className="state-panel state-error" role="alert">
          <CircleAlert size={22} aria-hidden="true" />
          <strong>Could not load customer details</strong>
          <span>{loadError}</span>
          <button className="button button-secondary" onClick={() => setReloadId((current) => current + 1)}>
            Try again
          </button>
        </div>
      ) : details && editing ? (
        <form onSubmit={saveDetails}>
          <p className="dialog-copy">Edit customer-facing names and addresses. Legal identifiers, types, status, plan, and industry are read-only.</p>
          <div className="field dialog-field">
            <label htmlFor="detail-tenant-name">Tenant name</label>
            <input id="detail-tenant-name" required maxLength={120} value={tenantName}
              onChange={(event) => setTenantName(event.currentTarget.value)} />
          </div>
          {entities.map((entity, index) => (
            <fieldset className="details-edit-group" key={entity.id}>
              <legend>Legal entity {index + 1}</legend>
              <div className="field dialog-field">
                <label htmlFor={`entity-name-${entity.id}`}>Display name</label>
                <input id={`entity-name-${entity.id}`} required maxLength={160} value={entity.name}
                  onChange={(event) => {
                    const value = event.currentTarget.value;
                    setEntities((current) => current.map((item) => item.id === entity.id ? { ...item, name: value } : item));
                  }} />
              </div>
              <div className="field dialog-field">
                <label htmlFor={`entity-legal-name-${entity.id}`}>Legal name</label>
                <input id={`entity-legal-name-${entity.id}`} required maxLength={200} value={entity.legalName}
                  onChange={(event) => {
                    const value = event.currentTarget.value;
                    setEntities((current) => current.map((item) => item.id === entity.id ? { ...item, legalName: value } : item));
                  }} />
              </div>
              <div className="field dialog-field">
                <label htmlFor={`entity-address-${entity.id}`}>Registered address</label>
                <textarea id={`entity-address-${entity.id}`} maxLength={1000} value={entity.registeredAddress ?? ""}
                  onChange={(event) => {
                    const value = event.currentTarget.value;
                    setEntities((current) => current.map((item) => item.id === entity.id ? { ...item, registeredAddress: value } : item));
                  }} />
              </div>
            </fieldset>
          ))}
          {sites.map((site, index) => (
            <fieldset className="details-edit-group" key={site.id}>
              <legend>Site {index + 1}</legend>
              <div className="field dialog-field">
                <label htmlFor={`site-name-${site.id}`}>Site name</label>
                <input id={`site-name-${site.id}`} required maxLength={120} value={site.name}
                  onChange={(event) => {
                    const value = event.currentTarget.value;
                    setSites((current) => current.map((item) => item.id === site.id ? { ...item, name: value } : item));
                  }} />
              </div>
              <div className="field dialog-field">
                <label htmlFor={`site-address-${site.id}`}>Site address</label>
                <textarea id={`site-address-${site.id}`} maxLength={1000} value={site.address ?? ""}
                  onChange={(event) => {
                    const value = event.currentTarget.value;
                    setSites((current) => current.map((item) => item.id === site.id ? { ...item, address: value } : item));
                  }} />
              </div>
            </fieldset>
          ))}
          {saveError && <div className="banner banner-error" role="alert">{saveError}</div>}
          <div className="dialog-actions">
            <button type="button" className="button button-quiet" onClick={() => setEditing(false)} disabled={saving}>Cancel</button>
            <button type="submit" className="button button-primary" disabled={saving}>
              {saving ? "Saving…" : "Save details"}
            </button>
          </div>
        </form>
      ) : details ? (
        <>
          <p className="dialog-copy">Tenant, company, and site information from the Platform Admin API.</p>
          {saved && <div className="banner banner-success" role="status">Customer details saved.</div>}
          <dl className="customer-detail-list">
            <div><dt>Customer group</dt><dd>{details.customerGroupName || "—"}</dd></div>
            <div><dt>Customer group ID</dt><dd><code>{details.customerGroupId}</code></dd></div>
            <div><dt>Group slug</dt><dd><code>{details.customerGroupSlug || "—"}</code></dd></div>
            <div><dt>Customer / tenant</dt><dd>{details.tenantName}</dd></div>
            <div><dt>Tenant ID</dt><dd><code>{details.tenantId}</code></dd></div>
            <div><dt>Tenant slug</dt><dd><code>{details.tenantSlug}</code></dd></div>
            <div><dt>Status</dt><dd>{formatStatus(details.tenantStatus)}</dd></div>
            <div><dt>Plan tier</dt><dd>{details.planTier || "—"}</dd></div>
            <div><dt>Industry</dt><dd>{formatStatus(details.industryType ?? "") || "—"}</dd></div>
            <div><dt>Administrator email</dt><dd>{details.adminEmail || "—"}</dd></div>
            <div><dt>Invitation status</dt><dd>{formatStatus(details.invitationStatus ?? "NOT_REQUESTED")}</dd></div>
            <div><dt>Workspace URL</dt><dd>{details.workspaceUrl || "—"}</dd></div>
          </dl>
          {details.legalEntities.map((entity) => (
            <section className="details-record" key={entity.id}>
              <h3>Legal entity</h3>
              <dl className="customer-detail-list">
                <div><dt>Name</dt><dd>{entity.name}</dd></div>
                <div><dt>Legal name</dt><dd>{entity.legalName}</dd></div>
                <div><dt>PAN</dt><dd><code>{entity.pan || "—"}</code></dd></div>
                <div><dt>Entity type</dt><dd>{formatStatus(entity.entityType)}</dd></div>
                <div><dt>Status</dt><dd>{formatStatus(entity.status)}</dd></div>
                <div><dt>Registered address</dt><dd>{entity.registeredAddress || "—"}</dd></div>
              </dl>
              {entity.gstRegistrations.map((registration) => (
                <dl className="customer-detail-list" key={registration.id}>
                  <div><dt>GSTIN</dt><dd><code>{registration.gstin}</code></dd></div>
                  <div><dt>Trade name</dt><dd>{registration.tradeName || "—"}</dd></div>
                  <div><dt>Registration type</dt><dd>{formatStatus(registration.registrationType)}</dd></div>
                  <div><dt>State code</dt><dd>{registration.stateCode}</dd></div>
                  <div><dt>Registration status</dt><dd>{formatStatus(registration.status)}</dd></div>
                  <div><dt>Effective from</dt><dd>{registration.effectiveFrom}</dd></div>
                  <div><dt>Effective to</dt><dd>{registration.effectiveTo || "—"}</dd></div>
                </dl>
              ))}
            </section>
          ))}
          {details.sites.map((site) => (
            <section className="details-record" key={site.id}>
              <h3>Site</h3>
              <dl className="customer-detail-list">
                <div><dt>Name</dt><dd>{site.name}</dd></div>
                <div><dt>Code</dt><dd><code>{site.code}</code></dd></div>
                <div><dt>Type</dt><dd>{formatStatus(site.siteType)}</dd></div>
                <div><dt>Status</dt><dd>{formatStatus(site.status)}</dd></div>
                <div><dt>GST registration</dt><dd><code>{site.gstRegistrationId || "—"}</code></dd></div>
                <div><dt>Address</dt><dd>{site.address || "—"}</dd></div>
              </dl>
            </section>
          ))}
          {canEditAdminEmail && (
            <p className="subtle-note">
              Changing the administrator email uses the existing invitation API and sends an invitation as an explicit action.
            </p>
          )}
          {details.tenantStatus === "archived" && (
            <p className="subtle-note">Archived customer details are read-only.</p>
          )}
          <div className="dialog-actions">
            {canEditDetails && (
              <button type="button" className="button button-primary" onClick={startEditing}>Edit details</button>
            )}
            {canEditAdminEmail && (
              <button type="button" className="button button-secondary" onClick={onEditAdminEmail}>
                Change email &amp; send invite
              </button>
            )}
          </div>
        </>
      ) : null}
      <div className="dialog-actions">
        <button type="button" className="button button-secondary" onClick={onClose}>Close</button>
      </div>
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
