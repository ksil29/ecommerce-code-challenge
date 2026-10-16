import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { Route } from "react-router";
import { describe, expect, it, vi } from "vitest";
import { api, ApiError } from "../../api";
import { ADMIN, product } from "../../test/fixtures";
import { currentLocation, currentNotice, renderPage, renderRoutes } from "../../test/render";
import { ProductFormPage } from "./ProductFormPage";

const STALE =
  "Someone changed product 7 after you opened it (a sale or another edit). Reload to see the current values.";

function openEditor() {
  renderRoutes(<Route path="/admin/products/:id/edit" element={<ProductFormPage />} />, { url: "/admin/products/7/edit", me: ADMIN });
}

describe("ProductFormPage", () => {
  it("creates a product and returns to the list with a notice", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([{ id: 1, name: "Audio" }]);
    const create = vi.spyOn(api.admin, "createProduct").mockResolvedValue(product({ id: 21, sku: "NB-200" }));
    renderPage("/admin/products/new", <ProductFormPage />, { me: ADMIN });

    await user.type(screen.getByLabelText("SKU"), "nb-200");
    await user.type(screen.getByLabelText("Name"), "Notebook");
    await user.type(screen.getByLabelText("Category"), "Stationery");
    await user.type(screen.getByLabelText("Price (USD)"), "4.50");
    await user.type(screen.getByLabelText("Stock on hand"), "40");
    expect(screen.queryByLabelText("Reserved")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "Save" }));

    await waitFor(() => expect(currentLocation()).toBe("/admin/products"));
    expect(currentNotice()).toBe("Saved NB-200");
    expect(create).toHaveBeenCalledWith({
      sku: "nb-200",
      name: "Notebook",
      description: "",
      category: "Stationery",
      price: "4.50",
      stock: "40",
      weightKg: "",
    });
  });

  it("edits the stock on hand and shows the units held by orders next to it", async () => {
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api.admin, "product").mockResolvedValue(product({ stock: 12, reserved: 3 }));
    openEditor();

    expect(await screen.findByLabelText("Stock on hand")).toHaveValue("12");
    const reserved = screen.getByLabelText("Reserved");
    expect(reserved).toHaveValue("3");
    expect(reserved).toHaveAttribute("readonly");
    expect(reserved).toHaveAccessibleDescription("Held by orders waiting for payment");
    expect(document.title).toBe("Edit BS-099 · Admin · Shop");
  });

  it("offers the current values when someone else saved the product first", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api.admin, "product")
      .mockResolvedValueOnce(product())
      .mockResolvedValue(product({ name: "Bluetooth Speaker Mini", version: 4 }));
    const update = vi.spyOn(api.admin, "updateProduct").mockRejectedValue(new ApiError(409, { detail: STALE, code: "STALE_VERSION" }));
    openEditor();

    await user.clear(await screen.findByLabelText("Stock on hand"));
    await user.type(screen.getByLabelText("Stock on hand"), "20");
    await user.click(screen.getByRole("button", { name: "Save" }));
    expect(await screen.findByText(STALE)).toBeInTheDocument();
    expect(update).toHaveBeenCalledWith(7, expect.objectContaining({ stock: "20" }), 3);

    await user.click(screen.getByRole("button", { name: "Reload the current values" }));

    expect(await screen.findByDisplayValue("Bluetooth Speaker Mini")).toBeInTheDocument();
    expect(screen.queryByText(STALE)).not.toBeInTheDocument();
  });

  it("shows the server's checks next to the fields and moves focus to the first one", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api.admin, "createProduct").mockRejectedValue(
      new ApiError(400, {
        detail: "The request is not valid",
        errors: [
          { field: "price", message: 'Price "$29.99" is not a plain decimal such as 19.99' },
          { field: "description", message: "Use at most 2000 characters" },
        ],
      }),
    );
    renderPage("/admin/products/new", <ProductFormPage />, { me: ADMIN });
    expect(screen.getByLabelText("SKU")).toHaveAccessibleDescription("Letters, digits and single hyphens; stored in upper case");

    await user.type(screen.getByLabelText("Price (USD)"), "$29.99");
    await user.click(screen.getByRole("button", { name: "Save" }));

    expect(await screen.findByText('Price "$29.99" is not a plain decimal such as 19.99')).toBeInTheDocument();
    const price = screen.getByLabelText("Price (USD)");
    await waitFor(() => expect(price).toHaveFocus());
    expect(price).toHaveClass("is-invalid");
    expect(price).toHaveAttribute("aria-invalid", "true");
    expect(price).toHaveAccessibleDescription('Price "$29.99" is not a plain decimal such as 19.99');
    expect(screen.getByLabelText("Description")).toHaveAccessibleDescription("Use at most 2000 characters");
    expect(screen.queryByText("A plain decimal such as 19.99")).not.toBeInTheDocument();
    expect(screen.getByRole("alert")).toHaveTextContent(/^The request is not valid$/);
  });

  it("explains a product that cannot be loaded", async () => {
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api.admin, "product").mockRejectedValue(new ApiError(404, { detail: "Product 7 was not found" }));
    openEditor();

    expect(await screen.findByRole("alert")).toHaveTextContent("Product 7 was not found");
    expect(screen.getByRole("heading", { level: 1, name: "Edit product" })).toBeInTheDocument();
  });
});
