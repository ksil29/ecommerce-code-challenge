# 2. Stock reservation with a conditional update, and idempotency keys

**Status:** accepted

## Context

Concurrent buyers must never take more units than exist, and a retried or double-submitted checkout must not create a second order or a second charge.

## Decision

- Keep two numbers per product: `stock`, the units on hand, and `reserved`, the units held by orders waiting for payment; shoppers can buy `stock - reserved`.
- Reserve with one statement per product: `UPDATE product SET reserved = reserved + :q WHERE id = :id AND stock - reserved >= :q RETURNING price`. If no row is updated the whole order rolls back. A payment lowers both numbers; a failed or expired payment lowers only `reserved`.
- Lock products in id order, so two multi-item orders cannot deadlock each other.
- Compute the total from the prices returned by the update and compare it with the total the shopper confirmed.
- Require an `Idempotency-Key` header, following the IETF Idempotency-Key draft and Stripe's rules. The key is unique in the database and stored with a SHA-256 fingerprint of the purchase: the account (or guest), the items with their quantities, and the total. A repeat with the same fingerprint returns the existing order; a different fingerprint gets `422 IDEMPOTENCY_KEY_REUSED`, which also keeps one account from replaying another's key. Contact and card details are not in the fingerprint: they do not change what is bought, and a shopper retyping them after a lost answer should get the order back, not an error.
- The checkout transaction takes a PostgreSQL advisory lock on the key first, so two requests with one key run one after the other and the second returns the first one's order, even when the first one took the last unit. A repeat that arrives while the payment is still running gets the order as it stands, pending, rather than the draft's `409`, because the order page keeps checking the status.
- Keys are forgotten after `app.orders.idempotency-key-ttl` (a day) by an hourly job; after that the same key starts a new order.
- A CSV import and the admin form set only `stock`, so they cannot overwrite units that orders hold. A file with fewer units than are held gets a warning in the import report; if the held units are paid for anyway, `stock` stops at zero and the audit log records a `STOCK_SHORTFALL` with the missing units.

## Consequences

- No overselling under concurrency, proven by a test with 200 buyers for 50 units.
- Row locks live only for the duration of a short transaction.
- Buyers of a single very popular product queue on one row. For flash sales the reservation would move to Redis or a queue (see the README's scaling notes).
