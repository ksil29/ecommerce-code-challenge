# 8. Delivery, quality gates and supply chain

**Status:** accepted

## Context

The code has to stay correct and the image trustworthy as dependencies change.

## Decision

- `./gradlew check` runs detekt, 189 tests and a line-coverage gate of 85% (Kover); Kotlin warnings are errors.
- The frontend build type-checks in strict mode and runs ESLint with the React hooks rules.
- Playwright runs the user and admin journeys, axe accessibility checks (WCAG 2.1 AA) and the API documentation page against `docker compose`.
- CI scans with CodeQL, Trivy (image, dependencies, secrets, configuration) and OWASP ZAP (baseline against the running app; a FAIL rule fails the build); Dependabot keeps dependencies current, except for major upgrades the versions table rules out.
- The release runs only when started by hand on `main`: after the checks pass, CI pushes the image to GHCR with build provenance and an SBOM, scans the pushed image with Trivy, and only then signs it with cosign (keyless) and attests a CycloneDX SBOM. A push to `main` runs the checks and publishes nothing. Gradle produces a CycloneDX SBOM of the runtime dependencies.
- A Helm chart describes the Kubernetes deployment; CI lints it and validates the rendered manifests with kubeconform.

## Consequences

- A regression in behaviour, coverage, style, accessibility or known vulnerabilities fails the build.
- The signed image and its SBOM can be verified before deployment, for example by an admission policy.
