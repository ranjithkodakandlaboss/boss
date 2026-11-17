import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { generateSlug, ProvisionCustomerForm } from "./ProvisionCustomerForm";

describe("ProvisionCustomerForm slug generation", () => {
  it("generates url-friendly slug from string", () => {
    expect(generateSlug("Acme Precision Engineering Pvt Ltd")).toBe("acme-precision-engineering-pvt-ltd");
    expect(generateSlug("  Special & Characters #123! ")).toBe("special-characters-123");
    expect(generateSlug("---Multiple---Dashes---")).toBe("multiple-dashes");
  });

  it("automatically generates URL-friendly slug from company name and shows workspace preview", async () => {
    const user = userEvent.setup();
    render(
      <ProvisionCustomerForm
        onCancel={vi.fn()}
        onSubmit={vi.fn()}
        onCreated={vi.fn()}
      />
    );

    const nameInput = screen.getByLabelText(/Customer group name/);
    await user.type(nameInput, "Alpha Tech Systems");

    const tenantSlugInput = screen.getByLabelText(/Tenant slug/) as HTMLInputElement;
    expect(tenantSlugInput.value).toBe("alpha-tech-systems");

    // Live URL preview visible in the form
    expect(screen.getByText("Company workspace slug")).toBeInTheDocument();
    expect(screen.getByText("alpha-tech-systems")).toBeInTheDocument();
  });

  it("allows admin to edit the slug before provisioning", async () => {
    const user = userEvent.setup();
    render(
      <ProvisionCustomerForm
        onCancel={vi.fn()}
        onSubmit={vi.fn()}
        onCreated={vi.fn()}
      />
    );

    const nameInput = screen.getByLabelText(/Customer group name/);
    await user.type(nameInput, "Beta Corp");

    const tenantSlugInput = screen.getByLabelText(/Tenant slug/) as HTMLInputElement;
    expect(tenantSlugInput.value).toBe("beta-corp");

    // Manually edit the slug
    await user.clear(tenantSlugInput);
    await user.type(tenantSlugInput, "custom-beta-slug");

    expect(tenantSlugInput.value).toBe("custom-beta-slug");
    expect(screen.getByText("custom-beta-slug")).toBeInTheDocument();
  });
});
