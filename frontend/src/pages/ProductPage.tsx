import { useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api";
import { useCart } from "../cart";
import { QuantityInput } from "../components/QuantityInput";
import { StockBadge } from "../components/StockBadge";
import { Thumb } from "../components/Thumb";
import { money } from "../format";
import { useSettings } from "../settings";
import { useLoad } from "../useLoad";
import { usePageTitle } from "../usePageTitle";

export function ProductPage() {
  const { sku = "" } = useParams();
  const product = useLoad(sku, () => api.product(sku));
  const { add } = useCart();
  const { maxQuantity } = useSettings();
  const [quantity, setQuantity] = useState(1);
  const [added, setAdded] = useState(false);
  usePageTitle(product.data?.name ?? "Product");

  if (product.error) {
    return (
      <>
        <h1 className="h3" tabIndex={-1}>
          Product
        </h1>
        <div className="alert alert-warning" role="alert">
          {product.error} <Link to="/">Back to the shop</Link>
        </div>
      </>
    );
  }
  if (!product.data) return <p className="text-body-secondary">Loading…</p>;

  const item = product.data;
  const max = Math.min(item.available, maxQuantity);

  return (
    <div className="row g-4">
      <div className="col-12">
        <Link className="small" to="/">← All products</Link>
      </div>
      <div className="col-md-5">
        <Thumb name={item.name} category={item.category} large />
      </div>
      <div className="col-md-7">
        <h1 className="h3" tabIndex={-1}>
          {item.name}
        </h1>
        <p className="text-body-secondary">
          <span className="mono">{item.sku}</span> · {item.category}
          {item.weightKg !== null && <> · {item.weightKg} kg</>}
        </p>
        {item.description && <p>{item.description}</p>}
        <p className="price fs-3 mb-1">{money(item.price)}</p>
        <p>
          <StockBadge available={item.available} />
        </p>
        <div role="status">
          {added && (
            <div className="alert alert-success py-2">
              Added to the cart. <Link to="/cart">View cart</Link>
            </div>
          )}
        </div>
        {item.available > 0 && (
          <div className="d-flex gap-2 align-items-center">
            <label className="visually-hidden" htmlFor="quantity">
              Quantity
            </label>
            <QuantityInput id="quantity" value={quantity} max={max} onChange={setQuantity} />
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                add(item.id, quantity);
                setAdded(true);
              }}
            >
              Add to cart
            </button>
          </div>
        )}
      </div>
    </div>
  );
}
