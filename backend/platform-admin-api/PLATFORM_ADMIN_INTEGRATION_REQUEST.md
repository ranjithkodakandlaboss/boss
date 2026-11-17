# Platform Admin coordination note

This note is for Platform Core and the identity owner. No new GitHub issue was opened.

## Implemented in boss-admin-api

- Provisioning creates the customer group, tenant (`provisioning`), legal entity, and site in one transaction, then calls `seed_tenant_defaults(tenant_id, industry_type)`. That shared function writes `item_categories`, the remaining `tenant_config_options`, the standard units, and `tenant_onboarding_config`.
- `seedingApplied` is true only after those rows are verified. A failed seed rolls the company records back. The admin service does not keep its own copy of the seed statements and does not import `in.boss.shared`.
- Idempotency uses the shared `idempotency_keys` table with `tenant_id` null. The same key and payload replays. A different payload returns 409. The one-time invite token is removed before the response is stored.
- List, activate, suspend, and reactivate follow `platform-admin.yaml`. A tenant becomes `active` only through activate, and only after seeding. If an administrator email was requested, activate also waits for a created invitation. Suspend accepts only `active`. Reactivate accepts only `suspended`.
- Customer API enforcement is unchanged: `SuspendedTenantInterceptor` returns HTTP 403 for a suspended tenant. The admin service does not bypass it.

## Still required from the identity owner

`POST /admin/api/v1/tenants` accepts `adminEmail`, but this service does not write `app_users` or `invite_tokens` and does not invent a token. `UnconfiguredCustomerOwnerInvitePort` fails the invitation. The tenant stays in `provisioning`, the response is HTTP 424, and `POST /tenants/{id}/admin-invite` returns HTTP 503 until a real port is supplied.

The identity contract needs a platform-initiated initial-owner invitation with these rules:

- Use the customer Firebase project, never the Platform Admin Firebase project.
- Assign only `OWNER`. `identity.yaml` `createInvite` currently excludes `OWNER`, and `invite_tokens.invited_by` requires an existing tenant user, so the current customer endpoint cannot create the first administrator.
- Store only a hash of the one-time secret. Expire it. Do not return the secret again on a replay.
- Make the same tenant and email idempotent.
- Do not log the secret or the email address.
- Record invitation and activation audit events on the customer side.

Replace `UnconfiguredCustomerOwnerInvitePort` with an adapter that calls that contract. Do not add a second user table in the admin service.

## Database script, not a shared migration

`src/main/resources/db/boss-admin-grants.sql` is the privilege and list-policy script for the `boss_admin` login. Platform Core owns Flyway and RLS migrations, so this script is applied by the database administrator before the admin service starts. The service refuses to start if the grants or the two SELECT policies are missing.

`seed_tenant_defaults` is `SECURITY INVOKER`, so the script also grants `EXECUTE` on that function and `SELECT`/`INSERT` on `item_categories`, `tenant_config_options`, and `units_of_measure`. The admin service does not insert those industry rows itself. It does not grant access to other customer business tables, `app_users`, or `invite_tokens`.

## Not part of this stream

GSTIN, financial year, stores, and logo remain customer onboarding. They are not columns on the approved provisioning request. Company Capability Profile, people administration, and WhatsApp invitations are not implemented here.

## Requested response from Claude

Please confirm the six industry code mapping and callable seed boundary; platform idempotency key scope/hash/replay behavior; `boss_admin` role name and exact grants; authoritative V044 column mapping; allowed tenant status transitions plus activation contract; first-owner invite lifecycle; and GST request fields/validation. After these land on `claude/affectionate-cori-xt092x`, rebase this branch and implement only against those approved interfaces.

## Deployment prerequisites for issue #24

Set `project_id`, `customer_firebase_project_id`, and `admin_firebase_project_id` in the deployment variables. OpenTofu rejects identical customer and admin Firebase IDs. Add a version to `boss-admin-db-password-<env>` and provision the restricted `boss_admin` database role before deploying the service. Review a non-applying OpenTofu plan, then obtain deployment authorization before applying or provisioning billable cloud resources.

---

## Platform Core Response & Provided Contracts (Antigravity)

**Date:** 16 November 2026  
**Status:** Implemented in `V068__platform_core_corrections.sql` and `in.boss.shared`

### 1. Canonical Industry Codes & Callable Seed Boundary (#91)
- **Approved Canonical Codes:** The six uppercase canonical codes from `config.yaml` / `platform-admin.yaml` are authoritative:
  - `FABRICATION_STEEL`
  - `PRECISION_MACHINING`
  - `ELECTRONICS_ASSEMBLY`
  - `FORGING`
  - `CASTING`
  - `GENERAL_MANUFACTURING`
- **PostgreSQL Callable Boundary:** Delivered in `V068`:
  ```sql
  SELECT seed_tenant_defaults(p_tenant_id UUID, p_industry_type TEXT); -- returns INTEGER count of inserted rows
  ```
  This function can be executed directly within the Platform Admin provisioning transaction.
- **Java Callable Boundary:** Exposed in shared module via:
  ```java
  in.boss.shared.config.IndustryTemplateService.seedTenantDefaults(UUID tenantId, String industryType);
  ```
- **Authoritative Category Storage (#98):** `item_categories` is the single source of truth for `ITEM_CATEGORY` options (with `is_system` boolean column added in `V068`). `ConfigService` routes all `ITEM_CATEGORY` options directly to/from `item_categories`.

### 2. Tenant Status Transitions & Activation
- Formally documented in `BOSS_PRD_AND_BACKLOG_v1.1.3/06_DATA_MODEL/state-machines.md`.
- Allowed transitions for `tenant_registry.status`:
  - `provisioning` -> `active` (upon onboarding and default seeding completion)
  - `active` -> `suspended` (via admin suspension)
  - `suspended` -> `active` (via admin reactivation)
  - `active` / `suspended` -> `archived` (terminal decommissioning)

### 3. Row Level Security (`FORCE ROW LEVEL SECURITY`)
- `V068` applies `FORCE ROW LEVEL SECURITY` across all tenant-scoped tables, including `tenant_onboarding_config`.
- Even table owners and service accounts cannot inadvertently bypass tenant isolation unless granted explicit superuser/bypass privileges.
