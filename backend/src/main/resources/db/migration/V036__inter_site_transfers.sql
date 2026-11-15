-- V036: Inter-site transfers and lines (tenant-scoped)
CREATE TABLE inter_site_transfers (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  transfer_number  TEXT NOT NULL,
  from_site_id     UUID NOT NULL REFERENCES physical_sites(id),
  to_site_id       UUID NOT NULL REFERENCES physical_sites(id),
  from_store_id    UUID NOT NULL REFERENCES stores(id),
  to_store_id      UUID NOT NULL REFERENCES stores(id),
  status           TEXT NOT NULL DEFAULT 'pending'
                     CHECK (status IN ('pending','in_transit','received','cancelled')),
  transfer_date    DATE NOT NULL,
  notes            TEXT,
  created_by       UUID NOT NULL,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, transfer_number)
);

CREATE TABLE inter_site_transfer_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  transfer_id     UUID NOT NULL REFERENCES inter_site_transfers(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  lot_id          UUID REFERENCES material_lots(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  received_qty    NUMERIC(15,3),
  line_no         INTEGER NOT NULL,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, transfer_id, line_no)
);

CREATE INDEX idx_transfers_tenant     ON inter_site_transfers(tenant_id);
CREATE INDEX idx_transfer_lines_xfer  ON inter_site_transfer_lines(tenant_id, transfer_id);
