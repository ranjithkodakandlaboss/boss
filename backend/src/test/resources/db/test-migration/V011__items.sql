-- V011: Items master (tenant-scoped). category_id FK added in V046.
CREATE TABLE items (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  code             TEXT NOT NULL,
  name             TEXT NOT NULL,
  description      TEXT,
  item_type        TEXT NOT NULL
                     CHECK (item_type IN ('raw_material','semi_finished','finished_goods',
                                          'consumable','service','tool','asset','other')),
  base_uom_id      UUID NOT NULL REFERENCES units_of_measure(id),
  purchase_uom_id  UUID REFERENCES units_of_measure(id),
  sales_uom_id     UUID REFERENCES units_of_measure(id),
  hsn_code_id      UUID REFERENCES hsn_codes(id),
  gst_rate         NUMERIC(5,2),
  min_stock_qty    NUMERIC(15,3),
  reorder_qty      NUMERIC(15,3),
  lead_time_days   INTEGER,
  std_cost         NUMERIC(15,2),
  last_purchase_price NUMERIC(15,2),
  is_active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE INDEX idx_items_tenant      ON items(tenant_id);
CREATE INDEX idx_items_code        ON items(tenant_id, code);
CREATE INDEX idx_items_type        ON items(tenant_id, item_type);
