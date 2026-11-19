-- V113: Document Vault
-- Tables, RLS policies, indexes, and role grants for tenant-scoped documents, immutable versions, and record links.

CREATE TABLE IF NOT EXISTS vault_documents (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenant_registry(id),
    title              TEXT NOT NULL,
    doc_type           TEXT NOT NULL DEFAULT 'General',
    category           TEXT NOT NULL CHECK (category IN (
                           'Job Documents',
                           'Quality Documents',
                           'Material Certificates',
                           'Supplier Documents',
                           'Company Certificates',
                           'Asset Documents'
                       )),
    status             TEXT NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'superseded', 'expired', 'archived')),
    issue_date         DATE,
    expiry_date        DATE,
    reminder_days      INTEGER NOT NULL DEFAULT 30,
    cert_status        TEXT CHECK (cert_status IN ('ai_extracted', 'verified', 'pending')),
    current_version    INTEGER NOT NULL DEFAULT 1,
    archived_at        TIMESTAMPTZ,
    archived_by_id     UUID REFERENCES app_users(id),
    archive_reason     TEXT,
    created_by_id      UUID NOT NULL REFERENCES app_users(id),
    updated_by_id      UUID REFERENCES app_users(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS vault_document_versions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenant_registry(id),
    document_id        UUID NOT NULL REFERENCES vault_documents(id) ON DELETE CASCADE,
    version_number     INTEGER NOT NULL,
    storage_path       TEXT NOT NULL,
    file_name          TEXT NOT NULL,
    content_type       TEXT NOT NULL DEFAULT 'application/octet-stream',
    file_size_bytes    BIGINT NOT NULL DEFAULT 0,
    checksum           TEXT,
    issue_date         DATE,
    expiry_date        DATE,
    reminder_days      INTEGER NOT NULL DEFAULT 30,
    renewal_notes      TEXT,
    uploaded_by_id     UUID NOT NULL REFERENCES app_users(id),
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_vault_document_version UNIQUE (tenant_id, document_id, version_number)
);

CREATE TABLE IF NOT EXISTS vault_document_links (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenant_registry(id),
    document_id        UUID NOT NULL REFERENCES vault_documents(id) ON DELETE CASCADE,
    entity_type        TEXT NOT NULL,
    entity_id          UUID,
    entity_ref         TEXT NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Row Level Security
ALTER TABLE vault_documents ENABLE ROW LEVEL SECURITY;
ALTER TABLE vault_documents FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'vault_documents' AND policyname = 'vault_documents_isolation'
  ) THEN
    CREATE POLICY vault_documents_isolation ON vault_documents
      USING (tenant_id = boss_current_tenant_id());
  END IF;
END $$;

ALTER TABLE vault_document_versions ENABLE ROW LEVEL SECURITY;
ALTER TABLE vault_document_versions FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'vault_document_versions' AND policyname = 'vault_document_versions_isolation'
  ) THEN
    CREATE POLICY vault_document_versions_isolation ON vault_document_versions
      USING (tenant_id = boss_current_tenant_id());
  END IF;
END $$;

ALTER TABLE vault_document_links ENABLE ROW LEVEL SECURITY;
ALTER TABLE vault_document_links FORCE ROW LEVEL SECURITY;

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_policies WHERE tablename = 'vault_document_links' AND policyname = 'vault_document_links_isolation'
  ) THEN
    CREATE POLICY vault_document_links_isolation ON vault_document_links
      USING (tenant_id = boss_current_tenant_id());
  END IF;
END $$;

-- Indexes
CREATE INDEX IF NOT EXISTS idx_vault_documents_tenant ON vault_documents(tenant_id);
CREATE INDEX IF NOT EXISTS idx_vault_documents_category ON vault_documents(tenant_id, category);
CREATE INDEX IF NOT EXISTS idx_vault_documents_status ON vault_documents(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_vault_documents_expiry ON vault_documents(tenant_id, expiry_date);

CREATE INDEX IF NOT EXISTS idx_vault_document_versions_doc ON vault_document_versions(tenant_id, document_id, version_number DESC);
CREATE INDEX IF NOT EXISTS idx_vault_document_links_doc ON vault_document_links(tenant_id, document_id);
CREATE INDEX IF NOT EXISTS idx_vault_document_links_entity ON vault_document_links(tenant_id, entity_type, entity_id);

-- Role grants
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'boss_app_role') THEN
    GRANT SELECT, INSERT, UPDATE, DELETE ON vault_documents TO boss_app_role;
    GRANT SELECT, INSERT, UPDATE, DELETE ON vault_document_versions TO boss_app_role;
    GRANT SELECT, INSERT, UPDATE, DELETE ON vault_document_links TO boss_app_role;
  END IF;
END $$;
