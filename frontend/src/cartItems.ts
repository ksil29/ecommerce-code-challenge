export type CartItems = Readonly<Record<string, number>>;

export function setQuantity(items: CartItems, productId: number, quantity: number, max: number): CartItems {
  const next = { ...items };
  const whole = Math.floor(quantity);
  if (!Number.isFinite(whole) || whole <= 0) {
    delete next[productId];
  } else {
    next[productId] = Math.min(whole, max);
  }
  return next;
}

export function addQuantity(items: CartItems, productId: number, quantity: number, max: number): CartItems {
  return setQuantity(items, productId, (items[productId] ?? 0) + Math.max(1, Math.floor(quantity)), max);
}

export function limitQuantities(items: CartItems, max: number): CartItems {
  if (Object.values(items).every((quantity) => quantity <= max)) return items;
  return Object.fromEntries(Object.entries(items).map(([productId, quantity]) => [productId, Math.min(quantity, max)]));
}

export function removeProducts(items: CartItems, productIds: number[]): CartItems {
  if (!productIds.some((id) => id in items)) return items;
  const next = { ...items };
  productIds.forEach((id) => delete next[id]);
  return next;
}

export function itemCount(items: CartItems): number {
  return Object.values(items).reduce((sum, quantity) => sum + quantity, 0);
}

export function toRequest(items: CartItems): { productId: number; quantity: number }[] {
  return Object.entries(items).map(([productId, quantity]) => ({ productId: Number(productId), quantity }));
}

export function parseItems(raw: string | null): CartItems {
  if (!raw) return {};
  try {
    const parsed: unknown = JSON.parse(raw);
    if (typeof parsed !== "object" || parsed === null || Array.isArray(parsed)) return {};
    let items: CartItems = {};
    for (const [key, value] of Object.entries(parsed)) {
      const productId = Number(key);
      if (Number.isInteger(productId) && productId > 0 && typeof value === "number") {
        items = setQuantity(items, productId, value, Number.MAX_SAFE_INTEGER);
      }
    }
    return items;
  } catch {
    return {};
  }
}
