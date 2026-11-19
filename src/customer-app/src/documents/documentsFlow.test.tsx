import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, expect, it, vi } from "vitest";
import { CustomerProvider } from "../auth/CustomerState";
import { signIn } from "../auth/session";
import DocumentsPage from "./DocumentsPage";
import DocumentDetailPage from "./DocumentDetailPage";
import type { VaultDocument, DocumentHistoryEntry } from "./types";

const mockDocuments: VaultDocument[] = [
  {
    id: "doc-1",
    title: "ISO 9001 Certificate",
    category: "Company Certificates",
    docType: "Quality Certificate",
    version: 1,
    status: "active",
    issueDate: "2025-01-01",
    expiryDate: new Date(Date.now() + 15 * 86400000).toISOString().slice(0, 10), // expiring in 15 days
    reminderDays: 30,
    certStatus: "verified",
    fileName: "iso_9001.pdf",
    fileSize: "1.2 MB",
    uploadedBy: "Alice Smith",
    uploadedAt: "2025-01-01T10:00:00Z",
    linkedEntities: [{ type: "Supplier", ref: "Apex Metals", id: "sup-1" }],
  },
  {
    id: "doc-2",
    title: "Machine Calibration Spec",
    category: "Asset Documents",
    docType: "Calibration",
    version: 1,
    status: "active",
    issueDate: "2024-01-01",
    expiryDate: new Date(Date.now() - 5 * 86400000).toISOString().slice(0, 10), // expired 5 days ago
    reminderDays: 30,
    fileName: "calibration.pdf",
    fileSize: "450 KB",
    uploadedBy: "Bob Jones",
    uploadedAt: "2024-01-01T10:00:00Z",
    linkedEntities: [{ type: "Asset", ref: "CNC-01 — Milling Machine", id: "ast-1" }],
  },
  {
    id: "doc-3",
    title: "Job Safety Plan",
    category: "Job Documents",
    docType: "Safety Plan",
    version: 1,
    status: "active",
    issueDate: "2025-01-01",
    expiryDate: new Date(Date.now() + 100 * 86400000).toISOString().slice(0, 10), // ok
    reminderDays: 30,
    fileName: "safety.pdf",
    fileSize: "2.1 MB",
    uploadedBy: "Charlie Brown",
    uploadedAt: "2025-01-01T10:00:00Z",
    linkedEntities: [{ type: "Job", ref: "JOB-2026-001", id: "job-1" }],
  },
  {
    id: "doc-archived",
    title: "Old Plant License",
    category: "Company Certificates",
    docType: "License",
    version: 1,
    status: "archived",
    issueDate: "2020-01-01",
    expiryDate: "2021-01-01",
    reminderDays: 30,
    archivedAt: "2021-02-01T10:00:00Z",
    archivedBy: "Admin User",
    archiveReason: "Plant decommissioned",
    fileName: "license.pdf",
    fileSize: "300 KB",
    uploadedBy: "Admin User",
    uploadedAt: "2020-01-01T10:00:00Z",
    linkedEntities: [],
  },
];

const mockHistory: DocumentHistoryEntry[] = [
  {
    id: "hist-1",
    action: "DOCUMENT_UPLOADED",
    description: "Uploaded initial document and file version",
    actor: "Alice Smith",
    timestamp: "2025-01-01T10:00:00Z",
  },
];

const mockSuppliers = [{ id: "sup-1", name: "Apex Metals" }];
const mockCustomers = [{ id: "cust-1", name: "Beta Corp" }];
const mockJobs = [{ id: "job-1", jobNumber: "JOB-2026-001", description: "Precision Shafts" }];
const mockAssets = [{ id: "ast-1", assetCode: "CNC-01", name: "Milling Machine" }];

const json = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status });

let currentDocs = [...mockDocuments];

beforeEach(() => {
  currentDocs = [...mockDocuments];
  signIn("test-session");
  vi.stubGlobal(
    "fetch",
    vi.fn((input: RequestInfo, init?: RequestInit) => {
      const url = String(input);
      const method = init?.method?.toUpperCase() || "GET";

      if (url.includes("/auth/me")) {
        return Promise.resolve(
          json({
            id: "u-1",
            tenantId: "t-1",
            email: "owner@test.com",
            displayName: "Owner",
            status: "active",
            roles: [{ role: "OWNER" }],
          })
        );
      }
      if (url.includes("/tenant/info")) {
        return Promise.resolve(json({ id: "t-1", name: "BOSS Works", status: "active" }));
      }
      if (url.includes("/onboarding")) {
        return Promise.resolve(json({ completed: true }));
      }
      if (url.includes("/api/v1/suppliers")) {
        return Promise.resolve(json(mockSuppliers));
      }
      if (url.includes("/api/v1/customers")) {
        return Promise.resolve(json(mockCustomers));
      }
      if (url.includes("/api/v1/jobs")) {
        return Promise.resolve(json(mockJobs));
      }
      if (url.includes("/api/v1/assets")) {
        return Promise.resolve(json(mockAssets));
      }

      // Documents API
      if (url.includes("/api/v1/documents/") && url.includes("/history")) {
        return Promise.resolve(json(mockHistory));
      }
      if (url.includes("/api/v1/documents/") && url.includes("/archive") && method === "PATCH") {
        const body = JSON.parse(String(init?.body || "{}"));
        const id = url.split("/documents/")[1].split("/archive")[0];
        const doc = currentDocs.find(d => d.id === id);
        if (doc) {
          doc.status = "archived";
          doc.archivedAt = new Date().toISOString();
          doc.archiveReason = body.reason;
          return Promise.resolve(json(doc));
        }
        return Promise.resolve(json({ error: "Document not found" }, 404));
      }
      if (url.includes("/api/v1/documents/") && url.includes("/renew") && method === "POST") {
        const id = url.split("/documents/")[1].split("/renew")[0];
        const doc = currentDocs.find(d => d.id === id);
        if (doc) {
          doc.version = doc.version + 1;
          doc.fileName = "renewed_file.pdf";
          doc.expiryDate = "2027-01-01";
          return Promise.resolve(json(doc));
        }
        return Promise.resolve(json({ error: "Document not found" }, 404));
      }
      if (url.includes("/api/v1/documents/") && method === "GET") {
        const id = url.split("/documents/")[1].split("?")[0];
        const doc = currentDocs.find(d => d.id === id);
        if (doc) return Promise.resolve(json(doc));
        return Promise.resolve(json({ error: "Document not found" }, 404));
      }
      if (url.includes("/api/v1/documents") && method === "POST") {
        const newDoc: VaultDocument = {
          id: `doc-${Date.now()}`,
          title: "Newly Uploaded Drawing",
          category: "Asset Documents",
          docType: "Drawing",
          version: 1,
          status: "active",
          fileName: "drawing.pdf",
          fileSize: "800 KB",
          uploadedBy: "Owner",
          uploadedAt: new Date().toISOString(),
          linkedEntities: [{ type: "Asset", ref: "CNC-01 — Milling Machine" }],
        };
        currentDocs.push(newDoc);
        return Promise.resolve(json(newDoc, 201));
      }
      if (url.includes("/api/v1/documents") && method === "GET") {
        return Promise.resolve(
          json({
            content: currentDocs,
            page: 0,
            size: 50,
            totalElements: currentDocs.length,
          })
        );
      }

      return Promise.resolve(json({}, 404));
    })
  );
});

describe("Document Vault Frontend Flow", () => {
  it("renders Document Vault list with toolbar, search, and category tabs", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents"]}>
          <Routes>
            <Route path="/documents" element={<DocumentsPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    // Initial loading or loaded documents
    await waitFor(() => {
      expect(screen.getByText("ISO 9001 Certificate")).toBeInTheDocument();
      expect(screen.getByText("Machine Calibration Spec")).toBeInTheDocument();
      expect(screen.getByText("Job Safety Plan")).toBeInTheDocument();
    });

    // Archived document hidden by default
    expect(screen.queryByText("Old Plant License")).not.toBeInTheDocument();

    // Category tabs visible
    expect(screen.getByRole("button", { name: "Job Documents" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Quality Documents" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Material Certificates" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Supplier Documents" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Company Certificates" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Asset Documents" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Customer Documents" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sales Documents" })).toBeInTheDocument();

    // Expiry badges in toolbar
    expect(screen.getByRole("button", { name: "1 expiring" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "1 expired" })).toBeInTheDocument();
  });

  it("filters documents by category", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents"]}>
          <Routes>
            <Route path="/documents" element={<DocumentsPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    await waitFor(() => {
      expect(screen.getByText("ISO 9001 Certificate")).toBeInTheDocument();
    });

    // Click "Job Documents" tab
    await userEvent.click(screen.getByRole("button", { name: "Job Documents" }));

    expect(screen.getByText("Job Safety Plan")).toBeInTheDocument();
    expect(screen.queryByText("ISO 9001 Certificate")).not.toBeInTheDocument();
    expect(screen.queryByText("Machine Calibration Spec")).not.toBeInTheDocument();

    // Click "All" tab to restore
    await userEvent.click(screen.getByRole("button", { name: "All" }));
    expect(screen.getByText("ISO 9001 Certificate")).toBeInTheDocument();
  });

  it("filters documents using search input", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents"]}>
          <Routes>
            <Route path="/documents" element={<DocumentsPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    await waitFor(() => {
      expect(screen.getByText("ISO 9001 Certificate")).toBeInTheDocument();
    });

    const searchInput = screen.getByPlaceholderText("Search documents…");
    await userEvent.type(searchInput, "calibration");

    expect(screen.getByText("Machine Calibration Spec")).toBeInTheDocument();
    expect(screen.queryByText("ISO 9001 Certificate")).not.toBeInTheDocument();
    expect(screen.queryByText("Job Safety Plan")).not.toBeInTheDocument();
  });

  it("filters by attention indicators (expiring and expired)", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents"]}>
          <Routes>
            <Route path="/documents" element={<DocumentsPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "1 expiring" })).toBeInTheDocument();
    });

    // Click 1 expiring button
    await userEvent.click(screen.getByRole("button", { name: "1 expiring" }));

    expect(screen.getByText("ISO 9001 Certificate")).toBeInTheDocument();
    expect(screen.queryByText("Machine Calibration Spec")).not.toBeInTheDocument();
    expect(screen.queryByText("Job Safety Plan")).not.toBeInTheDocument();

    // Click 1 expired button
    await userEvent.click(screen.getByRole("button", { name: "1 expired" }));

    expect(screen.getByText("Machine Calibration Spec")).toBeInTheDocument();
    expect(screen.queryByText("ISO 9001 Certificate")).not.toBeInTheDocument();

    // Clicking again clears attention
    await userEvent.click(screen.getByRole("button", { name: "1 expired" }));
    expect(screen.getByText("ISO 9001 Certificate")).toBeInTheDocument();
    expect(screen.getByText("Job Safety Plan")).toBeInTheDocument();
  });

  it("toggles archived visibility with Show archived / Hide archived", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents"]}>
          <Routes>
            <Route path="/documents" element={<DocumentsPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: "Show archived" })).toBeInTheDocument();
    });

    expect(screen.queryByText("Old Plant License")).not.toBeInTheDocument();

    // Click Show archived
    await userEvent.click(screen.getByRole("button", { name: "Show archived" }));

    expect(screen.getByText("Old Plant License")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Hide archived" })).toBeInTheDocument();

    // Click Hide archived
    await userEvent.click(screen.getByRole("button", { name: "Hide archived" }));
    expect(screen.queryByText("Old Plant License")).not.toBeInTheDocument();
  });

  it("opens Upload modal, validates required fields, and submits document", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents"]}>
          <Routes>
            <Route path="/documents" element={<DocumentsPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /Upload/i })).toBeInTheDocument();
    });

    // Open upload modal
    await userEvent.click(screen.getByRole("button", { name: /Upload/i }));
    expect(screen.getByText("Upload Document")).toBeInTheDocument();

    // Click upload without title -> Title is required (modal submit button)
    const getModalUploadBtn = () => screen.getAllByRole("button", { name: /^Upload$/ }).pop()!;
    await userEvent.click(getModalUploadBtn());
    expect(screen.getByText("Title is required")).toBeInTheDocument();

    // Fill title but no file
    const titleInput = screen.getByPlaceholderText("Document title");
    await userEvent.type(titleInput, "CNC Maintenance Manual");
    await userEvent.click(getModalUploadBtn());
    expect(screen.getByText("Please select a file")).toBeInTheDocument();

    // Select file
    const file = new File(["dummy drawing content"], "drawing.pdf", { type: "application/pdf" });
    const fileInput = document.querySelector('input[type="file"]') as HTMLInputElement;
    await userEvent.upload(fileInput, file);

    // Submit upload
    await userEvent.click(getModalUploadBtn());

    // Modal closes on success and newly uploaded doc appears
    await waitFor(() => {
      expect(screen.queryByText("Upload Document")).not.toBeInTheDocument();
      expect(screen.getByText("Newly Uploaded Drawing")).toBeInTheDocument();
    });
  });

  it("opens Renew modal via renew query param and detail page", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents?renew=doc-2"]}>
          <Routes>
            <Route path="/documents" element={<DocumentsPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    // Should open renew modal for doc-2 automatically
    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "Renew Document" })).toBeInTheDocument();
      expect(screen.getAllByText("Machine Calibration Spec").length).toBeGreaterThanOrEqual(1);
    });

    // Close modal
    await userEvent.click(screen.getByRole("button", { name: "Cancel" }));
    expect(screen.queryByRole("heading", { name: "Renew Document" })).not.toBeInTheDocument();
  });

  it("renders Document Detail page with metadata, file card, linked records, and history", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents/doc-1"]}>
          <Routes>
            <Route path="/documents/:id" element={<DocumentDetailPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    await waitFor(() => {
      expect(screen.getByRole("heading", { name: "ISO 9001 Certificate" })).toBeInTheDocument();
    });

    // Metadata checks
    expect(screen.getByText("Company Certificates")).toBeInTheDocument();
    expect(screen.getByText("Quality Certificate")).toBeInTheDocument();
    expect(screen.getAllByText("Alice Smith").length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText(/1\.2 MB/)).toBeInTheDocument();

    // Linked records
    expect(screen.getByText(/Supplier: Apex Metals/i)).toBeInTheDocument();

    // Audit history section
    expect(screen.getByText("Document Activity & Audit Trail")).toBeInTheDocument();
    await waitFor(() => {
      expect(screen.getByText("Uploaded initial document and file version")).toBeInTheDocument();
    });
  });

  it("archives document from detail page with reason", async () => {
    render(
      <CustomerProvider>
        <MemoryRouter initialEntries={["/documents/doc-3"]}>
          <Routes>
            <Route path="/documents/:id" element={<DocumentDetailPage />} />
          </Routes>
        </MemoryRouter>
      </CustomerProvider>
    );

    await waitFor(() => {
      expect(screen.getByRole("button", { name: /Archive/i })).toBeInTheDocument();
    });

    // Open archive modal
    await userEvent.click(screen.getByRole("button", { name: /Archive/i }));
    expect(screen.getByRole("heading", { name: "Archive Document" })).toBeInTheDocument();

    // Confirm button disabled until reason entered
    const confirmBtn = screen.getByRole("button", { name: "Archive Document" });
    expect(confirmBtn).toBeDisabled();

    // Enter reason
    const textarea = screen.getByPlaceholderText(/Superseded by new plant certificate/i);
    await userEvent.type(textarea, "Job finished and archived");
    expect(confirmBtn).not.toBeDisabled();

    // Confirm archive
    await userEvent.click(confirmBtn);

    // Modal closes and archived banner appears
    await waitFor(() => {
      expect(screen.queryByRole("heading", { name: "Archive Document" })).not.toBeInTheDocument();
      expect(screen.getByText(/Reason: Job finished and archived/i)).toBeInTheDocument();
    });
  });
});
