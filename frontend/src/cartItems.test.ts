import { describe, expect, it } from "vitest";
import { addQuantity, itemCount, limitQuantities, parseItems, removeProducts, setQuantity, toRequest } from "./cartItems";

describe("cart items", () => {
  it("adds to an existing line and caps the quantity", () => {
    let items = addQuantity({}, 7, 2, 99);
    items = addQuantity(items, 7, 3, 99);
    expect(items).toEqual({ 7: 5 });
    expect(addQuantity(items, 7, 500, 99)).toEqual({ 7: 99 });
    expect(setQuantity(items, 7, 12, 10)).toEqual({ 7: 10 });
  });

  it("removes a line when the quantity drops to zero or is not a number", () => {
    expect(setQuantity({ 7: 2 }, 7, 0, 99)).toEqual({});
    expect(setQuantity({ 7: 2 }, 7, Number.NaN, 99)).toEqual({});
    expect(removeProducts({ 7: 2, 8: 1 }, [8])).toEqual({ 7: 2 });
  });

  it("brings stored quantities down to a lower limit and leaves the rest alone", () => {
    const items = { 7: 40, 8: 3 };

    expect(limitQuantities(items, 10)).toEqual({ 7: 10, 8: 3 });
    expect(limitQuantities(items, 99)).toBe(items);
  });

  it("counts items and builds the request", () => {
    const items = { 7: 2, 8: 1 };
    expect(itemCount(items)).toBe(3);
    expect(toRequest(items)).toEqual([
      { productId: 7, quantity: 2 },
      { productId: 8, quantity: 1 },
    ]);
  });

  it("ignores damaged storage", () => {
    expect(parseItems("not json")).toEqual({});
    expect(parseItems("[1,2]")).toEqual({});
    expect(parseItems('{"7":2,"x":3,"9":"a","10":-1}')).toEqual({ 7: 2 });
  });
});
