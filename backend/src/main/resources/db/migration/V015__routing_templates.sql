-- V015: Routing templates and operations (superseded by V047 versioned routing — kept for FK continuity)
CREATE TABLE routing_templates (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  finished_item_id UUID REFERENCES items(id),
  name             TEXT NOT NULL,
  version          TEXT NOT NULL DEFAULT 'v1',
  is_default       BOOLEAN NOT NULL DEFAULT FALSE,
  status           TEXT NOT NULL DEFAULT 'active'
                     CHECK (status IN ('active','archived')),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE routing_operations (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant_registry(id),
  routing_id           UUID NOT NULL REFERENCES routing_templates(id),
  seq_no               INTEGER NOT NULL,
  name                 TEXT NOT NULL,
  work_centre          TEXT,
  setup_time_min       INTEGER NOT NULL DEFAULT 0,
  run_time_min         INTEGER NOT NULL DEFAULT 0,
  requires_inspection  BOOLEAN NOT NULL DEFAULT FALSE,
  inspection_type      TEXT CHECK (inspection_type IN ('in_process','final','customer','none')),
  is_optional          BOOLEAN NOT NULL DEFAULT FALSE,
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, routing_id, seq_no)
);

CREATE INDEX idx_routing_templates_tenant ON routing_templates(tenant_id);
CREATE INDEX idx_routing_ops_routing      ON routing_operations(tenant_id, routing_id);
