# 5. Database sessions, a CSRF cookie, and the password policy

**Status:** accepted

## Context

The single-page app and the API share one origin. Admins and optional customer accounts sign in; the application must run as several instances.

## Decision

- Session cookie (`HttpOnly`, `SameSite=Lax`, and `Secure` everywhere except the `local` profile) with sessions stored in PostgreSQL through Spring Session.
- CSRF token in a readable `XSRF-TOKEN` cookie (`SameSite=Lax`), sent back in the `X-XSRF-TOKEN` header.
- Passwords: 12 to 128 characters, any characters, normalized with NFKC, no composition rules or forced rotation, a blocklist of common passwords and of passwords containing the email or name (OWASP ASVS V2.1, NIST SP 800-63B).
- Argon2id with OWASP's parameters behind the delegating encoder.
- Five wrong passwords lock the account for 15 minutes; the counter is in the database.

## Consequences

- No session token in JavaScript; logout and lockout take effect immediately on every instance.
- Rejected alternative: JWT, which would need token storage in the browser and a revocation list.
- The registration endpoint reveals that an email is taken; with email verification the answer would be the same either way.
