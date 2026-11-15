# BOSS — Solution Architecture (Agent Reference)
**Version:** v1.1.1 | **Date:** 15 November 2026

This is the agent-readable summary. Full architectural detail is in `07_ARCHITECTURE/` (created during VS-01).

---

## Stack (Closed — Do Not Propose Alternatives)

| Layer | Technology | Notes |
|---|---|---|
| Frontend | React 18 + TypeScript + Vite | SPA; prototype baseline is authoritative |
| Backend | Java 21 + Spring Boot 3.x | Modular monolith |
| Database | PostgreSQL 15 on Cloud SQL | Flyway migrations only |
| Runtime | GCP Cloud Run | Serverless containers; no GKE |
| Auth (Customer) | Firebase Auth | Google Identity Platform |
| Auth (Admin) | Separate Firebase project | Never mix with customer auth |
| File storage | GCS | Documents, attachments, PDF output |
| Async | Pub/Sub + Transactional Outbox | No direct Pub/Sub publish |
| IaC | OpenTofu | State in GCS |
| CI/CD | GitHub Actions + WIF | No stored SA keys |
| Secrets | Secret Manager | No env var secrets |
| Observability | Cloud Logging + Cloud Monitoring + OpenTelemetry |  |

---

## Deployment Architecture

```
Internet → Cloud Load Balancer → Cloud Run: boss-api (customer)
                               → Cloud Run: boss-admin-api (platform, separate)
                                     ↓
                              Cloud SQL: PostgreSQL 15
                              GCS: file storage
                              Firebase Auth: JWT validation
                              Secret Manager: credentials
                              Pub/Sub: async events (via outbox relay)
```

**Two services, one database.** `boss-api` and `boss-admin-api` are separate Cloud Run services. `boss-admin-api` has its own Firebase project; customer JWTs are rejected by design.

---

## Multi-Tenancy Model

```
Customer Group (1)
  └─ BOSS Tenant (N) — workspace = tenant_registry row
       └─ Legal Entity (N) — registered company, has PAN
            └─ GST Registration (N) — GSTIN per state/category
                 └─ Physical Site (N) — factory/warehouse
                      └─ Store (N) — inventory location
```

**Tenant isolation:** PostgreSQL RLS on every tenant-scoped table. HikariCP `getConnection()` runs `SET LOCAL app.current_tenant_id = ?`. The `true` flag in `current_setting('app.current_tenant_id', true)` means unset context returns NULL (zero rows) — no data leak.

**BossContext record** (ThreadLocal, cleared in finally):
```java
record BossContext(UUID tenantId, UUID userId, Set<String> roles, UUID siteId, String firebaseUid)
```

---

## Modular Monolith Package Structure

```
in.boss.{domain}.api               — REST controllers, DTOs
in.boss.{domain}.domain            — Interfaces, value objects
in.boss.{domain}.domain.impl       — Service implementations
in.boss.{domain}.persistence       — JPA entities, Spring Data repos
in.boss.{domain}.events            — Outbox event publishers

in.boss.shared.context             — BossContext + BossContextHolder
in.boss.shared.security            — JWT filter, RBAC annotations
in.boss.shared.audit               — AuditService
in.boss.shared.outbox              — OutboxPublisher, relay
in.boss.shared.config              — ConfigService (configurable values)
in.boss.shared.rls                 — HikariCP RLS override
```

**Domain packages:** identity, platform, masterdata, inventory, sales, procurement, receiving, production, quality, dispatch, finance, whatsapp, maintenance, profile, config

---

## Key Architectural Patterns

### 1. Transactional Outbox
```
Service → write to outbox_events IN SAME TRANSACTION as business data
OutboxRelay (Cloud Scheduler → Cloud Tasks) → reads pending rows → publishes to Pub/Sub → marks published
```
Never call `pubSubTemplate.publish()` directly. Always use `OutboxPublisher.publish()`.

### 2. RLS Enforcement
```
HTTP request → JwtAuthFilter → BossContextHolder.set(ctx)
→ BusinessService → Repository → JPA → HikariCP.getConnection()
→ SET LOCAL app.current_tenant_id = ctx.tenantId()
→ SQL → RLS policy filters rows by tenant_id
→ finally: BossContextHolder.clear()
```
HikariCP closes connection on exception — never returns a dirty connection to the pool.

### 3. Pessimistic Concurrency for Inventory
```
SELECT qty, reserved_qty FROM material_lots WHERE id = ? FOR UPDATE
→ verify available = qty - reserved_qty >= requested
→ UPDATE material_lots SET reserved_qty = reserved_qty + requested
→ INSERT INTO stock_movements (...)
```
All in one transaction. `InventoryConcurrencyIntegrationTest` runs 10 threads.

### 4. Configurable Reference Data
```
Frontend dropdown → GET /api/v1/config/options/{category}?siteId=...
Backend → ConfigService.getOptions(tenantId, category, siteId)
→ looks up tenant_config_options with fallback to system defaults
```
See `18_CONFIGURABLE_BUSINESS_VALUES/design.md` for the full model.

### 5. Append-Only Ledgers
`stock_movements` and `audit_events` are protected by DB triggers in V050. Any UPDATE or DELETE raises `SQLSTATE 55P03`. No application code may work around this.

---

## Security Gates in CI

| Gate | Tool | Failure action |
|---|---|---|
| Secret scan | Trufflehog | Block merge |
| Dependency vulnerabilities | OWASP Dependency Check | Block merge |
| Static analysis | SpotBugs | Block merge |
| Container scan | Trivy | Block merge |
| `AdminApiCustomerTokenRejectionTest` | JUnit | Block merge |
| `RlsTenantIsolationTest` | JUnit + Testcontainers | Block merge |
| `RlsCoverageTest` | JUnit | Block merge — fails if any tenant-scoped table lacks RLS |

---

## Migration Naming

```
src/main/resources/db/migration/V001__extensions.sql
src/main/resources/db/migration/V002__customer_groups_and_tenant_registry.sql
...
src/main/resources/db/migration/V060__views.sql
```
Never modify a checksum-verified migration. Create a new one for fixes.
