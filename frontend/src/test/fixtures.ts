import type { Me, Order, Page, Product, Quote } from "../types";

export const GUEST: Me = { authenticated: false, username: null, admin: false, fullName: null, email: null };

export const CUSTOMER: Me = {
  authenticated: true,
  username: "ada@example.com",
  admin: false,
  fullName: "Ada Lovelace",
  email: "ada@example.com",
};

export const ADMIN: Me = { authenticated: true, username: "admin", admin: true, fullName: null, email: null };

export function product(overrides: Partial<Product> = {}): Product {
  const stock = overrides.stock ?? 12;
  const reserved = overrides.reserved ?? 0;
  return {
    id: 7,
    sku: "BS-099",
    name: "Bluetooth Speaker",
    description: "A small speaker for the desk",
    categoryId: 1,
    category: "Audio",
    price: "49.99",
    stock,
    reserved,
    available: Math.max(0, stock - reserved),
    weightKg: "0.40",
    archived: false,
    version: 3,
    ...overrides,
  };
}

export function pageOf<T>(items: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return { items, page: 0, size: 12, total: items.length, totalPages: 1, ...overrides };
}

export function quoteFor(lines: { product: Product; quantity: number; available?: boolean }[], missingProductIds: number[] = []): Quote {
  const quoteLines = lines.map(({ product, quantity, available = true }) => ({
    product,
    quantity,
    available,
    lineTotal: (Number(product.price) * quantity).toFixed(2),
  }));
  return {
    lines: quoteLines,
    missingProductIds,
    total: quoteLines.reduce((sum, line) => sum + Number(line.lineTotal), 0).toFixed(2),
    canCheckout: quoteLines.every((line) => line.available),
  };
}

export function order(overrides: Partial<Order> = {}): Order {
  return {
    id: "0b6f3c1e-8a52-4d0e-9a3b-2f6c1d7e9a10",
    status: "PAID",
    statusLabel: "Paid",
    customerName: "Ada Lovelace",
    customerEmail: "ada@example.com",
    total: "99.98",
    currency: "USD",
    cardLast4: "4242",
    paymentReference: "PAY-7Q2K",
    failureReason: null,
    createdAt: "2026-10-14T18:30:00Z",
    lines: [{ sku: "BS-099", productName: "Bluetooth Speaker", unitPrice: "49.99", quantity: 2, lineTotal: "99.98" }],
    ...overrides,
  };
}
