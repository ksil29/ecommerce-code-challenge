import { act, renderHook, waitFor } from "@testing-library/react";
import type { ReactNode } from "react";
import { MemoryRouter } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api } from "./api";
import { AuthProvider, useAuth } from "./auth";
import { CUSTOMER, GUEST } from "./test/fixtures";
import type { Me } from "./types";

function wrapper({ children }: { children: ReactNode }) {
  return (
    <MemoryRouter>
      <AuthProvider>{children}</AuthProvider>
    </MemoryRouter>
  );
}

describe("auth", () => {
  it("treats a failed session check as a guest", async () => {
    vi.spyOn(api, "me").mockRejectedValue(new TypeError("Failed to fetch"));

    const { result } = renderHook(() => useAuth(), { wrapper });

    await waitFor(() => expect(result.current.me).toEqual(GUEST));
  });

  it("signs a new customer in right after registration", async () => {
    vi.spyOn(api, "me").mockResolvedValueOnce(GUEST).mockResolvedValue(CUSTOMER);
    const register = vi.spyOn(api, "register").mockResolvedValue(CUSTOMER);
    const login = vi.spyOn(api, "login").mockResolvedValue(undefined);
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.me).toEqual(GUEST));

    let signedIn: Me | undefined;
    await act(async () => {
      signedIn = await result.current.register("ada@example.com", "Ada Lovelace", "slow green river under old bridges");
    });

    expect(register).toHaveBeenCalledWith("ada@example.com", "Ada Lovelace", "slow green river under old bridges");
    expect(login).toHaveBeenCalledWith("ada@example.com", "slow green river under old bridges");
    expect(signedIn).toEqual(CUSTOMER);
    expect(result.current.me).toEqual(CUSTOMER);
  });

  it("signs the customer out when any other call answers that the session is gone", async () => {
    vi.spyOn(api, "me").mockResolvedValue(CUSTOMER);
    vi.stubGlobal("fetch", vi.fn<typeof fetch>(async () => new Response(null, { status: 401 })));
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.me).toEqual(CUSTOMER));

    await act(() => api.myOrders().catch(() => undefined));

    await waitFor(() => expect(result.current.me).toEqual(GUEST));
  });

  it("becomes a guest again after logout", async () => {
    vi.spyOn(api, "me").mockResolvedValueOnce(CUSTOMER).mockResolvedValue(GUEST);
    const logout = vi.spyOn(api, "logout").mockResolvedValue(undefined);
    const { result } = renderHook(() => useAuth(), { wrapper });
    await waitFor(() => expect(result.current.me).toEqual(CUSTOMER));

    await act(() => result.current.logout());

    expect(logout).toHaveBeenCalled();
    expect(result.current.me).toEqual(GUEST);
  });
});
