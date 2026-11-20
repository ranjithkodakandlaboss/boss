import { useEffect, type ReactNode } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate } from "react-router-dom";
import { X } from "lucide-react";
import AppShell from "./components/layout/AppShell";
import { sheetParent } from "./components/layout/sheetRoutes";
import { CustomerProvider, useCustomer } from "./auth/CustomerState";
import { ShellProvider } from "./shell/ShellContext";
import { findScreen } from "./screens/catalog";
import ActivatePage from "./screens/ActivatePage";
import LoginPage from "./screens/LoginPage";
import OnboardingPage from "./screens/OnboardingPage";
import ScreenPage from "./screens/ScreenPage";
import { SalesProvider } from "./sales/proto/runtime";
import SalesHome from "./sales/proto/Sales";
import CustomerCreate from "./sales/proto/CustomerCreate";
import CustomerEdit from "./sales/proto/CustomerEdit";
import CustomerDetailPage from "./sales/proto/CustomerDetail";
import RfqFormPage from "./sales/proto/RFQCreate";
import RfqEditPage from "./sales/proto/RFQEdit";
import RfqDetailPage from "./sales/proto/RFQDetail";
import QuotationFormPage from "./sales/proto/QuotationCreate";
import QuotationDetailPage from "./sales/proto/QuotationDetail";
import PoCreatePage from "./sales/proto/CustomerPOCreate";
import PoDetailPage from "./sales/proto/PODetail";
import PoAmendPage from "./sales/proto/POAmend";
import { ProcurementProvider } from "./procurement/proto/runtime";
import ProcurementHome from "./procurement/proto/Procurement";
import SupplierDirectory from "./procurement/proto/Suppliers";
import SupplierRecord from "./procurement/proto/SupplierDetail";
import PrCreatePage from "./procurement/proto/PRCreate";
import PrRecordPage from "./procurement/proto/PRDetail";
import SupplierRfqPage from "./procurement/proto/SupplierRFQCreate";
import SupplierQuotePage from "./procurement/proto/SupplierQuotationDetail";
import SupplierPoPage from "./procurement/proto/SupplierPODetail";
import SupplierPoAmendPage from "./procurement/proto/SupplierPOAmend";
import GrnCreatePage from "./procurement/proto/GRNCreate";
import GrnRecordPage from "./procurement/proto/GRNDetail";
import SupplierReturnPage from "./procurement/proto/SupplierReturn";
import JobsPage from "./production/JobsPage";
import QualityPage from "./quality/QualityPage";
import InspectionDetailPage from "./quality/InspectionDetailPage";
import NcrDetailPage from "./quality/NcrDetailPage";
import ChecklistTemplatesPage from "./quality/ChecklistTemplatesPage";
import AssetsPage from "./maintenance/AssetsPage";
import { AssetDetailPage, MaintenanceEventDetailPage } from "./maintenance/AssetDetailPage";
import JobWorkspacePage from "./production/JobWorkspacePage";
import StageUpdatePage from "./production/StageUpdatePage";
import BomPage from "./inventory/BomPage";
import ItemDetail from "./inventory/ItemDetail";
import LotDetail from "./inventory/LotDetail";
import DispatchPage from "./dispatch/DispatchPage";
import DispatchDetailPage from "./dispatch/DispatchDetailPage";
import DocumentsPage from "./documents/DocumentsPage";
import DocumentDetailPage from "./documents/DocumentDetailPage";

function Background({ pathname }: { pathname: string }) {
  const basePath = pathname.split("?")[0];
  const search = pathname.includes("?") ? pathname.slice(pathname.indexOf("?")) : "";
  if (basePath === "/sales") {
    return <SalesHome backgroundSearch={search} />;
  }
  if (basePath === "/procurement" || basePath.startsWith("/procurement")) {
    return <ProcurementHome />;
  }
  const screen = findScreen(basePath);
  if (!screen) return null;
  return <ScreenPage screen={screen} />;
}

function SheetFrame({ parent, children }: { parent: string; children: ReactNode }) {
  const navigate = useNavigate();
  useEffect(() => {
    const onKey = (event: KeyboardEvent) => {
      if (event.key === "Escape") navigate(parent);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [navigate, parent]);
  return (
    <div className="fixed inset-0 z-[60] flex justify-end bg-black/40">
      <button type="button" className="absolute inset-0" aria-label="Close" onClick={() => navigate(parent)} />
      <div role="dialog" aria-modal="true" className="relative flex h-full min-w-0 w-full flex-col overflow-hidden bg-[var(--bg)] shadow-2xl md:w-[min(860px,94vw)]" style={{ borderLeft: "1px solid var(--border)" }}>
        <header className="flex h-12 items-center justify-between px-4 border-b" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          <span className="text-xs font-semibold uppercase tracking-wide text-[var(--text-3)]">Record</span>
          <button type="button" onClick={() => navigate(parent)} className="inline-flex h-8 items-center gap-1 rounded-lg px-2 text-sm font-semibold">
            <X size={16} /> Close
          </button>
        </header>
        <div className="min-h-0 min-w-0 flex-1 overflow-auto">{children}</div>
      </div>
    </div>
  );
}

function SheetLayout({ children }: { children: ReactNode }) {
  const location = useLocation();
  const parent = sheetParent(location.pathname);
  if (!parent) return children;
  return (
    <>
      <Background pathname={parent} />
      <SheetFrame parent={parent}>{children}</SheetFrame>
    </>
  );
}

function RoutedScreen() {
  const location = useLocation();
  const screen = findScreen(location.pathname);
  if (!screen) return <Navigate to="/" replace />;
  return (
    <SheetLayout>
      <ScreenPage screen={screen} />
    </SheetLayout>
  );
}

function SalesFrame() {
  return (
    <SalesProvider>
      <Routes>
        <Route index element={<SalesHome />} />
        <Route path="customers/new" element={<SheetLayout><CustomerCreate /></SheetLayout>} />
        <Route path="customers/:id/edit" element={<SheetLayout><CustomerEdit /></SheetLayout>} />
        <Route path="customers/:id" element={<SheetLayout><CustomerDetailPage /></SheetLayout>} />
        <Route path="rfq/new" element={<SheetLayout><RfqFormPage /></SheetLayout>} />
        <Route path="rfq/:id/edit" element={<SheetLayout><RfqEditPage /></SheetLayout>} />
        <Route path="rfq/:rfqId/quotation/new" element={<SheetLayout><QuotationFormPage /></SheetLayout>} />
        <Route path="rfq/:id" element={<SheetLayout><RfqDetailPage /></SheetLayout>} />
        <Route path="quotation/new" element={<SheetLayout><QuotationFormPage /></SheetLayout>} />
        <Route path="quotation/:id" element={<SheetLayout><QuotationDetailPage /></SheetLayout>} />
        <Route path="po/new" element={<SheetLayout><PoCreatePage /></SheetLayout>} />
        <Route path="po/:id/amend" element={<SheetLayout><PoAmendPage /></SheetLayout>} />
        <Route path="po/:id" element={<SheetLayout><PoDetailPage /></SheetLayout>} />
      </Routes>
    </SalesProvider>
  );
}

function Guard() {
  const location = useLocation();
  const customer = useCustomer();
  if (!customer.ready) return <p role="status">Loading session</p>;
  const open = location.pathname === "/login" || location.pathname === "/activate";
  if (!customer.user && !open) return <Navigate to="/login" replace />;
  if (customer.user && !customer.onboarding?.completed && location.pathname !== "/onboarding" && !open) {
    return <Navigate to="/onboarding" replace />;
  }
  return (
    <Routes>
      <Route path="login" element={<LoginPage />} />
      <Route path="activate" element={<ActivatePage />} />
      <Route path="onboarding" element={<OnboardingPage />} />
      <Route element={<AppShell />}>
        <Route path="sales/*" element={<SalesFrame />} />
        <Route element={<ProcurementProvider />}>
          <Route path="suppliers" element={<SupplierDirectory />} />
          <Route path="suppliers/:id" element={<SheetLayout><SupplierRecord /></SheetLayout>} />
          <Route path="procurement" element={<ProcurementHome />} />
          <Route path="procurement/pr/new" element={<PrCreatePage />} />
          <Route path="procurement/pr/:id/edit" element={<PrCreatePage />} />
          <Route path="procurement/pr/:id" element={<SheetLayout><PrRecordPage /></SheetLayout>} />
          <Route path="procurement/supplier-rfq/new" element={<SheetLayout><SupplierRfqPage /></SheetLayout>} />
          <Route path="procurement/quote/:id" element={<SheetLayout><SupplierQuotePage /></SheetLayout>} />
          <Route path="procurement/spo/:id/amend" element={<SupplierPoAmendPage />} />
          <Route path="procurement/spo/:id" element={<SheetLayout><SupplierPoPage /></SheetLayout>} />
          <Route path="procurement/grn/new" element={<GrnCreatePage />} />
          <Route path="procurement/grn/:id" element={<SheetLayout><GrnRecordPage /></SheetLayout>} />
          <Route path="procurement/supplier-return" element={<SupplierReturnPage />} />
        </Route>
        <Route path="quality" element={<QualityPage />} />
        <Route path="quality/inspection/:id" element={<SheetLayout><InspectionDetailPage /></SheetLayout>} />
        <Route path="quality/ncr/:id" element={<SheetLayout><NcrDetailPage /></SheetLayout>} />
        <Route path="quality/checklist-templates" element={<ChecklistTemplatesPage />} />
        <Route path="assets" element={<AssetsPage />} />
        <Route path="assets/me/:id" element={<SheetLayout><MaintenanceEventDetailPage /></SheetLayout>} />
        <Route path="assets/:id" element={<SheetLayout><AssetDetailPage /></SheetLayout>} />
        <Route path="dispatch" element={<DispatchPage />} />
        <Route path="dispatch/:id" element={<DispatchDetailPage />} />
        <Route path="documents" element={<DocumentsPage />} />
        <Route path="documents/:id" element={<DocumentDetailPage />} />
        <Route path="bom" element={<BomPage />} />
        <Route path="inventory/item/:id" element={<SheetLayout><ItemDetail /></SheetLayout>} />
        <Route path="inventory/lot/:id" element={<SheetLayout><LotDetail /></SheetLayout>} />
        <Route path="jobs" element={<JobsPage />} />
        <Route path="jobs/:id/stage/:stageId" element={<StageUpdatePage />} />
        <Route path="jobs/:id" element={<JobWorkspacePage />} />
        <Route path="sales/customers" element={<Navigate to="/sales?tab=customers" replace />} />
        <Route path="settings" element={<Navigate to="/admin" replace />} />
        <Route path="admin/audit" element={<Navigate to="/audit" replace />} />
        <Route path="*" element={<RoutedScreen />} />
      </Route>
    </Routes>
  );
}

export default function App() {
  return (
    <ShellProvider>
      <CustomerProvider>
        <Guard />
      </CustomerProvider>
    </ShellProvider>
  );
}
