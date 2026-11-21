import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import DocumentActions from "./DocumentActions";

const showToast = vi.hoisted(() => vi.fn());

vi.mock("../runtime", () => ({
  useStore: () => ({ showToast, companyConfig: {}, currentUser: {}, quotations: [] }),
}));

vi.mock("../../../auth/session", () => ({
  currentToken: () => "test-token",
  currentRefreshToken: () => null,
  currentTokenExpiresAt: () => null,
  storeRefreshedToken: vi.fn(),
}));

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  showToast.mockClear();
});

it("downloads the authenticated Customer PO PDF instead of printing local draft data", async () => {
  const fetchPdf = vi.fn().mockResolvedValue(new Response("%PDF-1.7", {
    headers: { "Content-Type": "application/pdf", "Content-Disposition": 'attachment; filename="CPO-1.pdf"' },
  }));
  const createObjectURL = vi.fn().mockReturnValue("blob:customer-po");
  const download = vi.spyOn(HTMLAnchorElement.prototype, "click").mockImplementation(() => {});
  const printWindow = vi.spyOn(window, "open").mockImplementation(() => null);
  vi.stubGlobal("fetch", fetchPdf);
  vi.stubGlobal("URL", Object.assign(class extends URL {}, { createObjectURL, revokeObjectURL: vi.fn() }));
  render(<DocumentActions customerPoId="po-1" documentType="Customer PO" reference="CPO-1" partyName="Buyer" />);

  fireEvent.click(screen.getByRole("button", { name: "PDF / Print" }));

  await waitFor(() => expect(download).toHaveBeenCalledOnce());
  const [pdfUrl, pdfInit] = fetchPdf.mock.calls[0];
  expect(pdfUrl).toMatch(/\/api\/v1\/cpos\/po-1\/document$/);
  expect(new Headers(pdfInit?.headers).get("Authorization")).toBe("Bearer test-token");
  expect(createObjectURL).toHaveBeenCalledWith(expect.objectContaining({ type: "application/pdf" }));
  expect(download.mock.contexts[0]).toHaveProperty("download", "CPO-1.pdf");
  expect(printWindow).not.toHaveBeenCalled();

  fetchPdf.mockResolvedValueOnce(new Response("%PDF-1.7", { headers: { "Content-Type": "application/pdf" } }));
  fireEvent.click(screen.getByRole("button", { name: "Send Email" }));
  expect(screen.getByText(/BOSS does not send email or WhatsApp/)).toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Download PDF" }));
  await waitFor(() => expect(download).toHaveBeenCalledTimes(2));
  expect(fetchPdf).toHaveBeenCalledTimes(2);
  expect(fetchPdf.mock.calls[1][0]).toBe(pdfUrl);
  expect(new Headers(fetchPdf.mock.calls[1][1]?.headers).get("Authorization")).toBe("Bearer test-token");
});

it("shows the missing-tax-context error without falling back to an incorrect printable copy", async () => {
  const fetchPdf = vi.fn().mockResolvedValue(new Response(null, { status: 422 }));
  const printWindow = vi.spyOn(window, "open").mockImplementation(() => null);
  vi.stubGlobal("fetch", fetchPdf);
  render(<DocumentActions customerPoId="po-1" documentType="Customer PO" reference="CPO-1" partyName="Buyer" />);

  fireEvent.click(screen.getByRole("button", { name: "PDF / Print" }));

  await waitFor(() => expect(showToast).toHaveBeenCalledWith(expect.objectContaining({
    type: "error", body: expect.stringContaining("GSTIN or address state"),
  })));
  expect(printWindow).not.toHaveBeenCalled();
});
