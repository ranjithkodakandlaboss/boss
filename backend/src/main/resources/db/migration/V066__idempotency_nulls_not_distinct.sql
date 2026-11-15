-- V066: Fix NULL tenant uniqueness for platform-scoped idempotency keys.
-- PostgreSQL's standard UNIQUE treats NULLs as DISTINCT (not equal), so two
-- rows (NULL, 'key') would not conflict.  NULLS NOT DISTINCT (PostgreSQL 15+)
-- makes NULL == NULL in the unique index, enabling correct conflict detection
-- for platform-scoped operations (tenant_id IS NULL).

ALTER TABLE idempotency_keys DROP CONSTRAINT IF EXISTS idempotency_keys_tenant_key_uq;

ALTER TABLE idempotency_keys
    ADD CONSTRAINT idempotency_keys_tenant_key_uq
    UNIQUE NULLS NOT DISTINCT (tenant_id, key);
