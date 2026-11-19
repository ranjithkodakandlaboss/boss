# BOSS Integration Contracts — Shared Platform
Branch: `claude/affectionate-cori-xt092x`  
Commit: c1136f5  
Tests: 27/27 passing (PostgreSQL 15, Testcontainers)  
Target: December 20 2026 build-complete

---

## 1. Auth / Tenant-Context Contract

Every request that reaches a business service MUST have a `BossContext` in `BossContextHolder`.

```java
// Record fields
record BossContext(
    UUID   tenantId,     // null only for platform-admin operations
    UUID   userId,       // app_users.id (not Firebase UID)
    Set<String> roles,   // e.g. {"OWNER", "OPERATOR"}
    UUID   siteId,       // optional — null if not site-scoped
    String firebaseUid   // Firebase UID, used for bootstrap only
)
```

`TenantContextResolver` (security filter) populates this from the Firebase JWT before any
controller runs. **Codex's platform-admin-api** may set `tenantId = null` for cross-tenant ops.

### Bootstrap path (Firebase UID → app_users)
Call the SECURITY DEFINER function directly (bypasses RLS):
```sql
SELECT * FROM boss_resolve_firebase_uid(:firebaseUid);
-- Returns: id (UUID), tenant_id, roles[]
```
This function is the only sanctioned way to look up a user record before the RLS context
is established. Do NOT query `app_users` directly with a superuser connection in application code.

---

## 2. Database Role Contract

| Role | Type | Used by |
|---|---|---|
| `boss_app_role` | NOLOGIN INHERIT | granted to the application login role |
| `boss_platform_admin_role` | NOLOGIN INHERIT | granted to the platform-admin login |
| Application login | LOGIN | set at deploy time; must be granted `boss_app_role` |

**Constraints:**
- `boss_platform_admin_role` MUST NOT have `BYPASSRLS`.
- `boss_platform_admin_role` MUST NOT have SELECT/INSERT/UPDATE/DELETE on customer business
  tables (`orders`, `production_jobs`, `invoices`, etc.).
- RLS is enforced for both roles. `set_config('app.current_tenant_id', tenantId, true)` must
  be called at the start of every transaction (handled by `BossConnectionWrapper`).

### Migrations
Flyway prefix convention:
```
V001–V060   core schema (do not modify)
V061–V069   shared platform (this agent's range)
V070+       feature-specific (Codex/Antigravity use their own range; agree before merging)
```
Never create an index without `IF NOT EXISTS` if a prior migration may have created it.

---

## 3. Idempotency Contract (issue #96)

All POST/PUT/PATCH to `/api/**` MUST include `Idempotency-Key` header (max 128 chars).

### Filter behaviour
| Scenario | Response |
|---|---|
| Missing key | 400 `MISSING_IDEMPOTENCY_KEY` |
| Key > 128 chars | 400 `INVALID_IDEMPOTENCY_KEY` |
| Same key + same payload | 200/201 + `X-Idempotent-Replayed: true` (no business logic re-runs) |
| Same key + different payload | 409 `IDEMPOTENCY_KEY_REUSE` |
| Concurrent in-flight | 503 `CONCURRENT_REQUEST` (retry after first completes) |
| Store unavailable | 503 `IDEMPOTENCY_STORE_UNAVAILABLE` (fail closed) |
| Expired key (TTL 24h) | treated as new key — business logic re-runs |

**Key scoping:**
- Tenant ops: scoped to `(tenant_id, key)`.
- Platform ops (null tenant): scoped to `(NULL, key)` — uniqueness enforced via `NULLS NOT DISTINCT` (V066, PG 15+).

**Only 2xx responses are cached.** Non-2xx expires the row; client may retry.

---

## 4. Tenant-Default Seeding Interface (issue #91)

```java
// Inject IndustryTemplateService
industryTemplateService.applyTemplate(UUID tenantId, String industryType);
industryTemplateService.seedStandardUoms(UUID tenantId);
```

**Approved industry types** (exact strings — do not add others without architecture review):
```
FABRICATION_STEEL
PRECISION_MACHINING
ELECTRONICS_ASSEMBLY
FORGING
CASTING
GENERAL_MANUFACTURING
```
`applyTemplate` is idempotent — calling twice does not duplicate rows.

---

## 5. Audit / Outbox Usage

```java
// Inject OutboxPublisher
outboxPublisher.publish(String aggregateType, UUID aggregateId,
                        String eventType, String payloadJson);
```
Call inside a transaction; the outbox row is committed atomically with the business change.
The relay process reads from `outbox_events` and publishes to PubSub.

`audit_events` is append-only — no UPDATE/DELETE (enforced by trigger). Every write through the
application should emit an audit event.

---

## 6. Required HTTP Headers / Error Format

```
Idempotency-Key: <client-generated UUID or string, max 128 chars>  [required: POST/PUT/PATCH]
X-Tenant-Id:     <UUID>   [optional override — resolved from JWT by default]
```

Error body shape (all error responses):
```json
{ "error": "ERROR_CODE_SNAKE_UPPER", "message": "Human-readable description." }
```

---

## 7. Test Conventions

- Use `@SpringBootTest` + `@AutoConfigureTestDatabase(replace = NONE)` + Testcontainers.
- `src/test/resources/application.properties` excludes Spring Cloud GCP auto-configurations.
  Do not remove this file; tests will fail to start without it.
- Use `@MockBean FirebaseApp firebaseApp` and `@MockBean FirebaseAuth firebaseAuth` in every
  `@SpringBootTest` class to prevent `FirebaseConfig` from initialising.
- RLS tests MUST run queries through a non-superuser connection (see `RlsTenantIsolationTest`).
- Testcontainers uses `postgres:15-alpine`. Do not upgrade without testing `NULLS NOT DISTINCT`.

---

## Sales PDF downloads

`GET /api/v1/quotations/{quotationId}/document` and `GET /api/v1/cpos/{cpoId}/document`
download an `application/pdf` attachment. Both use the document site's seller
registration and the customer's stored billing identity. For domestic GST classification
they use the state code of a supplied GSTIN, or a recognized address state if the
GSTIN is absent. A taxable document without sufficient jurisdiction data returns 422.
Rupee amounts use Indian digit grouping, for example `INR 2,25,000.00`.
These routes generate a printable copy; they do not send email or WhatsApp messages.

## Customer PO material availability preview

`GET /api/v1/cpos/{cpoId}/material-availability` returns consolidated remaining
requirements from current approved fixed BOMs and eligible stock at the order's site.
Each material includes its base UOM, required, eligible stock, total reserved,
freely available and shortage quantities. The response includes its calculation basis
and limitations. It is read-only and does not reserve stock or create a requisition.

The preview rejects orders with active CPO reservations because their BOM revision is
not snapshotted, as well as missing, ambiguous, ineffective or unsupported BOMs and
incompatible units. Specification-driven calculations and traded products without BOMs
are not supported. It is not a historical snapshot or a stock commitment.

A future reservation or shortage-PR operation must recalculate under appropriate locks,
reconcile existing reservations and persist its own requirement snapshot; it must not
trust or reuse a previous preview as authorization to allocate stock or create a PR.
Inventory reservations use three-decimal quantities, so that operation must define
safe rounding rather than silently truncating the preview's exact requirements.

## Sales lifecycle

- Quotation detail and list GETs show a live preview for drafts with missing or
  incomplete costing. This does not save a new snapshot or change historical records.
- `PATCH /api/v1/quotations/{id}/status` with `{"status":"sent"}` retries a missing
  or incomplete draft costing snapshot. Complete snapshots are retained. The
  drawing-number, fully valued on-hand inventory, and margin-approval rules
  still apply. Read the error `detail` for the specific blocker.
- Send `{"status":"cancelled"}` to the RFQ or quotation status endpoint to
  cancel linked quotations and Customer POs in the same transaction. The
  existing Customer PO cancellation policy still applies; a fulfilled PO
  blocks the cascade. Cancellation does not delete records or cancel existing
  production jobs, requisitions, or dispatches.
- `POST /api/v1/cpos` returns a flat Customer PO with an `id` and null
  `quotationId` for a direct order. Direct order lines accept active finished
  and semi-finished items only.
- Customer `rating` is an optional integer from 0 to 5. On PATCH, omit it to
  retain the current rating or send `null` to clear it. Document Vault uploads
  may link to `Customer`, `RFQ`, `Quotation`, or `CustomerPO`; cancelled sales
  records cannot receive new links.
- Customer deactivation (`DELETE /api/v1/customers/{id}` or PATCH with
  `{"status":"inactive"}`) is atomic. Active (`open`), partially fulfilled, or
  feasibility-approved Customer POs block deactivation with 409. Pending
  (`feasibility_pending`) and on-hold POs also block it if they have any
  non-cancelled production job, delivered quantity, or invoiced quantity.
  Otherwise pending/on-hold POs, pending RFQs, and pending quotations are
  cancelled through the normal audited lifecycle, releasing PO reservations.
  Fulfilled POs and completed quotation history remain unchanged.
- Inactive customers and their sales records are read-only and cannot receive
  new document links or new sales records. Reactivation is a separate,
  status-only customer PATCH (`{"status":"active"}`); combining reactivation
  with profile edits is rejected. Reactivation does not reopen cancelled records.

---

## 8. Known Risks / Pending Items

| Item | Owner | Status |
|---|---|---|
| `boss_resolve_firebase_uid` not covered by unit tests | Shared Platform | open |
| Antigravity `master-data` branch rebase onto integration branch | Antigravity | pending |
| Integration contract review by Codex | Codex | pending |
| CI pipeline runs for `codex/**` and `antigravity/**` added | done | ✅ |
| Docker proxy required locally for Testcontainers (API version gap) | devex | workaround in place |
