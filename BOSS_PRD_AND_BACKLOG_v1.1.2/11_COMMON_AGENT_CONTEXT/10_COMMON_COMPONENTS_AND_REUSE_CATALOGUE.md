# BOSS — Common Components and Reuse Catalogue
**Version:** v1.1.1 | **Date:** 15 November 2026
**Package:** `in.boss.shared.*` — Ranjith retains ownership; no agent edits without coordination

---

## Purpose

This catalogue lists all shared/common components that cut across BOSS domains. Before writing a new utility, check here. Using a shared component is mandatory when one exists; duplicating it without approval is a defect.

---

## 1. BossContext (Thread-Local Request Context)

**Location:** `in.boss.shared.context.BossContext`

```java
public record BossContext(
    UUID tenantId,
    UUID userId,
    Set<String> roles,
    UUID siteId,          // nullable — not all requests are site-scoped
    String firebaseUid
) {}
```

**Usage:**
- Set by `BossSecurityFilter` after Firebase token verification.
- Read by any service: `BossContextHolder.require()` (throws if unset) or `BossContextHolder.get()` (returns `Optional`).
- Cleared in `finally` block in the filter — never rely on it persisting across threads.
- **Never pass tenantId as a method parameter** when BossContext is available.

---

## 2. HikariCP RLS Override

**Location:** `in.boss.shared.db.RlsConnectionProvider`

Overrides HikariCP `getConnection()` to run:
```sql
SET LOCAL app.current_tenant_id = '<uuid>';
```
after each connection is checked out. If `BossContext.tenantId` is null, the connection is released and an exception is thrown — no unauthenticated connection gets into the pool.

**Agent rule:** Never write `SET LOCAL app.current_tenant_id` in application code. The connection provider does it. If you are writing a Flyway migration, `app.current_tenant_id` is NOT set during migrations (they run as the DB owner, without tenant isolation).

---

## 3. AuditService

**Location:** `in.boss.shared.audit.AuditService`

```java
void record(String entityType, UUID entityId, String action, JsonNode before, JsonNode after);
```

Writes to `audit_events` (append-only). `before`/`after` are the JSON snapshots. The table is protected by a DB trigger that blocks UPDATE and DELETE.

**Usage:** All state-changing operations (create, update, status change, delete) must call `AuditService.record()` in the same transaction.

---

## 4. OutboxEventPublisher

**Location:** `in.boss.shared.outbox.OutboxEventPublisher`

```java
void publish(String eventType, String aggregateType, UUID aggregateId, JsonNode payload);
```

Writes to `outbox_events` within the current transaction. The relay (separate Cloud Run job) picks up `status = 'pending'` rows and publishes to Pub/Sub. Never publish directly to Pub/Sub.

---

## 5. IdempotencyService

**Location:** `in.boss.shared.idempotency.IdempotencyService`

```java
<T> T executeOnce(String idempotencyKey, String operationType, Callable<T> operation);
```

Checks `idempotency_keys` table. If key already processed, returns the stored result without re-executing. Stores result on success. Caller passes `Idempotency-Key` header value.

**All POST endpoints that create or mutate data must use this service.** The OpenAPI contracts all include `Idempotency-Key` as a required header for mutations.

---

## 6. ConfigOptionsService

**Location:** `in.boss.shared.config.ConfigOptionsService`

```java
List<ConfigOption> getActiveOptions(String categoryCode);
ConfigOption requireOption(UUID optionId);
```

Fetches from `tenant_config_options` for the current tenant (via BossContext). Cached with a 60-second TTL per tenant+category.

**All domains that have selectable categories, types, or reasons must use this service.** See `18_CONFIGURABLE_BUSINESS_VALUES/design.md` for the full category code list.

---

## 7. InventoryReservationService

**Location:** `in.boss.shared.inventory.InventoryReservationService`

```java
UUID reserve(UUID tenantId, UUID itemId, UUID storeId, BigDecimal qty, UUID sourceJobId);
void release(UUID reservationId);
void issue(UUID reservationId, UUID jobId);
```

Manages `stock_reservation_records` with `SELECT FOR UPDATE` pessimistic locking. Used by production job feasibility checks and material issue flows.

**Concurrency contract:** `InventoryConcurrencyIntegrationTest` (10 threads, 6 available units) must pass. Never bypass with optimistic locking in this path.

---

## 8. SequentialNumberGenerator

**Location:** `in.boss.shared.numbering.SequentialNumberGenerator`

```java
String next(UUID tenantId, UUID gstRegId, String documentType, LocalDate documentDate);
```

Generates sequential document numbers (invoices, GRNs, dispatch challans). Uses `SELECT FOR UPDATE` on `invoice_sequence_counters`. Guarantees no gaps within a financial year per GSTIN.

---

## 9. PdfGenerationService

**Location:** `in.boss.shared.pdf.PdfGenerationService`

```java
String generateAndUpload(String templateName, Map<String, Object> model, String gcsPath);
```

Renders a Thymeleaf template to PDF and uploads to GCS. Returns the GCS path. Used by invoice PDF, dispatch challan, and clearance certificate generation.

---

## 10. FirebaseTokenVerifier

**Location:** `in.boss.shared.security.FirebaseTokenVerifier`

Verifies Firebase ID tokens and extracts claims. The `BossSecurityFilter` uses this — application code does not call it directly.

**Admin API:** A separate `AdminFirebaseTokenVerifier` uses the **admin** Firebase project credentials. Customer tokens are rejected with 403 by `AdminApiCustomerTokenRejectionFilter`. `AdminApiCustomerTokenRejectionTest` must pass on all admin stories.

---

## 11. BaseEntity / TenantScopedEntity

**Location:** `in.boss.shared.persistence`

```java
@MappedSuperclass
public abstract class TenantScopedEntity {
    @Id UUID id;
    @Column UUID tenantId;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    UUID createdBy;
    UUID updatedBy;
}
```

All JPA entities that live in tenant-scoped tables extend this. The `tenantId` is populated from `BossContext` in a `@PrePersist` / `@PreUpdate` callback — never set it manually in service code.

---

## 12. TestcontainersBase

**Location:** `in.boss.shared.test.TestcontainersBase`

Base class for all integration tests. Starts a PostgreSQL 15 container, runs all Flyway migrations, and sets up an isolated test tenant. Use `@SpringBootTest` + `extends TestcontainersBase`.

**H2 is banned.** Tests that bypass Testcontainers will fail CI.

---

## 13. Cross-Domain Event Contracts

Events published to Pub/Sub via the outbox relay. Consuming domains subscribe to their relevant topics.

| Event type | Published by | Consumed by |
|---|---|---|
| `job.stage.completed` | Production | Quality (triggers inspection) |
| `job.completed` | Production | Finance (triggers invoice eligibility) |
| `grn.inspection.passed` | Quality/GRN | Inventory (creates material lots) |
| `dispatch.pod.recorded` | Dispatch | Finance (triggers invoice creation) |
| `invoice.irn.registered` | Finance | Dispatch (marks invoice as sent) |
| `tenant.provisioned` | Platform Admin | Config (seeds tenant defaults) |

Event payloads are defined in `11_COMMON_AGENT_CONTEXT/08_CROSS_DOMAIN_INTEGRATION_STANDARDS.md`.

---

## Shared Database Tables (owned by `in.boss.shared.*`)

| Table | Owner domain |
|---|---|
| `audit_events` | shared.audit |
| `outbox_events` | shared.outbox |
| `idempotency_keys` | shared.idempotency |
| `tenant_registry` | shared.tenancy |
| `tenant_entitlements` | shared.tenancy |
| `config_categories` | shared.config |
| `tenant_config_options` | shared.config |
| `tenant_config_option_history` | shared.config |

No domain agent should write migrations that alter these tables without Ranjith's explicit approval.
