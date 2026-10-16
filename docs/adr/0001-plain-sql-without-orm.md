# 1. Plain SQL through JdbcClient, no ORM

**Status:** accepted

## Context

The behaviour that matters most is expressed in SQL: an upsert that skips unchanged rows (`ON CONFLICT … WHERE … IS DISTINCT FROM`), a stock decrement that only succeeds when there is enough stock (`UPDATE … WHERE stock >= :q RETURNING price`), a generated full-text column, and conditional status transitions.

## Decision

Use Spring's `JdbcClient` with hand-written SQL in repository classes. Schema changes go through Liquibase.

## Consequences

- Every statement that touches the database is visible and reviewable, including the locking behaviour.
- More code than JPA for plain CRUD; the catalog is small enough that this is not a burden.
- No lazy loading, no session cache surprises, no N+1 queries by accident.
- If type-safe queries become important, jOOQ fits the same approach without changing the model.
