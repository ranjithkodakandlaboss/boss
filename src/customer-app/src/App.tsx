import { useEffect, type ReactNode } from "react";
import { Navigate, Route, Routes, useLocation, useNavigate, type Location } from "react-router-dom";
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
import IncomingInspectionPage from "./procurement/proto/IncomingInspection";
import SupplierReturnPage from "./procurement/proto/SupplierReturn";

function Background({ pathname }: { pathname: string }) {
  const screen = findScreen(pathname.split("?")[0]);
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
      <div role="dialog" aria-modal="true" className="relative flex h-full w-full flex-col bg-[var(--bg)] shadow-2xl md:w-[min(860px,94vw)]" style={{ borderLeft: "1px solid var(--border)" }}>
        <header className="flex h-12 items-center justify-between px-4 border-b" style={{ borderColor: "var(--border)", background: "var(--surface)" }}>
          <span className="text-xs font-semibold uppercase tracking-wide text-[var(--text-3)]">Record</span>
          <button type="button" onClick={() => navigate(parent)} className="inline-flex h-8 items-center gap-1 rounded-lg px-2 text-sm font-semibold">
            <X size={16} /> Close
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-auto">{children}</div>
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

function recordBackground(location: Location) {
  return (location.state as { background?: Location } | null)?.background;
}

function SalesRecordModal({ children }: { children: ReactNode }) {
  const location = useLocation();
  const navigate = useNavigate();
  const background = recordBackground(location);
  const close = () => navigate(background ? `${background.pathname}${background.search}` : "/sales");
  return (
    <div className="fixed inset-0 z-[70] bg-black/40 p-3 md:p-6">
      <button type="button" aria-label="Close" className="absolute inset-0" onClick={close} />
      <div role="dialog" aria-modal="true" className="relative mx-auto flex h-full w-full max-w-6xl flex-col overflow-hidden rounded-xl bg-[var(--bg)] shadow-2xl">
        <div className="min-h-0 flex-1 overflow-auto">{children}</div>
      </div>
    </div>
  );
}

function SalesFrame() {
  const location = useLocation();
  const background = recordBackground(location);
  const pages = [
    <Route key="home" index element={<SalesHome />} />,
    <Route key="customer-new" path="customers/new" element={<CustomerCreate />} />,
    <Route key="customer-edit" path="customers/:id/edit" element={<CustomerEdit />} />,
    <Route key="customer" path="customers/:id" element={<CustomerDetailPage />} />,
    <Route key="rfq-new" path="rfq/new" element={<RfqFormPage />} />,
    <Route key="rfq-edit" path="rfq/:id/edit" element={<RfqEditPage />} />,
    <Route key="quote-from-rfq" path="rfq/:rfqId/quotation/new" element={<QuotationFormPage />} />,
    <Route key="rfq" path="rfq/:id" element={<RfqDetailPage />} />,
    <Route key="quote-new" path="quotation/new" element={<QuotationFormPage />} />,
    <Route key="quote" path="quotation/:id" element={<QuotationDetailPage />} />,
    <Route key="po-new" path="po/new" element={<PoCreatePage />} />,
    <Route key="po-amend" path="po/:id/amend" element={<PoAmendPage />} />,
    <Route key="po" path="po/:id" element={<PoDetailPage />} />,
  ];
  return (
    <SalesProvider>
      <Routes location={background || location}>{pages}</Routes>
      {background && (
        <Routes>
          <Route path="customers/new" element={<SalesRecordModal><CustomerCreate /></SalesRecordModal>} />
          <Route path="customers/:id/edit" element={<SalesRecordModal><CustomerEdit /></SalesRecordModal>} />
          <Route path="customers/:id" element={<SalesRecordModal><CustomerDetailPage /></SalesRecordModal>} />
          <Route path="rfq/new" element={<SalesRecordModal><RfqFormPage /></SalesRecordModal>} />
          <Route path="rfq/:id/edit" element={<SalesRecordModal><RfqEditPage /></SalesRecordModal>} />
          <Route path="rfq/:rfqId/quotation/new" element={<SalesRecordModal><QuotationFormPage /></SalesRecordModal>} />
          <Route path="rfq/:id" element={<SalesRecordModal><RfqDetailPage /></SalesRecordModal>} />
          <Route path="quotation/new" element={<SalesRecordModal><QuotationFormPage /></SalesRecordModal>} />
          <Route path="quotation/:id" element={<SalesRecordModal><QuotationDetailPage /></SalesRecordModal>} />
          <Route path="po/new" element={<SalesRecordModal><PoCreatePage /></SalesRecordModal>} />
          <Route path="po/:id/amend" element={<SalesRecordModal><PoAmendPage /></SalesRecordModal>} />
          <Route path="po/:id" element={<SalesRecordModal><PoDetailPage /></SalesRecordModal>} />
        </Routes>
      )}
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
          <Route path="suppliers/:id" element={<SupplierRecord />} />
          <Route path="procurement" element={<ProcurementHome />} />
          <Route path="procurement/pr/new" element={<PrCreatePage />} />
          <Route path="procurement/pr/:id/edit" element={<PrCreatePage />} />
          <Route path="procurement/pr/:id" element={<PrRecordPage />} />
          <Route path="procurement/supplier-rfq/new" element={<SupplierRfqPage />} />
          <Route path="procurement/quote/:id" element={<SupplierQuotePage />} />
          <Route path="procurement/spo/:id/amend" element={<SupplierPoAmendPage />} />
          <Route path="procurement/spo/:id" element={<SupplierPoPage />} />
          <Route path="procurement/grn/new" element={<GrnCreatePage />} />
          <Route path="procurement/grn/:id" element={<GrnRecordPage />} />
          <Route path="quality/inspection/:id" element={<IncomingInspectionPage />} />
          <Route path="procurement/supplier-return" element={<SupplierReturnPage />} />
        </Route>
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
