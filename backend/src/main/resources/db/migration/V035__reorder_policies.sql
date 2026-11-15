-- V035: Reorder policies and replenishment requests (tenant-scoped)
CREATE TABLE reorder_policies (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  item_id          UUID NOT NULL REFERENCES items(id),
  store_id         UUID NOT NULL REFERENCES stores(id),
  reorder_point    NUMERIC(15,3) NOT NULL,
  reorder_qty      NUMERIC(15,3) NOT NULL,
  max_stock        NUMERIC(15,3),
  lead_time_days   INTEGER,
  preferred_supplier_id UUID REFERENCES suppliers(id),
  is_active        BOOLEAN NOT NULL DEFAULT TRUE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, item_id, store_id)
);

CREATE TABLE replenishment_requests (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  policy_id       UUID NOT NULL REFERENCES reorder_policies(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  store_id        UUID NOT NULL REFERENCES stores(id),
  requested_qty   NUMERIC(15,3) NOT NULL,
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','pr_created','fulfilled','cancelled')),
  pr_id           UUID REFERENCES purchase_requisitions(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_reorder_policies_tenant ON reorder_policies(tenant_id, item_id);
CREATE INDEX idx_replenishment_tenant    ON replenishment_requests(tenant_id, status);
