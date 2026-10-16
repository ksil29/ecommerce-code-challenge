import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

async function expectNoSeriousViolations(page: Page) {
  await page.waitForLoadState("networkidle");
  const results = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"]).analyze();
  const serious = results.violations
    .filter((violation) => violation.impact === "serious" || violation.impact === "critical")
    .map((violation) => ({ rule: violation.id, help: violation.help, targets: violation.nodes.map((node) => node.target.join(" ")) }));
  expect(serious, JSON.stringify(serious, null, 2)).toEqual([]);
}

async function addToCart(page: Page, sku: string) {
  await page.goto(`/products/${sku}`);
  await page.getByRole("button", { name: "Add to cart" }).click();
  await expect(page.getByText("Added to the cart.")).toBeVisible();
}

for (const path of ["/", "/?q=speaker", "/products/BS-021", "/cart", "/login", "/register"]) {
  test(`${path} has no serious accessibility problems`, async ({ page }) => {
    await page.goto(path);
    await expectNoSeriousViolations(page);
  });
}

test("the shop fits a phone screen and opens its menu from a button", async ({ page }) => {
  await page.setViewportSize({ width: 360, height: 740 });
  await page.goto("/");
  await expect(page.getByText(/^\d+ products?$/)).toBeVisible();
  const signIn = page.getByRole("link", { name: "Sign in" });
  await expect(signIn).toBeHidden();

  const menu = page.getByRole("button", { name: "Menu" });
  await menu.click();

  await expect(menu).toHaveAttribute("aria-expanded", "true");
  await expect(signIn).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBeLessThanOrEqual(360);
  await expectNoSeriousViolations(page);
});

test("a filled cart has no serious accessibility problems", async ({ page }) => {
  await addToCart(page, "NB-100");
  await page.goto("/cart");
  await expect(page.getByRole("button", { name: "Remove" })).toBeVisible();
  await expectNoSeriousViolations(page);
});

test("checkout has no serious accessibility problems", async ({ page }) => {
  await addToCart(page, "NB-100");
  await page.goto("/checkout");
  await expect(page.getByText("Order summary")).toBeVisible();
  await expectNoSeriousViolations(page);
});

test("checkout after a submit with empty fields has no serious accessibility problems", async ({ page }) => {
  await addToCart(page, "NB-100");
  await page.goto("/checkout");
  await page.getByRole("button", { name: /^Pay/ }).click();
  const name = page.getByLabel("Name", { exact: true });
  await expect(name).toHaveAttribute("aria-invalid", "true");
  await expect(name).toBeFocused();
  await expect(page.getByLabel("Card number")).toHaveAttribute("aria-invalid", "true");
  await expectNoSeriousViolations(page);
});

test("the sign-in page after a wrong password has no serious accessibility problems", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email (or admin username)").fill(`nobody-${Date.now()}@example.com`);
  await page.getByLabel("Password").fill("not the password");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByRole("alert")).toContainText("Wrong email or password");
  await expect(page.getByLabel("Password")).toHaveAttribute("aria-invalid", "true");
  await expectNoSeriousViolations(page);
});

test("admin pages have no serious accessibility problems", async ({ page }) => {
  await page.goto("/login");
  await page.getByLabel("Email (or admin username)").fill("admin");
  await page.getByLabel("Password").fill(process.env.E2E_ADMIN_PASSWORD ?? "admin");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).toHaveURL(/\/admin\/products/);
  for (const path of ["/admin/products", "/admin/products/new", "/admin/import", "/admin/orders", "/admin/audit"]) {
    await page.goto(path);
    await expectNoSeriousViolations(page);
  }
});
