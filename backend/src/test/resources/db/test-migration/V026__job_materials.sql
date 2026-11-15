-- V026: Job material requirements and issues (tenant-scoped)
CREATE TABLE job_material_requirements (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  job_id           UUID NOT NULL REFERENCES production_jobs(id),
  job_stage_id     UUID REFERENCES job_stages(id),
  bom_line_id      UUID REFERENCES bom_lines(id),
  item_id          UUID NOT NULL REFERENCES items(id),
  required_qty     NUMERIC(15,3) NOT NULL CHECK (required_qty > 0),
  issued_qty       NUMERIC(15,3) NOT NULL DEFAULT 0,
  returned_qty     NUMERIC(15,3) NOT NULL DEFAULT 0,
  uom_id           UUID NOT NULL REFERENCES units_of_measure(id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE job_material_issues (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  job_id           UUID NOT NULL REFERENCES production_jobs(id),
  requirement_id   UUID REFERENCES job_material_requirements(id),
  item_id          UUID NOT NULL REFERENCES items(id),
  lot_id           UUID REFERENCES material_lots(id),
  quantity         NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id           UUID NOT NULL REFERENCES units_of_measure(id),
  issue_type       TEXT NOT NULL DEFAULT 'issue'
                     CHECK (issue_type IN ('issue','return','scrap')),
  store_id         UUID NOT NULL REFERENCES stores(id),
  issued_by        UUID NOT NULL,
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_job_mat_req_job  ON job_material_requirements(tenant_id, job_id);
CREATE INDEX idx_job_mat_iss_job  ON job_material_issues(tenant_id, job_id);
