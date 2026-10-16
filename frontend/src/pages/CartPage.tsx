import { useEffect, useState } from "react";
import { Link, useLocation } from "react-router";
import { api } from "../api";
import { useCart } from "../cart";
import { toRequest } from "../cartItems";
import { QuantityInput } from "../components/QuantityInput";
import { money } from "../format";
import { useSettings } from "../settings";
import { useLoad } from "../useLoad";
import { usePageTitle } from "../usePageTitle";

export function CartPage() {
  const { items, set, remove } = useCart();
  const { maxQuantity } = useSettings();
  const location = useLocation();
  const notice = (location.state as { notice?: string } | null)?.notice;
  const request = toRequest(items);
  const requestKey = JSON.stringify(request);
  const quote = useLoad(requestKey, () => (request.length > 0 ? api.quote(request) : Promise.resolve(null)));
  usePageTitle("Cart");

  const missing = quote.data?.missingProductIds;
  const [removedMissing, setRemovedMissing] = useState(false);
  if (!removedMissing && missing && missing.length > 0) setRemovedMissing(true);
  useEffect(() => {
    if (missing && missing.length > 0) remove(missing);
  }, [missing, remove]);

  return (
    <>
      <h1 className="h3 mb-3" tabIndex={-1}>
        Your cart
      </h1>
      {notice && (
        <div className="alert alert-warning" role="alert">
          {notice}
        </div>
      )}
      <div role="status">
        {removedMissing && (
          <div className="alert alert-info">Some products were removed from the catalog and taken out of your cart.</div>
        )}
      </div>
      {quote.error && (
        <div className="alert alert-danger" role="alert">
          {quote.error}
        </div>
      )}
      {request.length === 0 ? (
        <p>
          The cart is empty. <Link to="/">Browse products</Link>
        </p>
      ) : (
        quote.data && (
          <>
            <div className="table-responsive">
              <table className="table align-middle">
                <thead>
                  <tr>
                    <th>Product</th>
                    <th className="text-end">Price</th>
                    <th>Quantity</th>
                    <th className="text-end">Total</th>
                    <th>
                      <span className="visually-hidden">Actions</span>
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {quote.data.lines.map((line) => (
                    <tr key={line.product.id} className={line.available ? undefined : "table-warning"}>
                      <td>
                        <Link to={`/products/${encodeURIComponent(line.product.sku)}`}>{line.product.name}</Link>
                        <div className="small text-body-secondary mono">{line.product.sku}</div>
                        {!line.available && (
                          <div className="small text-danger">
                            {line.product.archived ? "No longer sold" : `Only ${line.product.available} in stock`}
                          </div>
                        )}
                      </td>
                      <td className="text-end">{money(line.product.price)}</td>
                      <td>
                        <QuantityInput
                          className="form-control form-control-sm quantity-input"
                          max={maxQuantity}
                          value={items[line.product.id] ?? line.quantity}
                          label={`Quantity of ${line.product.name}`}
                          onChange={(value) => set(line.product.id, value)}
                        />
                      </td>
                      <td className="text-end">{money(line.lineTotal)}</td>
                      <td className="text-end">
                        <button type="button" className="btn btn-link btn-sm text-danger" onClick={() => remove([line.product.id])}>
                          Remove
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
                <tfoot>
                  <tr>
                    <th colSpan={3} className="text-end">
                      Total
                    </th>
                    <th className="text-end">{money(quote.data.total)}</th>
                    <td />
                  </tr>
                </tfoot>
              </table>
            </div>
            <div className="d-flex justify-content-between align-items-center">
              <Link to="/">Continue shopping</Link>
              {quote.data.canCheckout ? (
                <Link className="btn btn-primary" to="/checkout">
                  Checkout
                </Link>
              ) : (
                <span className="text-body-secondary small">Change or remove the lines marked above to check out</span>
              )}
            </div>
          </>
        )
      )}
    </>
  );
}
