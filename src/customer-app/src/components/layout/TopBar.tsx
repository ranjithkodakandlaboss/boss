import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Bell, Check, ChevronDown, HelpCircle, Menu, Search, Settings } from "lucide-react";
import clsx from "clsx";
import { useCustomer } from "../../auth/CustomerState";
import { api, safeRandomUUID } from "../../auth/api";
import { signOut } from "../../auth/session";
import { useShell } from "../../shell/ShellContext";
import { PALETTES } from "../../shell/theme";
import QuickNewMenu from "./QuickNewMenu";
import GlobalSearchModal from "../search/GlobalSearchModal";
import Modal from "../shared/Modal";

type AppNotification = {
  id: string;
  notificationType: string;
  entityType: string | null;
  entityId: string | null;
  title: string;
  body: string | null;
  isRead: boolean;
  createdAt: string;
};

export default function TopBar({ onOpenMenu }: { onOpenMenu?: () => void }) {
  const { collapsed, palette, mode, setPalette, setMode } = useShell();
  const customer = useCustomer();
  const navigate = useNavigate();
  const [searchOpen, setSearchOpen] = useState(false);
  const [themeMenuOpen, setThemeMenuOpen] = useState(false);
  const [helpOpen, setHelpOpen] = useState(false);
  const [profileOpen, setProfileOpen] = useState(false);
  const [notesOpen, setNotesOpen] = useState(false);
  const [notifications, setNotifications] = useState<AppNotification[]>([]);
  const [notificationsLoading, setNotificationsLoading] = useState(false);
  const [notificationsError, setNotificationsError] = useState("");

  useEffect(() => {
    if (!notesOpen) return;
    let active = true;
    setNotificationsLoading(true);
    setNotificationsError("");
    api<AppNotification[]>("/api/v1/notifications")
      .then((items) => {
        if (!Array.isArray(items)) throw new Error("The notification response was invalid.");
        if (active) setNotifications(items);
      })
      .catch((error: unknown) => {
        if (active) setNotificationsError(error instanceof Error ? error.message : "Could not load notifications.");
      })
      .finally(() => {
        if (active) setNotificationsLoading(false);
      });
    return () => { active = false; };
  }, [notesOpen]);

  const openNotification = async (notification: AppNotification) => {
    if (!notification.isRead) {
      try {
        const updated = await api<AppNotification>(`/api/v1/notifications/${notification.id}/read`, {
          method: "POST",
          headers: { "Idempotency-Key": safeRandomUUID() },
        });
        setNotifications((current) => current.map((item) => item.id === updated.id ? updated : item));
      } catch (error) {
        setNotificationsError(error instanceof Error ? error.message : "Could not mark notification as read.");
        return;
      }
    }
    if (notification.entityType === "maintenance_event" && notification.entityId) {
      setNotesOpen(false);
      navigate(`/assets/me/${notification.entityId}`);
    }
  };

  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setSearchOpen(true);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <>
      <header
        className={clsx("fixed top-0 right-0 z-30 h-[70px] flex items-center justify-between px-3 sm:px-5 lg:px-7 gap-2 sm:gap-4 left-0", collapsed ? "md:left-[var(--sidebar-w-collapsed,64px)]" : "md:left-[var(--sidebar-width,216px)]")}
        style={{ background: "var(--app-bg, #F6F4FF)", borderBottom: "1px solid var(--border, #E5E0F5)" }}
      >
        <div className="flex items-center gap-1.5 sm:gap-3 flex-shrink-0 min-w-0">
          {onOpenMenu && (
            <button type="button" onClick={onOpenMenu} className="md:hidden w-8 h-8 rounded-full border flex items-center justify-center bg-[var(--surface)]" style={{ borderColor: "var(--border)" }} aria-label="Open menu">
              <Menu size={16} />
            </button>
          )}
          <button type="button" onClick={() => navigate("/admin")} className="flex flex-col text-left" title="Open Company Details in Admin & Settings" aria-label="Open Company Details in Admin & Settings">
            <span className="text-sm sm:text-base font-extrabold text-[var(--text)] tracking-tight inline-flex items-center gap-1">
              {customer.tenant?.name ?? "Company"} <ChevronDown size={13} className="text-[var(--text-2)] hidden sm:inline" />
            </span>
            <span className="text-[10px] sm:text-[11px] text-[var(--text-2)] font-medium hidden md:inline">{customer.user?.email ?? "Profile unavailable until sign-in"}</span>
          </button>
        </div>
        <div className="flex-1 flex justify-center sm:justify-start max-w-xs sm:max-w-md lg:max-w-xl mx-1 sm:mx-3 md:mx-6">
          <button type="button" onClick={() => setSearchOpen(true)} className="sm:hidden w-8 h-8 rounded-full border flex items-center justify-center bg-[var(--surface)]" style={{ borderColor: "var(--border)" }} aria-label="Global Record Search">
            <Search size={15} />
          </button>
          <button type="button" onClick={() => setSearchOpen(true)} className="hidden sm:flex w-full items-center gap-2 px-4 py-2 rounded-full border text-sm bg-[var(--surface)]" style={{ borderColor: "var(--border)" }}>
            <Search size={15} />
            <span className="truncate flex-1 text-left text-[13px] text-[var(--text-3)]">Search orders, quotes, jobs, approvals, materials...</span>
            <kbd className="hidden lg:inline-flex font-mono text-[10px] px-2 py-0.5 rounded-md border">Ctrl + K</kbd>
          </button>
        </div>
        <div className="flex items-center gap-1.5 sm:gap-2.5 flex-shrink-0">
          <QuickNewMenu />
          <div className="relative hidden sm:block">
            <button type="button" onClick={() => setThemeMenuOpen((open) => !open)} className="flex items-center gap-1.5 px-3 py-1.5 rounded-full border text-xs font-semibold bg-white text-[var(--text)]" style={{ borderColor: "var(--border)" }} title="Switch Visual Theme (Aurora Indigo / Ocean Teal / Graphite Amber)">
              <span className="w-2.5 h-2.5 rounded-full" style={{ background: PALETTES.find((item) => item.id === palette)?.accent }} />
              <span className="capitalize">{palette}</span>
              <ChevronDown size={11} />
            </button>
            {themeMenuOpen && (
              <>
                <div className="fixed inset-0 z-30" onClick={() => setThemeMenuOpen(false)} />
                <div className="absolute right-0 mt-2 w-56 rounded-xl border bg-[var(--surface)] shadow-xl z-40 p-1.5" style={{ borderColor: "var(--border)" }}>
                  <div className="px-3 py-1.5 text-[11px] font-bold text-[var(--text-2)] uppercase tracking-wider">Appearance</div>
                  <div className="grid grid-cols-2 gap-1 px-1 pb-1">
                    {(["light", "dark"] as const).map((item) => (
                      <button key={item} type="button" onClick={() => setMode(item)} className={clsx("px-2 py-1.5 rounded-lg text-xs font-semibold capitalize", mode === item ? "bg-[var(--brand-fill)] text-white" : "text-[var(--text)] hover:bg-[var(--surface-2)]")}>{item}</button>
                    ))}
                  </div>
                  <div className="px-3 py-1.5 text-[11px] font-bold text-[var(--text-2)] uppercase tracking-wider">Palette</div>
                  {PALETTES.map((item) => (
                    <button key={item.id} type="button" onClick={() => { setPalette(item.id); setThemeMenuOpen(false); }} className="w-full flex items-center justify-between px-3 py-2 rounded-lg text-xs text-left">
                      <span className="flex items-center gap-2"><span className="w-3 h-3 rounded-full" style={{ background: item.accent }} />{item.label}</span>
                      {palette === item.id && <Check size={14} className="text-[var(--brand)]" />}
                    </button>
                  ))}
                </div>
              </>
            )}
          </div>
          <button type="button" className="relative p-1.5 rounded-full text-[var(--text-2)]" aria-label="Notifications" onClick={() => setNotesOpen(true)}>
            <Bell size={18} />
            {notifications.some((item) => !item.isRead) && <span className="absolute -top-0.5 -right-0.5 w-2 h-2 rounded-full bg-[var(--danger,#dc2626)]" aria-label="Unread notifications" />}
          </button>
          <div className="relative hidden sm:block">
            <button type="button" onClick={() => setHelpOpen((open) => !open)} className="p-1.5 rounded-full text-[var(--text-2)]" title="System Documentation & Help" aria-label="Help">
              <HelpCircle size={19} />
            </button>
            {helpOpen && (
              <>
                <div className="fixed inset-0 z-30" onClick={() => setHelpOpen(false)} />
                <div className="absolute right-0 mt-2 w-64 rounded-xl border bg-[var(--surface)] shadow-xl z-40 p-3 text-xs space-y-2" style={{ borderColor: "var(--border)" }}>
                  <div className="font-bold text-[var(--text)]">BOSS — Business Operating Solution System v1.0</div>
                  <div className="text-[var(--text-2)]">Business operating system for manufacturing & trading MSMEs.</div>
                  <div className="pt-2 border-t text-[11px] text-[var(--text-3)]" style={{ borderColor: "var(--border)" }}>Tenant profile is not loaded.</div>
                </div>
              </>
            )}
          </div>
          <button type="button" onClick={() => setProfileOpen(true)} className="w-8 h-8 sm:w-9 sm:h-9 rounded-full text-white text-xs font-bold" style={{ background: "linear-gradient(135deg, var(--brand), var(--brand-secondary, #A855F7))" }} aria-label={customer.user?.displayName ?? "Not signed in"}>
            {(customer.user?.displayName ?? "?").slice(0, 1)}
          </button>
        </div>
      </header>
      <GlobalSearchModal open={searchOpen} onClose={() => setSearchOpen(false)} />
      <Modal open={notesOpen} title="Notifications" onClose={() => setNotesOpen(false)}>
        {notificationsLoading && <p className="text-sm text-[var(--text-2)]">Loading notifications...</p>}
        {notificationsError && <p role="alert" className="text-sm text-red-700">{notificationsError}</p>}
        {!notificationsLoading && !notificationsError && notifications.length === 0 && (
          <p className="text-sm text-[var(--text-2)]">No notifications.</p>
        )}
        {notifications.length > 0 && (
          <ul className="divide-y divide-[var(--border)]">
            {notifications.map((notification) => (
              <li key={notification.id}>
                <button
                  type="button"
                  onClick={() => void openNotification(notification)}
                  className={clsx("w-full text-left py-3 rounded-lg px-2 hover:bg-[var(--surface-2)]", !notification.isRead && "bg-[var(--surface-2)]")}
                >
                  <span className="block text-sm font-semibold text-[var(--text)]">{notification.title}</span>
                  {notification.body && <span className="block mt-1 text-xs text-[var(--text-2)]">{notification.body}</span>}
                  <span className="block mt-1 text-[10px] text-[var(--text-3)]">{new Date(notification.createdAt).toLocaleString()}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </Modal>
      <Modal open={profileOpen} title="My Profile" onClose={() => setProfileOpen(false)}>
        <p className="text-sm font-semibold text-[var(--text)]">{customer.user?.displayName || customer.user?.email || "Not signed in"}</p>
        <p className="text-xs text-[var(--text-2)] mt-1">{customer.user?.roles.map((role) => role.role).join(", ") || "Role and name come from the customer session."}</p>
        {customer.user && <button type="button" className="mt-3 text-xs font-semibold underline" onClick={() => { signOut(); window.location.assign("/login"); }}>Sign out</button>}
        <button type="button" onClick={() => { setProfileOpen(false); navigate("/admin"); }} className="mt-4 w-full flex items-center justify-center gap-2 py-2 rounded-xl border text-xs font-semibold" style={{ borderColor: "var(--border)" }}>
          <Settings size={14} /> Open Admin & System Settings
        </button>
      </Modal>
    </>
  );
}
