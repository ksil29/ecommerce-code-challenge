# API

Interactive documentation: <http://localhost:8080/swagger-ui.html> (in the `local` profile, which `docker compose` turns on; production does not serve it).

| Method | Path | Who |
|---|---|---|
| GET | `/api/products?q=&categoryId=&minPrice=&maxPrice=&inStock=&sort=&page=&size=` | public; `includeArchived=true` takes effect only for an admin |
| GET | `/api/products/{sku}`, `/api/categories` | public |
| GET | `/api/settings` (the most units of one product per order, the payment timeout, the reconciliation interval, the password lengths, and whether the API documentation is published) | public |
| POST | `/api/cart/quote` | public |
| POST | `/api/orders` (header `Idempotency-Key`) | public |
| GET | `/api/orders/{id}` | a customer's order: that customer or an admin; a guest's order: whoever has its link (the id is a random UUID) |
| POST | `/api/auth/register` (JSON), `/api/auth/login` (form fields `username` and `password`; `204` or `401`), `/api/auth/logout` (`204`); GET `/api/auth/me` | public |
| GET | `/api/me/orders` (order headers without lines; `/api/orders/{id}` has the lines) | signed in |
| GET | `/api/admin/products/{id}` | admin |
| POST, PUT | `/api/admin/products`, `/api/admin/products/{id}` | admin |
| POST | `/api/admin/products/{id}/archive`, `/api/admin/products/{id}/restore` | admin |
| POST | `/api/admin/imports` (multipart `file`) | admin |
| GET | `/api/admin/orders` (order headers without lines), `/api/admin/audit` | admin |

State-changing requests need the `X-XSRF-TOKEN` header with the value of the `XSRF-TOKEN` cookie. Errors use RFC 9457 problem details: business errors carry a `code` (for example `OUT_OF_STOCK`, `PRICE_CHANGED`, `STALE_VERSION`, `RATE_LIMITED`, `ORDER_TOO_LARGE`, and `IDEMPOTENCY_KEY_REUSED` with `422` when a key comes back with a different purchase), validation errors an `errors` list of fields, and an unexpected error the code `INTERNAL_ERROR` with no internal details. `401` and `403` come with an empty body. Too many requests get `429` with `Retry-After`.
