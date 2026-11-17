import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import App from "./App";
import * as adminAuth from "./auth/adminAuth";

describe("Platform Admin App component", () => {
  it("renders the sign-in screen with Enterprise Light structure when signed out", async () => {
    vi.spyOn(adminAuth, "getMissingFirebaseConfig").mockReturnValue([]);
    vi.spyOn(adminAuth, "getAdminAuth").mockReturnValue({} as any);
    vi.spyOn(adminAuth, "observeAdminUser").mockImplementation((_auth, callback) => {
      callback(null);
      return () => undefined;
    });

    render(<App />);

    expect(await screen.findByRole("heading", { name: "Sign in to Platform Admin" })).toBeInTheDocument();
    expect(screen.getByText("INTERNAL OPERATIONS")).toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toBeInTheDocument();
    expect(screen.getByLabelText("Password")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Continue with Google" })).toBeInTheDocument();
    expect(screen.getByText(/Separate Platform Admin Firebase authentication/)).toBeInTheDocument();
  });

  it("displays authentication error when sign-in fails", async () => {
    vi.spyOn(adminAuth, "getMissingFirebaseConfig").mockReturnValue([]);
    vi.spyOn(adminAuth, "getAdminAuth").mockReturnValue({} as any);
    vi.spyOn(adminAuth, "observeAdminUser").mockImplementation((_auth, callback) => {
      callback(null);
      return () => undefined;
    });
    vi.spyOn(adminAuth, "signInWithEmail").mockRejectedValue({
      code: "auth/invalid-credential",
    });

    const user = userEvent.setup();
    render(<App />);

    await user.type(screen.getByLabelText("Email"), "admin@boss.local");
    await user.type(screen.getByLabelText("Password"), "wrongpassword");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("The email or password was not accepted.");
  });

  it("renders configuration error screen when Firebase config is missing", () => {
    vi.spyOn(adminAuth, "getMissingFirebaseConfig").mockReturnValue(["VITE_PLATFORM_ADMIN_FIREBASE_API_KEY"]);

    render(<App />);

    expect(screen.getByText("Platform Admin is not configured")).toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent("Provide the dedicated Platform Admin Firebase web configuration");
    expect(screen.getByText(/VITE_PLATFORM_ADMIN_FIREBASE_API_KEY/)).toBeInTheDocument();
  });
});
