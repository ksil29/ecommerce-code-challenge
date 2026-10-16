import { fireEvent, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../api";
import { DEFAULT_SETTINGS } from "../settings";
import { product, quoteFor } from "../test/fixtures";
import { renderPage, renderRoutes, storedCart } from "../test/render";
import { CartPage } from "./CartPage";

const speaker = product();

describe("CartPage", () => {
  it("takes products that left the catalog out of the cart and says so", async () => {
    const quote = vi
      .spyOn(api, "quote")
      .mockResolvedValueOnce(quoteFor([{ product: speaker, quantity: 1 }], [9]))
      .mockResolvedValue(quoteFor([{ product: speaker, quantity: 1 }]));
    renderPage("/cart", <CartPage />, { cart: { 7: 1, 9: 1 } });

    await waitFor(() => expect(storedCart()).toEqual({ 7: 1 }));
    await waitFor(() => expect(quote).toHaveBeenCalledTimes(2));

    expect(await screen.findByText("Some products were removed from the catalog and taken out of your cart.")).toBeInTheDocument();
  });

  it("marks lines that cannot be bought and holds back the checkout", async () => {
    vi.spyOn(api, "quote").mockResolvedValue(
      quoteFor([
        { product: product({ stock: 6, reserved: 4 }), quantity: 5, available: false },
        { product: product({ id: 8, sku: "CS-020", name: "Chess Set", archived: true }), quantity: 1, available: false },
      ]),
    );
    renderPage("/cart", <CartPage />, { cart: { 7: 5, 8: 1 } });

    expect(await screen.findByText("Only 2 in stock")).toBeInTheDocument();
    expect(screen.getByText("No longer sold")).toBeInTheDocument();
    expect(screen.getByText("Change or remove the lines marked above to check out")).toBeInTheDocument();
    expect(screen.queryByRole("link", { name: "Checkout" })).not.toBeInTheDocument();
  });

  it("changes a quantity and asks for a new quote", async () => {
    const quote = vi.spyOn(api, "quote").mockResolvedValue(quoteFor([{ product: speaker, quantity: 1 }]));
    renderPage("/cart", <CartPage />, { cart: { 7: 1 } });

    fireEvent.change(await screen.findByLabelText("Quantity of Bluetooth Speaker"), { target: { value: "3" } });

    await waitFor(() => expect(quote).toHaveBeenLastCalledWith([{ productId: 7, quantity: 3 }]));
    expect(storedCart()).toEqual({ 7: 3 });
  });

  it("keeps a quantity within the shop's limit", async () => {
    vi.spyOn(api, "quote").mockResolvedValue(quoteFor([{ product: speaker, quantity: 1 }]));
    renderPage("/cart", <CartPage />, { cart: { 7: 1 }, settings: { ...DEFAULT_SETTINGS, maxQuantity: 5 } });
    const field = await screen.findByLabelText("Quantity of Bluetooth Speaker");
    await waitFor(() => expect(field).toHaveAttribute("max", "5"));

    fireEvent.change(field, { target: { value: "8" } });

    expect(storedCart()).toEqual({ 7: 5 });
  });

  it("names a quantity the server refuses", async () => {
    vi.spyOn(api, "quote").mockRejectedValue(
      new ApiError(400, {
        detail: "The request is not valid",
        errors: [{ field: "items[0].quantity", message: "Choose a quantity from 1 to 99" }],
      }),
    );
    renderPage("/cart", <CartPage />, { cart: { 7: 1 } });

    expect(await screen.findByRole("alert")).toHaveTextContent("The request is not valid: Choose a quantity from 1 to 99");
  });

  it("keeps what the shopper types while the cart is priced again", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "quote").mockResolvedValue(quoteFor([{ product: speaker, quantity: 1 }]));
    renderPage("/cart", <CartPage />, { cart: { 7: 1 } });
    const field = await screen.findByLabelText("Quantity of Bluetooth Speaker");

    await user.clear(field);
    await user.type(field, "25");

    expect(field).toHaveValue(25);
    expect(storedCart()).toEqual({ 7: 25 });
  });

  it("removes a line from a column that screen readers can name", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "quote").mockResolvedValue(quoteFor([{ product: speaker, quantity: 1 }]));
    renderPage("/cart", <CartPage />, { cart: { 7: 1 } });

    expect(await screen.findByRole("columnheader", { name: "Actions" })).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Remove" }));

    expect(screen.getByText(/The cart is empty/)).toBeInTheDocument();
    expect(storedCart()).toEqual({});
    expect(document.title).toBe("Cart · Shop");
  });

  it("explains why checkout sent the shopper back", async () => {
    vi.spyOn(api, "quote").mockResolvedValue(quoteFor([{ product: speaker, quantity: 1 }]));
    renderRoutes(<Route path="/cart" element={<CartPage />} />, {
      url: { pathname: "/cart", state: { notice: "Some items are no longer available in that quantity. Please review your cart." } },
      cart: { 7: 1 },
    });

    expect(screen.getByRole("alert")).toHaveTextContent("Some items are no longer available in that quantity. Please review your cart.");
    expect(await screen.findByRole("button", { name: "Remove" })).toBeInTheDocument();
  });
});
