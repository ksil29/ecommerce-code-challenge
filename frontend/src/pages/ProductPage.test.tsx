import { fireEvent, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../api";
import { DEFAULT_SETTINGS } from "../settings";
import { product } from "../test/fixtures";
import { renderRoutes, storedCart } from "../test/render";
import type { Settings } from "../types";
import { ProductPage } from "./ProductPage";

function openProduct(sku: string, settings?: Settings) {
  renderRoutes(<Route path="/products/:sku" element={<ProductPage />} />, { url: `/products/${sku}`, settings });
}

describe("ProductPage", () => {
  it("adds the chosen quantity, but no more than the units not held for other orders", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "product").mockResolvedValue(product({ stock: 5, reserved: 2 }));
    openProduct("BS-099");

    fireEvent.change(await screen.findByLabelText("Quantity"), { target: { value: "5" } });
    expect(screen.getByLabelText("Quantity")).toHaveValue(3);
    expect(screen.getByText("Only 3 left")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Add to cart" }));

    expect(screen.getByRole("status")).toHaveTextContent("Added to the cart.");
    expect(storedCart()).toEqual({ 7: 3 });
    expect(document.title).toBe("Bluetooth Speaker · Shop");
  });

  it("keeps the quantity within the shop's limit", async () => {
    vi.spyOn(api, "product").mockResolvedValue(product({ stock: 50 }));
    openProduct("BS-099", { ...DEFAULT_SETTINGS, maxQuantity: 4 });

    fireEvent.change(await screen.findByLabelText("Quantity"), { target: { value: "9" } });

    expect(screen.getByLabelText("Quantity")).toHaveValue(4);
  });

  it("does not offer a product whose units are all held for other orders", async () => {
    vi.spyOn(api, "product").mockResolvedValue(product({ stock: 2, reserved: 2 }));
    openProduct("BS-099");

    expect(await screen.findByText("Out of stock")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Add to cart" })).not.toBeInTheDocument();
  });

  it("explains a product that does not exist", async () => {
    vi.spyOn(api, "product").mockRejectedValue(new ApiError(404, { detail: "Product XX-000 was not found" }));
    openProduct("XX-000");

    expect(await screen.findByRole("alert")).toHaveTextContent(/Product XX-000 was not found/);
    expect(screen.getByRole("heading", { level: 1, name: "Product" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Back to the shop" })).toHaveAttribute("href", "/");
  });
});
