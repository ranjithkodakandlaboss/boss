-- V042: Approval requests (tenant-scoped)
CREATE TABLE approval_requests (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  entity_type      TEXT NOT NULL,
  entity_id        UUID NOT NULL,
  approval_type    TEXT NOT NULL,
  requested_by     UUID NOT NULL REFERENCES app_users(id),
  approver_id      UUID REFERENCES app_users(id),
  status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','approved','rejected','cancelled')),
  comments         TEXT,
  decided_at       TIMESTAMPTZ,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_approval_tenant     ON approval_requests(tenant_id, entity_type, entity_id);
CREATE INDEX idx_approval_approver   ON approval_requests(tenant_id, approver_id, status);
