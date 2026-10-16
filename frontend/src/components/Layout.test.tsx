import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api } from "../api";
import { ADMIN, CUSTOMER, GUEST } from "../test/fixtures";
import { currentLocation, renderRoutes } from "../test/render";
import { AccountLayout, AdminLayout, Layout } from "./Layout";

const shop = (
  <Route element={<Layout />}>
    <Route path="/" element={<p>Shop</p>} />
    <Route path="/account/orders" element={<p>My order history</p>} />
  </Route>
);

const admin = (
  <Route path="/admin" element={<AdminLayout />}>
    <Route path="orders" element={<p>Recent orders</p>} />
  </Route>
);

describe("Layout", () => {
  it("shows the cart size and the customer's links", async () => {
    renderRoutes(shop, { me: CUSTOMER, cart: { 7: 2, 8: 1 } });

    expect(await screen.findByRole("link", { name: "My orders" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: /^Cart/ })).toHaveTextContent("Cart 3");
    expect(screen.getByText("Ada Lovelace")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Admin" })).not.toBeInTheDocument();
  });

  it("opens the menu on a narrow screen and closes it after a link is followed", async () => {
    const user = userEvent.setup();
    renderRoutes(shop, { me: CUSTOMER });
    const toggle = screen.getByRole("button", { name: "Menu" });
    const menu = document.getElementById(toggle.getAttribute("aria-controls") ?? "");
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(menu).not.toHaveClass("show");

    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    expect(menu).toHaveClass("show");
    await user.click(await screen.findByRole("link", { name: "My orders" }));

    await waitFor(() => expect(currentLocation()).toBe("/account/orders"));
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(menu).not.toHaveClass("show");
    await user.click(toggle);
    await user.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
  });

  it("signs out and returns to the shop", async () => {
    const user = userEvent.setup();
    const logout = vi.spyOn(api, "logout").mockResolvedValue(undefined);
    renderRoutes(shop, { me: CUSTOMER, url: "/account/orders" });
    const button = await screen.findByRole("button", { name: "Log out" });
    vi.mocked(api.me).mockResolvedValue(GUEST);

    await user.click(button);

    await waitFor(() => expect(currentLocation()).toBe("/"));
    expect(logout).toHaveBeenCalled();
    expect(screen.getByRole("link", { name: "Sign in" })).toBeInTheDocument();
  });

  it("says when signing out failed", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "logout").mockRejectedValue(new TypeError("Failed to fetch"));
    renderRoutes(shop, { me: CUSTOMER });

    await user.click(await screen.findByRole("button", { name: "Log out" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("Could not log out: Failed to fetch");
  });
});

describe("AdminLayout", () => {
  it("sends a guest to sign in and back, keeping the query", async () => {
    renderRoutes(admin, { url: "/admin/orders?page=2" });

    await waitFor(() => expect(currentLocation()).toBe("/login?next=%2Fadmin%2Forders%3Fpage%3D2"));
  });

  it("keeps customers out", async () => {
    renderRoutes(admin, { url: "/admin/orders", me: CUSTOMER });

    expect(await screen.findByText("This area is for administrators.")).toBeInTheDocument();
    expect(screen.getByRole("heading", { level: 1, name: "Admin" })).toBeInTheDocument();
    expect(screen.queryByText("Recent orders")).not.toBeInTheDocument();
  });

  it("shows the admin pages to an admin", async () => {
    renderRoutes(admin, { url: "/admin/orders", me: ADMIN });

    expect(await screen.findByText("Recent orders")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Import CSV" })).toBeInTheDocument();
  });
});

describe("AccountLayout", () => {
  it("asks a guest to sign in before showing the order history", async () => {
    renderRoutes(
      <Route path="/account" element={<AccountLayout />}>
        <Route path="orders" element={<p>My order history</p>} />
      </Route>,
      { url: "/account/orders" },
    );

    await waitFor(() => expect(currentLocation()).toBe("/login?next=%2Faccount%2Forders"));
    expect(screen.queryByText("My order history")).not.toBeInTheDocument();
  });
});
