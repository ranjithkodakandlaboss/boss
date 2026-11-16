# BOSS Definition of Done — v1.1
**Version:** v1.1 | **Date:** 15 November 2026
**Authority:** Ranjith (architecture + security review) + Saad (domain review)

**A story is DONE when ALL applicable criteria for its profile are satisfied — not when code is generated, committed, or deployed.**

**Backend must pass its DoD before frontend integration is authorised (FD-53).**

---

## DoD Profiles

| Profile | Used for |
|---|---|
| INFRA | Infrastructure, CI/CD, GCP, OpenTofu, IAM |
| DATA | Schema migration, data model, seed data |
| BACKEND | Spring Boot domain logic, APIs, services |
| FRONTEND | React components, pages, integrations |
| INTEGRATION | Cross-domain service calls, event consumers, outbox |
| QA | Test setup, test data, E2E framework |
| DEVEX | Developer environment, local seed scripts, mock data, workflows |
| RELEASE | Deployment, smoke tests, rollback, monitoring |

---

## Profile: INFRA

A story is DONE when:

- [ ] Infrastructure as Code in OpenTofu — no manual console changes
- [ ] State stored in GCS; plan reviewed and applied cleanly
- [ ] WIF used for all GCP auth; no stored service account keys exist
- [ ] Secrets in Secret Manager only; never in environment variables or code
- [ ] `terraform plan` produces zero unexpected diffs after apply
- [ ] Cloud Run health check endpoint responds 200
- [ ] Cloud SQL connectivity verified from Cloud Run
- [ ] IAM roles applied using least-privilege principle
- [ ] CI/CD pipeline runs and passes on the main branch
- [ ] No `gcloud` commands that bypass IaC
- [ ] Reviewed by Ranjith before merge

---

## Profile: DATA

A story is DONE when:

- [ ] Flyway migration file follows naming: `V{NNN}__{description}.sql`
- [ ] Migration is idempotent in structure (no data loss on re-run)
- [ ] All new tables have: UUID PK, tenant_id FK, created_at, updated_at
- [ ] All tenant-scoped tables have RLS policy added in V051 or the story's migration
- [ ] `RlsTenantIsolationTest` passes on the new table
- [ ] `RlsNoContextTest` passes (unset context returns zero rows)
- [ ] Append-only tables have trigger preventing UPDATE/DELETE
- [ ] All FKs have correct ON DELETE behaviour documented
- [ ] CHECK constraints validated with integration tests
- [ ] Indexes added per `index-catalogue.md` for access patterns used by domain service
- [ ] No `ddl-auto: update` — only `validate`
- [ ] Migration dry-run succeeds on a copy of staging schema
- [ ] Reviewed by Ranjith before merge

---

## Profile: BACKEND

A story is DONE when:

### Functionality
- [ ] Explicit acceptance criteria in the story all pass
- [ ] Happy path tested (integration test, real Testcontainers PostgreSQL — no H2)
- [ ] All negative paths tested (bad input, missing data, wrong state, boundary values)
- [ ] Permission checks tested for each required role (at least one test per role that SHOULD work, one that SHOULD be rejected)
- [ ] Suspended tenant test: `SuspendedTenantRejectionTest` pattern applied where relevant

### Tenant Isolation
- [ ] `RlsTenantIsolationTest`: tenant A cannot read tenant B's data through this endpoint
- [ ] No raw SQL that bypasses RLS; always use HikariCP override with BossContext

### Concurrency (where applicable)
- [ ] Pessimistic locking used for inventory reservations and invoice sequence numbers
- [ ] 10-thread concurrency test for any resource that can be over-committed
- [ ] `InventoryConcurrencyIntegrationTest` or domain-equivalent passes

### Finance (where applicable)
- [ ] GST calculation correct for intra-state and inter-state
- [ ] `GstCalculationIntraInterStateTest` passes
- [ ] Invoice sequential numbering: `InvoiceSequentialNumberingTest` passes
- [ ] Actual cost NEVER updates customer invoice amount

### WhatsApp (where applicable)
- [ ] HMAC-SHA256 signature verified before any payload processing
- [ ] `WhatsAppHMACVerificationTest` passes
- [ ] wamid deduplication via idempotency_keys

### Platform Admin (where applicable)
- [ ] `AdminApiCustomerTokenRejectionTest` passes (customer JWT rejected on all /admin/* paths)

### Outbox and Audit
- [ ] All async events written to outbox_events IN the same transaction as the state change
- [ ] All significant state transitions record an audit event via `AuditService.record()` in the same transaction
- [ ] No direct Pub/Sub publish; only outbox relay
- [ ] Idempotency key verified for all external integration calls

### Code Quality
- [ ] Minimum 80% line coverage on new service classes
- [ ] 100% path coverage on security filters and auth code
- [ ] 100% test coverage (at least one integration test) on each new REST endpoint
- [ ] No compiler warnings; SpotBugs passes
- [ ] OWASP dependency check: run locally with NVD_API_KEY (per PR #107); zero HIGH/CRITICAL CVEs unresolved in runtime deps
- [ ] No secrets or credentials committed (secret scan passes in CI)

### Observability
- [ ] Structured log entry (at least INFO) for significant state transitions
- [ ] OpenTelemetry span for each domain service call
- [ ] At least one metric counter or histogram for the primary operation

### Documentation
- [ ] OpenAPI spec updated for all new/changed endpoints
- [ ] Domain PRD updated if behaviour differs from spec
- [ ] New state machine transitions documented in `06_DATA_MODEL/state-machines.md`

### Review
- [ ] At least one human reviewer approval (domain owner or Ranjith)
- [ ] Ranjith reviewed: security filters, tenant isolation code, inventory concurrency, finance calculations
- [ ] PR includes test run evidence (CI pass link)

---

## Profile: FRONTEND

A story is DONE when:

- [ ] All UI acceptance criteria pass
- [ ] Component renders correctly with real API data (no hardcoded/mock data in production code)
- [ ] Loading states handled (skeleton / spinner)
- [ ] Empty states handled (zero items, no results)
- [ ] Error states handled (API error, network error, validation error)
- [ ] Form validation shows field-level errors
- [ ] All roles that should see this feature can see it
- [ ] Roles that should NOT see this feature see an appropriate empty state or are redirected
- [ ] Prototype parity maintained against prototype baseline / FD-04 (no unilateral redesign; note FD-03 designates Java 21 / Spring Boot)
- [ ] React Query / state management correct (no stale data on navigation)
- [ ] No console errors or warnings
- [ ] Responsive on mobile browser width (375px minimum)
- [ ] TypeScript: no `any` without documented justification
- [ ] All API calls use the generated TypeScript client from OpenAPI spec
- [ ] At least one Playwright E2E test for the primary happy path (or added to staging test suite)
- [ ] No hardcoded INR amounts, percentages, or business logic (from API)
- [ ] Reviewed by Saad (domain) + Ranjith (security-sensitive screens)
- [ ] PR includes screenshot evidence of rendered feature

---

## Profile: INTEGRATION

A cross-domain integration story is DONE when:

- [ ] Cross-domain service calls use published API contracts only (no internal package imports across domain boundaries)
- [ ] Outbox event produced in the correct transaction
- [ ] Event consumer handles duplicate delivery (idempotency_keys checked)
- [ ] At least one integration test covering the full cross-domain flow end-to-end
- [ ] Contract test verifies producer schema matches consumer expectation
- [ ] 3-way match flow tested (GRN + Supplier Bill + SPO quantities reconciled)
- [ ] Error scenario: event consumer fails and retries correctly
- [ ] Reviewed by Ranjith

---

## Profile: QA

A story is DONE when:

- [ ] Test data fixtures created in `src/test/resources/sql/`
- [ ] Synthetic data only (no real pilot data in Dev/CI)
- [ ] E2E test framework set up and executes against staging
- [ ] Test run against staging DB migration dry-run passes
- [ ] All E2E journeys documented in test traceability matrix

---

## Profile: DEVEX

A story is DONE when:

- [ ] Synthetic data only — strictly zero customer PII or confidential data in any fixture or script
- [ ] One-command execution for local seed scripts (e.g. `./gradlew` or `./mvnw` or npm/bash script runs end-to-end without manual SQL tweaks)
- [ ] Baseline customer groups, tenant, users with distinct roles (all 9 tenant roles), and sample master data created cleanly
- [ ] Seed script is idempotent (can run repeatedly on clean or existing local database without unique constraint violations)
- [ ] Clear README or inline usage documentation detailing prerequisites, command syntax, and default test credentials
- [ ] Reviewed by Ranjith before merge

---

## Profile: RELEASE

A story is DONE when:

- [ ] Docker image built and pushed to Artifact Registry
- [ ] Cloud Run deployment succeeds without rollback
- [ ] Smoke test suite passes (health check + at least one domain API ping)
- [ ] Flyway migration applied to staging with zero errors
- [ ] Rollback procedure documented and tested
- [ ] Alerting policy verified in Cloud Monitoring (at least error-rate alert active)
- [ ] Dashboard shows the new feature's key metrics
- [ ] Reviewed by Ranjith before prod promotion

---

## CI-Gated Tests (MUST PASS on Every PR)

| Test | Domain | Failure risk |
|---|---|---|
| `RlsTenantIsolationTest` | All domains | Data leak across tenants |
| `RlsNoContextTest` | Shared Platform | No context = zero rows (must not error) |
| `HikariExceptionSafetyTest` | Shared Platform | Dirty connection returned to pool |
| `AuditEventAppendOnlyTest` | Shared Platform | Audit tamper prevention |
| `StockMovementAppendOnlyTest` | Inventory | Ledger integrity |
| `InventoryConcurrencyIntegrationTest` | Inventory | Race condition → oversell |
| `InvoiceSequentialNumberingTest` | Finance | Gapless numbering |
| `GstCalculationIntraInterStateTest` | Finance | Wrong GST → penalties |
| `WhatsAppHMACVerificationTest` | WhatsApp | Webhook spoofing |
| `AdminApiCustomerTokenRejectionTest` | Platform Admin | Customer accesses admin |
| `SuspendedTenantRejectionTest` | Identity | Suspended tenant serves data |

All 11 must pass before any PR can be merged.

---

## Security Scanning Gates (MUST PASS on Every PR)

| Gate | Tool | What it checks |
|---|---|---|
| Dependency CVE scan | OWASP Dependency-Check (run locally with `NVD_API_KEY` per PR #107) | Zero HIGH or CRITICAL CVE in runtime deps |
| Secret scan | Trufflehog or GitLeaks (CI-enforced) | No secrets/credentials committed |
| SAST | SpotBugs + Find Security Bugs (CI-enforced) | No HIGH-severity code vulnerabilities |
| Container scan | Trivy (CI-enforced) | No CRITICAL CVE in Docker image layers |

> [!NOTE]
> Per PR #107, OWASP Dependency-Check is run locally with an authentic `NVD_API_KEY` by developers prior to PR submission to avoid unauthenticated CI rate-limiting. CI automated gates strictly enforce TruffleHog, SpotBugs, PMD, Trivy, and JaCoCo.

---

## Coverage Thresholds

| Layer | Minimum | Rationale |
|---|---|---|
| New service classes | 80% line | Covers primary flows and negative paths |
| New controller methods | 100% (≥1 integration test per endpoint) | Every API path must be verified |
| Security filters | 100% | All paths: valid, invalid, expired, wrong tenant |
| DB constraint tests | 1 test per CHECK constraint | Verify DB-level invariants |
| Finance calculations | 100% path coverage on GST calculator | Statutory accuracy |
| Inventory concurrency | 10-thread test | Race condition verification |

Coverage percentage alone is not sufficient. Critical business logic (GST, inventory reservation, invoice numbering) must have tests that verify correctness, not just execution.

---

## Desirable Items — Deferral Pre-Conditions

The following items may be deferred from a release only with explicit founder sign-off:

| Item | Deferral condition |
|---|---|
| POD signature upload | Requires GCS multipart upload setup |
| In-app notification inbox | WebSocket infrastructure not set up |
| Approval workflow UI | Approval flow backend must still be DONE |
| In-process stage inspections | Final inspection must still be DONE |
| Manual inventory adjustment | Only after opening balance migration is DONE |
| Payment overdue alerts | Alerting infrastructure must exist |
| SPO amendment flow | SPO create/receive must be DONE first |

None of these may be deferred if a confirmed pilot customer has them as a stated requirement.
