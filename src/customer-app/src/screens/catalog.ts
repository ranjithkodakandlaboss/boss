export type ScreenKind = "home" | "list" | "record" | "form" | "onboarding" | "reports" | "admin";

export type ScreenDef = {
  id: string;
  path: string;
  prototype: string;
  title: string;
  kind: ScreenKind;
  tabs?: { id: string; label: string }[];
  columns?: string[];
  searchPlaceholder?: string;
  statuses?: string[];
  action?: { label: string; to?: string };
  sections?: string[];
  missing: string[];
};

const RECORD_GAP = [
  "Record body is not loaded from the customer API.",
  "Linked-document, history, and workflow actions from the prototype are not executable.",
];

const FORM_GAP = [
  "Submit does not persist a record.",
  "Dropdown options are not loaded from /api/v1/config/options.",
];

export const screens: ScreenDef[] = [
  { id: "UX-01", path: "", prototype: "src/workspaces/home/Home.tsx", title: "Home", kind: "home", missing: ["KPI values, attention rows, inbox preview, weekly activities, and Ask BOSS answers require tenant data.", "Greeting name is omitted because the prototype used a demo user."] },
  { id: "UX-inbox", path: "inbox", prototype: "src/workspaces/inbox/Inbox.tsx", title: "Unified Business Inbox", kind: "list", columns: ["From", "Subject", "Linked record", "Received", "Status"], searchPlaceholder: "Search inbox", missing: ["Message thread, archive, and link-to-record actions."] },
  { id: "UX-inbox-message", path: "inbox/:messageId", prototype: "src/workspaces/inbox/Inbox.tsx", title: "Inbox message", kind: "record", sections: ["Message", "Linked BOSS record"], missing: RECORD_GAP },
  { id: "UX-02b", path: "sales", prototype: "src/workspaces/sales/Sales.tsx", title: "Sales & Orders", kind: "list", tabs: [{ id: "pos", label: "Customer POs" }, { id: "rfqs", label: "RFQs" }, { id: "quotations", label: "Quotations" }, { id: "customers", label: "Customer Directory" }], columns: ["PO Number", "Customer", "Date", "Value", "Lines", "Terms", "Status"], searchPlaceholder: "Search Customer PO number or customer name", statuses: ["pending", "active", "partial", "complete", "cancelled"], action: { label: "Add Customer PO", to: "/sales/po/new" }, missing: ["Master-detail split, row actions, and quotation comparison are not populated.", "Counts in tab labels stay at zero because no records are loaded."] },
  { id: "UX-rfq-new", path: "sales/rfq/new", prototype: "src/workspaces/sales/RFQCreate.tsx", title: "New RFQ", kind: "form", sections: ["Header", "Line Items", "Technical", "Documents", "Review"], missing: FORM_GAP },
  { id: "UX-rfq", path: "sales/rfq/:id", prototype: "src/workspaces/sales/RFQDetail.tsx", title: "Customer RFQ", kind: "record", tabs: [{ id: "overview", label: "Overview" }, { id: "lines", label: "Line Items" }, { id: "technical", label: "Technical & Quality" }, { id: "commercial", label: "Commercial & Delivery" }, { id: "documents", label: "Documents" }, { id: "clarifications", label: "Clarifications" }, { id: "history", label: "Revision History" }], missing: RECORD_GAP },
  { id: "UX-rfq-edit", path: "sales/rfq/:id/edit", prototype: "src/workspaces/sales/RFQEdit.tsx", title: "Edit RFQ", kind: "form", sections: ["Header", "Line Items"], missing: FORM_GAP },
  { id: "UX-quote-from-rfq", path: "sales/rfq/:rfqId/quotation/new", prototype: "src/workspaces/sales/QuotationCreate.tsx", title: "New Quotation", kind: "form", sections: ["Source RFQ", "Lines", "Commercial terms"], missing: FORM_GAP },
  { id: "UX-quote-new", path: "sales/quotation/new", prototype: "src/workspaces/sales/QuotationCreate.tsx", title: "New Quotation", kind: "form", sections: ["Customer", "Lines", "Commercial terms"], missing: FORM_GAP },
  { id: "UX-quote", path: "sales/quotation/:id", prototype: "src/workspaces/sales/QuotationDetail.tsx", title: "Quotation", kind: "record", columns: ["#", "Drawing No.", "Rev", "Description", "Qty", "UOM", "Unit Price", "Line Value"], missing: RECORD_GAP },
  { id: "UX-po", path: "sales/po/:id", prototype: "src/workspaces/sales/PODetail.tsx", title: "Customer PO", kind: "record", tabs: [{ id: "overview", label: "Overview" }, { id: "lines", label: "Line Items" }, { id: "documents", label: "Documents" }, { id: "history", label: "History" }], sections: ["Customer RFQ", "Customer Quotation", "Customer PO", "Material Assessment", "Purchase Requisition", "Supplier PO", "GRN & Inspection", "Production Job", "Dispatch", "Customer Invoice", "Customer Payment"], missing: ["Order Journey is not derived because linked records are not loaded.", ...RECORD_GAP] },
  { id: "UX-po-new", path: "sales/po/new", prototype: "src/workspaces/sales/CustomerPOCreate.tsx", title: "Add Customer PO", kind: "form", sections: ["Accepted Quotation Source", "Direct Order Header", "Order Line Items"], missing: FORM_GAP },
  { id: "UX-amend", path: "sales/po/:id/amend", prototype: "src/workspaces/sales/POAmend.tsx", title: "Create Revised Customer PO", kind: "form", sections: ["Revision"], missing: ["The prototype blocks revision after execution starts. That rule cannot run without the Customer PO.", ...FORM_GAP] },
  { id: "UX-02a", path: "sales/customers/:id", prototype: "src/workspaces/sales/CustomerDetail.tsx", title: "Customer", kind: "record", tabs: [{ id: "overview", label: "Overview" }, { id: "orders", label: "Orders" }, { id: "rfqs", label: "RFQs" }, { id: "contacts", label: "Contacts" }, { id: "documents", label: "Documents" }, { id: "history", label: "History" }], missing: RECORD_GAP },
  { id: "UX-customer-edit", path: "sales/customers/:id/edit", prototype: "src/workspaces/sales/CustomerEdit.tsx", title: "Edit Customer", kind: "form", sections: ["Business identity and contact", "Commercial terms"], missing: FORM_GAP },
  { id: "UX-customer-new", path: "sales/customers/new", prototype: "src/workspaces/sales/CustomerCreate.tsx", title: "Add New Customer", kind: "form", sections: ["Business identity and contact", "Commercial terms"], missing: FORM_GAP },
  { id: "UX-03", path: "suppliers", prototype: "src/workspaces/suppliers/Suppliers.tsx", title: "Suppliers", kind: "list", columns: ["Trading Name", "City", "GSTIN", "Contact", "Phone", "Status"], searchPlaceholder: "Search suppliers", action: { label: "Add Supplier" }, missing: ["A single 0–5 supplier score is not shown. The approved contract defines on-time delivery, quality rejection, and average lead time, but not the weights for one combined score."] },
  { id: "UX-supplier", path: "suppliers/:id", prototype: "src/workspaces/suppliers/SupplierDetail.tsx", title: "Supplier", kind: "record", missing: RECORD_GAP },
  { id: "UX-04", path: "procurement", prototype: "src/workspaces/procurement/Procurement.tsx", title: "Procurement", kind: "list", tabs: [{ id: "pr", label: "Purchase Requisitions" }, { id: "quotes", label: "Supplier Quotations" }, { id: "spo", label: "Supplier POs" }, { id: "grn", label: "Receiving & Inventory" }, { id: "approvals", label: "Approvals" }], columns: ["Number", "Supplier", "Date", "Status"], searchPlaceholder: "Search PR number, requester or department", sections: ["PR", "Supplier Quotations", "Selected Quotation / Supplier PO", "Quality / GRN / Inventory"], action: { label: "New Purchase Requisition", to: "/procurement/pr/new" }, missing: ["A combined supplier score stays out of scope until its formula is approved."] },
  { id: "UX-pr-new", path: "procurement/pr/new", prototype: "src/workspaces/procurement/PRCreate.tsx", title: "New Purchase Requisition", kind: "form", sections: ["Requisition Header", "Material Lines", "Review & Submit"], columns: ["#", "Code", "Description", "Grade", "Qty", "UOM", "Est. Rate", "Est. Value"], missing: ["Estimated rates are not stored by the purchase requisition API."] },
  { id: "UX-pr-edit", path: "procurement/pr/:id/edit", prototype: "src/workspaces/procurement/PRCreate.tsx", title: "Edit Purchase Requisition", kind: "form", sections: ["Requisition Header", "Material Lines"], missing: ["Estimated rates are not stored by the purchase requisition API."] },
  { id: "UX-pr", path: "procurement/pr/:id", prototype: "src/workspaces/procurement/PRDetail.tsx", title: "Purchase Requisition", kind: "record", sections: ["Supplier Quotation Invitations", "Material Lines"], missing: ["The backend does not recommend a supplier. Selection stays a user decision."] },
  { id: "UX-supplier-rfq", path: "procurement/supplier-rfq/new", prototype: "src/workspaces/procurement/SupplierRFQCreate.tsx", title: "Supplier RFQ", kind: "form", sections: ["Request Scope & Delivery Details", "Supplier Quotation Details", "Quoted Line Items"], missing: ["The request email is sent when the supplier is invited. This form records the quotation the supplier returns."] },
  { id: "UX-supplier-quote", path: "procurement/quote/:id", prototype: "src/workspaces/procurement/SupplierQuotationDetail.tsx", title: "Supplier Quotation", kind: "record", columns: ["#", "Material", "Grade", "Qty", "UOM", "Unit Rate (₹)", "Tax %", "Freight"], missing: ["A supplier quotation PDF is not generated."] },
  { id: "UX-05", path: "procurement/spo/:id", prototype: "src/workspaces/procurement/SupplierPODetail.tsx", title: "Supplier PO", kind: "record", columns: ["Material", "Ordered", "Received", "Accepted", "Rejected", "UOM", "Rate", "Value"], missing: ["The supplier purchase order PDF is not generated."] },
  { id: "UX-spo-amend", path: "procurement/spo/:id/amend", prototype: "src/workspaces/procurement/SupplierPOAmend.tsx", title: "Amend Supplier PO", kind: "form", sections: ["Amendment"], missing: ["Evidence is limited to PDF, JPEG, PNG, MSG, and EML files of 5 MB or less."] },
  { id: "UX-grn-new", path: "procurement/grn/new", prototype: "src/workspaces/procurement/GRNCreate.tsx", title: "Record Goods Receipt Note (GRN)", kind: "form", sections: ["Ordered Items & Lot Allocation"], missing: ["Over-receipt is rejected because no receipt tolerance is configured."] },
  { id: "UX-05s", path: "procurement/grn/:id", prototype: "src/workspaces/procurement/GRNDetail.tsx", title: "GRN", kind: "record", columns: ["Material", "Grade", "Ordered", "Prev. Received", "This Receipt", "UOM", "Lots", "Lot #"], missing: ["The full quality workspace list is separate. Incoming inspection is started from this receipt."] },
  { id: "UX-05b", path: "procurement/supplier-return", prototype: "src/workspaces/procurement/SupplierReturn.tsx", title: "Supplier Return", kind: "form", sections: ["Return lines"], missing: ["A supplier return is posted against accepted stock. It is not created automatically from a rejected inspection."] },
  { id: "UX-06", path: "inventory", prototype: "src/workspaces/inventory/Inventory.tsx", title: "Stock on hand", kind: "list", columns: ["Code", "Material / Specification", "Location", "On Hand", "Reserved", "QC Hold", "In Transit", "Available", "Reorder Pt", "Status"], searchPlaceholder: "Search material or location", action: { label: "Create Purchase Requisition", to: "/procurement/pr/new" }, missing: ["Same-site store moves stay out of scope. Transfers must cross sites."] },
  { id: "UX-item", path: "inventory/item/:id", prototype: "src/workspaces/inventory/InventoryItemDetail.tsx", title: "Inventory item", kind: "record", missing: ["Incoming inspection is started from the goods receipt, not from this item."] },
  { id: "UX-07", path: "inventory/lot/:id", prototype: "src/workspaces/inventory/LotDetail.tsx", title: "Material lot", kind: "record", columns: ["Timestamp", "Movement", "Qty", "Balance", "Reference", "Note"], missing: ["Movement rows do not carry separate from and to store names."] },
  { id: "UX-cert", path: "inventory/cert/:id", prototype: "src/workspaces/inventory/CertificateDetail.tsx", title: "Certificate", kind: "record", missing: ["A separate certificate record with mechanical properties is not stored. The lot batch and receipt are the trace."] },
  { id: "UX-transfer", path: "inventory/transfer", prototype: "src/workspaces/inventory/StockTransfer.tsx", title: "Stock Transfer", kind: "form", sections: ["Source", "Destination", "Quantity"], missing: ["A transfer must move stock between different sites."] },
  { id: "UX-transfers", path: "inventory/transfers", prototype: "src/workspaces/inventory/InventoryTransfers.tsx", title: "Inventory transfers", kind: "list", columns: ["Transfer", "From", "To", "Status"], missing: ["A transfer must move stock between different sites."] },
  { id: "UX-returns", path: "inventory/returns", prototype: "src/workspaces/inventory/InventoryReturns.tsx", title: "Material returns", kind: "list", columns: ["Return", "Material", "Quantity", "Status"], missing: ["Returns post immediately to the source lot. They do not wait in a quarantine accept or reject queue."] },
  { id: "UX-cycle", path: "inventory/cycle", prototype: "src/workspaces/inventory/CycleCount.tsx", title: "Cycle Count", kind: "list", columns: ["Count", "Location", "Status"], missing: ["A counted increase needs an existing lot at that store."] },
  { id: "UX-remnants", path: "inventory/remnants", prototype: "src/workspaces/inventory/Remnants.tsx", title: "Remnants & Scrap", kind: "list", columns: ["Lot", "Material", "Quantity", "Status"], missing: ["Lots do not store a parent lot, so off-cuts are not a separate remnant identity."] },
  { id: "UX-08", path: "jobs", prototype: "src/workspaces/jobs/Jobs.tsx", title: "Production", kind: "list", columns: ["Job #", "Material Readiness", "Customer", "Current Stage", "Job Status", "Target Completion", "Projected"], searchPlaceholder: "Search jobs", statuses: ["active", "hold", "complete"], action: { label: "New Job" }, missing: ["Cancellation does not capture a separate WIP or finished-goods disposition. Unissued reservations are released."] },
  { id: "UX-09", path: "jobs/:id", prototype: "src/workspaces/jobs/JobWorkspace.tsx", title: "Job", kind: "record", columns: ["Material", "Stage", "Required", "Reserved", "Status"], missing: ["A material return posts to inventory immediately. It does not wait for a quarantine inspection."] },
  { id: "UX-10", path: "jobs/:id/stage/:stageId", prototype: "src/workspaces/jobs/StageUpdate.tsx", title: "Stage Update", kind: "form", sections: ["Stage update"], missing: ["Stage photos are not stored."] },
  { id: "UX-11", path: "quality", prototype: "src/workspaces/quality/Quality.tsx", title: "Quality", kind: "list", tabs: [{ id: "inspections", label: "Inspections" }, { id: "ncrs", label: "NCRs" }], columns: ["Inspection #", "Type", "Job ID", "Subject", "Received", "Inspected", "Inspector", "Sample", "Accept", "Reject", "Result"], searchPlaceholder: "Search inspections, NCRs, jobs…", missing: ["New Inspection and Raise NCR dialogs are not connected."] },
  { id: "UX-inspection", path: "quality/inspection/:id", prototype: "src/workspaces/quality/InspectionDetail.tsx", title: "Inspection", kind: "record", missing: ["This page records the incoming result for a goods-receipt lot. In-process, final, and customer inspections are not in this flow."] },
  { id: "UX-ncr", path: "quality/ncr/:id", prototype: "src/workspaces/quality/NCRDetail.tsx", title: "NCR", kind: "record", missing: RECORD_GAP },
  { id: "UX-12", path: "assets", prototype: "src/workspaces/assets/Assets.tsx", title: "Maintenance", kind: "list", tabs: [{ id: "assets", label: "Assets" }, { id: "maintenance", label: "Maintenance Events" }], columns: ["Asset", "Category", "Site", "Status"], searchPlaceholder: "Search assets", action: { label: "Log Event" }, missing: ["Maintenance documents and photos are not stored."] },
  { id: "UX-asset", path: "assets/:id", prototype: "src/workspaces/assets/AssetDetail.tsx", title: "Asset", kind: "record", columns: ["Event #", "Type", "Planned Date", "Start Date", "Completion Date", "Downtime", "Technician", "Description"], missing: RECORD_GAP },
  { id: "UX-maintenance", path: "assets/me/:id", prototype: "src/workspaces/assets/MaintenanceDetail.tsx", title: "Maintenance event", kind: "record", missing: RECORD_GAP },
  { id: "UX-dispatch", path: "dispatch", prototype: "src/workspaces/dispatch/Dispatch.tsx", title: "Dispatch", kind: "list", columns: ["Dispatch #", "Customer", "Jobs", "Carrier / Vehicle", "Dispatch Date", "Expected Delivery", "Qty", "Status"], searchPlaceholder: "Search dispatch", action: { label: "New Dispatch" }, missing: ["Statutory NIC e-way bill and vehicle GPS tracking are out of scope."] },
  { id: "UX-dispatch-detail", path: "dispatch/:id", prototype: "src/workspaces/dispatch/DispatchDetail.tsx", title: "Dispatch", kind: "record", missing: ["Statutory NIC e-way bill and route optimization are out of scope."] },
  { id: "UX-13", path: "documents", prototype: "src/workspaces/documents/Documents.tsx", title: "Documents", kind: "list", tabs: [{ id: "All", label: "All" }, { id: "Job Documents", label: "Job Documents" }, { id: "Quality Documents", label: "Quality Documents" }, { id: "Material Certificates", label: "Material Certificates" }, { id: "Supplier Documents", label: "Supplier Documents" }, { id: "Company Certificates", label: "Company Certificates" }, { id: "Asset Documents", label: "Asset Documents" }], columns: ["Document", "Category", "Linked record", "Expiry", "Status"], missing: ["Upload and renew actions are not connected."] },
  { id: "UX-document", path: "documents/:id", prototype: "src/workspaces/documents/DocumentDetail.tsx", title: "Document", kind: "record", missing: RECORD_GAP },
  { id: "UX-reports", path: "reports", prototype: "src/workspaces/reports/Reports.tsx", title: "Reports & Analytics", kind: "reports", sections: ["Production Summary", "Inventory Report", "Quality Report", "Procurement Report", "Asset Utilization", "Dispatch Report", "Financial Summary", "Multi-Site Overview"], missing: ["Report figures are not calculated from tenant data."] },
  { id: "UX-finance", path: "finance", prototype: "src/workspaces/finance/Finance.tsx", title: "Finance — Receivables & Payables", kind: "list", tabs: [{ id: "overview", label: "Overview" }, { id: "invoices", label: "Customer Invoices" }, { id: "payments", label: "Customer Payments" }, { id: "receivables", label: "Receivables & Ageing" }, { id: "bills", label: "Supplier Bills" }, { id: "supplier_payments", label: "Supplier Payments" }, { id: "payables", label: "Payables & Ageing" }, { id: "collections", label: "Statements & History" }, { id: "reconciliation", label: "Bank Reconciliation" }], columns: ["Invoice #", "Customer", "Customer PO", "Date", "Due Date", "Total", "Balance", "Status"], action: { label: "Create Customer Invoice", to: "/finance/invoice/new" }, missing: ["Ageing buckets and payment allocation need finance APIs. FD-39 still gates statutory IRN."] },
  { id: "UX-invoice-new", path: "finance/invoice/new", prototype: "src/workspaces/finance/InvoiceCreate.tsx", title: "Create Customer Invoice", kind: "form", columns: ["Item / Drawing", "HSN/SAC", "Ordered", "Invoiced", "Invoice Qty", "Rate (₹)", "GST %", "Line Total (₹)"], missing: [...FORM_GAP, "GST rates must come from reference data, not a hard-coded rate list."] },
  { id: "UX-invoice", path: "finance/invoice/:id", prototype: "src/workspaces/finance/InvoiceDetail.tsx", title: "Customer Invoice", kind: "record", columns: ["Item / Drawing", "HSN/SAC", "Qty", "Rate", "Tax %", "Line Total"], missing: RECORD_GAP },
  { id: "UX-receipt-new", path: "finance/payment/new", prototype: "src/workspaces/finance/CustomerPaymentCreate.tsx", title: "Record Customer Payment Receipt", kind: "form", columns: ["Invoice #", "Customer PO", "Due Date", "Invoice Total", "Outstanding", "Allocate (₹)"], missing: FORM_GAP },
  { id: "UX-receipt", path: "finance/payment/:id", prototype: "src/workspaces/finance/PaymentDetail.tsx", title: "Customer payment", kind: "record", missing: RECORD_GAP },
  { id: "UX-bill-new", path: "finance/bill/new", prototype: "src/workspaces/finance/SupplierBillCreate.tsx", title: "Capture Supplier Bill & 3-Way Match", kind: "form", columns: ["Item", "PO Qty / Rate", "GRN Recv", "QC Accepted", "Billed Qty", "Billed Rate (₹)", "Line Total (₹)", "Match Status"], missing: FORM_GAP },
  { id: "UX-bill", path: "finance/bill/:id", prototype: "src/workspaces/finance/SupplierBillDetail.tsx", title: "Supplier bill", kind: "record", columns: ["Material", "PO Qty", "GRN Recd", "QC Acc", "Billed Qty", "PO Rate", "Bill Rate", "Status"], missing: RECORD_GAP },
  { id: "UX-supplier-payment-new", path: "finance/supplier-payment/new", prototype: "src/workspaces/finance/SupplierPaymentCreate.tsx", title: "Record Supplier Payment", kind: "form", columns: ["Bill #", "Vendor Invoice #", "Due Date", "Status", "Outstanding", "Pay Amount (₹)"], missing: FORM_GAP },
  { id: "UX-supplier-payment", path: "finance/supplier-payment/:id", prototype: "src/workspaces/finance/SupplierPaymentDetail.tsx", title: "Supplier payment", kind: "record", missing: RECORD_GAP },
  { id: "UX-15", path: "audit", prototype: "src/workspaces/admin/AuditTimeline.tsx", title: "Business Activity & Audit Trail", kind: "list", columns: ["When", "Actor", "Action", "Scope", "Record"], missing: ["Audit rows come from audit_events. The API is not called from this foundation."] },
  { id: "UX-products", path: "products", prototype: "src/workspaces/products/Products.tsx", title: "Item Master", kind: "list", columns: ["Code", "Name", "Category", "Grade", "UOM", "Status"], searchPlaceholder: "Search items", missing: ["Grade is not a separate item field. It stays in the item description or category."] },
  { id: "UX-allocations", path: "allocations", prototype: "src/workspaces/allocations/Allocations.tsx", title: "Site Need", kind: "list", columns: ["Site", "Item", "Required", "Available", "Reserved", "Shortage", "Required by", "Source / action"], missing: ["Reserve covers the job's unissued requirements together. A second reservation is rejected while one is active."] },
  { id: "UX-job-transfer", path: "transfers", prototype: "src/workspaces/transfers/Transfers.tsx", title: "Move stock to the job", kind: "form", sections: ["New stock move"], missing: ["Job stock moves use the inter-site transfer ledger rather than a separate move list."] },
  { id: "UX-14", path: "admin", prototype: "src/workspaces/admin/AdminSecurity.tsx", title: "Admin & Settings", kind: "admin", tabs: [{ id: "company", label: "Company & Legal" }, { id: "sites", label: "Sites & Stores" }, { id: "users", label: "Users & Employees" }, { id: "roles", label: "Roles & Permissions" }, { id: "approvals", label: "Approval Policies" }, { id: "master-data", label: "Master Data & Business Configuration" }, { id: "integrations", label: "Integrations" }, { id: "ai", label: "AI & Usage" }, { id: "audit", label: "Audit & Activity" }], missing: ["This is the customer tenant admin inside the Customer App. It is not Platform Admin.", "Company, sites, users, and roles are not loaded. Demo sites and the role switcher are omitted."] },
  { id: "UX-onboarding", path: "onboarding", prototype: "src/workspaces/onboarding/Onboarding.tsx", title: "Welcome to BOSS", kind: "onboarding", sections: ["Welcome", "Company", "GST", "Sites", "Stores", "Users", "Review"], missing: ["Activation does not call tenant provisioning.", "The prototype's prefilled owner email and example company name are not copied.", "Entity type, financial year, and store defaults are not hard-coded."] },
];

export function findScreen(pathname: string): ScreenDef | undefined {
  const path = pathname.replace(/^\//, "");
  const exact = screens.find((screen) => screen.path === path);
  if (exact) return exact;
  return screens
    .filter((screen) => screen.path.includes(":") && matchPattern(screen.path, path))
    .sort((left, right) => specificity(right.path) - specificity(left.path))[0];
}

function specificity(pattern: string) {
  return pattern.split("/").filter((part) => !part.startsWith(":")).length;
}

function matchPattern(pattern: string, path: string) {
  const expression = new RegExp(`^${pattern.replace(/:[^/]+/g, "[^/]+")}$`);
  return expression.test(path);
}

export const NAV_LABELS = [
  "Home",
  "Inbox",
  "Sales & Orders",
  "Suppliers",
  "Procurement",
  "Inventory",
  "Production",
  "Quality",
  "Maintenance",
  "Dispatch",
  "Finance",
  "Documents",
  "Reports",
  "Audit Trail",
  "Admin & Settings",
];
