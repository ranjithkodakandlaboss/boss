-- V038: Maintenance schedules and work orders (tenant-scoped)
CREATE TABLE maintenance_schedules (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  asset_id         UUID NOT NULL REFERENCES assets(id),
  schedule_type    TEXT NOT NULL CHECK (schedule_type IN ('preventive','periodic','condition_based')),
  frequency_days   INTEGER,
  last_performed   DATE,
  next_due         DATE,
  description      TEXT,
  is_active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE maintenance_work_orders (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  wo_number        TEXT NOT NULL,
  asset_id         UUID NOT NULL REFERENCES assets(id),
  schedule_id      UUID REFERENCES maintenance_schedules(id),
  wo_type          TEXT NOT NULL CHECK (wo_type IN ('preventive','breakdown','inspection','other')),
  status           TEXT NOT NULL DEFAULT 'open'
                     CHECK (status IN ('open','in_progress','completed','cancelled')),
  priority         TEXT NOT NULL DEFAULT 'normal'
                     CHECK (priority IN ('low','normal','high','critical')),
  reported_date    DATE NOT NULL,
  scheduled_date   DATE,
  completed_date   DATE,
  description      TEXT,
  resolution       TEXT,
  assigned_to      UUID,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, wo_number)
);

CREATE INDEX idx_maint_schedule_asset ON maintenance_schedules(tenant_id, asset_id);
CREATE INDEX idx_maint_wo_tenant      ON maintenance_work_orders(tenant_id, asset_id);
