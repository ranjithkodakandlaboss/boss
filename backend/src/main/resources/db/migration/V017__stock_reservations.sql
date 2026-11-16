-- V017: Stock reservation records
CREATE TABLE stock_reservation_records (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id      UUID NOT NULL REFERENCES tenant_registry(id),
  lot_id         UUID NOT NULL REFERENCES material_lots(id),
  item_id        UUID NOT NULL REFERENCES items(id),
  reserved_qty   NUMERIC(15,3) NOT NULL CHECK (reserved_qty > 0),
  reference_type TEXT NOT NULL CHECK (reference_type IN ('cpo','production_job','other')),
  reference_id   UUID NOT NULL,
  status         TEXT NOT NULL DEFAULT 'active'
                   CHECK (status IN ('active','released','consumed')),
  reserved_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  released_at    TIMESTAMPTZ
);

CREATE INDEX idx_stock_reservations_tenant ON stock_reservation_records(tenant_id, lot_id);
CREATE INDEX idx_stock_reservations_ref    ON stock_reservation_records(reference_type, reference_id);
