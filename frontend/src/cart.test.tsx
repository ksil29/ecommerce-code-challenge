import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { api } from "./api";
import { CartProvider, useCart } from "./cart";
import { DEFAULT_SETTINGS, SettingsProvider } from "./settings";

function wrapper({ children }: { children: ReactNode }) {
  return <CartProvider>{children}</CartProvider>;
}

function withSettings({ children }: { children: ReactNode }) {
  return (
    <SettingsProvider>
      <CartProvider>{children}</CartProvider>
    </SettingsProvider>
  );
}

describe("cart", () => {
  it("keeps the cart between visits", () => {
    const first = renderHook(() => useCart(), { wrapper });
    act(() => first.result.current.add(7, 2));
    first.unmount();

    const { result } = renderHook(() => useCart(), { wrapper });

    expect(result.current.items).toEqual({ 7: 2 });
    expect(result.current.count).toBe(2);
  });

  it("follows a change made in another tab", () => {
    const { result } = renderHook(() => useCart(), { wrapper });

    act(() => {
      window.localStorage.setItem("shop.cart", JSON.stringify({ 8: 3 }));
      window.dispatchEvent(new StorageEvent("storage", { key: "shop.cart" }));
    });

    expect(result.current.items).toEqual({ 8: 3 });
  });

  it("keeps every line within the shop's quantity limit once the settings arrive", async () => {
    vi.spyOn(api, "settings").mockResolvedValue({ ...DEFAULT_SETTINGS, maxQuantity: 5 });
    window.localStorage.setItem("shop.cart", JSON.stringify({ 7: 8 }));
    const { result } = renderHook(() => useCart(), { wrapper: withSettings });

    await waitFor(() => expect(result.current.items).toEqual({ 7: 5 }));
    act(() => result.current.add(8, 9));

    expect(result.current.items).toEqual({ 7: 5, 8: 5 });
    expect(result.current.count).toBe(10);
    expect(JSON.parse(window.localStorage.getItem("shop.cart") ?? "null")).toEqual({ 7: 5, 8: 5 });
  });

  it("still works when the browser refuses storage", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new DOMException("Storage is disabled", "SecurityError");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new DOMException("Storage is full", "QuotaExceededError");
    });
    const { result } = renderHook(() => useCart(), { wrapper });

    act(() => result.current.add(7, 1));

    expect(result.current.count).toBe(1);
  });
});
