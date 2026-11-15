-- V014: BOM headers and lines (tenant-scoped, max 5 levels)
CREATE TABLE bom_headers (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  finished_item_id UUID NOT NULL REFERENCES items(id),
  version          TEXT NOT NULL DEFAULT 'v1',
  bom_type         TEXT NOT NULL DEFAULT 'production'
                     CHECK (bom_type IN ('production','cost_estimate','phantom')),
  is_default       BOOLEAN NOT NULL DEFAULT FALSE,
  effective_from   DATE,
  effective_to     DATE,
  status           TEXT NOT NULL DEFAULT 'draft'
                     CHECK (status IN ('draft','approved','superseded','archived')),
  created_by       UUID,
  approved_by      UUID,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, finished_item_id, version)
);

CREATE TABLE bom_lines (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant_registry(id),
  bom_header_id     UUID NOT NULL REFERENCES bom_headers(id),
  parent_line_id    UUID REFERENCES bom_lines(id),
  component_item_id UUID NOT NULL REFERENCES items(id),
  quantity          NUMERIC(15,5) NOT NULL CHECK (quantity > 0),
  uom_id            UUID NOT NULL REFERENCES units_of_measure(id),
  level             INTEGER NOT NULL DEFAULT 1 CHECK (level BETWEEN 1 AND 5),
  scrap_percent     NUMERIC(5,2) NOT NULL DEFAULT 0,
  is_optional       BOOLEAN NOT NULL DEFAULT FALSE,
  operation_ref     TEXT,
  notes             TEXT,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_bom_headers_tenant ON bom_headers(tenant_id, finished_item_id);
CREATE INDEX idx_bom_lines_header   ON bom_lines(tenant_id, bom_header_id);
