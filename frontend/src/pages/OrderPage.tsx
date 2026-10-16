import { useEffect, useState } from "react";
import { Link, useParams } from "react-router";
import { api } from "../api";
import { dateTime, money } from "../format";
import { useSettings } from "../settings";
import type { OrderStatus } from "../types";
import { useLoad } from "../useLoad";
import { usePageTitle } from "../usePageTitle";

const BADGE: Record<OrderStatus, string> = {
  PAID: "text-bg-success",
  PENDING_PAYMENT: "text-bg-warning",
  PAYMENT_FAILED: "text-bg-danger",
  EXPIRED: "text-bg-secondary",
};

const FAST_POLLS = 12;
const FAST_DELAY_SECONDS = 5;
const SLOW_DELAY_SECONDS = 30;

function secondsPolled(polls: number): number {
  const fast = Math.min(polls, FAST_POLLS);
  return fast * FAST_DELAY_SECONDS + (polls - fast) * SLOW_DELAY_SECONDS;
}

export function OrderPage() {
  const { id = "" } = useParams();
  const { paymentTimeoutSeconds, reconcileIntervalSeconds } = useSettings();
  const order = useLoad(id, () => api.order(id));
  const [polls, setPolls] = useState(0);
  const status = order.data?.status;
  const { reload } = order;
  const pollingLimit = paymentTimeoutSeconds + 2 * reconcileIntervalSeconds;
  const polling = status === "PENDING_PAYMENT" && secondsPolled(polls) <= pollingLimit;
  usePageTitle(`Order ${id.slice(0, 8)}`);

  useEffect(() => {
    if (!polling) return;
    const timer = window.setTimeout(
      () => {
        setPolls((count) => count + 1);
        reload();
      },
      (polls < FAST_POLLS ? FAST_DELAY_SECONDS : SLOW_DELAY_SECONDS) * 1000,
    );
    return () => window.clearTimeout(timer);
  }, [polling, polls, reload]);

  if (order.error) {
    return (
      <>
        <h1 className="h3" tabIndex={-1}>
          Order
        </h1>
        <div className="alert alert-warning" role="alert">
          {order.error}
        </div>
      </>
    );
  }
  if (!order.data) return <p className="text-body-secondary">Loading…</p>;
  const data = order.data;

  return (
    <div className="row">
      <div className="col-lg-8">
        <h1 className="h3" tabIndex={-1}>
          Order <span className="mono fs-6 text-body-secondary">{data.id}</span>
        </h1>
        <div role="status">
          <p>
            <span className={`badge ${BADGE[data.status]}`}>{data.statusLabel}</span>
            <span className="text-body-secondary small ms-2">{dateTime(data.createdAt)}</span>
          </p>
          {data.status === "PAID" && (
            <div className="alert alert-success">
              Thank you, {data.customerName}. The payment was approved (reference <span className="mono">{data.paymentReference}</span>).
            </div>
          )}
          {data.status === "PENDING_PAYMENT" && (
            <div className="alert alert-warning">
              The payment processor has not confirmed the payment yet. If it is not confirmed within the payment timeout, the
              order expires and the items go back to stock.{" "}
              {polling ? (
                "This page keeps checking."
              ) : (
                <button type="button" className="btn btn-sm btn-outline-dark ms-1" onClick={reload}>
                  Check again
                </button>
              )}
            </div>
          )}
          {data.status === "PAYMENT_FAILED" && (
            <div className="alert alert-danger">
              The payment did not go through: {data.failureReason}. Your cart is still there.{" "}
              <Link to="/checkout">Try another card</Link>
            </div>
          )}
          {data.status === "EXPIRED" && (
            <div className="alert alert-secondary">
              {data.failureReason}. The order expired and the items went back to stock.
            </div>
          )}
        </div>
        <table className="table">
          <thead>
            <tr>
              <th>Product</th>
              <th className="text-end">Price</th>
              <th className="text-end">Quantity</th>
              <th className="text-end">Total</th>
            </tr>
          </thead>
          <tbody>
            {data.lines.map((line) => (
              <tr key={line.sku}>
                <td>
                  {line.productName}
                  <div className="small text-body-secondary mono">{line.sku}</div>
                </td>
                <td className="text-end">{money(line.unitPrice)}</td>
                <td className="text-end">{line.quantity}</td>
                <td className="text-end">{money(line.lineTotal)}</td>
              </tr>
            ))}
          </tbody>
          <tfoot>
            <tr>
              <th colSpan={3} className="text-end">
                Total
              </th>
              <th className="text-end">{money(data.total)}</th>
            </tr>
          </tfoot>
        </table>
        <p className="small text-body-secondary">
          {data.customerEmail}
          {data.cardLast4 && <> · card ending {data.cardLast4}</>}
        </p>
        <Link to="/">Back to the shop</Link>
      </div>
    </div>
  );
}
