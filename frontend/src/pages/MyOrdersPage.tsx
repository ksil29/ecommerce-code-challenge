import { Link } from "react-router";
import { api } from "../api";
import { dateTime, money } from "../format";
import { useLoad } from "../useLoad";
import { usePageTitle } from "../usePageTitle";

export function MyOrdersPage() {
  const orders = useLoad("my-orders", () => api.myOrders());
  usePageTitle("My orders");

  return (
    <>
      <h1 className="h3 mb-3" tabIndex={-1}>
        My orders
      </h1>
      {orders.error && (
        <div className="alert alert-danger" role="alert">
          {orders.error}
        </div>
      )}
      {orders.data && orders.data.length === 0 && (
        <p>
          No orders yet. <Link to="/">Browse products</Link>
        </p>
      )}
      {orders.data && orders.data.length > 0 && (
        <div className="surface table-responsive">
          <table className="table align-middle mb-0">
            <thead>
              <tr>
                <th>Date</th>
                <th>Order</th>
                <th className="text-end">Total</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {orders.data.map((order) => (
                <tr key={order.id}>
                  <td>{dateTime(order.createdAt)}</td>
                  <td>
                    <Link className="mono small" to={`/orders/${order.id}`}>
                      {order.id.slice(0, 8)}
                    </Link>
                  </td>
                  <td className="text-end">{money(order.total)}</td>
                  <td>{order.statusLabel}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
