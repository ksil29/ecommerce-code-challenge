import { useState, type FormEvent } from "react";
import { Link, useLocation, useSearchParams } from "react-router";
import { api, errorMessage } from "../../api";
import { Pagination } from "../../components/Pagination";
import { money } from "../../format";
import type { Product } from "../../types";
import { useDraft } from "../../useDraft";
import { useLoad } from "../../useLoad";
import { usePageTitle } from "../../usePageTitle";

export function AdminProductsPage() {
  const [params, setParams] = useSearchParams();
  const location = useLocation();
  const notice = (location.state as { notice?: string } | null)?.notice;
  const query = params.toString();
  const products = useLoad(query, () => {
    const search = new URLSearchParams(query);
    search.set("includeArchived", "true");
    search.set("size", "50");
    if (!search.get("sort")) search.set("sort", "NAME");
    return api.products(search);
  });
  const [message, setMessage] = useState<string>();
  const [busy, setBusy] = useState(false);
  const appliedText = params.get("q") ?? "";
  const [text, setText] = useDraft(appliedText, appliedText);
  usePageTitle("Products · Admin");

  const search = (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const q = text.trim();
    setParams(q ? { q } : {});
  };

  const toggleArchived = async (product: Product) => {
    setMessage(undefined);
    setBusy(true);
    try {
      if (product.archived) {
        await api.admin.restore(product.id, product.version);
      } else {
        await api.admin.archive(product.id, product.version);
      }
    } catch (error) {
      setMessage(errorMessage(error));
    } finally {
      setBusy(false);
    }
    products.reload();
  };

  const goTo = (page: number) => {
    const next = new URLSearchParams(params);
    next.set("page", String(page));
    setParams(next);
  };

  return (
    <>
      <div className="d-flex flex-wrap gap-2 align-items-center mb-3">
        <h1 className="h4 mb-0 me-2" tabIndex={-1}>
          Products
        </h1>
        <form className="d-flex gap-2 me-auto" onSubmit={search} role="search">
          <label className="visually-hidden" htmlFor="admin-search">
            Search products
          </label>
          <input
            id="admin-search"
            name="q"
            className="form-control"
            placeholder="Search products"
            value={text}
            onChange={(event) => setText(event.target.value)}
          />
          <button type="submit" className="btn btn-outline-secondary">
            Search
          </button>
        </form>
        <Link className="btn btn-primary" to="/admin/products/new">
          New product
        </Link>
      </div>
      {notice && (
        <div className="alert alert-success" role="status">
          {notice}
        </div>
      )}
      {message && (
        <div className="alert alert-warning" role="alert">
          {message}
        </div>
      )}
      {products.error && (
        <div className="alert alert-danger" role="alert">
          {products.error}
        </div>
      )}
      {products.data && (
        <>
          <div className="table-responsive">
            <table className="table table-sm align-middle">
              <thead>
                <tr>
                  <th>SKU</th>
                  <th>Name</th>
                  <th>Category</th>
                  <th className="text-end">Price</th>
                  <th className="text-end">On hand</th>
                  <th className="text-end">Reserved</th>
                  <th className="text-end">Available</th>
                  <th>Status</th>
                  <th>
                    <span className="visually-hidden">Actions</span>
                  </th>
                </tr>
              </thead>
              <tbody>
                {products.data.items.map((product) => (
                  <tr key={product.id} className={product.archived ? "text-body-secondary" : undefined}>
                    <td className="mono">{product.sku}</td>
                    <td>{product.name}</td>
                    <td>{product.category}</td>
                    <td className="text-end">{money(product.price)}</td>
                    <td className="text-end">{product.stock}</td>
                    <td className="text-end">{product.reserved}</td>
                    <td className="text-end">{product.available}</td>
                    <td>{product.archived ? <span className="badge text-bg-secondary">Archived</span> : <span className="badge text-bg-success">Active</span>}</td>
                    <td className="text-end text-nowrap">
                      <Link className="btn btn-link btn-sm" to={`/admin/products/${product.id}/edit`}>
                        Edit
                      </Link>
                      <button
                        type="button"
                        className="btn btn-link btn-sm"
                        disabled={busy || products.loading}
                        onClick={() => void toggleArchived(product)}
                      >
                        {product.archived ? "Restore" : "Archive"}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="small text-body-secondary">{products.data.total} products, archived ones included</p>
          <Pagination page={products.data.page} totalPages={products.data.totalPages} onChange={goTo} />
        </>
      )}
    </>
  );
}
