import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError, SESSION_EXPIRED } from "../api";
import { ADMIN, CUSTOMER } from "../test/fixtures";
import { currentLocation, renderPage, renderRoutes } from "../test/render";
import { landingFor, LoginPage, safeNext } from "./LoginPage";

describe("safeNext", () => {
  it("keeps a path inside the shop", () => {
    expect(safeNext("/checkout")).toBe("/checkout");
    expect(safeNext("/admin/products?q=chess#top")).toBe("/admin/products?q=chess#top");
  });

  it.each([null, "", "checkout", "https://evil.example", "//evil.example", "/\\evil.example", "/\t/evil.example", "/\\[", "javascript:alert(1)"])(
    "drops %j because it leaves the shop",
    (next) => {
      expect(safeNext(next)).toBeNull();
    },
  );
});

describe("landingFor", () => {
  it("keeps customers out of the admin area", () => {
    expect(landingFor(CUSTOMER, "/admin/orders")).toBe("/");
    expect(landingFor(CUSTOMER, "/checkout")).toBe("/checkout");
    expect(landingFor(ADMIN, "/admin/orders")).toBe("/admin/orders");
    expect(landingFor(ADMIN, null)).toBe("/admin/products");
  });
});

describe("LoginPage", () => {
  it("explains the lockout after a wrong password and marks both fields", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "login").mockRejectedValue(new ApiError(401, {}));
    renderPage("/login", <LoginPage />);

    await user.type(screen.getByLabelText("Email (or admin username)"), "ada@example.com");
    await user.type(screen.getByLabelText("Password"), "wrong password");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/After 5 failed attempts the account is locked for 15 minutes/);
    const email = screen.getByLabelText("Email (or admin username)");
    await waitFor(() => expect(email).toHaveFocus());
    for (const field of [email, screen.getByLabelText("Password")]) {
      expect(field).toHaveAttribute("aria-invalid", "true");
      expect(field).toHaveAccessibleDescription(/Wrong email or password/);
    }
    expect(currentLocation()).toBe("/login");
  });

  it("moves focus to the message when the failure is not about the fields", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "login").mockRejectedValue(new ApiError(429, { detail: "Too many requests. Try again in 30 seconds." }));
    renderPage("/login", <LoginPage />);

    await user.type(screen.getByLabelText("Email (or admin username)"), "ada@example.com");
    await user.type(screen.getByLabelText("Password"), "slow green river under old bridges");
    await user.click(screen.getByRole("button", { name: "Sign in" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("Too many requests. Try again in 30 seconds.");
    await waitFor(() => expect(alert).toHaveFocus());
    expect(screen.getByLabelText("Password")).not.toHaveAttribute("aria-invalid");
  });

  it("says why the customer has to sign in again", () => {
    renderRoutes(<Route path="/login" element={<LoginPage />} />, {
      url: { pathname: "/login", search: "?next=%2Faccount%2Forders", state: { notice: SESSION_EXPIRED } },
    });

    expect(screen.getByRole("alert")).toHaveTextContent("Your session has expired. Sign in again.");
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute("href", "/register?next=%2Faccount%2Forders");
    expect(document.title).toBe("Sign in · Shop");
  });

  it("returns the customer to the page that asked for a sign-in", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "login").mockResolvedValue(undefined);
    renderPage("/login", <LoginPage />, { url: "/login?next=%2Fcheckout" });
    await user.type(screen.getByLabelText("Email (or admin username)"), "ada@example.com");
    await user.type(screen.getByLabelText("Password"), "slow green river under old bridges");
    vi.mocked(api.me).mockResolvedValue(CUSTOMER);

    await user.click(screen.getByRole("button", { name: "Sign in" }));

    await waitFor(() => expect(currentLocation()).toBe("/checkout"));
  });
});
