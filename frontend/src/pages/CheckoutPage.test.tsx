import { act, screen, waitFor } from "@testing-library/react";
import userEvent, { type UserEvent } from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../api";
import { CUSTOMER, order, product, quoteFor } from "../test/fixtures";
import { currentLocation, currentNotice, renderPage, storedCart } from "../test/render";
import type { Me } from "../types";
import { CheckoutPage } from "./CheckoutPage";

const speaker = product();
const twoSpeakers = quoteFor([{ product: speaker, quantity: 2 }]);
const LOST_ANSWER = "We did not get an answer from the server. Press Pay again; you will not be charged twice.";

function openCheckout(me?: Me) {
  vi.spyOn(api, "quote").mockResolvedValue(twoSpeakers);
  return renderPage("/checkout", <CheckoutPage />, { me, cart: { 7: 2 } });
}

async function fillIn(user: UserEvent, card = "4242 4242 4242 4242") {
  await user.type(await screen.findByLabelText("Name"), "Grace Hopper");
  await user.type(screen.getByLabelText("Email"), "grace@example.com");
  await user.type(screen.getByLabelText("Card number"), card);
}

function storedAttempt(): unknown {
  return JSON.parse(window.sessionStorage.getItem("shop.checkout") ?? "null");
}

function keysSent(placeOrder: { mock: { calls: [string, ...unknown[]][] } }): string[] {
  return placeOrder.mock.calls.map(([key]) => key);
}

describe("CheckoutPage", () => {
  it("pays, empties the cart and opens the order", async () => {
    const user = userEvent.setup();
    const placeOrder = vi.spyOn(api, "placeOrder").mockResolvedValue(order({ id: "o-1" }));
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-1"));
    expect(placeOrder).toHaveBeenCalledWith(expect.stringMatching(/^[0-9a-f-]{36}$/), {
      items: [{ productId: 7, quantity: 2 }],
      expectedTotal: "99.98",
      customerName: "Grace Hopper",
      customerEmail: "grace@example.com",
      cardNumber: "4242 4242 4242 4242",
    });
    expect(storedCart()).toEqual({});
    expect(storedAttempt()).toBeNull();
    expect(document.title).toBe("Checkout · Shop");
  });

  it("keeps the cart when the card is declined and starts a new attempt for the next card", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "placeOrder").mockResolvedValue(order({ id: "o-2", status: "PAYMENT_FAILED", statusLabel: "Payment failed" }));
    openCheckout();
    await user.type(await screen.findByLabelText("Name"), "Grace Hopper");
    await user.type(screen.getByLabelText("Email"), "grace@example.com");

    await user.click(screen.getByRole("button", { name: "Use card 4000 0000 0000 0002" }));
    expect(screen.getByLabelText("Card number")).toHaveValue("4000 0000 0000 0002");
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-2"));
    expect(storedCart()).toEqual({ 7: 2 });
    expect(storedAttempt()).toBeNull();
  });

  it("shows the new total and asks again with a new attempt when a price changed", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "quote")
      .mockResolvedValueOnce(twoSpeakers)
      .mockResolvedValue(quoteFor([{ product: product({ price: "54.99", version: 4 }), quantity: 2 }]));
    const placeOrder = vi
      .spyOn(api, "placeOrder")
      .mockRejectedValueOnce(new ApiError(409, { detail: "The order total changed to 109.98", code: "PRICE_CHANGED", total: "109.98" }))
      .mockResolvedValue(order({ id: "o-5" }));
    renderPage("/checkout", <CheckoutPage />, { cart: { 7: 2 } });

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    expect(await screen.findByText("The total changed to $109.98. Check the order and confirm again.")).toHaveFocus();
    expect(screen.getByLabelText("Card number")).toHaveValue("4242 4242 4242 4242");
    await user.click(await screen.findByRole("button", { name: "Pay $109.98" }));
    await waitFor(() => expect(currentLocation()).toBe("/orders/o-5"));
    const [first, second] = keysSent(placeOrder);
    expect(second).not.toBe(first);
    expect(placeOrder).toHaveBeenLastCalledWith(second, expect.objectContaining({ expectedTotal: "109.98" }));
  });

  it("sends the shopper back to the cart when the stock ran out", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "placeOrder").mockRejectedValue(
      new ApiError(409, { detail: "Some items are no longer available in that quantity", code: "OUT_OF_STOCK", productId: 7 }),
    );
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/cart"));
    expect(currentNotice()).toBe("Some items are no longer available in that quantity. Please review your cart.");
  });

  it("shows the server's checks next to the fields and moves focus to the first one", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "placeOrder").mockRejectedValue(
      new ApiError(400, {
        detail: "The request is not valid",
        errors: [
          { field: "customerEmail", message: "Enter a valid email address" },
          { field: "cardNumber", message: "Card number is not valid" },
        ],
      }),
    );
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    const email = screen.getByLabelText("Email");
    await waitFor(() => expect(email).toHaveFocus());
    expect(email).toHaveClass("is-invalid");
    expect(email).toHaveAttribute("aria-invalid", "true");
    expect(email).toHaveAccessibleDescription("Enter a valid email address");
    expect(screen.getByLabelText("Card number")).toHaveAccessibleDescription("Card number is not valid");
    expect(screen.getByLabelText("Name")).not.toHaveAttribute("aria-invalid");
    expect(screen.getByRole("alert")).toHaveTextContent("The request is not valid");
  });

  it("names a quantity the server refuses, since no field shows it", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "placeOrder").mockRejectedValue(
      new ApiError(400, {
        detail: "The request is not valid",
        errors: [{ field: "items[0].quantity", message: "Choose a quantity from 1 to 99" }],
      }),
    );
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("The request is not valid: Choose a quantity from 1 to 99");
    await waitFor(() => expect(alert).toHaveFocus());
  });

  it("asks to press Pay again after a lost answer and retries with the same idempotency key", async () => {
    const user = userEvent.setup();
    const placeOrder = vi
      .spyOn(api, "placeOrder")
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValue(order({ id: "o-3" }));
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent(LOST_ANSWER);
    await waitFor(() => expect(alert).toHaveFocus());
    expect(screen.getByLabelText("Name")).toHaveValue("Grace Hopper");
    expect(screen.getByLabelText("Card number")).toHaveValue("4242 4242 4242 4242");
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-3"));
    const [first, second] = keysSent(placeOrder);
    expect(first).toMatch(/^[0-9a-f-]{36}$/);
    expect(second).toBe(first);
  });

  it("treats a server error as a lost answer too", async () => {
    const user = userEvent.setup();
    const placeOrder = vi
      .spyOn(api, "placeOrder")
      .mockRejectedValueOnce(new ApiError(502, { detail: "Unexpected response (502)" }))
      .mockResolvedValue(order({ id: "o-6" }));
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(LOST_ANSWER);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-6"));
    const [first, second] = keysSent(placeOrder);
    expect(second).toBe(first);
  });

  it("reuses the key after a reload of the page while the cart and total stay the same", async () => {
    const user = userEvent.setup();
    const placeOrder = vi
      .spyOn(api, "placeOrder")
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValue(order({ id: "o-7" }));
    const { unmount } = openCheckout();
    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(LOST_ANSWER);
    unmount();

    openCheckout();
    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-7"));
    const [first, second] = keysSent(placeOrder);
    expect(second).toBe(first);
    expect(storedAttempt()).toBeNull();
  });

  it("starts a new attempt when the cart changed after a lost answer", async () => {
    const user = userEvent.setup();
    const placeOrder = vi
      .spyOn(api, "placeOrder")
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValue(order({ id: "o-8" }));
    const { unmount } = openCheckout();
    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(LOST_ANSWER);
    unmount();

    vi.spyOn(api, "quote").mockResolvedValue(quoteFor([{ product: speaker, quantity: 3 }]));
    renderPage("/checkout", <CheckoutPage />, { cart: { 7: 3 } });
    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $149.97" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-8"));
    const [first, second] = keysSent(placeOrder);
    expect(second).not.toBe(first);
  });

  it("shows why a reused key was refused and starts a new attempt on the next Pay", async () => {
    const user = userEvent.setup();
    const detail = "This Idempotency-Key was already used for a different purchase. Start a new checkout.";
    const placeOrder = vi
      .spyOn(api, "placeOrder")
      .mockRejectedValueOnce(new ApiError(422, { detail, code: "IDEMPOTENCY_KEY_REUSED" }))
      .mockResolvedValue(order({ id: "o-4" }));
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(detail);
    expect(storedAttempt()).toBeNull();
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-4"));
    const [first, second] = keysSent(placeOrder);
    expect(second).not.toBe(first);
  });

  it("keeps the key for a retry even when the browser refuses session storage", async () => {
    const user = userEvent.setup();
    const { getItem, setItem } = Storage.prototype;
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(function (this: Storage, key: string) {
      if (this === window.sessionStorage) throw new DOMException("Storage is disabled", "SecurityError");
      return getItem.call(this, key);
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(function (this: Storage, key: string, value: string) {
      if (this === window.sessionStorage) throw new DOMException("Storage is disabled", "SecurityError");
      setItem.call(this, key, value);
    });
    const placeOrder = vi
      .spyOn(api, "placeOrder")
      .mockRejectedValueOnce(new TypeError("Failed to fetch"))
      .mockResolvedValue(order({ id: "o-9" }));
    openCheckout();

    await fillIn(user);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(LOST_ANSWER);
    await user.click(screen.getByRole("button", { name: "Pay $99.98" }));

    await waitFor(() => expect(currentLocation()).toBe("/orders/o-9"));
    const [first, second] = keysSent(placeOrder);
    expect(second).toBe(first);
  });

  it("fills in the signed-in customer's details", async () => {
    openCheckout(CUSTOMER);

    expect(await screen.findByDisplayValue("Ada Lovelace")).toBeInTheDocument();
    expect(screen.getByLabelText("Email")).toHaveValue("ada@example.com");
  });

  it("waits for the session check, so a late answer cannot wipe what the shopper typed", async () => {
    const user = userEvent.setup();
    let answer: (me: Me) => void = () => undefined;
    const late = new Promise<Me>((resolve) => {
      answer = resolve;
    });
    const quote = vi.spyOn(api, "quote").mockResolvedValue(twoSpeakers);
    renderPage("/checkout", <CheckoutPage />, { me: late, cart: { 7: 2 } });
    await waitFor(() => expect(quote).toHaveBeenCalled());
    expect(screen.queryByLabelText("Name")).not.toBeInTheDocument();

    await act(async () => answer(CUSTOMER));
    await user.type(await screen.findByLabelText("Card number"), "4242 4242 4242 4242");

    expect(screen.getByLabelText("Name")).toHaveValue("Ada Lovelace");
    expect(screen.getByLabelText("Card number")).toHaveValue("4242 4242 4242 4242");
  });

  it("does not offer to pay for lines that cannot be bought", async () => {
    vi.spyOn(api, "quote").mockResolvedValue(quoteFor([{ product: product({ stock: 1 }), quantity: 2, available: false }]));
    renderPage("/checkout", <CheckoutPage />, { cart: { 7: 2 } });

    expect(await screen.findByText(/Some items in the cart are not available any more/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: /^Pay/ })).not.toBeInTheDocument();
  });

  it("sends an empty cart back to the shop", () => {
    renderPage("/checkout", <CheckoutPage />);

    expect(screen.getByRole("heading", { level: 1, name: "Checkout" })).toBeInTheDocument();
    expect(screen.getByText(/The cart is empty/)).toBeInTheDocument();
  });
});
