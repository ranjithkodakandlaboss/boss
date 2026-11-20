import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Route, Routes } from "react-router-dom";
import { beforeEach, describe, expect, it, vi } from "vitest";
import NcrDetailPage from "./NcrDetailPage";
import * as quality from "./api";

vi.mock("../auth/CustomerState", () => ({
  useCustomer: () => ({ user: { roles: [{ role: "OWNER" }] } }),
}));
vi.mock("./api", async (importOriginal) => ({
  ...await importOriginal<typeof import("./api")>(),
  getNcr: vi.fn(),
  updateNcr: vi.fn(),
  createConcession: vi.fn(),
  startNcrReinspection: vi.fn(),
}));

beforeEach(() => {
  vi.resetAllMocks();
  vi.mocked(quality.getNcr).mockResolvedValue({
    id: "ncr-id",
    ncrNumber: "NCR-1",
    inspectionId: "inspection-id",
    inspectionNumber: "INSP-1",
    inspectionType: "in_process",
    inspectionResult: "fail",
    itemId: "item-id",
    itemCode: "PART",
    itemName: "Part",
    jobId: "job-id",
    jobNumber: "JOB-1",
    stageId: "stage-id",
    stageName: "Assembly",
    lotId: "lot-id",
    lotNumber: "LOT-1",
    defectDescription: "Out of tolerance",
    status: "open",
    concessions: [],
  });
});

function renderPage() {
  render(
    <MemoryRouter initialEntries={["/quality/ncr/ncr-id"]}>
      <Routes><Route path="/quality/ncr/:id" element={<NcrDetailPage />} /></Routes>
    </MemoryRouter>,
  );
}

describe("NCR details", () => {
  it("shows linked production and inspection context and uses a consistent update drawer", async () => {
    vi.mocked(quality.updateNcr).mockResolvedValue({
      id: "ncr-id", ncrNumber: "NCR-1", status: "rework", disposition: "rework",
      inspectionId: "inspection-id", inspectionNumber: "INSP-1", jobId: "job-id",
      jobNumber: "JOB-1", stageName: "Assembly", lotNumber: "LOT-1", concessions: [],
    });
    vi.mocked(quality.startNcrReinspection).mockResolvedValue({
      id: "ncr-id", ncrNumber: "NCR-1", status: "rework", disposition: "rework",
      inspectionId: "inspection-id", inspectionNumber: "INSP-1", jobId: "job-id",
      jobNumber: "JOB-1", stageName: "Assembly", lotNumber: "LOT-1",
      reworkInspectionId: "reinspection-id", reworkInspectionNumber: "INSP-2", concessions: [],
    });
    renderPage();

    expect(await screen.findByText("JOB-1")).toBeInTheDocument();
    expect(screen.getByText("Assembly")).toBeInTheDocument();
    expect(screen.getByText("LOT-1")).toBeInTheDocument();
    expect(screen.getByText("INSP-1")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "Update" }));
    const updateDrawer = screen.getByRole("dialog", { name: "Update NCR" });
    expect(updateDrawer.parentElement?.className).toContain("justify-end");
    expect(updateDrawer.parentElement?.className).not.toContain("items-center");
    await userEvent.selectOptions(within(updateDrawer).getByLabelText("Status"), "rework");
    expect(within(updateDrawer).getByLabelText("Disposition")).toHaveValue("rework");
    await userEvent.click(within(updateDrawer).getByRole("button", { name: "Save" }));
    await waitFor(() => expect(quality.updateNcr).toHaveBeenCalledWith("ncr-id", expect.objectContaining({
      status: "rework",
      disposition: "rework",
    })));

    await userEvent.click(await screen.findByRole("button", { name: "Start Reinspection" }));
    await waitFor(() => expect(quality.startNcrReinspection).toHaveBeenCalledWith("ncr-id"));
    expect(await screen.findByRole("button", { name: "INSP-2" })).toBeInTheDocument();
  });
});
