-- V016: Material lots and stock movements (append-only ledger — trigger added in V050)
CREATE TABLE material_lots (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id    UUID NOT NULL REFERENCES tenant_registry(id),
  item_id      UUID NOT NULL REFERENCES items(id),
  store_id     UUID NOT NULL REFERENCES stores(id),
  lot_number   TEXT,
  batch_number TEXT,
  quantity     NUMERIC(15,3) NOT NULL DEFAULT 0,
  reserved_qty NUMERIC(15,3) NOT NULL DEFAULT 0,
  unit_cost    NUMERIC(15,4),
  status       TEXT NOT NULL DEFAULT 'available'
                 CHECK (status IN ('available','reserved','quarantine','consumed','disposed')),
  expiry_date  DATE,
  received_at  TIMESTAMPTZ,
  created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (quantity >= 0),
  CHECK (reserved_qty >= 0),
  CHECK (reserved_qty <= quantity)
);

CREATE TABLE stock_movements (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant_registry(id),
  movement_type  TEXT NOT NULL
                   CHECK (movement_type IN (
                     'receipt','issue','return','transfer_out','transfer_in',
                     'adjustment_add','adjustment_reduce','opening_balance','scrap','consume')),
  item_id        UUID NOT NULL REFERENCES items(id),
  lot_id         UUID REFERENCES material_lots(id),
  from_store_id  UUID REFERENCES stores(id),
  to_store_id    UUID REFERENCES stores(id),
  quantity       NUMERIC(15,3) NOT NULL,
  unit_cost      NUMERIC(15,4),
  reference_type TEXT,
  reference_id   UUID,
  performed_by   UUID,
  notes          TEXT,
  created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
  -- No updated_at: append-only. V050 adds blocking trigger.
);

CREATE INDEX idx_material_lots_tenant   ON material_lots(tenant_id, item_id, store_id);
CREATE INDEX idx_material_lots_status   ON material_lots(tenant_id, status);
CREATE INDEX idx_stock_movements_tenant ON stock_movements(tenant_id, item_id);
CREATE INDEX idx_stock_movements_lot    ON stock_movements(lot_id) WHERE lot_id IS NOT NULL;
