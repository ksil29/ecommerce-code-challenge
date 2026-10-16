import type {
  AuditEvent,
  Category,
  FieldError,
  ImportReport,
  Me,
  Order,
  OrderInput,
  OrderSummary,
  Page,
  Product,
  ProductInput,
  Quote,
  Settings,
} from "./types";

export const SESSION_EXPIRED = "Your session has expired. Sign in again.";

export class ApiError extends Error {
  readonly status: number;
  readonly code: string | undefined;
  readonly errors: FieldError[];
  readonly body: Record<string, unknown>;

  constructor(status: number, body: Record<string, unknown>) {
    super(typeof body.detail === "string" ? body.detail : `Request failed with status ${status}`);
    this.status = status;
    this.code = typeof body.code === "string" ? body.code : undefined;
    this.errors = Array.isArray(body.errors) ? (body.errors as FieldError[]) : [];
    this.body = body;
  }

  fieldErrors(): Record<string, string> {
    return Object.fromEntries(this.errors.map((error) => [error.field, error.message]));
  }
}

export function errorMessage(error: unknown, shownFields: readonly string[] = []): string {
  if (!(error instanceof ApiError)) return error instanceof Error ? error.message : "Something went wrong";
  if (error.status === 401) return SESSION_EXPIRED;
  if (error.status === 403) return "You are not allowed to do this.";
  const others = new Set(
    error.errors
      .filter((problem) => !shownFields.includes(problem.field) && problem.message !== error.message)
      .map((problem) => problem.message),
  );
  return others.size > 0 ? `${error.message}: ${[...others].join("; ")}` : error.message;
}

const SESSION_CHECKS = new Set(["/api/auth/login", "/api/auth/me"]);
const unauthorizedListeners = new Set<() => void>();

export function onUnauthorized(listener: () => void): () => void {
  unauthorizedListeners.add(listener);
  return () => {
    unauthorizedListeners.delete(listener);
  };
}

function csrfToken(): string | undefined {
  const cookie = document.cookie.split("; ").find((entry) => entry.startsWith("XSRF-TOKEN="));
  return cookie ? decodeURIComponent(cookie.slice("XSRF-TOKEN=".length)) : undefined;
}

async function request<T>(method: string, url: string, body?: BodyInit, headers: Record<string, string> = {}): Promise<T> {
  const allHeaders: Record<string, string> = { Accept: "application/json", ...headers };
  const token = method === "GET" ? undefined : csrfToken();
  if (token) allHeaders["X-XSRF-TOKEN"] = token;
  const response = await fetch(url, { method, body, headers: allHeaders, credentials: "same-origin" });
  if (response.status === 401 && !SESSION_CHECKS.has(url)) unauthorizedListeners.forEach((listener) => listener());
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  let data: unknown = {};
  if (text) {
    try {
      data = JSON.parse(text);
    } catch {
      data = { detail: `Unexpected response (${response.status})` };
    }
  }
  if (!response.ok) throw new ApiError(response.status, data as Record<string, unknown>);
  return data as T;
}

const jsonHeaders = { "Content-Type": "application/json" };

export const api = {
  me: () => request<Me>("GET", "/api/auth/me"),
  settings: () => request<Settings>("GET", "/api/settings"),
  login: (username: string, password: string) =>
    request<void>("POST", "/api/auth/login", new URLSearchParams({ username, password }), {
      "Content-Type": "application/x-www-form-urlencoded",
    }),
  logout: () => request<void>("POST", "/api/auth/logout"),
  register: (email: string, fullName: string, password: string) =>
    request<Me>("POST", "/api/auth/register", JSON.stringify({ email, fullName, password }), jsonHeaders),
  myOrders: () => request<OrderSummary[]>("GET", "/api/me/orders"),
  products: (params: URLSearchParams) => request<Page<Product>>("GET", `/api/products?${params.toString()}`),
  product: (sku: string) => request<Product>("GET", `/api/products/${encodeURIComponent(sku)}`),
  categories: () => request<Category[]>("GET", "/api/categories"),
  quote: (items: { productId: number; quantity: number }[]) =>
    request<Quote>("POST", "/api/cart/quote", JSON.stringify({ items }), jsonHeaders),
  placeOrder: (idempotencyKey: string, order: OrderInput) =>
    request<Order>("POST", "/api/orders", JSON.stringify(order), { ...jsonHeaders, "Idempotency-Key": idempotencyKey }),
  order: (id: string) => request<Order>("GET", `/api/orders/${encodeURIComponent(id)}`),
  admin: {
    product: (id: number) => request<Product>("GET", `/api/admin/products/${id}`),
    createProduct: (input: ProductInput) =>
      request<Product>("POST", "/api/admin/products", JSON.stringify(input), jsonHeaders),
    updateProduct: (id: number, input: ProductInput, version: number) =>
      request<Product>("PUT", `/api/admin/products/${id}`, JSON.stringify({ ...input, version }), jsonHeaders),
    archive: (id: number, version: number) =>
      request<Product>("POST", `/api/admin/products/${id}/archive`, JSON.stringify({ version }), jsonHeaders),
    restore: (id: number, version: number) =>
      request<Product>("POST", `/api/admin/products/${id}/restore`, JSON.stringify({ version }), jsonHeaders),
    importCsv: (file: File) => {
      const form = new FormData();
      form.append("file", file);
      return request<ImportReport>("POST", "/api/admin/imports", form);
    },
    orders: () => request<OrderSummary[]>("GET", "/api/admin/orders"),
    audit: () => request<AuditEvent[]>("GET", "/api/admin/audit?limit=200"),
  },
};
