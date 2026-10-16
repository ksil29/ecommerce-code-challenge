# Tests

The full list behind the summary in the [README](../README.md#quality-and-testing). The commands are in the README under [Checks](../README.md#checks).

- **189 backend tests**, on a real PostgreSQL through Testcontainers where the database matters:
  - rules and CSV parsing (unit), and the example file end to end; a row with a zero byte is refused while the other rows go in, a broken header line is a clear error, and a long file name is cleaned before it is stored; an import sets the on-hand count and leaves the units held by unpaid orders alone, and a file with fewer units than are held gets a warning, with the shortfall recorded if they are paid for;
  - search, optimistic locking, archive and restore;
  - checkout: approval, decline, insufficient funds, price changes, missing stock, a total too large for its column, rollback of a multi-line order, idempotent retries, an `Idempotency-Key` reused for a different purchase or by another account (`422`) and forgotten after a day, a charge that lands while the order is pending, a charge that lands after the order expired (flagged for a refund, stock returned once), and a snapshot of names and prices that later product changes do not touch;
  - **concurrency:** 200 buyers for 50 units sell exactly 50; the same idempotency key sent from two threads at once creates one order, also when only one unit is left; two imports with overlapping SKUs in opposite order do not deadlock, and neither does an import next to a checkout that holds the same products;
  - accounts: registration, password policy, Argon2 hashes, lockout for 15 minutes (audited as `guest`, logged by account id rather than email) and unlock, a successful sign-in clearing earlier failures, the same answer for an unknown user and a wrong password, a customer's order shown only to that customer and admins; at most four password hashes run at once; password lengths and Argon2 costs come from the settings, which refuse values below NIST's and OWASP's floors;
  - the admin account: no `ADMIN_PASSWORD` on the first start or a weak one stops the start, a new one replaces the password and ends the admin's sessions, and the password never reaches the error or the log;
  - the configuration: no credentials, API docs or trusted proxies in the base settings, the demo ones only in the `local` profile; on a running server, `X-Forwarded-For` is ignored from a peer that is not a trusted proxy and counted per client behind one;
  - the HTTP API, including a **role matrix** of every endpoint for a guest, a customer and an admin (60 checks), quantities outside 1 to the configured maximum (also summed over the lines of one product), the public settings, order lists without lines, bad search parameters, rate limiting (a percent-encoded path counts too), and on a running server the session cookie flags (`Secure`, `HttpOnly`, `SameSite=Lax`) and no session for a refused request;
  - the audit trail.
- **147 frontend tests** (Vitest and Testing Library; the API is stubbed, and each test drives a page the way a user does):
  - the API client: the CSRF header, the idempotency key, error mapping, every endpoint;
  - checkout: approval, decline, a changed price, missing stock, field errors, a `422` for a reused key, and a retry after a lost answer that reuses the idempotency key, also after a reload;
  - an expired session: the shopper is signed out and sent to sign in, then back to the same page;
  - the shop settings from the server: the quantity limit in the cart, the polling time of a pending payment, the password length on registration, the link to the API documentation only when the server publishes it;
  - the return address after sign-in, which must stay on the shop's own origin;
  - the cart: kept between visits, shared between tabs, products that left the catalog, a quantity typed while the cart is priced again; a category filter that survives Back; polling of a pending payment until the payment timeout has passed;
  - the admin forms (a stale version offers the current values, the reserved units shown next to the on-hand count) and the route guards;
  - accessibility: errors announced and tied to their fields, focus on the first invalid field and on each page's heading, page titles, and the menu at phone width.
- **Coverage:** backend 96% of lines (Kover, without the load-test code), frontend 99% (V8); both runs fail below 85%.
- **End to end (Playwright, 20 tests):**
  - a guest buys; a declined card keeps the cart; a product named `<script>…` renders as text and opens no dialog;
  - a customer registers, buys, sees the order history and is kept out of the admin area; a sign-in link that points to another site lands in the shop instead;
  - the admin imports the example file and finds it in the audit log; an archived product leaves the shop until it is restored;
  - the API documentation loads under the security headers;
  - **accessibility:** axe finds no serious or critical WCAG 2.1 AA problem on the shop, product, cart, checkout, sign-in, registration and admin pages, on a filled cart, on checkout and sign-in after an error, and at phone width.
  - The registration test sends its own `X-Forwarded-For` address, which the `local` profile accepts from the Docker network (production trusts no proxy until one is configured), so repeated local runs stay under the registration limit of 5 per 10 minutes.
- **Static analysis:** detekt and Kotlin warnings as errors; ESLint with the React hooks rules and strict TypeScript on the frontend.

The load test and the security scans are in [operations.md](operations.md).
