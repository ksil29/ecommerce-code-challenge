# 9. Production configuration by default, demo settings in a profile

**Status:** accepted

## Context

The reviewer must be able to start the shop with one command and sign in as the admin. A deployment must not inherit those demo settings by accident: a forgotten variable should stop the start, not fall back to a known password.

## Decision

- `application.yaml` is the production configuration. It holds no credentials, and the session cookie is `Secure`.
- The `local` profile (`application-local.yaml`) holds the demo settings: the `shop` database user, the admin password `admin`, the example catalog, Swagger UI with the OpenAPI document, and a cookie without `Secure`. `compose.yaml` and `bootTestRun` turn it on; the image and the Helm chart do not.
- The admin account is created from `ADMIN_USERNAME` and `ADMIN_PASSWORD`. The first start fails without a password, and a password that breaks the customer password policy fails every start. Only the `local` profile may use a weak password, and the log warns about it. A different `ADMIN_PASSWORD` replaces the password on the next start, ends the admin's open sessions, and the audit log records it.
- The base configuration believes `X-Forwarded-For` from no proxy. The `local` profile trusts private networks, and the Helm chart requires the gateway's addresses and namespace.
- `ConfigurationProfilesTest` pins the values of both configurations.

## Consequences

- No credential is in the code, the image or the base configuration, and a missing one stops the start.
- No password is generated and written to the log, where it would end up in log storage.
- A gateway left unconfigured makes every request seem to come from the gateway, so the per-address limits are shared: a visible fault instead of a silent way around them, and the chart does not render without the values.
- Turning on the `local` profile in production would bring back the demo settings; the profile is not in the image's or the chart's settings, and the warning about the weak admin password shows in the log.
- Rejected alternatives: a generated admin password printed to the log; defaults in `application.yaml` overridden per environment, which fall back to the demo values when a variable is missing.
