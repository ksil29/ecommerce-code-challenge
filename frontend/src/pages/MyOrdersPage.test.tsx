import { screen } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { api } from "../api";
import { CUSTOMER, order } from "../test/fixtures";
import { renderPage } from "../test/render";
import { MyOrdersPage } from "./MyOrdersPage";

describe("MyOrdersPage", () => {
  it("lists the customer's orders with a link to each", async () => {
    vi.spyOn(api, "myOrders").mockResolvedValue([order()]);
    renderPage("/account/orders", <MyOrdersPage />, { me: CUSTOMER });

    expect(await screen.findByRole("link", { name: "0b6f3c1e" })).toHaveAttribute("href", "/orders/0b6f3c1e-8a52-4d0e-9a3b-2f6c1d7e9a10");
    expect(screen.getByText("$99.98")).toBeInTheDocument();
    expect(screen.getByText("Paid")).toBeInTheDocument();
  });

  it("points a new customer to the shop", async () => {
    vi.spyOn(api, "myOrders").mockResolvedValue([]);
    renderPage("/account/orders", <MyOrdersPage />, { me: CUSTOMER });

    expect(await screen.findByText(/No orders yet/)).toBeInTheDocument();
  });
});
