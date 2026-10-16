import { screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api } from "../../api";
import { ADMIN, order } from "../../test/fixtures";
import { renderPage } from "../../test/render";
import { AdminOrdersPage } from "./AdminOrdersPage";

describe("AdminOrdersPage", () => {
  it("shows the payment reference or the reason it failed, and refreshes", async () => {
    const user = userEvent.setup();
    const orders = vi.spyOn(api.admin, "orders").mockResolvedValue([
      order(),
      order({
        id: "7d1e5a90-1c2b-4f3e-8a6d-5b4c3d2e1f00",
        status: "PAYMENT_FAILED",
        statusLabel: "Payment failed",
        paymentReference: null,
        failureReason: "Card declined",
      }),
    ]);
    renderPage("/admin/orders", <AdminOrdersPage />, { me: ADMIN });

    expect(await screen.findByText("PAY-7Q2K")).toBeInTheDocument();
    expect(screen.getByText("Card declined")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "7d1e5a90" })).toHaveAttribute("href", "/orders/7d1e5a90-1c2b-4f3e-8a6d-5b4c3d2e1f00");

    await user.click(screen.getByRole("button", { name: "Refresh" }));

    expect(orders).toHaveBeenCalledTimes(2);
  });

  it("says when there are no orders", async () => {
    vi.spyOn(api.admin, "orders").mockResolvedValue([]);
    renderPage("/admin/orders", <AdminOrdersPage />, { me: ADMIN });

    expect(await screen.findByText("No orders yet.")).toBeInTheDocument();
  });
});
