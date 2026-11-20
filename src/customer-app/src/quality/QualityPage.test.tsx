import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import QualityPage from "./QualityPage";
import InspectionDetailPage from "./InspectionDetailPage";
import * as quality from "./api";

vi.mock("../auth/CustomerState", () => ({
  useCustomer: () => ({ user: { roles: [{ role: "OWNER" }] } }),
}));
vi.mock("./api", async (importOriginal) => ({
  ...await importOriginal<typeof import("./api")>(),
  listConfigOptions: vi.fn(),
  listInspections: vi.fn(),
  listInspectionReferences: vi.fn(),
  listNcrs: vi.fn(),
  listTemplates: vi.fn(),
  getInspection: vi.fn(),
  recordResult: vi.fn(),
  createInspection: vi.fn(),
  createNcr: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(quality.listConfigOptions).mockImplementation(async (category) =>
    category === "INSPECTION_TYPES"
      ? [{ id: "type-id", code: "VISUAL", label: "Visual" }]
      : [{ id: "defect-id", code: "SURFACE", label: "Surface" }]);
  vi.mocked(quality.listInspections).mockResolvedValue([{
    id: "inspection-id", inspectionNumber: "INSP-1", inspectionType: "final",
    referenceType: "production_job", referenceId: "job-id", itemId: "item-id",
    itemName: "Part", status: "pending",
  }]);
  vi.mocked(quality.listInspectionReferences).mockResolvedValue([{
    id: "job-id", label: "JOB-1 | PART | Part", itemId: "item-id",
    itemCode: "PART", itemName: "Part", quantity: 10, status: "in_progress",
  }]);
  vi.mocked(quality.listNcrs).mockResolvedValue([]);
  vi.mocked(quality.listTemplates).mockResolvedValue([{
    id: "template-id", name: "Dimensional Check", scope: "job", inspectionType: "final", isActive: true,
    items: [{ id: "measurement-id", sortOrder: 1, description: "Length", responseType: "numeric", isMandatory: true, unit: "mm", specMin: 9.5, specMax: 10.5 }],
  }]);
});

function renderPage() {
  render(<MemoryRouter><QualityPage /></MemoryRouter>);
}

describe("Quality reference data", () => {
  it("submits selected category IDs and fixed severity codes for an inspection's item", async () => {
    vi.mocked(quality.createNcr).mockResolvedValue({
      id: "ncr-id", ncrNumber: "NCR-1", status: "open",
    });
    renderPage();
    await screen.findAllByText("INSP-1");
    await userEvent.click(screen.getByRole("button", { name: "New NCR" }));
    const dialog = within(screen.getByRole("dialog", { name: "Raise NCR" }));
    await userEvent.selectOptions(dialog.getByLabelText("Inspection"), "inspection-id");
    await userEvent.selectOptions(dialog.getByLabelText("Defect category"), "defect-id");
    await userEvent.selectOptions(dialog.getByLabelText("Severity"), "major");
    fireEvent.change(dialog.getByLabelText("Description"), { target: { value: "Surface damage" } });
    fireEvent.change(dialog.getByLabelText("Quantity"), { target: { value: "0" } });
    await userEvent.click(dialog.getByRole("button", { name: "Raise NCR" }));
    expect(dialog.getByText("Enter a quantity greater than zero.")).toBeInTheDocument();
    expect(quality.createNcr).not.toHaveBeenCalled();
    fireEvent.change(dialog.getByLabelText("Quantity"), { target: { value: "1" } });
    await userEvent.click(dialog.getByRole("button", { name: "Raise NCR" }));
    await waitFor(() => expect(quality.createNcr).toHaveBeenCalledWith(expect.objectContaining({
      inspectionId: "inspection-id", itemId: "item-id", defectCategoryId: "defect-id", severity: "major",
    })));
    expect(quality.listConfigOptions).not.toHaveBeenCalledWith("DEFECT_SEVERITY");
  }, 20000);

  it("surfaces category loading failures and refuses to submit without a selection", async () => {
    vi.mocked(quality.listConfigOptions).mockRejectedValue(new Error("Service unavailable"));
    renderPage();
    await userEvent.click(screen.getByRole("button", { name: "New Inspection" }));
    const dialog = within(screen.getByRole("dialog", { name: "New Inspection" }));
    expect(await dialog.findByText("Unable to load inspection categories: Service unavailable")).toBeInTheDocument();
    await userEvent.click(dialog.getByRole("button", { name: "Create" }));
    expect(dialog.getByText("Select an inspection category and linked record.")).toBeInTheDocument();
    expect(quality.createInspection).not.toHaveBeenCalled();
  }, 20000);

  it("submits the inspection option separately from the workflow type", async () => {
    vi.mocked(quality.createInspection).mockResolvedValue({
      id: "new-id", inspectionNumber: "INSP-2", inspectionType: "final",
      referenceType: "production_job", referenceId: "job-id", status: "pending",
    });
    renderPage();
    await userEvent.click(screen.getByRole("button", { name: "New Inspection" }));
    const dialog = within(screen.getByRole("dialog", { name: "New Inspection" }));
    await userEvent.selectOptions(await dialog.findByLabelText("Inspection category"), "type-id");
    await userEvent.selectOptions(await dialog.findByLabelText("Production job"), "job-id:item-id");
    await userEvent.click(dialog.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(quality.createInspection).toHaveBeenCalledWith(expect.objectContaining({
      inspectionType: "final", inspectionTypeOptionId: "type-id", referenceType: "production_job",
      referenceId: "job-id", itemId: "item-id",
    })));
  }, 20000);

  it("uses a linked-record picker and inspection template instead of a raw UUID", async () => {
    vi.mocked(quality.createInspection).mockResolvedValue({
      id: "new-id", inspectionNumber: "INSP-2", inspectionType: "final",
      referenceType: "production_job", referenceId: "job-id", status: "pending",
    });
    renderPage();
    await userEvent.click(screen.getByRole("button", { name: "New Inspection" }));
    const dialog = within(screen.getByRole("dialog", { name: "New Inspection" }));
    await userEvent.selectOptions(await dialog.findByLabelText("Inspection category"), "type-id");
    expect(dialog.queryByLabelText("Reference ID")).not.toBeInTheDocument();
    const reference = await dialog.findByLabelText("Production job");
    await userEvent.selectOptions(reference, "job-id:item-id");
    const template = await dialog.findByLabelText("Inspection template");
    await userEvent.selectOptions(template, "template-id");
    expect(dialog.getByText(/Length.*9\.5.*10\.5.*mm/)).toBeInTheDocument();
    await userEvent.click(dialog.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(quality.createInspection).toHaveBeenCalledWith(expect.objectContaining({
      referenceType: "production_job", referenceId: "job-id", itemId: "item-id", templateId: "template-id",
    })));
  }, 20000);

  it("records inspection results in a right-side drawer", async () => {
    vi.mocked(quality.getInspection).mockResolvedValue({
      id: "inspection-id", inspectionNumber: "INSP-1", inspectionType: "final",
      referenceType: "production_job", referenceId: "job-id", status: "pending",
      checklistResponses: [{
        id: "response-id", checklistItemId: "measurement-id", itemDescription: "Length",
        responseType: "numeric", isMandatory: true, unit: "mm", specMin: 9.5, specMax: 10.5, answered: false,
      }], linkedNcrs: [],
    });
    render(
      <MemoryRouter initialEntries={["/quality/inspection/inspection-id"]}>
        <Routes><Route path="/quality/inspection/:id" element={<InspectionDetailPage />} /></Routes>
      </MemoryRouter>,
    );
    await userEvent.click(await screen.findByRole("button", { name: "Record Result" }));
    const resultDrawer = screen.getByRole("dialog", { name: "Record Inspection Result" });
    expect(resultDrawer).toBeInTheDocument();
    expect(within(resultDrawer).getByText("Specification: 9.5 to 10.5 mm")).toBeInTheDocument();
    fireEvent.change(within(resultDrawer).getByPlaceholderText("Value"), { target: { value: "11" } });
    expect(within(resultDrawer).getByRole("button", { name: "Save Result" })).toBeDisabled();
    expect(within(resultDrawer).getByRole("button", { name: "Pass" })).toBeDisabled();
    await userEvent.click(within(resultDrawer).getByRole("button", { name: "Fail" }));
    expect(within(resultDrawer).getByRole("button", { name: "Save Result" })).toBeEnabled();
  }, 20000);
});
