import { afterEach, describe, expect, it, vi } from "vitest";
import { api, ApiError, errorMessage, onUnauthorized } from "./api";

function serve(status: number, body: string | null = null) {
  const fetchMock = vi.fn<typeof fetch>(async () => new Response(body, { status }));
  vi.stubGlobal("fetch", fetchMock);
  return fetchMock;
}

function sent(fetchMock: ReturnType<typeof serve>, call = 0) {
  const [url, init] = fetchMock.mock.calls[call] ?? [];
  return { url, method: init?.method, headers: init?.headers as Record<string, string>, body: init?.body };
}

const input = {
  sku: "BS-099",
  name: "Bluetooth Speaker",
  description: "",
  category: "Audio",
  price: "49.99",
  stock: "12",
  weightKg: "",
};

afterEach(() => {
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT";
});

describe("api", () => {
  it("sends the CSRF token with changes but not with reads", async () => {
    document.cookie = "XSRF-TOKEN=a%2Bb";
    const fetchMock = serve(200, "[]");

    await api.categories();
    await api.logout();

    expect(sent(fetchMock, 0).headers).not.toHaveProperty("X-XSRF-TOKEN");
    expect(sent(fetchMock, 1).headers).toMatchObject({ "X-XSRF-TOKEN": "a+b" });
  });

  it("sends the order as JSON with its idempotency key", async () => {
    const fetchMock = serve(201, JSON.stringify({ id: "o-1" }));
    const order = {
      items: [{ productId: 7, quantity: 2 }],
      expectedTotal: "99.98",
      customerName: "Ada Lovelace",
      customerEmail: "ada@example.com",
      cardNumber: "4242 4242 4242 4242",
    };

    await api.placeOrder("5f1d0f53-3c55-4b8e-9d6a-0c2a1b7e4f10", order);

    const request = sent(fetchMock);
    expect(request).toMatchObject({ url: "/api/orders", method: "POST" });
    expect(request.headers).toMatchObject({
      Accept: "application/json",
      "Content-Type": "application/json",
      "Idempotency-Key": "5f1d0f53-3c55-4b8e-9d6a-0c2a1b7e4f10",
    });
    expect(JSON.parse(String(request.body))).toEqual(order);
  });

  it("escapes the SKU and sends the sign-in as a form", async () => {
    const fetchMock = serve(200, "{}");

    await api.product("AB/1 2");
    await api.login("ada@example.com", "slow green river");

    expect(sent(fetchMock, 0).url).toBe("/api/products/AB%2F1%202");
    expect(sent(fetchMock, 1).headers).toMatchObject({ "Content-Type": "application/x-www-form-urlencoded" });
    expect(String(sent(fetchMock, 1).body)).toBe("username=ada%40example.com&password=slow+green+river");
  });

  it.each([
    ["the shop settings", () => api.settings(), "GET", "/api/settings"],
    ["my orders", () => api.myOrders(), "GET", "/api/me/orders"],
    ["a page of products", () => api.products(new URLSearchParams({ q: "chess", page: "2" })), "GET", "/api/products?q=chess&page=2"],
    ["a quote", () => api.quote([{ productId: 7, quantity: 2 }]), "POST", "/api/cart/quote"],
    ["an order", () => api.order("o-1"), "GET", "/api/orders/o-1"],
    ["a product for editing", () => api.admin.product(7), "GET", "/api/admin/products/7"],
    ["a new product", () => api.admin.createProduct(input), "POST", "/api/admin/products"],
    ["a product update", () => api.admin.updateProduct(7, input, 3), "PUT", "/api/admin/products/7"],
    ["an archive", () => api.admin.archive(7, 3), "POST", "/api/admin/products/7/archive"],
    ["a restore", () => api.admin.restore(7, 3), "POST", "/api/admin/products/7/restore"],
    ["an import", () => api.admin.importCsv(new File(["sku\n"], "products.csv")), "POST", "/api/admin/imports"],
    ["all orders", () => api.admin.orders(), "GET", "/api/admin/orders"],
    ["the audit log", () => api.admin.audit(), "GET", "/api/admin/audit?limit=200"],
  ])("asks for %s at the right endpoint", async (_, call, method, url) => {
    const fetchMock = serve(200, "{}");

    await call();

    expect(sent(fetchMock)).toMatchObject({ method, url });
  });

  it("sends the version the admin saw with every change to a product", async () => {
    const fetchMock = serve(200, "{}");

    await api.admin.updateProduct(7, input, 3);
    await api.admin.archive(7, 4);

    expect(JSON.parse(String(sent(fetchMock, 0).body))).toEqual({ ...input, version: 3 });
    expect(JSON.parse(String(sent(fetchMock, 1).body))).toEqual({ version: 4 });
  });

  it("returns nothing for 204 No Content", async () => {
    serve(204);

    await expect(api.logout()).resolves.toBeUndefined();
  });

  it("turns an error response into an ApiError with the field errors", async () => {
    serve(
      409,
      JSON.stringify({
        detail: "An account with this email already exists",
        code: "EMAIL_TAKEN",
        errors: [{ field: "email", message: "An account with this email already exists" }],
      }),
    );

    const error = await api.register("ada@example.com", "Ada Lovelace", "slow green river").catch((failure: unknown) => failure);

    expect(error).toBeInstanceOf(ApiError);
    expect(error).toMatchObject({ status: 409, code: "EMAIL_TAKEN", message: "An account with this email already exists" });
    expect((error as ApiError).fieldErrors()).toEqual({ email: "An account with this email already exists" });
  });

  it("reports a response that is not JSON", async () => {
    serve(502, "<html>Bad gateway</html>");

    await expect(api.categories()).rejects.toMatchObject({ status: 502, code: undefined, message: "Unexpected response (502)" });
  });

  it("explains failures in words a shopper understands", () => {
    expect(errorMessage(new ApiError(401, {}))).toBe("Your session has expired. Sign in again.");
    expect(errorMessage(new ApiError(403, {}))).toBe("You are not allowed to do this.");
    expect(errorMessage(new ApiError(500, {}))).toBe("Request failed with status 500");
    expect(errorMessage(new TypeError("Failed to fetch"))).toBe("Failed to fetch");
    expect(errorMessage("nothing useful")).toBe("Something went wrong");
  });

  it("adds the field problems that the form does not show next to a field", () => {
    const error = new ApiError(400, {
      detail: "The request is not valid",
      errors: [
        { field: "items[0].quantity", message: "Choose a quantity from 1 to 99" },
        { field: "items[1].quantity", message: "Choose a quantity from 1 to 99" },
        { field: "cardNumber", message: "Enter a card number" },
      ],
    });

    expect(errorMessage(error, ["cardNumber"])).toBe("The request is not valid: Choose a quantity from 1 to 99");
    expect(errorMessage(error)).toBe("The request is not valid: Choose a quantity from 1 to 99; Enter a card number");
    const taken = new ApiError(409, {
      detail: "An account with this email already exists",
      errors: [{ field: "email", message: "An account with this email already exists" }],
    });
    expect(errorMessage(taken)).toBe("An account with this email already exists");
  });

  it("reports an expired session, but not a wrong password or the session check", async () => {
    const listener = vi.fn();
    const stop = onUnauthorized(listener);
    serve(401);

    await expect(api.login("ada@example.com", "wrong password")).rejects.toMatchObject({ status: 401 });
    await expect(api.me()).rejects.toMatchObject({ status: 401 });
    expect(listener).not.toHaveBeenCalled();
    await expect(api.myOrders()).rejects.toMatchObject({ status: 401 });
    expect(listener).toHaveBeenCalledTimes(1);

    stop();
    await expect(api.admin.orders()).rejects.toMatchObject({ status: 401 });
    expect(listener).toHaveBeenCalledTimes(1);
  });
});
