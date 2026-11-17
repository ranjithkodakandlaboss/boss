import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi, beforeEach } from "vitest";
import { AccountActivationScreen } from "./AccountActivationScreen";
import * as platformAdminApi from "../api/platformAdminApi";

describe("AccountActivationScreen", () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it("shows an error when no invitation token is provided", async () => {
    render(<AccountActivationScreen token="" />);
    expect(await screen.findByRole("alert")).toHaveTextContent("No invitation token was provided");
  });

  it("renders activation form when token is valid", async () => {
    vi.spyOn(platformAdminApi, "validateInviteToken").mockResolvedValue({
      valid: true,
      inviteId: "invite-123",
      tenantId: "tenant-123",
      tenantName: "Acme Precision Forging",
      tenantSlug: "acme-precision",
      email: "owner@acme.example",
      role: "OWNER",
      status: "pending",
      expiresAt: "2026-12-01T00:00:00Z",
      isExpired: false,
    });

    render(<AccountActivationScreen token="valid-secret-token" />);

    expect(await screen.findByRole("heading", { name: "Activate your account" })).toBeInTheDocument();
    expect(screen.getByText("Acme Precision Forging")).toBeInTheDocument();
    expect(screen.getByLabelText("Email address")).toHaveValue("owner@acme.example");
    expect(screen.getByLabelText(/Create password/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Confirm password/)).toBeInTheDocument();
  });

  it("validates password matching before submitting", async () => {
    vi.spyOn(platformAdminApi, "validateInviteToken").mockResolvedValue({
      valid: true,
      inviteId: "invite-123",
      tenantId: "tenant-123",
      tenantName: "Acme Precision Forging",
      tenantSlug: "acme-precision",
      email: "owner@acme.example",
      role: "OWNER",
      status: "pending",
      expiresAt: "2026-12-01T00:00:00Z",
      isExpired: false,
    });

    const user = userEvent.setup();
    render(<AccountActivationScreen token="valid-secret-token" />);

    await screen.findByRole("heading", { name: "Activate your account" });
    await user.type(screen.getByLabelText(/Create password/), "Password123!");
    await user.type(screen.getByLabelText(/Confirm password/), "Mismatch123!");
    await user.click(screen.getByRole("button", { name: /Activate account & join workspace/ }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Passwords do not match");
  });

  it("successfully activates account and shows success screen", async () => {
    vi.spyOn(platformAdminApi, "validateInviteToken").mockResolvedValue({
      valid: true,
      inviteId: "invite-123",
      tenantId: "tenant-123",
      tenantName: "Acme Precision Forging",
      tenantSlug: "acme-precision",
      email: "owner@acme.example",
      role: "OWNER",
      status: "pending",
      expiresAt: "2026-12-01T00:00:00Z",
      isExpired: false,
    });

    const activateMock = vi.spyOn(platformAdminApi, "activateCustomerAccount").mockResolvedValue({
      activated: true,
      userId: "user-123",
      tenantId: "tenant-123",
      tenantSlug: "acme-precision",
      status: "active",
    });

    const user = userEvent.setup();
    render(<AccountActivationScreen token="valid-secret-token" />);

    await screen.findByRole("heading", { name: "Activate your account" });
    await user.type(screen.getByLabelText("Full name"), "John Acme");
    await user.type(screen.getByLabelText(/Create password/), "SecureP@ss123");
    await user.type(screen.getByLabelText(/Confirm password/), "SecureP@ss123");
    await user.click(screen.getByRole("button", { name: /Activate account & join workspace/ }));

    await waitFor(() => {
      expect(activateMock).toHaveBeenCalledWith(
        platformAdminApi.platformAdminApiBaseUrl(),
        expect.objectContaining({
          token: "valid-secret-token",
          displayName: "John Acme",
        }),
      );
    });

    expect(await screen.findByText("Account activated successfully!")).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Welcome to Acme Precision Forging" })).toBeInTheDocument();
    expect(screen.getByText("acme-precision")).toBeInTheDocument();
  });
});
