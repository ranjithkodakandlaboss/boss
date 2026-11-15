-- V029: Dispatches, dispatch lines, POD records, packing specifications (tenant-scoped)
CREATE TABLE dispatches (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  dispatch_number  TEXT NOT NULL,
  cpo_id           UUID REFERENCES cpos(id),
  customer_id      UUID NOT NULL REFERENCES customers(id),
  site_id          UUID NOT NULL REFERENCES physical_sites(id),
  status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','packed','dispatched','delivered','cancelled')),
  dispatch_date    DATE,
  vehicle_number   TEXT,
  transporter      TEXT,
  lr_number        TEXT,
  delivery_address JSONB,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, dispatch_number)
);

CREATE TABLE dispatch_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  dispatch_id     UUID NOT NULL REFERENCES dispatches(id),
  cpo_line_id     UUID REFERENCES cpo_lines(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  lot_id          UUID REFERENCES material_lots(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  packing_spec_id UUID,
  line_no         INTEGER NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, dispatch_id, line_no)
);

CREATE TABLE pod_records (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  dispatch_id      UUID NOT NULL REFERENCES dispatches(id) UNIQUE,
  received_by      TEXT,
  received_at      TIMESTAMPTZ,
  signature_ref    TEXT,
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE packing_specifications (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  item_id         UUID REFERENCES items(id),
  name            TEXT NOT NULL,
  packing_type    TEXT,
  units_per_pack  NUMERIC(15,3),
  gross_weight_kg NUMERIC(10,3),
  net_weight_kg   NUMERIC(10,3),
  dimensions_cm   JSONB,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE dispatch_lines
  ADD CONSTRAINT fk_dl_packing_spec FOREIGN KEY (packing_spec_id) REFERENCES packing_specifications(id);

CREATE INDEX idx_dispatches_tenant     ON dispatches(tenant_id, customer_id);
CREATE INDEX idx_dispatches_status     ON dispatches(tenant_id, status);
CREATE INDEX idx_dispatch_lines_disp   ON dispatch_lines(tenant_id, dispatch_id);
