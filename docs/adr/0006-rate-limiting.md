# 6. Rate limiting in the application

**Status:** accepted

## Context

Sign-in, registration, checkout and imports can be abused: password guessing, account spam, card testing, resource exhaustion. There is no gateway in front of the application in this setup.

## Decision

A servlet filter in front of Spring Security applies token buckets (Bucket4j) per client address: 10 sign-ins per minute, 5 registrations per 10 minutes, 30 orders per minute, 10 imports per minute and 600 other API calls per minute. A refused request gets `429` with `Retry-After` and a problem-details body. The limits are configuration, and `RATE_LIMIT_ENABLED=false` turns them off for load tests from one machine.

## Consequences

- Abuse is slowed even without infrastructure in front of the app.
- Buckets live in each instance's memory, so with N instances the effective limit is up to N times higher. The per-account lockout in the database still holds.
- In production the global limits belong at the gateway or ingress, or in Bucket4j backed by Redis.
