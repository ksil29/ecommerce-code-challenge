# 7. Observability with OpenTelemetry

**Status:** accepted

## Context

Operators need to see what the shop is doing: whether checkouts fail, whether the payment provider is slow, whether the database is the bottleneck.

## Decision

- Micrometer with the OpenTelemetry bridge: traces exported over OTLP, the trace and span id in every log line, JSON logs (ECS) in deployed environments.
- Prometheus metrics on a separate management port, plus business metrics: orders by status, refused checkouts by reason, import rows by result, payment and import timers, and a gauge of orders waiting for a payment answer.
- For local runs, the `grafana/otel-lgtm` image with a provisioned dashboard and five alert rules (server errors, latency, stuck payments, payment declines, database pool exhaustion).

## Consequences

- One standard for traces and metrics; the backend (Tempo, Jaeger, a vendor) is a configuration choice.
- Spring Security's own observations are switched off, because they made every request a separate trace.
- Logs are not shipped by the application; in production a collector or the platform reads standard output.
