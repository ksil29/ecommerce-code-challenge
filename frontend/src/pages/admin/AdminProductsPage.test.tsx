import { screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../../api";
import { ADMIN, pageOf, product } from "../../test/fixtures";
import { currentLocation, renderPage } from "../../test/render";
import { AdminProductsPage } from "./AdminProductsPage";

describe("AdminProductsPage", () => {
  it("lists archived products too, archives one and restores it", async () => {
    const user = userEvent.setup();
    const products = vi
      .spyOn(api, "products")
      .mockResolvedValueOnce(pageOf([product()]))
      .mockResolvedValueOnce(pageOf([product({ archived: true, version: 4 })]))
      .mockResolvedValue(pageOf([product({ version: 5 })]));
    const archive = vi.spyOn(api.admin, "archive").mockResolvedValue(product({ archived: true, version: 4 }));
    const restore = vi.spyOn(api.admin, "restore").mockResolvedValue(product({ version: 5 }));
    renderPage("/admin/products", <AdminProductsPage />, { me: ADMIN });

    await user.click(await screen.findByRole("button", { name: "Archive" }));
    expect(archive).toHaveBeenCalledWith(7, 3);
    await user.click(await screen.findByRole("button", { name: "Restore" }));

    expect(restore).toHaveBeenCalledWith(7, 4);
    expect(await screen.findByRole("button", { name: "Archive" })).toBeInTheDocument();
    expect(String(products.mock.calls[0]?.[0])).toBe("includeArchived=true&size=50&sort=NAME");
  });

  it("shows the units on hand, held by orders and still available", async () => {
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product({ stock: 12, reserved: 5 })]));
    renderPage("/admin/products", <AdminProductsPage />, { me: ADMIN });

    const row = await screen.findByRole("row", { name: /BS-099/ });
    const headers = screen.getAllByRole("columnheader").map((header) => header.textContent);
    const cells = within(row).getAllByRole("cell").map((cell) => cell.textContent);
    expect(cells[headers.indexOf("On hand")]).toBe("12");
    expect(cells[headers.indexOf("Reserved")]).toBe("5");
    expect(cells[headers.indexOf("Available")]).toBe("7");
    expect(screen.getByRole("columnheader", { name: "Actions" })).toBeInTheDocument();
  });

  it("pages through the list and keeps text typed but not searched", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()], { total: 120, totalPages: 3 }));
    renderPage("/admin/products", <AdminProductsPage />, { me: ADMIN });

    await user.type(screen.getByLabelText("Search products"), "chess");
    await user.click(await screen.findByRole("button", { name: "Next" }));

    await waitFor(() => expect(currentLocation()).toBe("/admin/products?page=1"));
    expect(screen.getByLabelText("Search products")).toHaveValue("chess");
  });

  it("explains why a change was refused", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    vi.spyOn(api.admin, "archive").mockRejectedValue(
      new ApiError(409, {
        detail: "Someone changed product 7 after you opened it (a sale or another edit). Reload to see the current values.",
        code: "STALE_VERSION",
      }),
    );
    renderPage("/admin/products", <AdminProductsPage />, { me: ADMIN });

    await user.click(await screen.findByRole("button", { name: "Archive" }));

    expect(await screen.findByRole("alert")).toHaveTextContent(/Someone changed product 7 after you opened it/);
  });

  it("searches by text and keeps focus on the search button", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/admin/products", <AdminProductsPage />, { me: ADMIN });
    expect(screen.getByRole("heading", { level: 1, name: "Products" })).toBeInTheDocument();

    await user.type(screen.getByLabelText("Search products"), " chess ");
    await user.click(screen.getByRole("button", { name: "Search" }));

    await waitFor(() => expect(currentLocation()).toBe("/admin/products?q=chess"));
    expect(screen.getByRole("button", { name: "Search" })).toHaveFocus();
    expect(document.title).toBe("Products · Admin · Shop");
  });
});
