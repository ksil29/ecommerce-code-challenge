import { useRef, useState, type FormEvent } from "react";
import { Link, useNavigate } from "react-router";
import { api, ApiError, errorMessage } from "../api";
import { useAuth } from "../auth";
import { useCart } from "../cart";
import { toRequest } from "../cartItems";
import { forgetAttempt, purchaseSignature, resumeAttempt, type Attempt } from "../checkoutAttempt";
import { money } from "../format";
import type { Quote } from "../types";
import { useErrorFocus } from "../useErrorFocus";
import { useLoad } from "../useLoad";
import { usePageTitle } from "../usePageTitle";

const TEST_CARDS = [
  ["4242 4242 4242 4242", "Approved"],
  ["4000 0000 0000 0002", "Declined"],
  ["4000 0000 0000 9995", "Insufficient funds"],
  ["4000 0000 0000 0119", "Processor timeout: the order waits and is settled by the reconciliation job"],
] as const;

const FIELDS = ["customerName", "customerEmail", "cardNumber"] as const;

const LOST_ANSWER = "We did not get an answer from the server. Press Pay again; you will not be charged twice.";

const OUT_OF_STOCK = "Some items are no longer available in that quantity. Please review your cart.";

function explain(error: unknown): string {
  if (error instanceof ApiError && error.code === "PRICE_CHANGED") {
    return `The total changed to ${money(String(error.body.total))}. Check the order and confirm again.`;
  }
  if (!(error instanceof ApiError) || error.status >= 500) return LOST_ANSWER;
  return errorMessage(error, FIELDS);
}

interface CheckoutFormProps {
  defaults: { customerName: string; customerEmail: string };
  request: { productId: number; quantity: number }[];
  total: string;
  onTotalChanged: () => void;
}

function CheckoutForm({ defaults, request, total, onTotalChanged }: CheckoutFormProps) {
  const { clear } = useCart();
  const navigate = useNavigate();
  const attempt = useRef<Attempt | null>(null);
  const [form, setForm] = useState({ ...defaults, cardNumber: "" });
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [message, setMessage] = useState<string>();
  const [submitting, setSubmitting] = useState(false);
  const { formRef, messageRef, failed } = useErrorFocus();

  const endAttempt = () => {
    attempt.current = null;
    forgetAttempt();
  };

  const submit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    const current = resumeAttempt(purchaseSignature(request, total), attempt.current);
    attempt.current = current;
    setSubmitting(true);
    setErrors({});
    setMessage(undefined);
    try {
      const order = await api.placeOrder(current.key, { items: request, expectedTotal: total, ...form });
      endAttempt();
      if (order.status !== "PAYMENT_FAILED") clear();
      navigate(`/orders/${order.id}`);
    } catch (error) {
      if (error instanceof ApiError && error.code === "OUT_OF_STOCK") {
        navigate("/cart", { state: { notice: OUT_OF_STOCK } });
        return;
      }
      if (error instanceof ApiError && error.status === 422) endAttempt();
      if (error instanceof ApiError && error.code === "PRICE_CHANGED") onTotalChanged();
      setErrors(error instanceof ApiError && error.status === 400 ? error.fieldErrors() : {});
      setMessage(explain(error));
      failed();
    } finally {
      setSubmitting(false);
    }
  };

  const field = (name: (typeof FIELDS)[number], label: string, type: string, autoComplete: string) => {
    const error = errors[name];
    return (
      <div className="mb-3">
        <label className="form-label" htmlFor={name}>
          {label}
        </label>
        <input
          id={name}
          type={type}
          autoComplete={autoComplete}
          className={`form-control ${error ? "is-invalid" : ""}`}
          value={form[name]}
          onChange={(event) => setForm({ ...form, [name]: event.target.value })}
          aria-invalid={error ? true : undefined}
          aria-describedby={error ? `${name}-error` : undefined}
          required
        />
        {error && (
          <div id={`${name}-error`} className="invalid-feedback">
            {error}
          </div>
        )}
      </div>
    );
  };

  return (
    <>
      {message && (
        <div ref={messageRef} tabIndex={-1} className="alert alert-warning" role="alert">
          {message}
        </div>
      )}
      <form ref={formRef} onSubmit={(event) => void submit(event)} noValidate>
        {field("customerName", "Name", "text", "name")}
        {field("customerEmail", "Email", "email", "email")}
        {field("cardNumber", "Card number", "text", "off")}
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? "Placing the order…" : `Pay ${money(total)}`}
        </button>
      </form>
      <div className="card mt-4">
        <div className="card-body">
          <h2 className="h6">Test cards (the payment is simulated)</h2>
          <table className="table table-sm mb-0">
            <tbody>
              {TEST_CARDS.map(([number, outcome]) => (
                <tr key={number}>
                  <td className="mono">{number}</td>
                  <td className="small">{outcome}</td>
                  <td className="text-end">
                    <button
                      type="button"
                      className="btn btn-link btn-sm"
                      aria-label={`Use card ${number}`}
                      onClick={() => setForm({ ...form, cardNumber: number })}
                    >
                      Use
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>
    </>
  );
}

function Summary({ quote }: { quote: Quote }) {
  return (
    <div className="card">
      <div className="card-body">
        <h2 className="h6">Order summary</h2>
        <ul className="list-unstyled mb-2">
          {quote.lines.map((line) => (
            <li key={line.product.id} className="d-flex justify-content-between small py-1">
              <span>
                {line.quantity} × {line.product.name}
              </span>
              <span>{money(line.lineTotal)}</span>
            </li>
          ))}
        </ul>
        <div className="d-flex justify-content-between fw-semibold border-top pt-2">
          <span>Total</span>
          <span>{money(quote.total)}</span>
        </div>
      </div>
    </div>
  );
}

export function CheckoutPage() {
  const { items } = useCart();
  const { me } = useAuth();
  const request = toRequest(items);
  const requestKey = JSON.stringify(request);
  const quote = useLoad(requestKey, () => (request.length > 0 ? api.quote(request) : Promise.resolve(null)));
  usePageTitle("Checkout");

  const heading = (
    <h1 className="h3 mb-3" tabIndex={-1}>
      Checkout
    </h1>
  );

  if (request.length === 0) {
    return (
      <>
        {heading}
        <p>
          The cart is empty. <Link to="/">Browse products</Link>
        </p>
      </>
    );
  }
  if (quote.error) {
    return (
      <>
        {heading}
        <div className="alert alert-danger" role="alert">
          {quote.error}
        </div>
      </>
    );
  }
  if (!quote.data || me === null) return <p className="text-body-secondary">Loading…</p>;
  if (!quote.data.canCheckout) {
    return (
      <>
        {heading}
        <div className="alert alert-warning" role="alert">
          Some items in the cart are not available any more. <Link to="/cart">Review the cart</Link>
        </div>
      </>
    );
  }

  return (
    <div className="row g-4">
      <div className="col-lg-7">
        {heading}
        <CheckoutForm
          defaults={{ customerName: me.fullName ?? "", customerEmail: me.email ?? "" }}
          request={request}
          total={quote.data.total}
          onTotalChanged={quote.reload}
        />
      </div>
      <div className="col-lg-5">
        <Summary quote={quote.data} />
      </div>
    </div>
  );
}
