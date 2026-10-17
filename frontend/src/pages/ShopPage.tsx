import { useState, type ChangeEvent, type FormEvent } from "react";
import { Link, useSearchParams } from "react-router";
import { api } from "../api";
import { useCart } from "../cart";
import { Pagination } from "../components/Pagination";
import { StockBadge } from "../components/StockBadge";
import { Thumb } from "../components/Thumb";
import { money } from "../format";
import { useDraft } from "../useDraft";
import { useLoad } from "../useLoad";
import { usePageTitle } from "../usePageTitle";

const SORTS = [
  ["RELEVANCE", "Relevance"],
  ["NAME", "Name"],
  ["PRICE_ASC", "Price: low to high"],
  ["PRICE_DESC", "Price: high to low"],
  ["NEWEST", "Newest"],
] as const;

const TEXT_FILTERS = ["q", "categoryId", "minPrice", "maxPrice", "sort"] as const;

type Filters = Record<(typeof TEXT_FILTERS)[number], string> & { inStock: boolean };

function filtersIn(params: URLSearchParams): Filters {
  return {
    q: params.get("q") ?? "",
    categoryId: params.get("categoryId") ?? "",
    minPrice: params.get("minPrice") ?? "",
    maxPrice: params.get("maxPrice") ?? "",
    sort: params.get("sort") ?? "RELEVANCE",
    inStock: params.get("inStock") === "true",
  };
}

function addressOf(filters: Filters) {
  const next = new URLSearchParams();
  TEXT_FILTERS.forEach((key) => {
    const value = filters[key].trim();
    if (value) next.set(key, value);
  });
  if (filters.inStock) next.set("inStock", "true");
  return next;
}

export function ShopPage() {
  const [params, setParams] = useSearchParams();
  const query = params.toString();
  const products = useLoad(query, () => api.products(new URLSearchParams(query)));
  const categories = useLoad("categories", () => api.categories());
  const { add } = useCart();
  const [added, setAdded] = useState<string>();
  const applied = new URLSearchParams(params);
  applied.delete("page");
  const filterQuery = applied.toString();
  const [draft, setDraft] = useDraft(filtersIn(params), filterQuery);
  usePageTitle("Products");

  const edit = (change: Partial<Filters>) => setDraft((current) => ({ ...current, ...change }));

  const choose = (event: ChangeEvent<HTMLInputElement | HTMLSelectElement>, change: Partial<Filters>) => {
    const next = { ...draft, ...change };
    setDraft(next);
    if (event.currentTarget.form?.reportValidity()) setParams(addressOf(next));
  };

  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setParams(addressOf(draft));
  };

  const goTo = (page: number) => {
    const next = new URLSearchParams(params);
    next.set("page", String(page));
    setParams(next);
    window.scrollTo({ top: 0, behavior: "instant" });
  };

  return (
    <>
      <h1 className="h3 mb-3" tabIndex={-1}>
        Products
      </h1>
      <form className="row g-2 align-items-end mb-4 mx-0 surface search-panel" onSubmit={search} role="search">
        <div className="col-12 col-lg-4">
          <label className="form-label small" htmlFor="q">
            Search
          </label>
          <input
            id="q"
            name="q"
            className="form-control"
            placeholder="Name, description or SKU"
            value={draft.q}
            onChange={(event) => edit({ q: event.target.value })}
          />
        </div>
        <div className="col-6 col-lg-2">
          <label className="form-label small" htmlFor="categoryId">
            Category
          </label>
          <select
            id="categoryId"
            name="categoryId"
            className="form-select"
            value={draft.categoryId}
            onChange={(event) => choose(event, { categoryId: event.target.value })}
          >
            <option value="">All</option>
            {categories.data?.map((category) => (
              <option key={category.id} value={category.id}>
                {category.name}
              </option>
            ))}
          </select>
        </div>
        <div className="col-3 col-lg-1">
          <label className="form-label small" htmlFor="minPrice">
            Min $
          </label>
          <input
            id="minPrice"
            name="minPrice"
            type="number"
            min="0"
            step="0.01"
            className="form-control"
            value={draft.minPrice}
            onChange={(event) => edit({ minPrice: event.target.value })}
          />
        </div>
        <div className="col-3 col-lg-1">
          <label className="form-label small" htmlFor="maxPrice">
            Max $
          </label>
          <input
            id="maxPrice"
            name="maxPrice"
            type="number"
            min="0"
            step="0.01"
            className="form-control"
            value={draft.maxPrice}
            onChange={(event) => edit({ maxPrice: event.target.value })}
          />
        </div>
        <div className="col-6 col-lg-2">
          <label className="form-label small" htmlFor="sort">
            Sort
          </label>
          <select id="sort" name="sort" className="form-select" value={draft.sort} onChange={(event) => choose(event, { sort: event.target.value })}>
            {SORTS.map(([value, label]) => (
              <option key={value} value={value}>
                {label}
              </option>
            ))}
          </select>
        </div>
        <div className="col-6 col-lg-2 d-flex gap-2 align-items-center">
          <div className="form-check mb-0">
            <input
              id="inStock"
              name="inStock"
              type="checkbox"
              className="form-check-input"
              checked={draft.inStock}
              onChange={(event) => choose(event, { inStock: event.target.checked })}
            />
            <label className="form-check-label small" htmlFor="inStock">
              In stock
            </label>
          </div>
          <button type="submit" className="btn btn-primary ms-auto">
            Search
          </button>
        </div>
      </form>

      <div role="status">
        {added && (
          <div className="alert alert-success py-2">
            Added {added} to the cart. <Link to="/cart">View cart</Link>
          </div>
        )}
      </div>
      {products.error && (
        <div className="alert alert-danger" role="alert">
          {products.error}
        </div>
      )}
      {products.data && (
        <>
          <p className="text-body-secondary small">
            <span role="status">
              {products.data.total} {products.data.total === 1 ? "product" : "products"}
              {products.data.totalPages > 1 && ` · page ${products.data.page + 1} of ${products.data.totalPages}`}
            </span>
            {filterQuery && (
              <>
                {" · "}
                <Link to="/">Clear filters</Link>
              </>
            )}
          </p>
          {products.data.items.length === 0 ? (
            <p>No products match these filters.</p>
          ) : (
            <div className="row row-cols-1 row-cols-sm-2 row-cols-lg-3 g-3">
              {products.data.items.map((product) => (
                <div className="col" key={product.id}>
                  <div className="card h-100 product-card">
                    <Thumb name={product.name} category={product.category} />
                    <div className="card-body d-flex flex-column">
                      <h2 className="card-title h6">
                        <Link to={`/products/${encodeURIComponent(product.sku)}`}>{product.name}</Link>
                      </h2>
                      <p className="small text-body-secondary mb-2">
                        <span className="mono">{product.sku}</span> · {product.category}
                      </p>
                      {product.description && <p className="card-text small line-clamp-3">{product.description}</p>}
                      <div className="mt-auto d-flex align-items-center justify-content-between">
                        <span className="price">{money(product.price)}</span>
                        <StockBadge available={product.available} />
                      </div>
                    </div>
                    <div className="card-footer bg-transparent border-0 pt-0 pb-3 px-3">
                      <button
                        type="button"
                        className="btn btn-outline-primary btn-sm w-100"
                        disabled={product.available <= 0}
                        onClick={() => {
                          add(product.id, 1);
                          setAdded(product.name);
                        }}
                      >
                        Add to cart
                      </button>
                    </div>
                  </div>
                </div>
              ))}
            </div>
          )}
          <Pagination page={products.data.page} totalPages={products.data.totalPages} onChange={goTo} />
        </>
      )}
      {products.loading && !products.data && <p className="text-body-secondary">Loading products…</p>}
    </>
  );
}
