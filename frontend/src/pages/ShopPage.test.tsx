import { screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { api } from "../api";
import { pageOf, product } from "../test/fixtures";
import { currentLocation, renderPage, storedCart } from "../test/render";
import { ShopPage } from "./ShopPage";

describe("ShopPage", () => {
  it("applies a category, a sort or the stock filter as soon as it is chosen, with the text typed so far", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([{ id: 1, name: "Audio" }]);
    const products = vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/", <ShopPage />);

    await user.type(screen.getByLabelText("Search"), "  speaker ");
    await screen.findByRole("option", { name: "Audio" });
    await user.selectOptions(screen.getByLabelText("Category"), "Audio");

    await waitFor(() => expect(currentLocation()).toBe("/?q=speaker&categoryId=1&sort=RELEVANCE"));
    expect(screen.getByLabelText("Category")).toHaveFocus();

    await user.selectOptions(screen.getByLabelText("Sort"), "Price: low to high");
    await user.click(screen.getByLabelText("In stock"));

    await waitFor(() => expect(currentLocation()).toBe("/?q=speaker&categoryId=1&sort=PRICE_ASC&inStock=true"));
    await waitFor(() => expect(String(products.mock.lastCall?.[0])).toBe("q=speaker&categoryId=1&sort=PRICE_ASC&inStock=true"));
    expect(screen.getByLabelText("In stock")).toHaveFocus();
  });

  it("applies typed text and prices on Search and leaves out the empty filters", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    const products = vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/", <ShopPage />);

    await user.type(screen.getByLabelText("Search"), "speaker");
    await user.type(screen.getByLabelText("Min $"), "10");
    await user.type(screen.getByLabelText("Max $"), "50");
    expect(currentLocation()).toBe("/");
    expect(products).toHaveBeenCalledTimes(1);

    await user.click(screen.getByRole("button", { name: "Search" }));

    await waitFor(() => expect(currentLocation()).toBe("/?q=speaker&minPrice=10&maxPrice=50&sort=RELEVANCE"));
    expect(screen.getByRole("button", { name: "Search" })).toHaveFocus();
  });

  it("searches on Enter and leaves the focus in the search box", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/", <ShopPage />);

    await user.type(screen.getByLabelText("Search"), "speaker{Enter}");

    await waitFor(() => expect(currentLocation()).toBe("/?q=speaker&sort=RELEVANCE"));
    expect(screen.getByLabelText("Search")).toHaveFocus();
    expect(screen.getByLabelText("Search")).toHaveValue("speaker");
  });

  it("does not apply a choice while a price is invalid", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/", <ShopPage />);

    await user.type(screen.getByLabelText("Min $"), "-5");
    await user.selectOptions(screen.getByLabelText("Sort"), "Newest");

    expect(screen.getByLabelText("Min $")).toBeInvalid();
    expect(currentLocation()).toBe("/");
  });

  it("names the page and announces how many products match", async () => {
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/", <ShopPage />, { url: "/?q=speaker" });

    expect(screen.getByRole("heading", { level: 1, name: "Products" })).toBeInTheDocument();
    expect(await screen.findByText("1 product")).toHaveAttribute("role", "status");
    expect(document.title).toBe("Products · Shop");
  });

  it("keeps the category from the address selected once the categories load", async () => {
    vi.spyOn(api, "categories").mockResolvedValue([
      { id: 1, name: "Audio" },
      { id: 2, name: "Books" },
    ]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/", <ShopPage />, { url: "/?categoryId=2" });

    await screen.findByRole("option", { name: "Books" });

    expect(screen.getByLabelText("Category")).toHaveValue("2");
  });

  it("pages through the results with the filters applied and says which page is shown", async () => {
    const user = userEvent.setup();
    const scrollTo = vi.spyOn(window, "scrollTo");
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockImplementation(async (params) =>
      pageOf([product()], { page: Number(params.get("page") ?? 0), total: 30, totalPages: 3 }),
    );
    renderPage("/", <ShopPage />, { url: "/?q=speaker" });
    expect(await screen.findByText("30 products · page 1 of 3")).toHaveAttribute("role", "status");

    await user.click(screen.getByRole("button", { name: "Next" }));

    await waitFor(() => expect(currentLocation()).toBe("/?q=speaker&page=1"));
    expect(await screen.findByText("30 products · page 2 of 3")).toBeInTheDocument();
    expect(scrollTo).toHaveBeenCalledWith({ top: 0, behavior: "instant" });
    expect(screen.getByRole("button", { name: "Next" })).toHaveFocus();
  });

  it("keeps text typed but not searched when paging, and offers to clear only real filters", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()], { total: 30, totalPages: 3 }));
    renderPage("/", <ShopPage />);

    await user.type(screen.getByLabelText("Search"), "lamp");
    await user.click(await screen.findByRole("button", { name: "Next" }));

    await waitFor(() => expect(currentLocation()).toBe("/?page=1"));
    expect(screen.getByLabelText("Search")).toHaveValue("lamp");
    expect(screen.queryByRole("link", { name: "Clear filters" })).not.toBeInTheDocument();
  });

  it("empties the form when the filters are cleared", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([{ id: 1, name: "Audio" }]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([product()]));
    renderPage("/", <ShopPage />, { url: "/?q=speaker&categoryId=1&minPrice=10&sort=PRICE_ASC&inStock=true" });
    await screen.findByRole("option", { name: "Audio" });

    await user.click(await screen.findByRole("link", { name: "Clear filters" }));

    await waitFor(() => expect(currentLocation()).toBe("/"));
    expect(screen.getByLabelText("Search")).toHaveValue("");
    expect(screen.getByLabelText("Category")).toHaveValue("");
    expect(screen.getByLabelText("Min $")).toHaveValue(null);
    expect(screen.getByLabelText("Sort")).toHaveValue("RELEVANCE");
    expect(screen.getByLabelText("In stock")).not.toBeChecked();
  });

  it("announces a product added to the cart and disables the ones held for other orders", async () => {
    const user = userEvent.setup();
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue(
      pageOf([product(), product({ id: 8, sku: "CS-020", name: "Chess Set", stock: 4, reserved: 4 })]),
    );
    renderPage("/", <ShopPage />);

    const [speaker, chess] = await screen.findAllByRole("button", { name: "Add to cart" });
    expect(chess).toBeDisabled();
    expect(screen.getByText("Out of stock")).toBeInTheDocument();
    await user.click(speaker as HTMLElement);

    const added = screen.getByText(/Added Bluetooth Speaker to the cart/);
    expect(added.closest("[role='status']")).not.toBeNull();
    expect(storedCart()).toEqual({ 7: 1 });
  });

  it("says when nothing matches", async () => {
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockResolvedValue(pageOf([]));
    renderPage("/", <ShopPage />, { url: "/?q=unicorn" });

    expect(await screen.findByText("No products match these filters.")).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "Clear filters" })).toHaveAttribute("href", "/");
  });

  it("explains products that cannot be loaded", async () => {
    vi.spyOn(api, "categories").mockResolvedValue([]);
    vi.spyOn(api, "products").mockRejectedValue(new TypeError("Failed to fetch"));
    renderPage("/", <ShopPage />);

    expect(await screen.findByRole("alert")).toHaveTextContent("Failed to fetch");
  });
});
