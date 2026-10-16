import { render, screen } from "@testing-library/react";
import type { ReactElement, ReactNode } from "react";
import { MemoryRouter, Route, Routes, useLocation, type InitialEntry } from "react-router";
import { vi } from "vitest";
import { api } from "../api";
import { AuthProvider } from "../auth";
import { CartProvider } from "../cart";
import { DEFAULT_SETTINGS, SettingsProvider } from "../settings";
import type { Me, Settings } from "../types";
import { GUEST } from "./fixtures";

interface Options {
  url?: InitialEntry;
  me?: Me | Promise<Me>;
  cart?: Record<number, number>;
  settings?: Settings;
}

function CurrentLocation() {
  const { pathname, search, state } = useLocation();
  const notice = (state as { notice?: string } | null)?.notice;
  return (
    <div data-testid="location" data-notice={notice}>
      {pathname}
      {search}
    </div>
  );
}

export function renderRoutes(routes: ReactNode, { url = "/", me = GUEST, cart, settings = DEFAULT_SETTINGS }: Options = {}) {
  vi.spyOn(api, "me").mockImplementation(async () => me);
  vi.spyOn(api, "settings").mockResolvedValue(settings);
  if (cart) window.localStorage.setItem("shop.cart", JSON.stringify(cart));
  return render(
    <MemoryRouter initialEntries={[url]}>
      <SettingsProvider>
        <AuthProvider>
          <CartProvider>
            <Routes>
              {routes}
              <Route path="*" element={null} />
            </Routes>
            <CurrentLocation />
          </CartProvider>
        </AuthProvider>
      </SettingsProvider>
    </MemoryRouter>,
  );
}

export function renderPage(path: string, page: ReactElement, options: Options = {}) {
  return renderRoutes(<Route path={path} element={page} />, { url: path, ...options });
}

export function currentLocation(): string {
  return screen.getByTestId("location").textContent ?? "";
}

export function currentNotice(): string | null {
  return screen.getByTestId("location").getAttribute("data-notice");
}

export function storedCart(): unknown {
  return JSON.parse(window.localStorage.getItem("shop.cart") ?? "null");
}
