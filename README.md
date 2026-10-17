# E-commerce code challenge

A shop with a product catalog, CSV import, search, a cart and checkout with a simulated payment, optional customer accounts, and an admin back office with an audit log.

**Stack:** Kotlin 2.4, Spring Boot 4.1 on Java 25, PostgreSQL 18, Liquibase, Spring Security, React with TypeScript (Vite), Gradle, Docker.

## For the reviewer

```bash
docker compose up --build
```

Then open <http://localhost:8080> for the shop and <http://localhost:8080/login> for the admin (user `admin`, password `admin`: demo settings for a local run only, see [configuration](#configuration)). The example CSV, downloaded on **2026-10-14**, is imported on the first start.

| The brief asks for | Where to look |
|---|---|
| A local database | PostgreSQL 18 in `compose.yaml`; the schema in Liquibase changelogs |
| CRUD for products, with a UI | **Admin → Products** (`/admin/products`); deleting a product archives it |
| Import from a CSV | **Admin → Import** (`/admin/import`); [what happens to each row of the example file](#the-example-csv) |
| Search, with a UI | The shop's front page: whole or partial words, category, price, stock and sorting |
| Purchase with a fake payment, with a UI | Cart → Checkout; [card numbers for each outcome](#try-a-purchase) |
| Runnable as a Docker container | `Dockerfile`, `compose.yaml` |
| Decisions, approach, alternatives | [Questions](#questions-and-the-defaults-the-app-follows), [decisions](#decisions), [alternatives](#alternatives-considered), [ADRs](docs/adr) |
| Instructions to run locally | [Run it](#run-it) |
| No comments in the code if AI was used | [AI](#ai) |

What I would look at first:

- **Stock cannot be oversold.** Each product keeps two numbers, on hand and reserved. One conditional `UPDATE … SET reserved = reserved + :quantity WHERE stock - reserved >= :quantity` per product; a test sends 200 buyers for 50 units, and exactly 50 are sold.
- **A payment is charged once.** Checkout requires an `Idempotency-Key`, so a double click or a retry returns the same order. The payment runs outside the database transaction, and a reconciliation job settles orders the processor never answered.
- **The dirty CSV is handled row by row.** One set of rules serves the import and the admin form, every problem in a row is reported, the valid rows still go in, and importing the same file twice changes nothing.
- **Admins and shoppers are kept apart.** Guests can buy, customer accounts are optional, `/api/admin/**` needs the admin role, and a test checks every endpoint as a guest, a customer and an admin.

What goes beyond the brief (observability, a load test, security scanning, a Helm chart, a signed image) is listed [at the end](#beyond-the-brief) and described in [`docs/operations.md`](docs/operations.md).

## Questions, and the defaults the app follows

The challenge says it is about asking the right questions. When it arrived I asked the recruiter these four. Until there is an answer, the app follows the default in brackets, and each one is a small change if the answer is different.

1. Some SKUs repeat in the CSV with a different price or stock. Should the later row update the product, or should the conflict go to review? *(SKU is the key and the last row wins; the import report shows every conflict.)*
2. What should happen to rows with a blank name, a price of "free" or "$29.99", or negative stock? *(They are rejected with a reason per row, and the valid rows still go in.)*
3. Should a purchase cover one product or a cart, and do shoppers need accounts? *(A cart with guest checkout; accounts are optional and keep the order history; only an admin edits products.)*
4. Is there a catalog size or a number of concurrent buyers to design for? *(Stock is never oversold under concurrent orders, a repeated payment request does not charge twice, and the application can run as several instances.)*

## Run it

```bash
docker compose up --build
```

| What | Where |
|---|---|
| Shop | <http://localhost:8080> |
| Admin | <http://localhost:8080/login>, user `admin`, password `admin` (set `ADMIN_PASSWORD` to change it) |
| API documentation (Swagger UI) | <http://localhost:8080/swagger-ui.html> |
| Health, metrics (Prometheus format) | <http://localhost:8081/actuator/health>, <http://localhost:8081/actuator/prometheus> |

On the first start the example CSV is imported, so the catalog is not empty. Customers can register at `/register` or buy as guests.

### Try a purchase

The payment is simulated. These card numbers produce each outcome:

| Card | Outcome |
|---|---|
| 4242 4242 4242 4242 | Approved |
| 4000 0000 0000 0002 | Declined |
| 4000 0000 0000 9995 | Insufficient funds |
| 4000 0000 0000 0119 | The processor does not answer: the order stays pending, and after the payment timeout (15 minutes) the reconciliation job finds no charge, expires the order and returns the stock |

Any other number that passes the Luhn check is approved.

### Run locally without building the image

Requirements: JDK 17 or newer to start Gradle (the build downloads JDK 25 through the Gradle toolchain), Node 24.15 or newer (or 22.22.2 or newer on Node 22), and Docker.

```bash
./gradlew bootTestRun
```

This starts the backend on port 8080 with the `local` profile and PostgreSQL in a Testcontainers container: the example CSV is imported and the admin password is `admin`.

```bash
cd frontend && npm ci && npm run dev
```

The frontend runs on <http://localhost:5173> and proxies `/api` to port 8080.

### Configuration

`application.yaml` is the production configuration: it holds no credentials, and the session cookie is `Secure`. The application does not start without `DB_URL`, `DB_USER` and `DB_PASSWORD`; the first start also needs `ADMIN_PASSWORD`, and an admin password that breaks the password policy stops the start. `docker compose` and `bootTestRun` turn on the `local` profile ([`application-local.yaml`](src/main/resources/application-local.yaml)), which adds the demo database user, the `admin` password, the example catalog, Swagger UI, and a session cookie that works over plain HTTP. The ports in `compose.yaml` listen only on `127.0.0.1`. Limits and timings (the most units per order line, the payment timeout, how long idempotency keys live, the rate limits and others) are settings under `app.*`, listed in [`docs/operations.md`](docs/operations.md#settings); the browser reads the ones it needs from `GET /api/settings`.

### Checks

| Command | What it runs |
|---|---|
| `./gradlew check` | detekt, 189 backend tests (needs Docker for PostgreSQL) and the 85% line-coverage gate |
| `cd frontend && npm ci && npm run lint && npm test && npm run build` | ESLint, 151 frontend tests with the coverage gate, and the strict TypeScript build of the app and the browser tests |
| `cd frontend && npm ci && npx playwright install chromium && npm run e2e` | 21 browser tests, including accessibility checks, against a running stack (`docker compose up`) |

The commands for the load test, the security scan and the SBOM are in [`docs/operations.md`](docs/operations.md).

## The example CSV

Downloaded on **2026-10-14** from the email that brought the challenge PDF. It is kept unchanged in `src/main/resources/sample/products.csv` (SHA-256 `6fc46b84badb1ac37b8b19a2d0741aa7728d6c93a9fe949b19f676892dc8fd24`).

The file is deliberately dirty. What the importer does with each problem:

| Line | SKU | In the file | Result |
|---|---|---|---|
| 4 | WM-042 | price `$29.99` | rejected: not a plain decimal |
| 7 | YM-015 | price `free` | rejected |
| 16 | DL-007 | stock `-5` | rejected |
| 20 | XS-001 | name `<script>alert('xss')</script>` | imported as text; the page shows it as text |
| 25 | HD-099 | empty name | rejected |
| 29 | SQL-001 | name `Robert'); DROP TABLE products;--` | imported as text; queries are parameterized, so it never runs |
| 36 | RS-001 | the SKU again, new price and stock | replaces line 2 and is reported as a conflict |
| 41 | WS-001 | name made of spaces | rejected |
| 47 | MB-001 | price `0.00` | rejected: the price must be above zero |
| 50 | GK-088 | empty weight | imported without a weight, with a warning |
| 51 | VC-001 | stock `0` | imported, shown as out of stock |
| 52 | GC-025 | empty category, stock 99999, weight 0 | rejected for the category; the other two values are valid |
| 53, 59 | CI-001, QI-001 | a comma and quotes inside the name | parsed correctly |
| 56, 89 | BS-021 | the SKU twice more; line 89 repeats line 11 | each replaces the previous one; the product ends with line 89's values |
| 62, 63 | | empty rows | skipped |

Result: 97 rows, **85 products created**, 7 rejected, 3 conflicts, 1 warning, 2 empty rows. Rows with the same name and different SKUs (Running Shoes, Bluetooth Speaker) are different products, because the SKU is the key.

## Decisions

### Stack

- **Kotlin and Spring Boot on Java 25**, the current LTS. Virtual threads are on.
- **PostgreSQL** is the local database. A lot of the correctness lives in it: check constraints, unique keys, upserts, conditional updates and full-text search.
- **Plain SQL through Spring's `JdbcClient`, Liquibase for the schema, no ORM.** The parts that matter here are SQL features: an upsert that skips unchanged rows, a stock decrement that only succeeds if enough stock is left, a generated search column. An ORM would hide exactly those parts.
- **React with TypeScript**, built by Vite and served by the same Spring Boot application. One container, one origin, no CORS.

### Catalog rules

One set of rules (`ProductRules`) checks both CSV rows and the admin form, so a product that cannot be imported cannot be typed in either.

- **SKU:** trimmed and upper-cased; letters, digits and single hyphens; unique.
- **Price:** a plain decimal with up to two places, above zero. Values like `$29.99`, `free` or `1,000.00` are rejected, not guessed. Zero is rejected too: there is no flow for free products, and a zero in a feed is more often a mistake than a promotion.
- **Stock:** the number on hand, a whole number from 0 to 1,000,000. Shoppers can buy what is on hand minus what orders waiting for payment hold.
- **Field limits:** SKU 64 characters, name 200, description 10,000, category 100; on accounts and orders, email 320 (the mail standard allows 64 characters before the @ and 255 after) and a person's name 200. The brief sets none, so these leave room for real data and refuse what nobody meant to type before it reaches the database or anything downstream (invoices, shipping labels, the payment provider). Raising one is a constant in `ProductRules` and a migration, and in PostgreSQL widening a text column does not rewrite the table. An order total has room for 16 digits before the point, enough for 100 lines of 99 units at the highest price; an order that would not fit is refused (`ORDER_TOO_LARGE`).
- **Weight:** optional. An empty weight is saved as empty with a warning, because the product can still be sold; zero is allowed for digital goods.
- **Category:** required, free text matched case-insensitively. The file has 18 values and the brief says string/enum; a closed enum would reject every new category a feed brings. A fixed list managed by the admin is a small change if that is preferred.
- **Names are data.** Markup and SQL in names are stored as text through parameterized SQL, and React escapes them on render. The Content Security Policy (`default-src 'self'`) is a second line of defence.
- **Delete means archive.** Orders reference products, so archived products leave the shop and the search but stay in the database. Importing them again brings them back.
- **Optimistic locking.** Each product has a version. The admin form sends it back, and a form opened before someone else's change gets a 409 instead of overwriting it. A payment lowers the on-hand count and bumps the version, so an admin cannot overwrite a count that a sale has just changed; reservations change only the reserved count, which nobody edits by hand.

### CSV import

- The file is read whole (up to 5 MB and 10,000 rows), as strict UTF-8 with the BOM removed. Header names are matched case-insensitively and the columns can come in any order. Quoting follows RFC 4180.
- Each row is checked on its own and every problem in it is reported, not just the first.
- When a SKU repeats in the file, an identical row is skipped as a duplicate, and a different one replaces the earlier row; the report says which fields changed.
- The accepted rows are written in **one transaction**, as creates, updates or no-ops. Unchanged rows are not written, so importing the same file twice changes nothing. A file that cannot be parsed changes nothing at all.
- **Rows are written in SKU order.** Two imports that touch the same SKUs in a different order used to lock rows in opposite order and deadlock; `ImportConcurrencyTest` reproduced the deadlock before the fix and passes after it. "Last row wins" is decided before sorting, so the order of writes does not change the result. Between two different imports, the one that commits last wins; if the newest data must win regardless of upload order, the feed needs a timestamp per row and the upsert must compare it ([ADR 4](docs/adr/0004-lock-order-in-imports.md)).
- The file's stock is the number on hand, and the import sets only that; units held by orders waiting for payment stay held. If a file reports fewer units than those orders hold, the import report warns, the product shows as out of stock, and if the held units are paid for anyway, the audit log records the shortfall (`STOCK_SHORTFALL`) so someone can backorder or refund. The admin form works the same way: it edits the on-hand count and shows the reserved one.

### Search

- PostgreSQL full-text search on a generated, weighted column (name and SKU above description) with a GIN index, ranked by relevance.
- Partial words and SKU fragments ("blue" finds "Bluetooth") also match through `ILIKE` on name and SKU, backed by a trigram index. LIKE wildcards in the search text are escaped.
- Filters by category, price range and stock; sorting by relevance, name, price or newest; pages of up to 100. In the shop, a category, sort or stock choice applies at once; typed text and prices apply on Search.

### Cart, checkout and payment

- The cart lives in the browser. The server prices it (`POST /api/cart/quote`), so the browser never decides a price.
- `POST /api/orders` requires an `Idempotency-Key` header, which follows the IETF Idempotency-Key draft and Stripe's rules. The key is stored with a fingerprint of the purchase: the account (or guest), the items and the total. A repeat of the same purchase returns the first order and charges nothing, so a double click or a retry after a timeout is safe; the same key with a different purchase, or from another account, gets `422`. Two requests with one key wait for each other (an advisory lock on the key). Keys are forgotten after a day (`app.orders.idempotency-key-ttl`), after which the same key starts a new order. The checkout page keeps its key until an order comes back, also across a reload.
- **Stock is reserved in one transaction:** for each product, in id order, `UPDATE … SET reserved = reserved + :quantity WHERE id = :id AND stock - reserved >= :quantity RETURNING price`. Concurrent buyers cannot oversell, and no lock is held between requests. The total is computed from the prices returned by that update. If it differs from the total the shopper confirmed, the order is refused with 409 and nothing is taken.
- The order is saved as **pending payment** with a snapshot of names and prices. The payment is called **outside** the transaction, so no database lock waits on the provider.
- **Approved** → paid, and the reserved units leave the on-hand count. **Declined** → payment failed, and the reservation is dropped in the same transaction as the status change. Every status change is conditional on the current status, so a reservation is settled only once.
- **No answer from the processor** → the order stays pending. A scheduled reconciliation job asks the provider about orders that have been pending longer than the timeout: charged → paid, no charge → expired with the stock returned. ShedLock makes sure only one instance runs the job at a time.
- The fake gateway answers the same way for the same order every time, checks card numbers with Luhn, and only the last four digits are stored. `PaymentGateway` is the seam for a real provider (Stripe, Adyen, or Mercado Pago and Conekta for Mexico): payment intent with an idempotency key, signed webhook, refunds through the provider's API.
- Known gap: if a charge arrives after the order has expired, it is logged and audited as needing a refund. With a real provider that refund would be automatic.

### Accounts and security

- **Accounts are optional.** Guests can buy; a registered customer sees their order history and gets checkout filled in. Forced registration is a known cause of abandoned carts.
- **Roles:** `/api/admin/**` needs the admin role, `/api/me/**` any signed-in user, everything else is public. A customer's order is shown only to that customer and to admins; a guest's order to whoever has its link, which carries a random id.
- **Passwords** follow OWASP ASVS and NIST SP 800-63B: 12 to 128 characters, no composition rules, common passwords and ones containing the email or name refused. Hashes are Argon2id, and 5 wrong passwords lock the account for 15 minutes.
- **Sessions** are stored in PostgreSQL, so any instance can serve any request; the CSRF token comes back in the `X-XSRF-TOKEN` header.
- **Rate limiting** per client address answers `429` with `Retry-After`. Behind a gateway the address comes from `X-Forwarded-For`, which only a configured trusted proxy may set; production trusts none by default. The responses carry a strict Content Security Policy and the other protection headers.
- **Secrets** are not in the code or the base configuration: the database and admin credentials come from the environment (Kubernetes secrets in the Helm chart), and the demo `admin` password belongs to the local run only ([configuration](#configuration)).

The details are in [`docs/security.md`](docs/security.md) and ADRs [5](docs/adr/0005-sessions-csrf-and-passwords.md), [6](docs/adr/0006-rate-limiting.md) and [9](docs/adr/0009-production-configuration-and-local-profile.md).

### Audit log

Every product change made in the admin (with the old and new values), every import (with its counts), account registration and lock, creation of the admin account and change of its password, and order status change is written to `audit_event` in the same transaction as the change, with the actor (a username, `guest`, or `system` for the start-up and scheduled jobs). Admins see it under **Admin → Audit log** and at `/api/admin/audit`.

### Several instances

The application keeps no state that the instances need to share: sessions are in the database, the scheduled job is guarded by ShedLock, Liquibase applies the changelogs under its lock, and every status change is idempotent, so it can run as several instances behind a load balancer. Only the rate-limit buckets are per instance ([ADR 6](docs/adr/0006-rate-limiting.md)).

## Quality and testing

- **189 backend tests**, on a real PostgreSQL through Testcontainers where the database matters: every checkout branch, the example file end to end, accounts and lockout, a role matrix of every endpoint for a guest, a customer and an admin, and **concurrency**: 200 buyers for 50 units sell exactly 50, one idempotency key sent from two threads creates one order even for the last item in stock, and neither two overlapping imports nor an import and a checkout deadlock.
- **151 frontend tests** (Vitest and Testing Library, with the API stubbed): the API client, every checkout branch including a retry after a lost answer that reuses the idempotency key, an expired session, the return address after sign-in, the cart, the shop settings, the admin forms, the route guards, and focus, labels and announcements for screen readers.
- **21 browser tests** (Playwright) against the running stack: purchases by a guest and a customer, a declined card, a sort that stays while paging, a `<script>` product name shown as text, the admin import and the audit log, and axe accessibility checks (WCAG 2.1 AA), also on error states and at phone width.
- **Coverage:** backend 96% of lines (Kover, without the load-test code), frontend 99% (V8); both runs fail below 85%.
- **Static analysis:** detekt and Kotlin warnings as errors; ESLint with the React hooks rules and strict TypeScript on the frontend.

The full list is in [`docs/testing.md`](docs/testing.md).

## Alternatives considered

| Topic | Chosen | Alternative | Why |
|---|---|---|---|
| UI | React SPA served by the backend | Server-rendered pages (Thymeleaf) | A richer UI and a clean API boundary, still one container |
| Persistence | `JdbcClient` and SQL | JPA/Hibernate, jOOQ | The important behaviour is SQL; jOOQ would add type safety at the cost of code generation |
| Migrations | Liquibase with SQL changelogs | Flyway | Both fit; Liquibase gives rollback and preconditions if they are ever needed, and the changes stay plain SQL |
| Search | PostgreSQL full-text and trigram | Elasticsearch or OpenSearch | Better tuning and facets at scale, but another service to run and keep in sync; not worth it for this catalog |
| Cart | Browser storage, priced by the server | Server-side cart | Keeps the server stateless; a server cart is needed once carts must follow a user across devices |
| Payment | Synchronous fake call plus reconciliation | Asynchronous flow with an outbox and provider webhooks | For a real provider I would use webhooks and an outbox; the reconciliation job covers the failure that matters here |
| Sessions | PostgreSQL (Spring Session JDBC) | Redis, or JWT | No extra service; Redis is the next step at higher traffic; JWT would put tokens in JavaScript and make logout and lockout harder |
| Password hashing | Argon2id | bcrypt, PBKDF2 | Memory-hard, OWASP's first choice; the delegating encoder still reads bcrypt hashes |
| Rate limiting | In the application (Bucket4j) | At the gateway or ingress, or Bucket4j on Redis | Works without infrastructure in front; the limits are per instance |
| Import | In the request, one transaction | Background job (for example Spring Batch) | Fine at 5 MB; large feeds would need a background job with progress, chunked commits and a history |

## Scaling notes

- Reads (catalog, search) scale with more instances, PostgreSQL read replicas and a cache in front of the catalog.
- Orders for **different** products scale with the database. Orders for **one** product queue on that product's row; for a flash sale with thousands of buyers per second for a single item I would move stock reservation into Redis with an atomic decrement, put buyers through a queue or a waiting room, and settle orders asynchronously.

## What a full production setup would still need

- **Access:** single sign-on for admins (OpenID Connect) with multi-factor authentication and finer roles (catalog manager, support); email verification and password reset; a check against breached passwords; bot protection on checkout and registration.
- **Fulfilment:** a paid order counts as shipped here. With a warehouse in between, paid units would stay as a third number, committed, until the warehouse ships them and its feed stops counting them; and a recorded shortfall would start a backorder or a refund automatically.
- **Business:** a real payment provider with signed webhooks, refunds and chargebacks; VAT (IVA 16%), prices in MXN and CFDI electronic invoices through an authorized provider for a Mexican shop; stock held in the cart with an expiry; shipping; product images on object storage behind a CDN.
- **Integration:** an outbox publishing order events (Kafka) for warehouse, shipping and accounting.
- **Operations:** an API gateway or ingress with TLS and global rate limits; separate environments and canary releases; backward-compatible migrations (expand and contract), applied by a separate database user so that the application's own user cannot change the schema (`spring.liquibase.user`); point-in-time recovery for PostgreSQL with restore drills; secrets in a vault, mounted as files rather than environment variables; JSON logs shipped to Loki; SLOs with on-call runbooks.
- **Data protection:** retention rules, deletion on request and a privacy notice (in Mexico, under the LFPDPPP).

## Beyond the brief

Not asked for, and not needed to run the shop. Added because the brief says enterprise-grade; the details and commands are in [`docs/operations.md`](docs/operations.md).

- **Observability:** OpenTelemetry traces, business metrics, logs with trace ids, and a Grafana dashboard with five alert rules (`compose.observability.yaml`).
- **Load test:** Gatling; a one-minute run on a laptop, rising to 100 browsing and 20 buying users per second, had no errors and a 95th percentile of 103 ms (2026-10-16).
- **Security scanning:** an OWASP ZAP baseline scan of the running app (no failures), plus Trivy, CodeQL and Dependabot in CI.
- **Kubernetes:** a Helm chart with probes, autoscaling, a restricted security context, a Gateway API route (Ingress NGINX was retired in March 2026) and a network policy, validated in CI.
- **Supply chain:** a CycloneDX SBOM, and a release that runs only when started by hand and pushes a signed image with an attested SBOM to GHCR.
- **Patched dependencies:** Tomcat and Jackson are newer than the versions Spring Boot manages, Swagger UI is newer than the one springdoc brings, and the Dockerfile removes an unused binary with known vulnerabilities from the base image; Trivy and ZAP found them.
- **CI:** GitHub Actions run the checks above, the image scans and the browser tests on every push to `main` and every pull request.

## API

Interactive documentation: <http://localhost:8080/swagger-ui.html>. The endpoints with the role each one needs, the CSRF header and the error format are in [`docs/api.md`](docs/api.md).

## Project layout

```
src/main/kotlin/…/catalog          products, rules, search, CSV import
src/main/kotlin/…/ordering         cart pricing, checkout, payment, reconciliation
src/main/kotlin/…/account          accounts, password policy, lockout
src/main/kotlin/…/audit            audit log and business metrics
src/main/kotlin/…/web/api          REST controllers and error mapping
src/main/kotlin/…/config           security, rate limiting, SPA serving, scheduling, OpenAPI
src/main/resources/db/changelog    Liquibase changelogs
src/gatling                        load test
frontend/                          React and TypeScript app with its Vitest tests; Playwright and accessibility tests in e2e/
deploy/helm/shop                   Helm chart
deploy/grafana                     Grafana dashboard and alert rules
docs/adr                           architecture decision records
docs/                              operations and extras, security, tests and API in detail
.zap/                              OWASP ZAP rules
.github/                           CI with a manual release, CodeQL, load test workflow, Dependabot
```

## AI

The project was built with AI assistance (Claude Code). As the challenge asks, the code has no comments; the reasoning is in this README and in the ADRs ([`docs/adr`](docs/adr)).
