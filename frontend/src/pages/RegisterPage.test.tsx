import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../api";
import { DEFAULT_SETTINGS } from "../settings";
import { CUSTOMER } from "../test/fixtures";
import { currentLocation, renderPage } from "../test/render";
import { RegisterPage } from "./RegisterPage";

describe("RegisterPage", () => {
  it("counts an emoji as one character, like the server, and can show the password", async () => {
    const user = userEvent.setup();
    renderPage("/register", <RegisterPage />);

    fireEvent.change(screen.getByLabelText("Password"), { target: { value: "żółw 🐢" } });

    expect(screen.getByText(/\(6 so far\)/)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Show" }));
    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "text");
    expect(document.title).toBe("Create an account · Shop");
  });

  it("takes the password length limits from the server", async () => {
    renderPage("/register", <RegisterPage />, { settings: { ...DEFAULT_SETTINGS, passwordMinLength: 15, passwordMaxLength: 20 } });
    const password = screen.getByLabelText("Password");

    expect(await screen.findByText(/Use 15 to 20 characters \(0 so far\)/)).not.toHaveClass("text-warning-emphasis");
    fireEvent.change(password, { target: { value: "fourteen chars" } });
    expect(screen.getByText(/\(14 so far\)/)).toHaveClass("text-warning-emphasis");
    fireEvent.change(password, { target: { value: "fifteen letters" } });
    expect(screen.getByText(/\(15 so far\)/)).not.toHaveClass("text-warning-emphasis");
    fireEvent.change(password, { target: { value: "twenty one characters" } });
    expect(screen.getByText(/\(21 so far\)/)).toHaveClass("text-warning-emphasis");
    expect(password).toHaveAccessibleDescription(/Use 15 to 20 characters/);
  });

  it("shows the default password length limits until the server answers", () => {
    renderPage("/register", <RegisterPage />);

    expect(screen.getByText(/Use 12 to 128 characters/)).toBeInTheDocument();
  });

  it("signs the new customer in and returns to the page that asked for it", async () => {
    const user = userEvent.setup();
    const register = vi.spyOn(api, "register").mockResolvedValue(CUSTOMER);
    vi.spyOn(api, "login").mockResolvedValue(undefined);
    renderPage("/register", <RegisterPage />, { url: "/register?next=%2Fcheckout" });
    await user.type(screen.getByLabelText("Email"), "ada@example.com");
    await user.type(screen.getByLabelText("Name"), "Ada Lovelace");
    await user.type(screen.getByLabelText("Password"), "slow green river under old bridges");
    vi.mocked(api.me).mockResolvedValue(CUSTOMER);

    await user.click(screen.getByRole("button", { name: "Create account" }));

    await waitFor(() => expect(currentLocation()).toBe("/checkout"));
    expect(register).toHaveBeenCalledWith("ada@example.com", "Ada Lovelace", "slow green river under old bridges");
  });

  it("keeps the return address on the link to sign in instead", () => {
    renderPage("/register", <RegisterPage />, { url: "/register?next=%2Fcheckout" });

    expect(screen.getByRole("link", { name: "Sign in" })).toHaveAttribute("href", "/login?next=%2Fcheckout");
  });

  it("shows the server's reasons next to the fields and moves focus to the first one", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "register").mockRejectedValue(
      new ApiError(400, { detail: "The request is not valid", errors: [{ field: "password", message: "This password is too common" }] }),
    );
    renderPage("/register", <RegisterPage />);

    await user.type(screen.getByLabelText("Email"), "ada@example.com");
    await user.type(screen.getByLabelText("Name"), "Ada Lovelace");
    await user.type(screen.getByLabelText("Password"), "password1234");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByText("This password is too common")).toBeInTheDocument();
    const password = screen.getByLabelText("Password");
    await waitFor(() => expect(password).toHaveFocus());
    expect(password).toHaveClass("is-invalid");
    expect(password).toHaveAttribute("aria-invalid", "true");
    expect(password).toHaveAccessibleDescription(/^This password is too common Use 12 to 128 characters/);
    expect(screen.getByLabelText("Email")).not.toHaveAttribute("aria-invalid");
    expect(screen.getByRole("alert")).toHaveTextContent("The request is not valid");
  });

  it("explains a taken email once, next to the field", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "register").mockRejectedValue(
      new ApiError(409, {
        detail: "An account with this email already exists",
        code: "EMAIL_TAKEN",
        errors: [{ field: "email", message: "An account with this email already exists" }],
      }),
    );
    renderPage("/register", <RegisterPage />);

    await user.type(screen.getByLabelText("Email"), "ada@example.com");
    await user.click(screen.getByRole("button", { name: "Create account" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/^An account with this email already exists$/);
    await waitFor(() => expect(screen.getByLabelText("Email")).toHaveFocus());
    expect(screen.getByLabelText("Email")).toHaveAccessibleDescription("An account with this email already exists");
  });
});
