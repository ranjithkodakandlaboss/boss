import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, it } from "vitest";
import App from "./App";
import { NAV_LABELS, screens } from "./screens/catalog";
import ConfirmDialog from "./components/shared/ConfirmDialog";
import { getStatusLabel } from "./components/shared/StatusChip";
import EmptyState from "./components/shared/EmptyState";
import ErrorState from "./components/shared/ErrorState";
import LoadingState from "./components/shared/LoadingState";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <App />
    </MemoryRouter>,
  );
}

describe("customer app shell", () => {
  it("renders the prototype navigation without a demo company", () => {
    renderAt("/");
    const nav = screen.getByRole("navigation", { name: "Primary" });
    for (const label of NAV_LABELS.filter((item) => item !== "Admin & Settings")) {
      expect(within(nav).getByRole("link", { name: label })).toBeInTheDocument();
    }
    expect(screen.getByRole("link", { name: "Admin & Settings" })).toBeInTheDocument();
    expect(screen.getByText("Company")).toBeInTheDocument();
    expect(document.body.textContent).not.toMatch(/Apex Precision|ranjith302@gmail.com/i);
  });

  it("keeps the sales workspace tabs from the prototype", async () => {
    renderAt("/sales");
    expect(screen.getByRole("heading", { name: "Customer POs" })).toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "Customer Directory" }));
    expect(screen.getByRole("heading", { name: "Customer Directory" })).toBeInTheDocument();
  });

  it("shows an empty table instead of seeded rows", () => {
    renderAt("/sales");
    expect(screen.getByText("No records")).toBeInTheDocument();
    expect(screen.queryByText(/PO-2026/)).not.toBeInTheDocument();
  });

  it("covers loading and error states", () => {
    const { rerender } = render(<LoadingState label="Loading records" />);
    expect(screen.getByRole("status")).toHaveTextContent("Loading records");
    rerender(<ErrorState body="The customer API did not return records." />);
    expect(screen.getByRole("alert")).toHaveTextContent("did not return records");
    rerender(<EmptyState title="No records" body="Nothing seeded." />);
    expect(screen.getByText("Nothing seeded.")).toBeInTheDocument();
  });

  it("confirms a blocked save without writing a record", async () => {
    const onConfirm = vi.fn();
    render(
      <ConfirmDialog open title="New Job" body="Nothing was saved." confirmLabel="Close" onConfirm={onConfirm} onCancel={vi.fn()} />,
    );
    await userEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(onConfirm).toHaveBeenCalledOnce();
  });

  it("uses prototype status labels", () => {
    expect(getStatusLabel("pending_approval")).toBe("Pending Approval");
    expect(getStatusLabel("qc_hold")).toBe("Quality Hold");
  });

  it("registers every prototype route used by the shell", () => {
    const required = ["", "sales", "procurement", "inventory", "jobs", "quality", "finance", "admin", "onboarding"];
    for (const path of required) {
      expect(screens.some((screen) => screen.path === path)).toBe(true);
    }
    expect(screens.every((screen) => screen.missing.length > 0)).toBe(true);
  });
});
