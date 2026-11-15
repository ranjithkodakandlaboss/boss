# BOSS — Engineering Standards and Definition of Done (Agent Summary)
**Version:** v1.1.1 | **Date:** 15 November 2026

Authoritative full DoD is in `DEFINITION_OF_DONE.md`. This summary covers the most important rules for agents.

---

## The Core Rule

A story is DONE only when **all applicable criteria** are met for its profile. Backend stories require backend DoD. Frontend stories require frontend DoD. A passing test suite does not make a story done if observability, review, or documentation criteria are unmet.

---

## Definition of Done by Story Type

### BACKEND story

| Criterion | Requirement |
|---|---|
| Acceptance criteria | All items explicitly verified in test or manual evidence |
| Unit tests | ≥80% branch coverage on domain/service layer |
| Integration tests | Testcontainers (real PostgreSQL) — no H2 |
| API tests | Every endpoint: success, 400, 401, 403, 404, tenant isolation, concurrent requests where relevant |
| RLS test | `RlsTenantIsolationTest` for new tenant-scoped tables |
| Security | `AdminApiCustomerTokenRejectionTest` for admin endpoints |
| Append-only | `StockMovementAppendOnlyTest` or equivalent for ledger tables |
| Concurrency | `InventoryConcurrencyIntegrationTest` for inventory reservation |
| OpenAPI match | Response shape matches the approved contract exactly |
| Flyway migration | V{NNN} migration runs cleanly on fresh schema |
| Audit events | Written for all create/update/delete operations |
| Outbox | Async side effects published via outbox, not direct Pub/Sub |
| Logging | Structured JSON log on key transitions; no sensitive data in logs |
| Human review | Ranjith has reviewed code before merge |

### FRONTEND story

| Criterion | Requirement |
|---|---|
| Acceptance criteria | All AC verified in Playwright E2E test |
| Backend DoD | Corresponding backend story is DONE |
| Contract | Implements against approved OpenAPI contract |
| Config options | All dropdowns/lists loaded from `/api/v1/config/options/{category}` |
| Prototype parity | Screen layout matches approved prototype |
| Role-based UI | RBAC applied — hidden controls for unauthorised roles |
| Error states | 400/401/403/404/500 handled gracefully with meaningful messages |
| Loading states | Spinner/skeleton shown for all async operations |
| Responsive | Works at 1280px and 1440px viewport widths |
| Human review | Ranjith has reviewed before merge |

### DATA / MIGRATION story

| Criterion | Requirement |
|---|---|
| Migration runs clean | Flyway migration applies successfully on fresh empty schema |
| Migration is idempotent | Re-running (after rollback) produces same result |
| All FKs valid | No orphaned FK references |
| RLS policies applied | V051 or later migration covers all new tenant-scoped tables |
| Append-only triggers | V050 trigger covers stock_movements and audit_events |
| Indexes documented | All indexes have business rationale |
| Table count reconciled | `RlsCoverageTest` passes after migration |

### INFRA story

| Criterion | Requirement |
|---|---|
| IaC applied | OpenTofu plan shows no diff after apply |
| No secrets in IaC | Secrets come from Secret Manager; not hardcoded |
| CI/CD functional | Merge to main triggers deployment to dev |
| Rollback tested | Manual rollback procedure documented and tested in dev |

---

## Test Naming Conventions

```
{Domain}{Feature}Test           — unit test
{Domain}{Feature}IntegrationTest — integration test (Testcontainers)
{Domain}E2ETest                 — Playwright end-to-end
{Feature}ConcurrencyIntegrationTest — concurrent access test
```

## Test Evidence Requirements

For each story, the merge PR must include:
1. Test run output showing all tests pass
2. `RlsTenantIsolationTest` result (backend stories)
3. Playwright screenshot or trace file (frontend stories)
4. Migration run output (DATA stories)

---

## Code Quality Rules

- No Lombok except `@Slf4j` — use Java records and explicit constructors
- No business logic in controllers — controllers call services only
- No `@Transactional` in controllers — only in service implementations
- No raw SQL in service layer — JPA or named queries only; SQL belongs in repositories
- No static utility methods for business logic — inject services
- No `System.out.println` or `e.printStackTrace()` — use SLF4J
- All monetary amounts: `BigDecimal` / `NUMERIC(15,2)` — no `double` or `float`
- All timestamps: `Instant` or `OffsetDateTime` — no `java.util.Date`
- GST rates from configurable reference data or lookup table — never hard-coded

---

## Branch and PR Rules

- Branch name: `{owner}/{vs-number}/{story-id}-{brief}`
  - e.g. `ranjith/vs-02/epic-01-s002-hikaricp-rls`
- PR title: `[EPIC-NN-SNN] Story title`
- PR description: What it does, testing performed, AC evidence
- All CI gates must pass before review is requested
- Ranjith reviews all PRs before merge
