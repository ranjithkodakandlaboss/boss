import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { expect, it, vi } from "vitest";
import CreatableSelect from "./CreatableSelect";

const copper = { id: "cat-1", label: "Conductor" };

it("shows existing options and an add action", () => {
  render(<CreatableSelect label="category" ariaLabel="Category" value="cat-1" onChange={() => undefined} options={[copper]} creatable onCreate={async () => copper} fields={[{ name: "name", label: "Name", required: true }]} />);
  expect(screen.getByRole("option", { name: "Conductor" })).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Add category" })).toHaveTextContent("+ Add new");
});

it("offers add new when a successful load has no options", () => {
  render(<CreatableSelect label="category" ariaLabel="Category" value="" onChange={() => undefined} options={[]} creatable onCreate={async () => copper} fields={[{ name: "name", label: "Name", required: true }]} />);
  expect(screen.getByText("No options available. Add your first option.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Add category" })).toHaveTextContent("Add New");
});

it("offers add new when the search has no match", () => {
  render(<CreatableSelect label="category" ariaLabel="Category" value="" onChange={() => undefined} options={[copper]} creatable onCreate={async () => copper} fields={[{ name: "name", label: "Name", required: true }]} />);
  fireEvent.change(screen.getByLabelText("Category search"), { target: { value: "missing" } });
  expect(screen.getByText("No matching option found. Add new value.")).toBeInTheDocument();
  expect(screen.getByRole("button", { name: "Add category" })).toBeInTheDocument();
});

it("shows the API error and retry without an add action", () => {
  const retry = vi.fn();
  render(<CreatableSelect label="category" ariaLabel="Category" value="" onChange={() => undefined} options={[]} error="Categories could not be loaded." onRetry={retry} creatable onCreate={async () => copper} />);
  expect(screen.getByRole("alert")).toHaveTextContent("Categories could not be loaded.");
  expect(screen.queryByRole("button", { name: "Add category" })).not.toBeInTheDocument();
  fireEvent.click(screen.getByRole("button", { name: "Retry" }));
  expect(retry).toHaveBeenCalledOnce();
});

it("saves a new value, selects it, and keeps a duplicate in the form", async () => {
  const onChange = vi.fn();
  const onCreate = vi.fn(async () => ({ id: "cat-2", label: "Resin" }));
  const { rerender } = render(<CreatableSelect label="category" ariaLabel="Category" value="" onChange={onChange} options={[]} creatable fields={[{ name: "name", label: "Name", required: true }]} unique={(values) => values.name === "Conductor" ? "This value already exists." : null} onCreate={onCreate} />);
  fireEvent.click(screen.getByRole("button", { name: "Add category" }));
  fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Conductor" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  expect(await screen.findByRole("alert")).toHaveTextContent("This value already exists.");
  expect(onCreate).not.toHaveBeenCalled();
  fireEvent.change(screen.getByLabelText("Name"), { target: { value: "Resin" } });
  fireEvent.click(screen.getByRole("button", { name: "Save" }));
  await waitFor(() => expect(onChange).toHaveBeenCalledWith("cat-2"));
  rerender(<CreatableSelect label="category" ariaLabel="Category" value="cat-2" onChange={onChange} options={[{ id: "cat-2", label: "Resin" }]} creatable fields={[{ name: "name", label: "Name", required: true }]} onCreate={onCreate} />);
  expect(screen.getByRole("option", { name: "Resin" })).toBeInTheDocument();
});

it("does not offer add for a system-controlled list", () => {
  render(<CreatableSelect label="priority" ariaLabel="Priority" value="normal" onChange={() => undefined} options={[{ id: "normal", label: "normal" }]} />);
  expect(screen.queryByRole("button", { name: "Add priority" })).not.toBeInTheDocument();
});
