# Operations and extras

What the project has beyond the brief: observability, a load test, security scanning, a Kubernetes chart, supply-chain checks and CI. None of it is needed to run the shop with `docker compose up --build`; the main [README](../README.md) covers the brief.

## Observability

With traces and metrics in Grafana:

```bash
docker compose -f compose.yaml -f compose.observability.yaml up --build
```

Grafana runs on <http://localhost:3000> (the `grafana/otel-lgtm` image: Tempo for traces, Prometheus for metrics) with a provisioned **Shop overview** dashboard and five alert rules.

- **Logs:** with tracing on, every log line carries the trace and span id; the observability overlay switches the output to JSON (ECS).
- **Traces:** OpenTelemetry through Micrometer, exported over OTLP. HTTP requests, the payment call (`shop.payment`) and the CSV import (`shop.import`) are spans.
- **Metrics:** Prometheus endpoint on the management port, plus business metrics: `shop.orders` by status, `shop.checkout.rejected` by reason, `shop.import.rows` by result, a gauge of orders waiting for a payment answer, and latency histograms for HTTP requests, payments and imports.
- **Dashboard and alerts:** API traffic and latency by endpoint, orders by outcome, refused checkouts, payment latency, import results, the database pool, JVM memory and CPU. Alerts fire on server errors above 1%, latency above 500 ms at the 95th percentile, orders stuck waiting for the payment provider, payment declines above 30%, and requests queueing for a database connection. The queries use the names the OTLP path gives the timers, in milliseconds (`http_server_requests_milliseconds_*`, `shop_payment_milliseconds_*`). Scraped from `/actuator/prometheus`, as the Helm chart's pod annotations offer, the same timers are in seconds (`*_seconds_*`), so in a cluster the latency panels and the server-error and latency alerts need those names, and the latency threshold becomes 0.5.
- **Health:** liveness and readiness at `/livez` and `/readyz` on the application port, so a probe also fails when the main connector does; the full health, metrics and Prometheus endpoints stay on the management port (8081), which is not published outside the cluster or the machine.

## Load test

Start the stack with the rate limits off, `RATE_LIMIT_ENABLED=false docker compose up`, then run `./gradlew gatlingRun`. `BROWSERS_PER_SEC`, `BUYERS_PER_SEC`, `DURATION_SECONDS` and `BASE_URL` change the profile. In CI the load test is a separate workflow that runs only when started by hand.

One minute, browsing traffic rising to 100 new users per second plus 20 buyers per second, on a laptop running the stack in Docker, on 2026-10-16: 10,543 requests and 1,200 orders, no errors, median 24 ms, 95th percentile 103 ms, 99th percentile 394 ms, about 173 requests per second. Earlier runs on the same laptop gave a 95th percentile of 140 and 196 ms, so the spread between runs is larger than the difference between versions. The run fails if the 95th percentile goes above 500 ms or more than 1% of requests fail. It is a laptop smoke test of the design, not a capacity figure.

## Security scanning

The OWASP ZAP baseline scan against the running stack (the report lands in `build/zap`):

```bash
mkdir -p build/zap && cp .zap/rules.tsv build/zap/ && chmod a+w build/zap
docker run --rm --network pit-ecommerce_default -v "$PWD/build/zap:/zap/wrk:rw" \
  zaproxy/zap-stable:2.17.0 zap-baseline.py -t http://app:8080 -j -I -c rules.tsv -r zap-baseline.html
```

Result: no failures, and no warnings outside the Swagger UI pages, whose looser policy is out of scope on purpose. Missing protection headers or a cookie without `SameSite` fail the scan (`.zap/rules.tsv`), and in CI they fail the build; the rules that are switched off are informational or intended, such as the readable `XSRF-TOKEN` cookie. ZAP can't exempt a single cookie from the `HttpOnly` rule, so that rule is off, and `SessionCookieTest` checks on a running server that the session cookie is `Secure`, `HttpOnly` and `SameSite=Lax`.

In CI also CodeQL for Kotlin and TypeScript, Trivy for the image, dependencies, secrets and configuration, and Dependabot. CodeQL compiles the Kotlin code with its extractor inside the Gradle daemon, so `gradle.properties` gives the daemon 3 GB instead of the default 512 MB, which the extractor runs out of.

## Supply chain and release

- `./gradlew cyclonedxDirectBom` writes the CycloneDX SBOM of the runtime dependencies.
- The release runs only when started by hand: **Actions → CI → Run workflow** on `main`. After the checks pass, it pushes the image to GHCR with build provenance, scans the pushed image with Trivy, and only then signs it with cosign (keyless) and attests a CycloneDX SBOM. A push to `main` runs the checks and publishes nothing ([ADR 8](adr/0008-delivery-and-supply-chain.md)).

## Kubernetes

A Helm chart is in [`deploy/helm/shop`](../deploy/helm/shop) (probes, autoscaling, a disruption budget, a restricted security context, a network policy). It reads the database and admin credentials from the `shop-database` and `shop-admin` secrets (keys `username` and `password`); the admin password must meet the password policy, or the pod does not start.

**Exposure.** The chart publishes the shop through the Gateway API: an `HTTPRoute` that attaches to a Gateway the platform team runs (`httpRoute.parentRefs`). Ingress NGINX, the controller most charts assumed, was retired in March 2026 and gets no more fixes, security fixes included. Gateway API is the Kubernetes project's successor to Ingress, and most controllers implement it (Envoy Gateway, Istio, Cilium, NGINX Gateway Fabric, Traefik, Kong, the cloud providers' gateways). For a cluster that still runs another Ingress controller, `ingress.enabled=true` renders a plain `Ingress`, with the cluster's default class unless `ingress.className` names one.

**The client address behind the gateway.** The rate limits count requests per client address, and behind a gateway that address comes from `X-Forwarded-For`. The application believes that header only on connections from a trusted proxy, and its production configuration trusts none. So the chart does not render without two values:

- `proxy.addresses`: the addresses of the gateway pods, as a comma-separated list of CIDR ranges such as `10.244.0.0/16`, or a regular expression. It becomes `SERVER_TOMCAT_REMOTEIP_INTERNAL_PROXIES`.
- `proxy.namespace`: the namespace those pods run in. `proxy.podLabels` narrows the policy below to the proxy pods, which matters when the namespace holds other workloads.

Each wrong guess fails in its own way. Trust no one, and every request seems to come from the gateway, so all shoppers share one limit. Trust the whole private network, and any pod in the cluster can send the header and pick its own address.

**Network policy** (on by default, incoming traffic only): the application port only from the gateway's namespace (and pods, with `proxy.podLabels`), the metrics port 8081 only from `networkPolicy.metricsNamespace` (`monitoring`). The kubelet's probes are always allowed, and outgoing traffic (the database, traces) is not restricted. The policy closes the spoofing case even when the address list is broad, because only the gateway can connect at all. Some gateways do not reach the application as ordinary pods (Cilium's built-in Gateway API, or a cloud load balancer that sends traffic straight to pod addresses); for those, set `networkPolicy.enabled=false` and allow the gateway with the CNI's or the cloud's own rules.

A values file for a Gateway in `gateway-system`, the same as [`ci/gateway-api-values.yaml`](../deploy/helm/shop/ci/gateway-api-values.yaml) ([`ci/ingress-values.yaml`](../deploy/helm/shop/ci/ingress-values.yaml) is the Ingress variant):

```yaml
proxy:
  namespace: gateway-system
  podLabels:
    app.kubernetes.io/component: proxy
  addresses: 10.244.0.0/16
httpRoute:
  enabled: true
  parentRefs:
    - name: public
      namespace: gateway-system
      sectionName: https
  hostnames:
    - shop.example.com
```

```bash
helm install shop deploy/helm/shop -f my-values.yaml --set image.repository=ghcr.io/<owner>/<repo> --set image.tag=<commit sha> \
  --set database.url=jdbc:postgresql://<host>:5432/shop
```

The chart runs the container with a read-only root filesystem, no capabilities and a restricted seccomp profile, probes on the application port, a pre-stop pause for rolling updates, a CPU-based autoscaler and a disruption budget (left out when the chart runs a single replica). CI lints it with both values files and validates every rendered resource with kubeconform against Kubernetes 1.37.1; the `HTTPRoute` is checked against the Gateway API schema from the CRDs catalog, pinned to one commit.

## Runtime

Liquibase changelogs in plain SQL, applied at startup under Liquibase's lock, so several instances can start together; graceful shutdown; a container that runs as a non-root user, with the heap at half the container's memory (20 to 160 simultaneous sign-ins stay at about 645 MiB of the chart's 768 MiB) and an exit on `OutOfMemoryError`, so Kubernetes restarts it instead of leaving it half-working; a readiness check in `compose.yaml`; the production configuration by default, with the demo settings in the `local` profile ([ADR 9](adr/0009-production-configuration-and-local-profile.md)).

## Settings

Every value can be overridden like any Spring Boot property, for example `APP_ORDERS_MAX_QUANTITY=20` as an environment variable. Credentials and the profiles are in the README under [Configuration](../README.md#configuration).

| Setting | Default | What it does |
|---|---|---|
| `app.orders.max-quantity` | 99 | The most units of one product in an order; the browser reads it from `GET /api/settings` |
| `app.orders.payment-timeout` | 15 minutes | How long an order waits for the payment provider before reconciliation settles it |
| `app.orders.reconcile-interval` | 1 minute | How often reconciliation runs |
| `app.orders.reconcile-batch-size` | 100 | How many waiting orders one run settles; the rest wait for the next run |
| `app.orders.idempotency-key-ttl` | 24 hours | How long an `Idempotency-Key` is remembered |
| `app.orders.history-size` | 50 | How many past orders a customer sees |
| `app.security.max-failed-logins`, `lock-duration` | 5, 15 minutes | Account lockout |
| `app.security.max-concurrent-hashes` | 4 | Password hashes computed at once (19 MiB each) |
| `app.security.password.min-length`, `max-length` | 12, 128 | Password length; below 8 the application refuses to start (NIST SP 800-63B), and the browser reads both from `GET /api/settings` |
| `app.security.argon2.memory-kib`, `iterations`, `parallelism` | 19456, 2, 1 | Argon2id cost; a setting weaker than OWASP's lightest one (7 MiB with 5 iterations) stops the start. Changing it only affects new hashes, and old ones keep working |
| `app.rate-limit.*` | see [security](security.md) | Requests per client address for sign-in, registration, orders, imports and the rest of the API; `enabled` turns them off for load tests |
| `app.rate-limit.max-tracked-addresses`, `idle-expiry` | 100,000, 30 minutes | Memory bound of the rate limiter |
| `server.tomcat.remoteip.internal-proxies` | none (`local`: private networks) | The proxies whose `X-Forwarded-For` is believed, as CIDR ranges or a regular expression; the Helm chart requires it (`proxy.addresses`) |
| `app.csv-import.max-rows` | 10,000 | Rows per CSV file; the file size limit is `spring.servlet.multipart.max-file-size` (5 MB) |
| `app.admin.username`, `password` | `admin`, none | The admin account ([ADR 9](adr/0009-production-configuration-and-local-profile.md)) |
| `app.sample-data.enabled` | `false` | Imports the example CSV into an empty catalog |
| `spring.session.timeout` | 30 minutes | Session lifetime without activity |

Limits that follow the database schema stay in code, because changing them needs a migration as well: the lengths of SKU (64), product name (200), description (10,000), category (100), email (320) and person name (200), and the largest stock (1,000,000), price (10 digits before the point) and weight. So do the page size limits of the API (100 products per page, 500 rows in the admin lists), which are part of its contract. The order total column has room for 16 digits before the point; an order that would not fit is refused with `ORDER_TOO_LARGE`, which the default limits cannot reach.

## CI

GitHub Actions on every push to `main` and every pull request: backend checks, frontend lint, tests and build, the Helm chart (lint and kubeconform), the image build with the Trivy scans, and the end-to-end and accessibility tests with the ZAP scan. CodeQL also runs weekly. The release and the load test run only when started by hand.

## Versions

Everything else is on the latest stable release; the table explains the versions that needed a decision.

| Tool | Used | Latest | Why |
|---|---|---|---|
| Spring Boot | 4.1.1 | 4.2 is a milestone | Latest stable |
| Java | 25 | 27 | 25 is the current LTS; 27 is a short-lived release |
| Kotlin | 2.4.20 | 2.4.21 | Newer than the 2.3 that Spring Boot 4.1 manages; 2.4.20 is the newest version the CodeQL Kotlin extractor is built for |
| Tomcat, Jackson | 11.0.27; 2.21.7 and 3.1.7 | 11.0.27; 2.22 and 3.2 | Newer patches than Spring Boot 4.1.1 manages, because the managed ones have known high and critical vulnerabilities (found by Trivy); kept on the minor lines Spring Boot is tested with. The overrides go once Spring Boot 4.1.2 ships |
| Swagger UI | 5.33.2 | 5.33.2 | springdoc 3.1.1 brings 5.32.14, whose bundled DOMPurify 3.4.13 has two known vulnerabilities (found by ZAP); 5.33.2 bundles 3.4.16. The dependency and `springdoc.swagger-ui.version` go once springdoc ships a newer Swagger UI. Production does not serve Swagger UI anyway |
| JRE base image | `eclipse-temurin:25-jre` (Ubuntu 26.04) | the same | The Ubuntu 26.04 image ships Pebble, Canonical's service manager, built with a Go release that has three high-severity vulnerabilities (found by Trivy). The container never runs it, since the entrypoint is Java, so the Dockerfile deletes it. That line goes once the base image ships a patched Pebble |
| Node (in the image) | 24 | 26 | 24 is the current LTS; `@types/node` stays on 24 with it |
| TypeScript | 5.9 | 7.0 | 7.0 is the new native compiler, and `typescript-eslint` supports TypeScript below 6.1 |
| React Router | 7.18 | 8.4 | 8 is a fresh major release; 7 is maintained |
| detekt | 2.0.0-alpha.6 | 1.23.8 stable | The stable line does not support Kotlin 2.4 |

## Alternatives considered

| Topic | Chosen | Alternative | Why |
|---|---|---|---|
| Observability | OpenTelemetry, Grafana LGTM for local runs | Zipkin or Jaeger with Prometheus | One standard for traces and metrics, one container to look at them |
| Load testing | Gatling (Kotlin) | k6, JMeter | Same language and build as the application |
