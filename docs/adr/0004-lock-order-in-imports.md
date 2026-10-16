# 4. Consistent lock order in CSV imports

**Status:** accepted

## Context

An import writes all accepted rows in one transaction, and each upsert keeps its row locked until commit. A test that ran two imports at the same time with the same SKUs in opposite order made PostgreSQL report `deadlock detected` and roll one import back.

## Decision

Resolve repeated SKUs first (the last row in the file wins), then write the resulting rows sorted by SKU, and create categories sorted by name before that. Before writing, lock the products that already exist in id order (`SELECT … ORDER BY id FOR UPDATE`), the same order a checkout uses, so an import and a checkout that hold the same products cannot deadlock either.

## Consequences

- Concurrent imports, and an import next to a checkout, wait for each other instead of deadlocking; both cases in `ImportConcurrencyTest` failed before the change and pass after it.
- "Last row wins" is unaffected: it is decided before sorting, and each SKU is written once per import.
- Between two different imports, the one that commits last wins. If the business needs "newest data wins", the feed must carry a timestamp per row and the upsert must compare it.
