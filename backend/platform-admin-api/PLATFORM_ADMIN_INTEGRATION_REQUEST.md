# Platform Admin integration requests

This implementation follows the checked-in `platform-admin.yaml` and the actual committed migrations. The following contract or platform changes are required before the full assigned scope can be accepted. No OpenAPI contract, migration, shared security component, infrastructure file, or CI workflow was changed here.

## Contract changes needed

1. **Tenant activation:** `platform-admin.yaml` defines provision, suspend, and reactivate, but no activation operation. Provisioning creates status `provisioning`; `reactivate` is defined only for a suspended tenant. Add and approve an activation operation, including allowed source statuses and its response, before exposing an activation route.
2. **GST registration:** `TenantProvisionRequest` has no GSTIN, registration type, trade name, or effective date. `gst_registrations.gstin` and `effective_from` are non-null. Add the approved GST registration input(s) and validation rules (or explicitly define GST as a later onboarding step) before provisioning can create this record.
3. **Initial tenant administrator:** `adminEmail` is optional and `identity.yaml` has no platform-initiated invitation endpoint. `app_users.firebase_uid` is required, and `invite_tokens.invited_by` requires an existing tenant user. Define the customer identity invite/first-owner flow and the contract's `adminInviteToken` lifecycle. This service intentionally does not fabricate a Firebase UID, write the customer identity tables, or mint an unpersisted token.
4. **Idempotency:** provisioning requires `Idempotency-Key`, while V063 changes the idempotency key to `(tenant_id, key)` and permits multiple `NULL` tenant IDs. A pre-tenant operation needs an approved platform-scoped idempotency scheme or a shared middleware contract that covers `tenant_id IS NULL` uniquely.
5. **Industry seeding:** contract response includes `seedingApplied`; the tenant seeding story #91 is open, and no `seed_tenant_defaults()` function exists in checked-in migrations. V062's industry codes also differ from the six v1.1.3 request values. Complete and integrate #91 before reporting seeding as applied.

## Restricted database principal required

Committed V061 grants `boss_app` SELECT/INSERT/UPDATE/DELETE on every current and future table. The admin service must not use `boss_app`. Add an integration migration/role provisioning step for a distinct `boss_admin` login role with `NOSUPERUSER NOBYPASSRLS`, and grant only:

- `SELECT, INSERT` on `customer_groups`;
- `SELECT, INSERT` on `tenant_registry` and column-level `UPDATE(status, updated_at)` only;
- `INSERT` on `legal_entities`, `gst_registrations`, `physical_sites`, and `tenant_onboarding_config` (tenant rows remain protected by RLS);
- `SELECT` on `platform_admin_users`;
- `INSERT` on `platform_audit_events` (never UPDATE/DELETE);
- schema usage. PostgreSQL's built-in UUID generation requires no broad customer-table grant. Revoke inherited/default function EXECUTE grants if any `SECURITY DEFINER` routine can read customer data; the service invokes no custom database function.

Do not grant access to `app_users`, `user_roles`, `invite_tokens`, or customer business tables. Tenant scoped writes use transaction-local `app.current_tenant_id`; do not grant `BYPASSRLS`. The runtime `DatabasePrivilegeGuard` checks that the configured principal is non-superuser/non-BYPASSRLS, has the exact table privilege allowlist above, and cannot access any other public table or view. The exact grants should be reviewed alongside the identity invitation design. This repository change intentionally adds no migration or live database grant.

## Migration/schema mismatch to resolve

The V1.1.3 prose schema's `platform_admin_users`/`platform_audit_events` columns differ from committed V044 (`platform_admin_users` has no `role`; the audit FK is `admin_id` and includes `entity_type`, `entity_id`, `action`). Runtime SQL in this service follows committed migration V044. Align the schema document or migration through the platform owner before further contract work.

## Deployment plan when the development project is ready

Provide the BOSS development GCP project ID and the selected platform-admin Firebase project ID. Then review a non-applying OpenTofu plan for a dedicated service account, separate `boss-admin-api` Cloud Run service, Secret Manager bindings for its database credential, admin Firebase project configuration, and Cloud SQL connectivity. Do not apply or provision resources until separately authorized and billing is configured.
