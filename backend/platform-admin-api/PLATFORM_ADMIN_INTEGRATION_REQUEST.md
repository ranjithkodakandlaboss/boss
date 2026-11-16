# Platform Admin integration requests

This implementation follows the checked-in `platform-admin.yaml` and committed migrations from `claude/affectionate-cori-xt092x` at `4903f7a`. It adds V067 for the `tenant_onboarding_config` table already specified by the approved v1.1.3 physical schema; no API contract is changed. It also adds a separate `boss-admin-api` Cloud Run service definition, service account, secret mount, Firebase project input, image build, and CI build/test path. No cloud resources were applied.

**Coordination status:** the shared implementation commits for #91 and #96 are present in that integration baseline, but both issues remain open and their checked-in interfaces do not support the Platform Admin contract. I attempted to raise a coordination issue through the GitHub connector; it returned 403 (`Resource not accessible by integration`). This document is the current integration request for Claude. No shared change is being made from this branch.

## Contract changes needed

1. **Tenant activation:** `platform-admin.yaml` defines provision, suspend, and reactivate, but no activation operation. Provisioning creates status `provisioning`; `reactivate` is defined only for a suspended tenant. Add and approve an activation operation, including allowed source statuses and its response, before exposing an activation route.
2. **GST registration:** `TenantProvisionRequest` has no GSTIN, registration type, trade name, or effective date. `gst_registrations.gstin` and `effective_from` are non-null. Add the approved GST registration input(s) and validation rules (or explicitly define GST as a later onboarding step) before provisioning can create this record.
3. **Initial tenant administrator:** `adminEmail` is optional and `identity.yaml` has no platform-initiated invitation endpoint. `app_users.firebase_uid` is required, and `invite_tokens.invited_by` requires an existing tenant user. Define the customer identity invite/first-owner flow and the contract's `adminInviteToken` lifecycle. This service intentionally does not fabricate a Firebase UID, write the customer identity tables, or mint an unpersisted token.
4. **Idempotency:** provisioning requires `Idempotency-Key`, while V063 changes the idempotency key to `(tenant_id, key)` and permits multiple `NULL` tenant IDs. A pre-tenant operation needs an approved platform-scoped idempotency scheme or a shared middleware contract that covers `tenant_id IS NULL` uniquely.
5. **Industry seeding (#91):** the contract/schema use `FABRICATION_STEEL`, `PRECISION_MACHINING`, `ELECTRONICS_ASSEMBLY`, `FORGING`, `CASTING`, `GENERAL_MANUFACTURING`. The shared commit adds `IndustryTemplateService.applyTemplate`, but V062 and that service instead use `auto_components`, `sheet_metal`, `injection_moulding`, `precision_machining`, `electronic_assemblies`, `textiles`; no `seed_tenant_defaults()` function exists. Please confirm canonical values and provide a callable boundary that can join the provisioning transaction while preserving the six approved values. Until then provisioning records the contract's `industryType` in `tenant_onboarding_config` and returns `seedingApplied=false`.
6. **Tenant provisioning status transitions:** provisioning writes `provisioning`; the contract has no activation route or approved transition matrix. Current suspend behavior allows any non-suspended status, including `archived`, to become `suspended`. Please approve the allowed source/target matrix for suspend/reactivate and activation, including whether archived tenants are terminal. No activation route or new state transitions are added until approval.

## Restricted database principal required

Committed V061 grants `boss_app` SELECT/INSERT/UPDATE/DELETE on every current and future table. The admin service must not use `boss_app`. Please approve/provide a distinct `boss_admin` login role with `NOSUPERUSER NOBYPASSRLS` and the exact grants. The service's currently required access is:

- `INSERT` plus column-level `SELECT(id)` on `customer_groups`;
- `SELECT, INSERT` on `tenant_registry`, column-level `SELECT(id)`, and column-level `UPDATE(status, updated_at)` only;
- `INSERT` plus column-level `SELECT(id)` on `legal_entities` and `physical_sites` (tenant rows remain protected by RLS);
- `SELECT, INSERT` on `tenant_onboarding_config` because tenant listing reads industry type under RLS;
- `SELECT` on `platform_admin_users`;
- `INSERT` on `platform_audit_events` (never UPDATE/DELETE);
- schema usage. GST registration access is pending the approved request fields. PostgreSQL's built-in UUID generation requires no broad customer-table grant. Revoke inherited/default function EXECUTE grants if any `SECURITY DEFINER` routine can read customer data; the service invokes no custom database function.

Do not grant access to `app_users`, `user_roles`, `invite_tokens`, or customer business tables. Tenant-scoped writes and onboarding reads use transaction-local `app.current_tenant_id`; do not grant `BYPASSRLS`. The runtime guard currently expects the configured `boss_admin` principal, checks effective public-table and sequence privileges, and validates required column-level grants. Treat that principal/grant allowlist as provisional until Claude confirms the database role design; this branch adds no role migration or live database grant.

## Migration/schema mismatch to resolve

The V1.1.3 prose schema's `platform_admin_users`/`platform_audit_events` columns differ from committed V044 (`platform_admin_users` has no `role`; the audit FK is `admin_id` and includes `entity_type`, `entity_id`, `action`). Runtime SQL in this service follows committed migration V044. Align the schema document or migration through the platform owner before further contract work.

## Existing integration test limitation

`AdminApiCustomerTokenRejectionTest` now copies and runs the actual `backend/src/main/resources/db/migration` set, then creates the proposed restricted `boss_admin` test principal. This is not yet integration evidence: Testcontainers cannot discover the configured Docker Desktop daemon (HTTP 400 with empty Docker `/info` data), so the suite stops before any integration test method runs. The Java 21 verifier unit tests are the only tests executed successfully so far. Once the agreed database role design and a usable PostgreSQL 15 Testcontainers environment are available, run the full suite before integration.

## Requested response from Claude

Please confirm the six industry code mapping and callable seed boundary; platform idempotency key scope/hash/replay behavior; `boss_admin` role name and exact grants; authoritative V044 column mapping; allowed tenant status transitions plus activation contract; first-owner invite lifecycle; and GST request fields/validation. After these land on `claude/affectionate-cori-xt092x`, rebase this branch and implement only against those approved interfaces.

## Deployment prerequisites for issue #24

Set `project_id`, `customer_firebase_project_id`, and `admin_firebase_project_id` in the deployment variables. OpenTofu rejects identical customer and admin Firebase IDs. Add a version to `boss-admin-db-password-<env>` and provision the restricted `boss_admin` database role before deploying the service. Review a non-applying OpenTofu plan, then obtain deployment authorization before applying or provisioning billable cloud resources.
