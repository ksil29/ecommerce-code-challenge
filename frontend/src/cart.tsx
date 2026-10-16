import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { addQuantity, itemCount, limitQuantities, parseItems, removeProducts, setQuantity, type CartItems } from "./cartItems";
import { useSettings } from "./settings";

const STORAGE_KEY = "shop.cart";

interface CartContextValue {
  items: CartItems;
  count: number;
  add: (productId: number, quantity: number) => void;
  set: (productId: number, quantity: number) => void;
  remove: (productIds: number[]) => void;
  clear: () => void;
}

const CartContext = createContext<CartContextValue | null>(null);

function load(): CartItems {
  try {
    return parseItems(window.localStorage.getItem(STORAGE_KEY));
  } catch {
    return {};
  }
}

function save(items: CartItems) {
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(items));
  } catch {
    return;
  }
}

export function CartProvider({ children }: { children: ReactNode }) {
  const { maxQuantity } = useSettings();
  const [stored, setStored] = useState<CartItems>(load);
  const items = useMemo(() => limitQuantities(stored, maxQuantity), [stored, maxQuantity]);

  useEffect(() => save(items), [items]);

  useEffect(() => {
    const onStorage = (event: StorageEvent) => {
      if (event.key === STORAGE_KEY) setStored(load());
    };
    window.addEventListener("storage", onStorage);
    return () => window.removeEventListener("storage", onStorage);
  }, []);

  const add = useCallback(
    (productId: number, quantity: number) => setStored((current) => addQuantity(current, productId, quantity, maxQuantity)),
    [maxQuantity],
  );
  const set = useCallback(
    (productId: number, quantity: number) => setStored((current) => setQuantity(current, productId, quantity, maxQuantity)),
    [maxQuantity],
  );
  const remove = useCallback((productIds: number[]) => setStored((current) => removeProducts(current, productIds)), []);
  const clear = useCallback(() => setStored({}), []);

  const value = useMemo<CartContextValue>(
    () => ({ items, count: itemCount(items), add, set, remove, clear }),
    [items, add, set, remove, clear],
  );

  return <CartContext.Provider value={value}>{children}</CartContext.Provider>;
}

export function useCart(): CartContextValue {
  const value = useContext(CartContext);
  if (!value) throw new Error("useCart must be used inside CartProvider");
  return value;
}
