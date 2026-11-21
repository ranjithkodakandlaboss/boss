import { useEffect, useState, type FormEvent } from "react";
import { Plus, Pencil, UserMinus, RotateCcw, Shield } from "lucide-react";
import { api, ApiError } from "../auth/api";
import { useCustomer } from "../auth/CustomerState";
import Button from "../components/shared/Button";
import ConfirmDialog from "../components/shared/ConfirmDialog";
import RecordSheet from "../components/shared/RecordSheet";
import StatusChip from "../components/shared/StatusChip";

export interface UserRow {
  id: string;
  email: string;
  displayName?: string;
  phone?: string;
  status: string;
  roles?: Array<{ role: string }>;
  createdAt?: string;
}

const ROLES = [
  { value: "OWNER", label: "Owner / Full Admin" },
  { value: "OPS_MANAGER", label: "Operations Manager" },
  { value: "SALES_MANAGER", label: "Sales Manager" },
  { value: "PURCHASE_MANAGER", label: "Purchase Manager" },
  { value: "PRODUCTION_SUPERVISOR", label: "Production Supervisor" },
  { value: "QUALITY_INSPECTOR", label: "Quality Inspector" },
  { value: "STORE_KEEPER", label: "Store Keeper" },
  { value: "ACCOUNTS_STAFF", label: "Accounts Staff" },
  { value: "FIELD_WORKER", label: "Field Worker / Operator" },
];

export default function UsersAdmin() {
  const { user: currentUser } = useCustomer();
  const canManage = Boolean(currentUser?.roles.some(({ role }) => role === "OWNER"));

  const [users, setUsers] = useState<UserRow[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [saving, setSaving] = useState(false);

  // Form State
  const [modalOpen, setModalOpen] = useState(false);
  const [editingUser, setEditingUser] = useState<UserRow | null>(null);
  const [form, setForm] = useState({ displayName: "", email: "", role: "OPS_MANAGER", phone: "", status: "active" });

  // Delete / Deactivate confirm
  const [deactivateConfirmUser, setDeactivateConfirmUser] = useState<UserRow | null>(null);

  async function loadUsers() {
    setLoading(true);
    setError("");
    try {
      const response = await api<{ content?: UserRow[] }>("/api/v1/users");
      setUsers(response.content ?? []);
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Failed to load users.");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    void loadUsers();
  }, []);

  function openAddUser() {
    setEditingUser(null);
    setForm({ displayName: "", email: "", role: "OPS_MANAGER", phone: "", status: "active" });
    setModalOpen(true);
  }

  function openEditUser(user: UserRow) {
    setEditingUser(user);
    setForm({
      displayName: user.displayName || "",
      email: user.email,
      role: user.roles?.[0]?.role || "OPS_MANAGER",
      phone: user.phone || "",
      status: user.status || "active",
    });
    setModalOpen(true);
  }

  async function handleSubmit(event: FormEvent) {
    event.preventDefault();
    if (!canManage) return;
    setSaving(true);
    setError("");
    try {
      if (editingUser) {
        await api(`/api/v1/users/${editingUser.id}`, {
          method: "PUT",
          body: JSON.stringify(form),
        });
      } else {
        await api("/api/v1/users", {
          method: "POST",
          body: JSON.stringify(form),
        });
      }
      setModalOpen(false);
      await loadUsers();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Could not save user.");
    } finally {
      setSaving(false);
    }
  }

  async function handleDeactivate() {
    if (!deactivateConfirmUser || !canManage) return;
    setSaving(true);
    try {
      await api(`/api/v1/users/${deactivateConfirmUser.id}`, { method: "DELETE" });
      setDeactivateConfirmUser(null);
      await loadUsers();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Could not deactivate user.");
    } finally {
      setSaving(false);
    }
  }

  async function handleReactivate(user: UserRow) {
    if (!canManage) return;
    setSaving(true);
    try {
      await api(`/api/v1/users/${user.id}`, {
        method: "PUT",
        body: JSON.stringify({ status: "active" }),
      });
      await loadUsers();
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : cause instanceof Error ? cause.message : "Could not reactivate user.");
    } finally {
      setSaving(false);
    }
  }

  if (loading) {
    return <p className="px-6 py-4 text-sm text-[var(--text-2)]">Loading users and employees…</p>;
  }

  return (
    <div className="px-6 pb-6 space-y-4">
      {error && <p role="alert" className="p-3 rounded-lg border border-[var(--error)] text-sm text-[var(--error)]">{error}</p>}

      <div className="flex items-center justify-between pb-2 border-b" style={{ borderColor: "var(--border)" }}>
        <div>
          <h2 className="text-base font-bold text-[var(--text)]">Users & Employees ({users.length})</h2>
          <p className="text-xs text-[var(--text-2)]">Manage team members, assign operational roles, and activate or deactivate accounts.</p>
        </div>
        {canManage && (
          <Button className="text-xs px-3 py-1.5" onClick={openAddUser}>
            <Plus size={14} className="mr-1" /> Add User
          </Button>
        )}
      </div>

      <div className="overflow-x-auto rounded-xl border" style={{ borderColor: "var(--border)" }}>
        <table className="w-full text-left text-sm">
          <thead style={{ background: "var(--surface-2)" }}>
            <tr>
              <th className="py-2.5 px-4 text-xs font-semibold">User</th>
              <th className="py-2.5 px-4 text-xs font-semibold">Email</th>
              <th className="py-2.5 px-4 text-xs font-semibold">Role</th>
              <th className="py-2.5 px-4 text-xs font-semibold">Phone / WhatsApp</th>
              <th className="py-2.5 px-4 text-xs font-semibold">Status</th>
              <th className="py-2.5 px-4 text-xs font-semibold text-right">Actions</th>
            </tr>
          </thead>
          <tbody>
            {users.map((row) => {
              const roleName = row.roles?.[0]?.role;
              const roleObj = ROLES.find((r) => r.value === roleName);
              const isDeactivated = row.status === "deactivated";
              return (
                <tr key={row.id} className="border-t hover:bg-[var(--surface-2)]" style={{ borderColor: "var(--border)" }}>
                  <td className="py-2.5 px-4 font-semibold text-[var(--text)]">
                    {row.displayName || "—"}
                  </td>
                  <td className="py-2.5 px-4 font-mono text-[var(--text-2)] text-xs">
                    {row.email}
                  </td>
                  <td className="py-2.5 px-4">
                    <span className="inline-flex items-center gap-1 font-medium text-xs">
                      <Shield size={12} className="text-[var(--brand)]" />
                      {roleObj?.label || roleName || "User"}
                    </span>
                  </td>
                  <td className="py-2.5 px-4 text-[var(--text-2)] text-xs">
                    {row.phone || "—"}
                  </td>
                  <td className="py-2.5 px-4">
                    <StatusChip status={row.status} size="sm" />
                  </td>
                  <td className="py-2.5 px-4 text-right">
                    <div className="inline-flex items-center gap-1">
                      {canManage && (
                        <>
                          <button
                            type="button"
                            onClick={() => openEditUser(row)}
                            className="btn-ghost p-1.5 rounded text-[var(--text-2)] hover:text-[var(--text)]"
                            title="Edit User"
                          >
                            <Pencil size={15} />
                          </button>
                          {isDeactivated ? (
                            <button
                              type="button"
                              onClick={() => void handleReactivate(row)}
                              className="btn-ghost p-1.5 rounded text-[var(--success)] hover:opacity-80"
                              title="Reactivate User"
                            >
                              <RotateCcw size={15} />
                            </button>
                          ) : (
                            <button
                              type="button"
                              onClick={() => setDeactivateConfirmUser(row)}
                              className="btn-ghost p-1.5 rounded text-[var(--error)] hover:opacity-80"
                              title="Deactivate (Soft Delete)"
                            >
                              <UserMinus size={15} />
                            </button>
                          )}
                        </>
                      )}
                    </div>
                  </td>
                </tr>
              );
            })}
            {users.length === 0 && (
              <tr>
                <td colSpan={6} className="py-6 text-center text-sm text-[var(--text-2)]">No users found.</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      {/* Add / Edit User Drawer */}
      {modalOpen && (
        <RecordSheet
          title={editingUser ? `Edit User "${editingUser.displayName || editingUser.email}"` : "Add New User / Employee"}
          onClose={() => setModalOpen(false)}
          primary={{
            label: saving ? "Saving…" : editingUser ? "Save Changes" : "Create User",
            disabled: saving || !form.email.trim() || !form.displayName.trim(),
            onClick: () => { void handleSubmit({ preventDefault: () => {} } as FormEvent); },
          }}
          secondary={{ label: "Cancel", onClick: () => setModalOpen(false) }}
        >
          <form onSubmit={(e) => void handleSubmit(e)} className="space-y-4">
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Full Name *
              <input
                required
                value={form.displayName}
                onChange={(e) => setForm((f) => ({ ...f, displayName: e.target.value }))}
                className="eoss-input"
                placeholder="e.g. Ramesh Kumar"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Email Address *
              <input
                required
                type="email"
                disabled={Boolean(editingUser)}
                value={form.email}
                onChange={(e) => setForm((f) => ({ ...f, email: e.target.value }))}
                className="eoss-input font-mono"
                placeholder="e.g. ramesh@company.com"
              />
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Role & Permissions *
              <select
                value={form.role}
                onChange={(e) => setForm((f) => ({ ...f, role: e.target.value }))}
                className="eoss-input"
              >
                {ROLES.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
              </select>
            </label>
            <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
              Phone / WhatsApp
              <input
                value={form.phone}
                onChange={(e) => setForm((f) => ({ ...f, phone: e.target.value }))}
                className="eoss-input font-mono"
                placeholder="e.g. +91 9876543210"
              />
            </label>
            {editingUser && (
              <label className="flex flex-col gap-1 text-sm font-medium text-[var(--text)]">
                Account Status
                <select
                  value={form.status}
                  onChange={(e) => setForm((f) => ({ ...f, status: e.target.value }))}
                  className="eoss-input"
                >
                  <option value="active">Active</option>
                  <option value="suspended">Suspended</option>
                  <option value="deactivated">Deactivated</option>
                </select>
              </label>
            )}
          </form>
        </RecordSheet>
      )}

      {/* Deactivate User Confirmation */}
      <ConfirmDialog
        open={deactivateConfirmUser !== null}
        title="Deactivate User?"
        body={deactivateConfirmUser ? `Are you sure you want to deactivate ${deactivateConfirmUser.displayName || deactivateConfirmUser.email}? This will revoke system access while preserving transaction history (soft delete).` : ""}
        confirmLabel="Deactivate User"
        onConfirm={() => void handleDeactivate()}
        onCancel={() => setDeactivateConfirmUser(null)}
      />
    </div>
  );
}
