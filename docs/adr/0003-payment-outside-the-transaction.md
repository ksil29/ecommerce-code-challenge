# 3. Payment outside the database transaction, settled by reconciliation

**Status:** accepted

## Context

A payment provider can take seconds to answer or not answer at all. Holding row locks while waiting would block other buyers, and a crash between the charge and the status update must not leave money and stock inconsistent.

## Decision

1. Reserve stock and create the order as `PENDING_PAYMENT` in one transaction, and commit.
2. Call the provider outside any transaction, with the order id as the idempotency key.
3. Record the outcome in a second short transaction. Every transition is conditional on the current status, and a decline or expiry returns the stock in the same transaction.
4. A scheduled job, guarded by ShedLock, asks the provider about orders pending longer than the timeout and settles them.

## Consequences

- No database lock waits on the network.
- A crash at any point leaves an order the reconciliation job can settle.
- A charge that arrives after expiry is detected, logged and audited as needing a refund; with a real provider the refund would be automatic.
- With a real provider, signed webhooks would replace most of the polling, and an outbox would publish order events.
