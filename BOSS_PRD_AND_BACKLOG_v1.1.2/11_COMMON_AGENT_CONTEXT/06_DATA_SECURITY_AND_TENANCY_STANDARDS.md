# BOSS — Data Security and Tenancy Standards
**Version:** v1.1 | **Date:** 15 November 2026
**Authority:** Ranjith (architecture + security review)

These are non-negotiable. No story is DONE unless it satisfies these standards.

---

## 1. Multi-Tenancy Model

BOSS uses a **shared database, shared schema** multi-tenancy approach:
- One Cloud SQL PostgreSQL instance per environment (dev/stage/prod)
- One `boss_<env>` database
- All tenant data co-located in the same tables
- **PostgreSQL Row-Level Security (RLS)** enforces tenant isolation at the database level

The tenant context is set per-request by overriding HikariCP's `getConnection()`:

```java
@Override
public Connection getConnection() throws SQLException {
    Connection conn = super.getConnection();
    try {
        BossContext ctx = BossContextHolder.getContext();
        if (ctx != null) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT set_config('app.current_tenant_id', ?, true)")) {
                ps.setString(1, ctx.tenantId().toString());
                ps.execute();
            }
        }
    } catch (Exception e) {
        conn.close();  // NEVER return a dirty connection
        throw e;
    }
    return conn;
}
```

The `true` second argument to `set_config()` makes the setting **transaction-local** (reset on COMMIT/ROLLBACK). This prevents context leaking between requests on the same connection.

The `true` argument to `current_setting('app.current_tenant_id', true)` in RLS policies **returns NULL instead of raising an error** when the setting is not set. NULL::uuid ≠ anything → zero rows. This is the secure default: unset context = no data.

---

## 2. RLS Policy — Mandatory Pattern

Every tenant-scoped table MUST have this RLS policy:

```sql
ALTER TABLE {table_name} ENABLE ROW LEVEL SECURITY;
ALTER TABLE {table_name} FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation ON {table_name}
  USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid);
```

`FORCE ROW LEVEL SECURITY` ensures the policy applies even to the table owner (the application's DB user). This prevents application-level privilege escalation.

Tables WITHOUT RLS (platform-wide reference or platform admin):
- `outbox_events` (relay job runs as platform role)
- `idempotency_keys` (platform-level deduplication)
- `hsn_codes` (reference data, no tenant data)
- `tenant_registry` (read by platform, not tenant app)
- `tenant_entitlements` (read by platform auth layer)
- `customer_groups` (read by platform auth layer)
- `platform_admin_users`, `platform_audit_events` (admin service only)

---

## 3. Authentication

### Customer App
- Firebase Auth (Google Identity Platform) issues JWTs
- Spring Boot `JwtAuthenticationFilter` validates JWT signature against Firebase public keys
- `BossContext` extracted from JWT claims and stored in `BossContextHolder` (ThreadLocal)
- `BossContext` contains: `tenantId`, `userId`, `roles`, `siteId` (optional)

### Platform Admin App
- **Separate Firebase project** — never shared with customer Firebase project
- `boss-admin-api` is a **separate Cloud Run service** — never co-deployed with `boss-api`
- Admin JWT cannot authenticate against customer API and vice versa (different issuers)
- CI-gated test: `AdminApiCustomerTokenRejectionTest` — passes a valid customer JWT to every admin endpoint and asserts HTTP 401

---

## 4. RBAC — Role-Based Access Control

9 roles defined in `user_roles.tenant_role`:

| Role | Scope | Key Capabilities |
|---|---|---|
| OWNER | Tenant-wide | Full access, consolidated dashboard |
| OPS_MANAGER | Tenant/site | Production, inventory, quality, dispatch |
| SALES_MANAGER | Tenant | RFQs, quotations, CPOs, invoices |
| PURCHASE_MANAGER | Tenant | PRs, SPOs, GRNs, bills, payments |
| PRODUCTION_SUPERVISOR | Site | Jobs, stages, material issues |
| STORE_KEEPER | Site | Stock, GRN confirmation, issues/returns |
| ACCOUNTS_STAFF | Tenant | Invoices, receipts, bills, payments |
| QUALITY_INSPECTOR | Site | Inspections, NCRs |
| FIELD_WORKER | Site | WhatsApp only — job/stage updates |

RBAC enforcement:
- Spring Security `@PreAuthorize` annotation on each service method
- `BossContext.hasRole(role)` check before any state-changing operation
- Role checks are IN ADDITION TO RLS, not instead of it

Concession approval requires OWNER or OPS_MANAGER. No other role may grant concessions.

---

## 5. Audit

Every significant state change must write to `audit_events`:

```sql
INSERT INTO audit_events 
  (tenant_id, actor_id, entity_type, entity_id, action, old_data, new_data, ip_address)
VALUES (...);
```

`audit_events` is append-only: a DB trigger blocks UPDATE and DELETE.

The `AuditService` (in `in.boss.shared.audit`) is the ONLY way to write audit events.

Do NOT write to `audit_events` directly in domain code — use `AuditService.record()`.

---

## 6. Outbox Pattern

No async event may be published directly to Pub/Sub.

All events MUST be written to `outbox_events` in the SAME DATABASE TRANSACTION as the state change:

```java
// Correct — same transaction
@Transactional
public void createInvoice(CreateInvoiceCommand cmd) {
    Invoice invoice = invoiceRepository.save(...);
    outboxPublisher.publish("invoice.created", invoice.getId(), payload);
    // Both committed together or both rolled back
}

// WRONG — do NOT do this
@Transactional
public void createInvoice(CreateInvoiceCommand cmd) {
    Invoice invoice = invoiceRepository.save(...);
    pubSubTemplate.publish("invoice-topic", payload);  // This may succeed even if save fails
}
```

The outbox relay job (separate background process) publishes from `outbox_events` to Pub/Sub and marks them published. It retries on failure.

---

## 7. Idempotency

All external integration calls (IRP/IRN, WhatsApp, BSP, payment gateway) must use `idempotency_keys`:

1. Generate a stable key: `SHA256(operation_type + entity_id + relevant_params)`
2. Check `idempotency_keys` table before calling
3. If key exists and `created_at` < 24h ago: return cached response
4. If not: call, then INSERT into `idempotency_keys`

WhatsApp incoming messages: deduplicate on `wamid` (Meta's message ID).

---

## 8. Secrets Management

- ALL secrets (Firebase service account, DB password, HMAC keys, GSP API key) stored in Secret Manager
- Cloud Run service account has `roles/secretmanager.secretAccessor` for specific secrets only
- Zero secrets in environment variables, source code, Flyway scripts, or config files
- CI/CD uses Workload Identity Federation — no stored service account JSON keys
- Secret scan (Trufflehog or GitLeaks) is a CI gate on every PR

---

## 9. Append-Only Ledgers

Two tables must NEVER be updated or deleted:

| Table | Why |
|---|---|
| `stock_movements` | Inventory ledger — correctness requires complete unmodified history |
| `audit_events` | Tamper-proof audit — legal and compliance requirement |

Both have DB triggers (`V050`) that RAISE EXCEPTION on UPDATE or DELETE.

To correct an inventory error: add an `adjustment_add` or `adjustment_reduce` stock movement.
To correct an audit error: you cannot — flag to Ranjith.

---

## 10. Suspended Tenant Handling

A tenant with `status = 'suspended'` must receive HTTP 403 on ALL API calls, including read-only.

The `SuspendedTenantInterceptor` (in `in.boss.shared.security`) runs BEFORE controller code and checks `tenant_registry.status`.

CI-gated test: `SuspendedTenantRejectionTest` verifies this for a sample of endpoints.

---

## 11. PII Handling

PII fields in BOSS (email, phone, name, address):
- Stored in encrypted Cloud SQL at-rest (GCP-managed key)
- Never logged at INFO or below (only entity IDs in logs, not names/emails)
- Never in URL parameters
- Accessible only to roles with business need

GSTIN is not PII but is commercially sensitive — treat accordingly.

---

## 12. What Ranjith Reviews

Ranjith must review before merge:
- Any change to `in.boss.shared.*` packages
- Any change to RLS policies or HikariCP override
- Any security filter, JWT validation, or token verification code
- Any WhatsApp HMAC verification code
- Any inventory reservation/release logic (concurrency-critical)
- Any invoice sequential numbering logic
- Any Platform Admin auth separation code
- Any migration that adds or removes RLS from a table
