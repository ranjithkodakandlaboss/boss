-- V125: Enforce global uniqueness of firebase_uid across all tenants.
--
-- A Firebase UID identifies exactly one person in one Firebase project.
-- Allowing the same firebase_uid in multiple tenants creates non-deterministic
-- tenant resolution at sign-in time (boss_resolve_firebase_uid would match
-- multiple rows and pick the first non-deterministically).
--
-- If the existing data has duplicates (the same person in multiple tenants),
-- this migration will fail — that is intentional: the duplicate must be resolved
-- before the constraint can be enforced.

CREATE UNIQUE INDEX IF NOT EXISTS uidx_app_users_firebase_uid
  ON app_users (firebase_uid);
