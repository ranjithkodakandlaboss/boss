-- V025: Production jobs and job stages (tenant-scoped)
CREATE TABLE production_jobs (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  job_number       TEXT NOT NULL,
  cpo_line_id      UUID REFERENCES cpo_lines(id),
  finished_item_id UUID NOT NULL REFERENCES items(id),
  routing_id       UUID REFERENCES routing_templates(id),
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  quantity         NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id           UUID NOT NULL REFERENCES units_of_measure(id),
  status           TEXT NOT NULL DEFAULT 'planned'
                     CHECK (status IN ('planned','released','in_progress',
                                       'completed','cancelled','on_hold')),
  planned_start    DATE,
  planned_end      DATE,
  actual_start     TIMESTAMPTZ,
  actual_end       TIMESTAMPTZ,
  priority         TEXT NOT NULL DEFAULT 'normal'
                     CHECK (priority IN ('low','normal','high','urgent')),
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, job_number)
);

CREATE TABLE job_stages (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  job_id           UUID NOT NULL REFERENCES production_jobs(id),
  routing_op_id    UUID REFERENCES routing_operations(id),
  seq_no           INTEGER NOT NULL,
  name             TEXT NOT NULL,
  status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','in_progress','completed','skipped')),
  actual_start     TIMESTAMPTZ,
  actual_end       TIMESTAMPTZ,
  operator_id      UUID,
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, job_id, seq_no)
);

CREATE INDEX idx_production_jobs_tenant ON production_jobs(tenant_id);
CREATE INDEX idx_production_jobs_status ON production_jobs(tenant_id, status);
CREATE INDEX idx_job_stages_job         ON job_stages(tenant_id, job_id);
