import { expect, test, type Page } from "@playwright/test";
import { fileURLToPath } from "node:url";

const sampleCsv = fileURLToPath(new URL("../../src/main/resources/sample/products.csv", import.meta.url));

const resultCount = /^\d+ products?( · page \d+ of \d+)?$/;

const freshClientAddress = `198.18.${Math.floor(Math.random() * 256)}.${1 + Math.floor(Math.random() * 254)}`;

interface ListedProduct {
  id: number;
  sku: string;
  archived: boolean;
  version: number;
}

async function addToCart(page: Page, sku: string) {
  await page.goto(`/products/${sku}`);
  await page.getByRole("button", { name: "Add to cart" }).click();
  await expect(page.getByText("Added to the cart.")).toBeVisible();
}

async function pay(page: Page, card: string) {
  await page.goto("/checkout");
  await expect(page.getByText("Order summary")).toBeVisible();
  const name = page.getByLabel("Name", { exact: true });
  if (!(await name.inputValue())) await name.fill("Guest Buyer");
  const email = page.getByLabel("Email", { exact: true });
  if (!(await email.inputValue())) await email.fill("guest@example.com");
  await page.getByLabel("Card number").fill(card);
  await page.getByRole("button", { name: /^Pay/ }).click();
}

async function signInAsAdmin(page: Page, url = "/login") {
  await page.goto(url);
  await page.getByLabel("Email (or admin username)").fill("admin");
  await page.getByLabel("Password").fill(process.env.E2E_ADMIN_PASSWORD ?? "admin");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/admin\/products/);
}

async function restoreIfArchived(page: Page, sku: string) {
  const search = new URLSearchParams({ q: sku, includeArchived: "true", size: "50" });
  const listing = await page.request.get(`/api/products?${search.toString()}`);
  if (!listing.ok()) return;
  const { items } = (await listing.json()) as { items: ListedProduct[] };
  const product = items.find((item) => item.sku === sku);
  if (!product?.archived) return;
  const token = (await page.context().cookies()).find((cookie) => cookie.name === "XSRF-TOKEN")?.value ?? "";
  await page.request.post(`/api/admin/products/${product.id}/restore`, {
    data: { version: product.version },
    headers: { "X-XSRF-TOKEN": decodeURIComponent(token) },
  });
}

test("a guest searches, buys with an approved card and sees the paid order", async ({ page }) => {
  await page.goto("/?q=speaker");
  await expect(page.getByText(resultCount)).toBeVisible();
  await page.locator(".product-card", { hasText: "BS-099" }).getByRole("button", { name: "Add to cart" }).click();
  await page.getByRole("link", { name: "View cart" }).click();
  await expect(page.getByRole("heading", { name: "Your cart" })).toBeVisible();
  await expect(page.getByRole("link", { name: "Bluetooth Speaker" })).toBeVisible();
  await page.getByRole("link", { name: "Checkout" }).click();

  await pay(page, "4242 4242 4242 4242");

  await expect(page.getByText("Paid", { exact: true })).toBeVisible();
  await expect(page.getByText(/Thank you, Guest Buyer/)).toBeVisible();
});

test("a sort applies as soon as it is chosen and stays while paging", async ({ page }) => {
  await page.goto("/");
  await page.getByLabel("Sort").selectOption("PRICE_ASC");
  await expect(page).toHaveURL(/sort=PRICE_ASC/);

  await page.getByRole("button", { name: "Next" }).click();

  await expect(page.getByText(/^\d+ products · page 2 of \d+$/)).toBeVisible();
  await expect(page).toHaveURL(/sort=PRICE_ASC/);
  await expect(page.getByLabel("Sort")).toHaveValue("PRICE_ASC");
  const prices = (await page.locator(".product-card .price").allTextContents()).map((price) => Number(price.replace(/[^\d.]/g, "")));
  expect(prices).toEqual([...prices].sort((a, b) => a - b));
});

test("a declined card keeps the cart for another try", async ({ page }) => {
  await addToCart(page, "PZ-024");
  await pay(page, "4000 0000 0000 0002");

  await expect(page.getByText("Payment failed", { exact: true })).toBeVisible();
  await expect(page.getByText(/Card declined/)).toBeVisible();
  await page.getByRole("link", { name: "Try another card" }).click();
  await expect(page.getByText("Order summary")).toBeVisible();
});

test("product names with markup are shown as text", async ({ page }) => {
  let dialogs = 0;
  page.on("dialog", (dialog) => {
    dialogs++;
    void dialog.dismiss();
  });
  await page.goto("/?q=XS-001");
  await expect(page.getByText("<script>alert('xss')</script>")).toBeVisible();
  expect(dialogs).toBe(0);
});

test.describe(() => {
  test.use({ extraHTTPHeaders: { "X-Forwarded-For": freshClientAddress } });

  test("a customer registers, buys, sees the order history and cannot open the admin area", async ({ page }) => {
    const email = `e2e-${Date.now()}@example.com`;
    await page.goto("/register");
    await page.getByLabel("Email", { exact: true }).fill(email);
    await page.getByLabel("Name", { exact: true }).fill("Edsger Dijkstra");
    await page.getByLabel("Password", { exact: true }).fill("too short");
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByText("Use at least 12 characters")).toBeVisible();

    await page.getByLabel("Password", { exact: true }).fill("slow green river under old bridges");
    await page.getByRole("button", { name: "Create account" }).click();
    await expect(page.getByRole("navigation").getByText("Edsger Dijkstra")).toBeVisible();

    await addToCart(page, "NB-100");
    await pay(page, "4242 4242 4242 4242");
    await expect(page.getByText("Paid", { exact: true })).toBeVisible();

    await page.getByRole("link", { name: "My orders" }).click();
    await expect(page.getByRole("cell", { name: "Paid" })).toHaveCount(1);

    await page.goto("/admin/products");
    await expect(page.getByText("This area is for administrators.")).toBeVisible();
  });
});

test("the admin imports the example file and finds it in the audit log", async ({ page }) => {
  await signInAsAdmin(page);
  await page.getByRole("link", { name: "Import CSV" }).click();
  await page.getByLabel("CSV file").setInputFiles(sampleCsv);
  await page.getByRole("button", { name: "Import" }).click();

  await expect(page.getByText("Report for products.csv")).toBeVisible();
  await expect(page.getByRole("cell", { name: "WM-042" })).toBeVisible();
  await expect(page.getByText('Price "$29.99" is not a plain decimal such as 19.99')).toBeVisible();

  await page.getByRole("link", { name: "Audit log" }).click();
  await expect(page.getByText("IMPORT_COMPLETED").first()).toBeVisible();
});

test("a sign-in link that points to another site lands in the shop instead", async ({ page }) => {
  await signInAsAdmin(page, "/login?next=/%5Cevil.example");
  await expect(page.getByRole("link", { name: "Import CSV" })).toBeVisible();
});

test("the API documentation loads under the security headers", async ({ page }) => {
  const errors: string[] = [];
  page.on("console", (message) => {
    if (message.type() === "error") errors.push(message.text());
  });
  await page.goto("/swagger-ui.html");
  await expect(page.getByRole("heading", { name: /Shop API/ })).toBeVisible();
  await expect(page.getByText("/api/orders").first()).toBeVisible();
  expect(errors).toEqual([]);
});

test("an archived product leaves the shop until the admin restores it", async ({ page }) => {
  await signInAsAdmin(page);
  await restoreIfArchived(page, "CS-020");
  try {
    await page.goto("/admin/products?q=CS-020");
    const row = page.getByRole("row", { name: /CS-020/ });
    await row.getByRole("button", { name: "Archive" }).click();
    await expect(row.getByText("Archived")).toBeVisible();

    await page.goto("/?q=CS-020");
    await expect(page.getByText(resultCount)).toBeVisible();
    await expect(page.locator(".product-card", { hasText: "CS-020" })).toHaveCount(0);

    await page.goto("/admin/products?q=CS-020");
    await page.getByRole("row", { name: /CS-020/ }).getByRole("button", { name: "Restore" }).click();
    await expect(page.getByRole("row", { name: /CS-020/ }).getByText("Active")).toBeVisible();
    await page.goto("/?q=CS-020");
    await expect(page.locator(".product-card", { hasText: "CS-020" })).toBeVisible();
  } finally {
    await restoreIfArchived(page, "CS-020");
  }
});
