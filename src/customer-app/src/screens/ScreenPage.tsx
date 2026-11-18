import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { Plus } from "lucide-react";
import { loadList, SAVE_BLOCKED } from "../api/records";
import type { ScreenDef } from "./catalog";
import Tabs from "../components/shared/Tabs";
import DataTable, { StateBody } from "../components/shared/DataTable";
import FilterBar from "../components/shared/FilterBar";
import { SelectField, TextField } from "../components/shared/Fields";
import Button from "../components/shared/Button";
import ConfirmDialog from "../components/shared/ConfirmDialog";
import { getStatusLabel } from "../components/shared/StatusChip";
import CompanyPanel from "./CompanyPanel";
import HomePage from "./HomePage";
import OnboardingPage from "./OnboardingPage";
import CertificateDetail from "../inventory/CertificateDetail";
import CycleCountPage from "../inventory/CycleCountPage";
import InventoryHome from "../inventory/InventoryHome";
import InventoryTransfers from "../inventory/InventoryTransfers";
import ItemDetail from "../inventory/ItemDetail";
import ItemMaster from "../inventory/ItemMaster";
import LotDetail from "../inventory/LotDetail";
import MaterialReturns from "../inventory/MaterialReturns";
import RemnantsPage from "../inventory/RemnantsPage";
import SiteNeed from "../inventory/SiteNeed";
import StockTransferPage from "../inventory/StockTransferPage";

const SALES_TABS: Record<string, { title: string; columns: string[]; placeholder: string; statuses?: string[]; action?: { label: string; to: string } }> = {
  pos: {
    title: "Customer POs",
    columns: ["PO Number", "Customer", "Date", "Value", "Lines", "Terms", "Status"],
    placeholder: "Search Customer PO number or customer name",
    statuses: ["pending", "active", "partial", "complete", "cancelled"],
    action: { label: "Add Customer PO", to: "/sales/po/new" },
  },
  rfqs: {
    title: "Customer RFQs",
    columns: ["RFQ Number", "Customer", "Received", "Required By", "Lines", "Channel", "Quotation", "Status", "Owner"],
    placeholder: "Search RFQ number or customer name",
    statuses: ["open", "quoted", "converted", "lost", "cancelled"],
    action: { label: "New RFQ", to: "/sales/rfq/new" },
  },
  quotations: {
    title: "Quotations",
    columns: ["Quotation #", "Customer", "RFQ Ref", "Quoted Date", "Valid Until", "Total Value", "Status"],
    placeholder: "Search quotation number, RFQ or customer",
  },
  customers: {
    title: "Customer Directory",
    columns: ["Customer", "GSTIN / State", "Contact", "Terms", "Customer POs", "RFQs", "Rating", "Status"],
    placeholder: "Search customers",
    statuses: ["active", "inactive"],
    action: { label: "Add New Customer", to: "/sales/customers/new" },
  },
};

const STOCK_LINKS = [
  { to: "/inventory", label: "Stock on hand" },
  { to: "/products", label: "Item master" },
  { to: "/inventory/transfer", label: "Move stock" },
  { to: "/inventory/transfers", label: "Transfers" },
  { to: "/inventory/returns", label: "Returns" },
  { to: "/allocations", label: "Site need" },
];

export default function ScreenPage({ screen }: { screen: ScreenDef }) {
  if (screen.kind === "home") return <HomePage />;
  if (screen.kind === "onboarding") return <OnboardingPage />;
  if (screen.path === "inventory") return <InventoryHome />;
  if (screen.path === "inventory/transfers" || screen.path === "transfers") return <InventoryTransfers />;
  if (screen.path === "inventory/transfer") return <StockTransferPage />;
  if (screen.path === "inventory/returns") return <MaterialReturns />;
  if (screen.path === "inventory/cycle") return <CycleCountPage />;
  if (screen.path === "inventory/remnants") return <RemnantsPage />;
  if (screen.path === "inventory/item/:id") return <ItemDetail />;
  if (screen.path === "inventory/lot/:id") return <LotDetail />;
  if (screen.path === "inventory/cert/:id") return <CertificateDetail />;
  if (screen.path === "products") return <ItemMaster />;
  if (screen.path === "allocations") return <SiteNeed />;
  return <Workspace screen={screen} />;
}

function Workspace({ screen }: { screen: ScreenDef }) {
  const [params, setParams] = useSearchParams();
  const navigate = useNavigate();
  const [blocked, setBlocked] = useState(false);
  const [formError, setFormError] = useState("");
  const [search, setSearch] = useState("");
  const tab = screen.tabs?.some((item) => item.id === params.get("tab")) ? params.get("tab")! : screen.tabs?.[0]?.id ?? "";
  const state = loadList(params.get("state"));
  const salesTab = screen.path === "sales" ? SALES_TABS[tab] : undefined;
  const title = salesTab?.title ?? screen.title;
  const columns = salesTab?.columns ?? screen.columns;
  const statuses = salesTab ? salesTab.statuses : screen.statuses;
  const searchPlaceholder = salesTab?.placeholder ?? screen.searchPlaceholder;
  const action = salesTab ? salesTab.action : screen.action;
  const showStock = screen.path === "inventory" || screen.path.startsWith("inventory/") || screen.path === "products" || screen.path === "allocations";

  return (
    <div className="flex flex-col min-h-[calc(100vh-var(--topbar-h))]">
      {showStock && (
        <div className="flex gap-1 px-3 overflow-x-auto border-b" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          {STOCK_LINKS.map((item) => (
            <Link key={item.to} to={item.to} className="px-3 py-2.5 text-sm font-medium whitespace-nowrap" style={{ color: item.to === `/${screen.path}` ? "var(--brand)" : "var(--text-2)" }}>
              {item.label}
            </Link>
          ))}
        </div>
      )}
      <div className="flex items-start justify-between gap-3 px-6 py-4">
        <div>
          <h1 className="text-lg font-bold text-[var(--text)]">{title}</h1>
          {screen.path === "products" && <p className="text-xs text-[var(--text-2)]">The canonical catalogue of materials and grades. Physical stock belongs in Stock on Hand.</p>}
          {screen.path === "admin" && <p className="text-sm text-[var(--text-2)] mt-0.5">Company, people, and the audit trail. Edit is on the open tab.</p>}
        </div>
        {action && (
          action.to ? (
            <Button onClick={() => navigate(action.to!)}><Plus size={15} /> {action.label}</Button>
          ) : (
            <Button onClick={() => setBlocked(true)}><Plus size={15} /> {action.label}</Button>
          )
        )}
      </div>
      {screen.tabs && <Tabs tabs={screen.tabs} active={tab} onChange={(id) => setParams({ tab: id }, { replace: true })} />}
      {screen.kind === "list" && (
        <FilterBar search={search} onSearch={setSearch} placeholder={searchPlaceholder ?? `Search ${title}`}>
          {statuses && (
            <select className="eoss-input min-h-[36px]" aria-label={`${title} status`} defaultValue="all">
              <option value="all">All statuses</option>
              {statuses.map((status) => <option key={status} value={status}>{getStatusLabel(status)}</option>)}
            </select>
          )}
        </FilterBar>
      )}
      {screen.sections && screen.kind !== "list" && (
        <div className="px-6 pb-4 grid gap-3 md:grid-cols-2">
          {screen.sections.map((section) => (
            <section key={section} className="rounded-xl border p-5" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
              <h2 className="text-sm font-bold text-[var(--text)]">{section}</h2>
            </section>
          ))}
        </div>
      )}
      {screen.kind === "form" && (
        <form className="px-6 pb-8 max-w-3xl space-y-4" onSubmit={(event) => { event.preventDefault(); setFormError(SAVE_BLOCKED); }}>
          <TextField label="Reference" name="reference" />
          <SelectField label="Options" name="options" defaultValue="">
            <option value="">No options loaded</option>
          </SelectField>
          {formError && <p role="alert" className="text-sm text-[var(--error)]">{formError}</p>}
          <Button type="submit">Save</Button>
        </form>
      )}
      {(screen.kind === "list" || screen.kind === "reports") && columns && (
        <DataTable columns={columns} state={state} emptyTitle="No records" />
      )}
      {screen.kind === "reports" && (
        <div className="grid gap-3 p-6 md:grid-cols-2">
          {screen.sections?.map((section) => (
            <button key={section} type="button" className="eoss-card p-4 text-left" onClick={() => setBlocked(true)}>
              <h2 className="font-bold text-[var(--text)]">{section}</h2>
              <p className="text-xs text-[var(--text-2)] mt-1">Figures are unavailable until tenant records load.</p>
            </button>
          ))}
        </div>
      )}
      {screen.path === "admin" && tab === "company" && <CompanyPanel />}
      {(screen.kind === "record" || screen.kind === "admin") && !screen.columns && !(screen.path === "admin" && tab === "company") && (
        <StateBody state={state} title="No records" />
      )}
      {screen.kind === "record" && screen.columns && <DataTable columns={screen.columns} state={state} emptyTitle="No lines" />}
      <ConfirmDialog open={blocked} title={action?.label ?? "Unavailable"} body={SAVE_BLOCKED} confirmLabel="Close" onConfirm={() => setBlocked(false)} onCancel={() => setBlocked(false)} />
    </div>
  );
}
