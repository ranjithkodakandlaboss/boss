-- V039: Job costing headers and lines (tenant-scoped)
CREATE TABLE job_costing_headers (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  job_id           UUID NOT NULL REFERENCES production_jobs(id) UNIQUE,
  est_material_cost NUMERIC(15,2) NOT NULL DEFAULT 0,
  est_labour_cost  NUMERIC(15,2) NOT NULL DEFAULT 0,
  est_overhead_cost NUMERIC(15,2) NOT NULL DEFAULT 0,
  act_material_cost NUMERIC(15,2) NOT NULL DEFAULT 0,
  act_labour_cost  NUMERIC(15,2) NOT NULL DEFAULT 0,
  act_overhead_cost NUMERIC(15,2) NOT NULL DEFAULT 0,
  status           TEXT NOT NULL DEFAULT 'estimated'
                     CHECK (status IN ('estimated','in_progress','actual','closed')),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE job_costing_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  costing_id      UUID NOT NULL REFERENCES job_costing_headers(id),
  cost_type       TEXT NOT NULL CHECK (cost_type IN ('material','labour','overhead','other')),
  description     TEXT NOT NULL,
  quantity        NUMERIC(15,3),
  unit_cost       NUMERIC(15,4),
  amount          NUMERIC(15,2) NOT NULL,
  is_estimated    BOOLEAN NOT NULL DEFAULT TRUE,
  reference_id    UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_job_costing_tenant ON job_costing_headers(tenant_id, job_id);
CREATE INDEX idx_job_costing_lines  ON job_costing_lines(tenant_id, costing_id);
