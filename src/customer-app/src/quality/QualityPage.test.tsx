import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import QualityPage from "./QualityPage";
import * as quality from "./api";
import { allPages } from "../inventory/api";

vi.mock("../auth/CustomerState", () => ({
  useCustomer: () => ({ user: { roles: [{ role: "OWNER" }] } }),
}));
vi.mock("../inventory/api", () => ({ allPages: vi.fn() }));
vi.mock("./api", async (importOriginal) => ({
  ...await importOriginal<typeof import("./api")>(),
  listConfigOptions: vi.fn(),
  listInspections: vi.fn(),
  listNcrs: vi.fn(),
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
  vi.mocked(quality.listNcrs).mockResolvedValue([]);
  vi.mocked(allPages).mockResolvedValue([{ id: "item-id", code: "PART", name: "Part" }]);
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
    expect(dialog.getByText("Select an inspection category and item, and enter a reference ID.")).toBeInTheDocument();
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
    await userEvent.selectOptions(dialog.getByLabelText("Item"), "item-id");
    fireEvent.change(dialog.getByLabelText("Reference ID"), { target: { value: "job-id" } });
    await userEvent.click(dialog.getByRole("button", { name: "Create" }));
    await waitFor(() => expect(quality.createInspection).toHaveBeenCalledWith(expect.objectContaining({
      inspectionType: "final", inspectionTypeOptionId: "type-id", referenceType: "production_job",
      referenceId: "job-id", itemId: "item-id",
    })));
  }, 20000);
});
