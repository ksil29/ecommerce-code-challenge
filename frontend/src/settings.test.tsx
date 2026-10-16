import { renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { describe, expect, it, vi } from "vitest";
import { api } from "./api";
import { DEFAULT_SETTINGS, SettingsProvider, useSettings } from "./settings";

const SERVER_SETTINGS = {
  maxQuantity: 5,
  paymentTimeoutSeconds: 120,
  reconcileIntervalSeconds: 30,
  passwordMinLength: 15,
  passwordMaxLength: 64,
  apiDocs: true,
};

function wrapper({ children }: { children: ReactNode }) {
  return <SettingsProvider>{children}</SettingsProvider>;
}

describe("settings", () => {
  it("asks the server once and uses its answer", async () => {
    const settings = vi.spyOn(api, "settings").mockResolvedValue(SERVER_SETTINGS);

    const { result } = renderHook(() => useSettings(), { wrapper });

    expect(result.current).toEqual(DEFAULT_SETTINGS);
    await waitFor(() => expect(result.current).toEqual(SERVER_SETTINGS));
    expect(settings).toHaveBeenCalledTimes(1);
  });

  it("keeps the defaults when the server cannot be asked", async () => {
    const settings = vi.spyOn(api, "settings").mockRejectedValue(new TypeError("Failed to fetch"));

    const { result } = renderHook(() => useSettings(), { wrapper });

    await waitFor(() => expect(settings).toHaveBeenCalled());
    await expect(settings.mock.results[0]?.value).rejects.toThrow("Failed to fetch");
    expect(result.current).toEqual({
      maxQuantity: 99,
      paymentTimeoutSeconds: 900,
      reconcileIntervalSeconds: 60,
      passwordMinLength: 12,
      passwordMaxLength: 128,
      apiDocs: false,
    });
  });

  it("falls back to the default for a value that is missing or makes no sense", async () => {
    vi.spyOn(api, "settings").mockResolvedValue({
      maxQuantity: 0,
      paymentTimeoutSeconds: 1.5,
      reconcileIntervalSeconds: 45,
      apiDocs: "yes",
    } as unknown as typeof SERVER_SETTINGS);

    const { result } = renderHook(() => useSettings(), { wrapper });

    await waitFor(() => expect(result.current.reconcileIntervalSeconds).toBe(45));
    expect(result.current).toEqual({ ...DEFAULT_SETTINGS, reconcileIntervalSeconds: 45 });
  });

  it("works without a provider", () => {
    const { result } = renderHook(() => useSettings());

    expect(result.current).toEqual(DEFAULT_SETTINGS);
  });
});
