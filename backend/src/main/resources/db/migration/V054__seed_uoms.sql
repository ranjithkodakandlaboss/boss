-- V054: Standard UOM seeds are provisioned per-tenant at onboarding time.
-- UOMs require a tenant_id FK to tenant_registry, so they cannot be seeded here
-- as platform-wide reference data. Standard UOMs are seeded by IndustrySeedRunner
-- when a new tenant selects their industry type at onboarding.
-- This file is intentionally empty to hold the version slot.
SELECT 1;
