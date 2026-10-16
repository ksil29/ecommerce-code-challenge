import { act, fireEvent, screen } from "@testing-library/react";
import { Route } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api } from "../api";
import { DEFAULT_SETTINGS } from "../settings";
import { order } from "../test/fixtures";
import { renderRoutes } from "../test/render";
import type { Settings } from "../types";
import { OrderPage } from "./OrderPage";

const pending = order({ status: "PENDING_PAYMENT", statusLabel: "Waiting for payment", paymentReference: null });

function openOrder(settings?: Settings) {
  renderRoutes(<Route path="/orders/:id" element={<OrderPage />} />, { url: `/orders/${pending.id}`, settings });
}

async function wait(seconds: number, times = 1) {
  for (let tick = 0; tick < times; tick++) {
    await act(() => vi.advanceTimersByTimeAsync(seconds * 1000));
  }
}

describe("OrderPage", () => {
  it("checks a pending payment every five seconds and announces the outcome", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const load = vi.spyOn(api, "order").mockResolvedValueOnce(pending).mockResolvedValue(order());
    openOrder();
    await wait(0);
    expect(screen.getByRole("status")).toHaveTextContent(/Waiting for payment.*has not confirmed the payment yet/);

    await wait(5);

    expect(screen.getByRole("status")).toHaveTextContent(/Paid.*Thank you, Ada Lovelace/);
    await wait(60);
    expect(load).toHaveBeenCalledTimes(2);
    expect(document.title).toBe("Order 0b6f3c1e · Shop");
  });

  it("checks every five seconds for a minute, then every thirty seconds until the timeout and two reconciliation runs have passed", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const load = vi.spyOn(api, "order").mockResolvedValue(pending);
    openOrder({ ...DEFAULT_SETTINGS, paymentTimeoutSeconds: 120, reconcileIntervalSeconds: 30 });
    await wait(0);
    expect(screen.getByText(/This page keeps checking/)).toBeInTheDocument();

    await wait(5, 12);
    expect(load).toHaveBeenCalledTimes(13);
    await wait(30, 4);
    expect(load).toHaveBeenCalledTimes(17);
    expect(screen.getByText(/This page keeps checking/)).toBeInTheDocument();
    await wait(30);
    expect(load).toHaveBeenCalledTimes(18);

    await wait(30, 10);
    expect(load).toHaveBeenCalledTimes(18);
    fireEvent.click(screen.getByRole("button", { name: "Check again" }));
    await wait(0);
    expect(load).toHaveBeenCalledTimes(19);
  });

  it("keeps checking for seventeen and a half minutes with the default settings", async () => {
    vi.useFakeTimers({ toFake: ["setTimeout", "clearTimeout"] });
    const load = vi.spyOn(api, "order").mockResolvedValue(pending);
    openOrder();
    await wait(0);

    await wait(5, 12);
    await wait(30, 32);
    expect(load).toHaveBeenCalledTimes(45);
    expect(screen.queryByRole("button", { name: "Check again" })).not.toBeInTheDocument();
    await wait(30);
    expect(load).toHaveBeenCalledTimes(46);
    expect(screen.getByRole("button", { name: "Check again" })).toBeInTheDocument();
    await wait(30, 5);
    expect(load).toHaveBeenCalledTimes(46);
  });

  it("explains a failed payment and offers another card", async () => {
    vi.spyOn(api, "order").mockResolvedValue(
      order({ status: "PAYMENT_FAILED", statusLabel: "Payment failed", paymentReference: null, failureReason: "Card declined" }),
    );
    openOrder();

    expect(await screen.findByText(/The payment did not go through: Card declined/)).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Try another card" })).toHaveAttribute("href", "/checkout");
  });

  it("explains an expired order", async () => {
    vi.spyOn(api, "order").mockResolvedValue(
      order({ status: "EXPIRED", statusLabel: "Expired", paymentReference: null, failureReason: "The payment was not confirmed in time" }),
    );
    openOrder();

    expect(await screen.findByText(/The order expired and the items went back to stock/)).toBeInTheDocument();
  });

  it("names the page and explains an order it cannot show", async () => {
    vi.spyOn(api, "order").mockRejectedValue(new Error(`Order ${pending.id} was not found`));
    openOrder();

    expect(await screen.findByRole("alert")).toHaveTextContent(`Order ${pending.id} was not found`);
    expect(screen.getByRole("heading", { level: 1, name: "Order" })).toBeInTheDocument();
  });
});
