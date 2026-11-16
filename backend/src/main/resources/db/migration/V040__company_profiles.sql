-- V040: Company profiles (tenant-scoped)
CREATE TABLE company_profiles (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  legal_entity_id  UUID NOT NULL REFERENCES legal_entities(id),
  display_name     TEXT NOT NULL,
  tagline          TEXT,
  website          TEXT,
  about            TEXT,
  logo_gcs_path    TEXT,
  banner_gcs_path  TEXT,
  contact_email    TEXT,
  contact_phone    TEXT,
  certifications   JSONB,
  capabilities     JSONB,
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','published','archived')),
  published_at     TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE company_profile_sections (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  profile_id      UUID NOT NULL REFERENCES company_profiles(id),
  section_type    TEXT NOT NULL,
  title           TEXT,
  content         JSONB,
  sort_order      INTEGER NOT NULL DEFAULT 0,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_company_profiles_tenant   ON company_profiles(tenant_id);
CREATE INDEX idx_company_profile_sections  ON company_profile_sections(tenant_id, profile_id);
