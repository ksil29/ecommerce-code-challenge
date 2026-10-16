import { Link } from "react-router";
import { api } from "../../api";
import { dateTime, money } from "../../format";
import { useLoad } from "../../useLoad";
import { usePageTitle } from "../../usePageTitle";

export function AdminOrdersPage() {
  const orders = useLoad("admin-orders", () => api.admin.orders());
  usePageTitle("Orders · Admin");

  return (
    <>
      <div className="d-flex align-items-center mb-3">
        <h1 className="h4 me-auto mb-0" tabIndex={-1}>
          Recent orders
        </h1>
        <button type="button" className="btn btn-outline-secondary btn-sm" onClick={orders.reload}>
          Refresh
        </button>
      </div>
      {orders.error && (
        <div className="alert alert-danger" role="alert">
          {orders.error}
        </div>
      )}
      {orders.data && orders.data.length === 0 && <p>No orders yet.</p>}
      {orders.data && orders.data.length > 0 && (
        <div className="table-responsive">
          <table className="table table-sm align-middle">
            <thead>
              <tr>
                <th>Created</th>
                <th>Order</th>
                <th>Customer</th>
                <th className="text-end">Total</th>
                <th>Status</th>
                <th>Details</th>
              </tr>
            </thead>
            <tbody>
              {orders.data.map((order) => (
                <tr key={order.id}>
                  <td className="small">{dateTime(order.createdAt)}</td>
                  <td>
                    <Link className="mono small" to={`/orders/${order.id}`}>
                      {order.id.slice(0, 8)}
                    </Link>
                  </td>
                  <td>
                    {order.customerName}
                    <div className="small text-body-secondary">{order.customerEmail}</div>
                  </td>
                  <td className="text-end">{money(order.total)}</td>
                  <td>{order.statusLabel}</td>
                  <td className="small text-body-secondary">{order.failureReason ?? order.paymentReference ?? ""}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </>
  );
}
