import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api } from "./api";
import { App } from "./App";
import { AuthProvider } from "./auth";
import { CartProvider } from "./cart";
import { DEFAULT_SETTINGS, SettingsProvider } from "./settings";
import { ADMIN, CUSTOMER, GUEST, pageOf, product } from "./test/fixtures";
import type { Me, Settings } from "./types";

function openApp(url: string, me: Me = GUEST, settings: Settings = DEFAULT_SETTINGS) {
  vi.spyOn(api, "me").mockResolvedValue(me);
  vi.spyOn(api, "settings").mockResolvedValue(settings);
  render(
    <MemoryRouter initialEntries={[url]}>
      <SettingsProvider>
        <AuthProvider>
          <CartProvider>
            <App />
          </CartProvider>
        </AuthProvider>
      </SettingsProvider>
    </MemoryRouter>,
  );
}

async function expectSingleHeading(name: string) {
  const heading = await screen.findByRole("heading", { level: 1, name });
  expect(screen.getAllByRole("heading", { level: 1 })).toHaveLength(1);
  return heading;
}

describe("App", () => {
  it("shows a not-found page for an unknown address", () => {
    openApp("/no-such-page");

    expect(screen.getByRole("heading", { name: "Page not found" })).toBeInTheDocument();
    expect(document.title).toBe("Page not found · Shop");
  });

  it("opens the product list for /admin", async () => {
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    openApp("/admin", ADMIN);

    expect(await screen.findByRole("link", { name: "New product" })).toBeInTheDocument();
    await expectSingleHeading("Products");
    await waitFor(() => expect(String(vi.mocked(api.products).mock.lastCall?.[0])).toContain("includeArchived=true"));
  });

  it("shows the sign-in page inside the shop layout", () => {
    openApp("/login");

    expect(screen.getByRole("heading", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "API documentation" })).not.toBeInTheDocument();
  });

  it("links the API documentation only when the server publishes it", async () => {
    openApp("/login", GUEST, { ...DEFAULT_SETTINGS, apiDocs: true });

    expect(await screen.findByRole("link", { name: "API documentation" })).toHaveAttribute("href", "/swagger-ui.html");
  });

  it("names each page in the title and moves focus to its heading after a route change", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    vi.spyOn(api, "product").mockResolvedValue(product());
    openApp("/");

    const shop = await expectSingleHeading("Products");
    await waitFor(() => expect(document.title).toBe("Products · Shop"));
    expect(shop).not.toHaveFocus();

    await user.click(screen.getByRole("link", { name: /^Cart/ }));
    const cart = await expectSingleHeading("Your cart");
    await waitFor(() => expect(cart).toHaveFocus());
    expect(document.title).toBe("Cart · Shop");

    await user.click(screen.getByRole("link", { name: "Products" }));
    await user.click(await screen.findByRole("link", { name: "Bluetooth Speaker" }));
    const speaker = await expectSingleHeading("Bluetooth Speaker");
    await waitFor(() => expect(speaker).toHaveFocus());
    expect(document.title).toBe("Bluetooth Speaker · Shop");
  });

  it("sends a customer whose session expired to sign in again and back", async () => {
    vi.stubGlobal("fetch", vi.fn<typeof fetch>(async () => new Response(null, { status: 401 })));
    openApp("/account/orders?sort=newest", CUSTOMER);

    const heading = await expectSingleHeading("Sign in");
    expect(screen.getByRole("alert")).toHaveTextContent("Your session has expired. Sign in again.");
    expect(screen.getByRole("link", { name: "Create an account" })).toHaveAttribute(
      "href",
      "/register?next=%2Faccount%2Forders%3Fsort%3Dnewest",
    );
    expect(screen.getByRole("link", { name: "Sign in" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Log out" })).not.toBeInTheDocument();
    await waitFor(() => expect(heading).toHaveFocus());
  });
});
