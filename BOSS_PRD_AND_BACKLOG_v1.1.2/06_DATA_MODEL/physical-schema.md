# BOSS Physical Schema — Complete DDL
**Version:** v1.1.2 | **Date:** 15 November 2026
**Tables:** 93 tables (v1.1.1 had 88; v1.1.2 adds 5: notifications×2, approval_requests, frame_agreement_lines, replenishment_requests)
**Migrations:** V001–V060 (V045_1, V046_1 replace invalid V045b/V046a identifiers)

**v1.0 audit finding AUD-01**: 27 of ~50+ required tables were defined. This document provides the complete corrected schema.

Convention:
- All PKs are UUID (`gen_random_uuid()`)
- All tenant-scoped tables have `tenant_id UUID NOT NULL REFERENCES tenant_registry(id)`
- Timestamps: `created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`, `updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()`
- RLS policy: `USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)` — `true` parameter makes unset context return NULL (not error) → zero rows (secure default)
- Monetary amounts: `NUMERIC(15,2)` — no floating point
- Append-only ledger tables (stock_movements, audit_events): DB trigger blocks UPDATE/DELETE

---

## Migration Sequence

```
V001 — Extensions
V002 — Customer Groups and Tenant Registry
V003 — Tenant Entitlements
V004 — Audit Events
V005 — Outbox Events + Idempotency Keys
V006 — App Users + User Roles + Invite Tokens
V007 — Legal Entities + GST Registrations
V008 — Physical Sites + Stores
V009 — Units of Measure + UOM Conversions
V010 — HSN Codes (reference, no RLS)
V011 — Items
V012 — Customers + Customer Contacts
V013 — Suppliers + Supplier Contacts
V014 — BOM Headers + BOM Lines
V015 — Routing Templates + Routing Operations
V016 — Material Lots + Stock Movements (append-only)
V017 — Stock Reservation Records
V018 — RFQs + RFQ Lines
V019 — Quotations + Quotation Lines + Quotation Revisions
V020 — Customer Purchase Orders + CPO Lines
V021 — Purchase Requisitions + PR Lines
V022 — Supplier Quotations + Supplier Quotation Lines
V023 — Supplier Purchase Orders + SPO Lines
V024 — GRNs + GRN Lines
V025 — Production Jobs + Job Stages
V026 — Job Material Requirements + Job Material Issues
V027 — Quality Inspections + Inspection Parameters
V028 — NCRs + Concessions
V029 — Dispatches + Dispatch Lines + POD Records
V030 — Invoice Sequence Counters
V031 — Invoices + Invoice Lines
V032 — Receipts + Receipt Allocations
V033 — Supplier Bills + Supplier Payments
V034 — Frame Agreements + Frame Agreement Lines
V035 — Reorder Policies + Replenishment Requests
V036 — Inter-Site Transfers + Transfer Lines
V037 — Assets + Asset Locations
V038 — Maintenance Schedules + Maintenance Work Orders
V039 — Job Costing Headers + Job Costing Lines
V040 — Company Profiles + Company Profile Sections
V041 — Notifications + Notification Preferences [NOTE: packing_specifications was absorbed into V029]
V042 — Approval Requests
V043 — Frame Agreement Lines + Replenishment Requests (completes V034/V035)
V044 — Platform Admin Users + Platform Audit Events
V045 — Tenant Configuration: item_categories, asset_type_configs, inspection_parameter_templates (FD-57)
V045_1 — Config Categories Global Registry (was V045b — renamed to valid Flyway format)
V046 — Items category FK (category_id replaces free-text category)
V046_1 — Tenant Config Options + History (was V046a — renamed to valid Flyway format)
V047 — Versioned Routing Templates + Stages (replaces V015 routing_templates + routing_operations)
V050 — Append-Only Triggers (stock_movements, audit_events)
V051 — RLS Policy Definitions (all tenant-scoped tables)
V052 — Index Definitions
V053 — Constraint Additions
V054 — Seed: standard_uoms function
V055 — Seed: hsn_codes (common codes)
V060 — Views: inventory_position_view, invoice_summary_view
```

---

## V001 — Extensions

```sql
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "pgcrypto";
```

---

## V002 — Customer Groups and Tenant Registry

```sql
CREATE TABLE customer_groups (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name            TEXT NOT NULL,
  slug            TEXT NOT NULL UNIQUE,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','suspended','archived')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE tenant_registry (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  customer_group_id UUID NOT NULL REFERENCES customer_groups(id),
  name            TEXT NOT NULL,
  slug            TEXT NOT NULL UNIQUE,
  status          TEXT NOT NULL DEFAULT 'provisioning'
                    CHECK (status IN ('provisioning','active','suspended','archived')),
  plan_tier       TEXT NOT NULL DEFAULT 'pilot',
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V003 — Tenant Entitlements

```sql
CREATE TABLE tenant_entitlements (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  feature     TEXT NOT NULL,
  max_value   INTEGER,
  enabled     BOOLEAN NOT NULL DEFAULT TRUE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V004 — Audit Events (append-only)

```sql
CREATE TABLE audit_events (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  actor_id    UUID,
  entity_type TEXT NOT NULL,
  entity_id   UUID NOT NULL,
  action      TEXT NOT NULL,
  old_data    JSONB,
  new_data    JSONB,
  ip_address  INET,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- RLS: tenant-scoped. Append-only trigger added in V050.
```

---

## V005 — Outbox Events + Idempotency Keys

```sql
CREATE TABLE outbox_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID REFERENCES tenant_registry(id),  -- nullable for platform events
  aggregate_type  TEXT NOT NULL,
  aggregate_id    UUID NOT NULL,
  event_type      TEXT NOT NULL,
  payload         JSONB NOT NULL,
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','published','failed')),
  attempts        INTEGER NOT NULL DEFAULT 0,
  published_at    TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- NO RLS — relay job runs as platform, not tenant

CREATE TABLE idempotency_keys (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  key             TEXT NOT NULL UNIQUE,
  request_hash    TEXT NOT NULL,
  response_status INTEGER NOT NULL,
  response_body   JSONB,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at      TIMESTAMPTZ NOT NULL
);
```

---

## V006 — App Users + User Roles + Invite Tokens

```sql
CREATE TABLE app_users (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  firebase_uid    TEXT NOT NULL,
  email           TEXT NOT NULL,
  display_name    TEXT,
  phone_wa        TEXT,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','suspended','deactivated')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, firebase_uid),
  UNIQUE (tenant_id, email)
);

CREATE TABLE user_roles (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  user_id     UUID NOT NULL REFERENCES app_users(id),
  role        TEXT NOT NULL
                CHECK (role IN (
                  'OWNER','OPS_MANAGER','SALES_MANAGER','PURCHASE_MANAGER',
                  'PRODUCTION_SUPERVISOR','STORE_KEEPER','ACCOUNTS_STAFF',
                  'QUALITY_INSPECTOR','FIELD_WORKER','PLATFORM_ADMIN')),
  site_id     UUID,
  granted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, user_id, role, site_id)
);

CREATE TABLE invite_tokens (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  email       TEXT NOT NULL,
  role        TEXT NOT NULL,
  token_hash  TEXT NOT NULL UNIQUE,
  invited_by  UUID NOT NULL REFERENCES app_users(id),
  status      TEXT NOT NULL DEFAULT 'pending'
                CHECK (status IN ('pending','accepted','expired','cancelled')),
  expires_at  TIMESTAMPTZ NOT NULL,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V007 — Legal Entities + GST Registrations

```sql
CREATE TABLE legal_entities (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  name            TEXT NOT NULL,
  legal_name      TEXT NOT NULL,
  pan             TEXT CHECK (pan ~ '^[A-Z]{5}[0-9]{4}[A-Z]$'),
  entity_type     TEXT CHECK (entity_type IN (
                    'PRIVATE_LIMITED','LLP','SOLE_PROPRIETORSHIP','PARTNERSHIP','PUBLIC_LIMITED','HUF','OTHER')),
  registered_address JSONB,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','inactive','dissolved')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE gst_registrations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  legal_entity_id UUID NOT NULL REFERENCES legal_entities(id),
  gstin           TEXT NOT NULL,
  trade_name      TEXT,
  registration_type TEXT NOT NULL DEFAULT 'regular'
                    CHECK (registration_type IN ('regular','composition','sez','casual','uin')),
  state_code      TEXT NOT NULL GENERATED ALWAYS AS (LEFT(gstin, 2)) STORED,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','cancelled','suspended')),
  effective_from  DATE NOT NULL,
  effective_to    DATE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CONSTRAINT uq_gstin UNIQUE (tenant_id, gstin),
  CONSTRAINT chk_gstin_format CHECK (
    gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'
  )
);
```

---

## V008 — Physical Sites + Stores

```sql
CREATE TABLE physical_sites (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  legal_entity_id UUID NOT NULL REFERENCES legal_entities(id),
  gst_reg_id      UUID REFERENCES gst_registrations(id),
  name            TEXT NOT NULL,
  code            TEXT NOT NULL,
  site_type       TEXT NOT NULL DEFAULT 'factory'
                    CHECK (site_type IN ('factory','warehouse','office','depot','other')),
  address         JSONB,
  status          TEXT NOT NULL DEFAULT 'active',
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE stores (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  name            TEXT NOT NULL,
  code            TEXT NOT NULL,
  store_type      TEXT NOT NULL DEFAULT 'raw_material'
                    CHECK (store_type IN ('raw_material','finished_goods','work_in_progress',
                                         'consumable','tool','rejected','quarantine')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, site_id, code)
);
```

---

## V009 — Units of Measure + UOM Conversions

```sql
CREATE TABLE units_of_measure (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  name        TEXT NOT NULL,
  symbol      TEXT NOT NULL,
  uom_type    TEXT NOT NULL DEFAULT 'quantity'
                CHECK (uom_type IN ('quantity','weight','volume','length','area','time','energy','other')),
  is_base     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, symbol)
);

CREATE TABLE uom_conversions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  from_uom_id     UUID NOT NULL REFERENCES units_of_measure(id),
  to_uom_id       UUID NOT NULL REFERENCES units_of_measure(id),
  factor          NUMERIC(20,8) NOT NULL CHECK (factor > 0),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, from_uom_id, to_uom_id)
);
```

---

## V010 — HSN Codes (reference data, no RLS)

```sql
CREATE TABLE hsn_codes (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code        TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL,
  gst_rate    NUMERIC(5,2),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- NO RLS — platform-wide reference data
```

---

## V011 — Items

```sql
CREATE TABLE items (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  code            TEXT NOT NULL,
  name            TEXT NOT NULL,
  description     TEXT,
  item_type       TEXT NOT NULL
                    CHECK (item_type IN ('raw_material','semi_finished','finished_goods',
                                         'consumable','service','tool','asset','other')),
  base_uom_id     UUID NOT NULL REFERENCES units_of_measure(id),
  purchase_uom_id UUID REFERENCES units_of_measure(id),
  sales_uom_id    UUID REFERENCES units_of_measure(id),
  hsn_code_id     UUID REFERENCES hsn_codes(id),
  gst_rate        NUMERIC(5,2),
  min_stock_qty   NUMERIC(15,3),
  reorder_qty     NUMERIC(15,3),
  lead_time_days  INTEGER,
  std_cost        NUMERIC(15,2),
  last_purchase_price NUMERIC(15,2),
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);
```

---

## V012 — Customers + Customer Contacts

```sql
CREATE TABLE customers (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  code            TEXT NOT NULL,
  name            TEXT NOT NULL,
  legal_name      TEXT,
  gstin           TEXT CHECK (gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  pan             TEXT,
  customer_type   TEXT NOT NULL DEFAULT 'business'
                    CHECK (customer_type IN ('business','individual')),
  billing_address JSONB,
  shipping_address JSONB,
  credit_limit    NUMERIC(15,2),
  credit_days     INTEGER DEFAULT 30,
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE customer_contacts (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  customer_id UUID NOT NULL REFERENCES customers(id),
  name        TEXT NOT NULL,
  role        TEXT,
  email       TEXT,
  phone       TEXT,
  is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V013 — Suppliers + Supplier Contacts

```sql
CREATE TABLE suppliers (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  code            TEXT NOT NULL,
  name            TEXT NOT NULL,
  legal_name      TEXT,
  gstin           TEXT CHECK (gstin ~ '^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$'),
  pan             TEXT,
  supplier_type   TEXT NOT NULL DEFAULT 'manufacturer'
                    CHECK (supplier_type IN ('manufacturer','trader','importer','service_provider','other')),
  billing_address JSONB,
  payment_terms   INTEGER DEFAULT 30,
  credit_limit    NUMERIC(15,2),
  performance_score NUMERIC(3,2),  -- computed; updated on each GRN+Inspection
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

CREATE TABLE supplier_contacts (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id   UUID NOT NULL REFERENCES tenant_registry(id),
  supplier_id UUID NOT NULL REFERENCES suppliers(id),
  name        TEXT NOT NULL,
  role        TEXT,
  email       TEXT,
  phone       TEXT,
  is_primary  BOOLEAN NOT NULL DEFAULT FALSE,
  created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE supplier_performance_records (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  supplier_id     UUID NOT NULL REFERENCES suppliers(id),
  spo_id          UUID,  -- FK added in V023
  grn_id          UUID,  -- FK added in V024
  promised_date   DATE,
  actual_date     DATE,
  delay_days      INTEGER GENERATED ALWAYS AS (
                    CASE WHEN actual_date IS NOT NULL AND promised_date IS NOT NULL
                    THEN GREATEST(0, actual_date - promised_date) ELSE NULL END
                  ) STORED,
  quantity_ordered NUMERIC(15,3),
  quantity_received NUMERIC(15,3),
  quantity_rejected NUMERIC(15,3),
  quality_pass    BOOLEAN,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V014 — BOM Headers + BOM Lines

```sql
CREATE TABLE bom_headers (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  finished_item_id UUID NOT NULL REFERENCES items(id),
  version         TEXT NOT NULL DEFAULT 'v1',
  bom_type        TEXT NOT NULL DEFAULT 'production'
                    CHECK (bom_type IN ('production','cost_estimate','phantom')),
  is_default      BOOLEAN NOT NULL DEFAULT FALSE,
  effective_from  DATE,
  effective_to    DATE,
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','approved','superseded','archived')),
  created_by      UUID,
  approved_by     UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, finished_item_id, version)
);

CREATE TABLE bom_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  bom_header_id   UUID NOT NULL REFERENCES bom_headers(id),
  parent_line_id  UUID REFERENCES bom_lines(id),
  component_item_id UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,5) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  level           INTEGER NOT NULL DEFAULT 1 CHECK (level <= 5),
  scrap_percent   NUMERIC(5,2) NOT NULL DEFAULT 0,
  is_optional     BOOLEAN NOT NULL DEFAULT FALSE,
  operation_ref   TEXT,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V015 — Routing Templates + Routing Operations

```sql
CREATE TABLE routing_templates (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  finished_item_id UUID REFERENCES items(id),
  name            TEXT NOT NULL,
  version         TEXT NOT NULL DEFAULT 'v1',
  is_default      BOOLEAN NOT NULL DEFAULT FALSE,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','archived')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE routing_operations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  routing_id      UUID NOT NULL REFERENCES routing_templates(id),
  seq_no          INTEGER NOT NULL,
  name            TEXT NOT NULL,
  work_centre     TEXT,
  setup_time_min  INTEGER NOT NULL DEFAULT 0,
  run_time_min    INTEGER NOT NULL DEFAULT 0,
  requires_inspection BOOLEAN NOT NULL DEFAULT FALSE,
  inspection_type TEXT CHECK (inspection_type IN ('in_process','final','customer','none')),
  is_optional     BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, routing_id, seq_no)
);
```

---

## V016 — Material Lots + Stock Movements (append-only)

```sql
CREATE TABLE material_lots (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  store_id        UUID NOT NULL REFERENCES stores(id),
  lot_number      TEXT,
  batch_number    TEXT,
  quantity        NUMERIC(15,3) NOT NULL DEFAULT 0,
  reserved_qty    NUMERIC(15,3) NOT NULL DEFAULT 0,
  unit_cost       NUMERIC(15,4),
  status          TEXT NOT NULL DEFAULT 'available'
                    CHECK (status IN ('available','reserved','quarantine','consumed','disposed')),
  expiry_date     DATE,
  received_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (quantity >= 0),
  CHECK (reserved_qty >= 0),
  CHECK (reserved_qty <= quantity)
);

CREATE TABLE stock_movements (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  movement_type   TEXT NOT NULL
                    CHECK (movement_type IN (
                      'receipt','issue','return','transfer_out','transfer_in',
                      'adjustment_add','adjustment_reduce','opening_balance','scrap','consume')),
  item_id         UUID NOT NULL REFERENCES items(id),
  lot_id          UUID REFERENCES material_lots(id),
  from_store_id   UUID REFERENCES stores(id),
  to_store_id     UUID REFERENCES stores(id),
  quantity        NUMERIC(15,3) NOT NULL,
  unit_cost       NUMERIC(15,4),
  reference_type  TEXT,
  reference_id    UUID,
  performed_by    UUID,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
  -- No updated_at: append-only. V050 adds trigger blocking UPDATE/DELETE.
);
```

---

## V017 — Stock Reservation Records

```sql
CREATE TABLE stock_reservation_records (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  lot_id          UUID NOT NULL REFERENCES material_lots(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  reserved_qty    NUMERIC(15,3) NOT NULL CHECK (reserved_qty > 0),
  reference_type  TEXT NOT NULL CHECK (reference_type IN ('cpo','production_job','other')),
  reference_id    UUID NOT NULL,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','released','consumed')),
  reserved_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  released_at     TIMESTAMPTZ
);
```

---

## V018 — RFQs + RFQ Lines

```sql
CREATE TABLE rfqs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  rfq_number      TEXT NOT NULL,
  customer_id     UUID NOT NULL REFERENCES customers(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','sent','closed','cancelled')),
  received_date   DATE NOT NULL,
  required_by_date DATE,
  customer_ref    TEXT,
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, rfq_number)
);

CREATE TABLE rfq_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  rfq_id          UUID NOT NULL REFERENCES rfqs(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID REFERENCES items(id),
  description     TEXT NOT NULL,
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  drawing_ref     TEXT,
  spec_notes      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, rfq_id, line_no)
);
```

---

## V019 — Quotations + Quotation Lines + Quotation Revisions

```sql
CREATE TABLE quotations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_number TEXT NOT NULL,
  rfq_id          UUID REFERENCES rfqs(id),
  customer_id     UUID NOT NULL REFERENCES customers(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  revision        INTEGER NOT NULL DEFAULT 1,
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','sent','accepted','rejected','expired','superseded','negotiating')),
  valid_until     DATE,
  currency        TEXT NOT NULL DEFAULT 'INR',
  total_amount    NUMERIC(15,2),
  discount_pct    NUMERIC(5,2),
  payment_terms   TEXT,
  delivery_terms  TEXT,
  estimated_material_cost NUMERIC(15,2),
  estimated_labour_cost   NUMERIC(15,2),
  estimated_overhead      NUMERIC(15,2),
  estimated_margin_pct    NUMERIC(5,2),
  notes           TEXT,
  created_by      UUID NOT NULL,
  superseded_by   UUID REFERENCES quotations(id),
  accepted_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, quotation_number)
);

CREATE TABLE quotation_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_id    UUID NOT NULL REFERENCES quotations(id),
  rfq_line_id     UUID REFERENCES rfq_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID REFERENCES items(id),
  description     TEXT NOT NULL,
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,4) NOT NULL,
  gst_rate        NUMERIC(5,2),
  line_amount     NUMERIC(15,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,
  delivery_weeks  INTEGER,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, quotation_id, line_no)
);

CREATE TABLE quotation_negotiation_history (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  quotation_id    UUID NOT NULL REFERENCES quotations(id),
  event_type      TEXT NOT NULL
                    CHECK (event_type IN ('sent','counteroffer_received','counteroffer_accepted',
                                          'counteroffer_rejected','revision_created','accepted','rejected')),
  our_total       NUMERIC(15,2),
  customer_offer  NUMERIC(15,2),
  notes           TEXT,
  performed_by    UUID,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V020 — Customer Purchase Orders + CPO Lines

```sql
CREATE TABLE customer_purchase_orders (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  cpo_number      TEXT NOT NULL,
  customer_id     UUID NOT NULL REFERENCES customers(id),
  quotation_id    UUID REFERENCES quotations(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  billing_gst_id  UUID REFERENCES gst_registrations(id),
  status          TEXT NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open','feasibility_pending','feasibility_approved',
                                       'in_production','dispatched','fulfilled','cancelled','on_hold')),
  customer_po_ref TEXT,
  order_date      DATE NOT NULL,
  required_date   DATE,
  currency        TEXT NOT NULL DEFAULT 'INR',
  total_value     NUMERIC(15,2),
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, cpo_number)
);

CREATE TABLE cpo_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  cpo_id          UUID NOT NULL REFERENCES customer_purchase_orders(id),
  quotation_line_id UUID REFERENCES quotation_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID REFERENCES items(id),
  description     TEXT NOT NULL,
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,4) NOT NULL,
  gst_rate        NUMERIC(5,2),
  line_amount     NUMERIC(15,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,
  dispatched_qty  NUMERIC(15,3) NOT NULL DEFAULT 0,
  invoiced_qty    NUMERIC(15,3) NOT NULL DEFAULT 0,
  required_date   DATE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, cpo_id, line_no)
);
```

---

## V021 — Purchase Requisitions + PR Lines

```sql
CREATE TABLE purchase_requisitions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  pr_number       TEXT NOT NULL,
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  origin_type     TEXT NOT NULL DEFAULT 'manual'
                    CHECK (origin_type IN ('manual','production_job','replenishment','stock_driven')),
  reference_id    UUID,
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','submitted','approved','po_created','cancelled')),
  required_by     DATE,
  notes           TEXT,
  requested_by    UUID NOT NULL,
  approved_by     UUID,
  approved_at     TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, pr_number)
);

CREATE TABLE pr_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  pr_id           UUID NOT NULL REFERENCES purchase_requisitions(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  est_unit_cost   NUMERIC(15,4),
  store_id        UUID REFERENCES stores(id),
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, pr_id, line_no)
);
```

---

## V022 — Supplier Quotations + Lines

```sql
CREATE TABLE supplier_quotations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  sq_number       TEXT NOT NULL,
  supplier_id     UUID NOT NULL REFERENCES suppliers(id),
  pr_id           UUID REFERENCES purchase_requisitions(id),
  status          TEXT NOT NULL DEFAULT 'received'
                    CHECK (status IN ('received','evaluated','accepted','rejected','expired')),
  quote_date      DATE NOT NULL,
  valid_until     DATE,
  currency        TEXT NOT NULL DEFAULT 'INR',
  delivery_days   INTEGER,
  payment_terms   INTEGER,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, sq_number)
);

CREATE TABLE supplier_quotation_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  sq_id           UUID NOT NULL REFERENCES supplier_quotations(id),
  pr_line_id      UUID REFERENCES pr_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL,
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,4) NOT NULL,
  gst_rate        NUMERIC(5,2),
  delivery_days   INTEGER,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, sq_id, line_no)
);
```

---

## V023 — Supplier Purchase Orders + SPO Lines

```sql
CREATE TABLE supplier_purchase_orders (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  spo_number      TEXT NOT NULL,
  supplier_id     UUID NOT NULL REFERENCES suppliers(id),
  pr_id           UUID REFERENCES purchase_requisitions(id),
  sq_id           UUID REFERENCES supplier_quotations(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  billing_gst_id  UUID REFERENCES gst_registrations(id),
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','issued','partially_received','fully_received','cancelled')),
  order_date      DATE NOT NULL,
  promised_date   DATE,
  currency        TEXT NOT NULL DEFAULT 'INR',
  total_value     NUMERIC(15,2),
  payment_terms   INTEGER,
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, spo_number)
);

CREATE TABLE spo_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  spo_id          UUID NOT NULL REFERENCES supplier_purchase_orders(id),
  sq_line_id      UUID REFERENCES supplier_quotation_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,4) NOT NULL,
  gst_rate        NUMERIC(5,2),
  line_amount     NUMERIC(15,2) GENERATED ALWAYS AS (quantity * unit_price) STORED,
  received_qty    NUMERIC(15,3) NOT NULL DEFAULT 0,
  to_store_id     UUID REFERENCES stores(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, spo_id, line_no)
);

-- Backfill FK on supplier_performance_records
ALTER TABLE supplier_performance_records
  ADD CONSTRAINT fk_spo FOREIGN KEY (spo_id) REFERENCES supplier_purchase_orders(id);
```

---

## V024 — GRNs + GRN Lines

```sql
CREATE TABLE grns (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  grn_number      TEXT NOT NULL,
  spo_id          UUID NOT NULL REFERENCES supplier_purchase_orders(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','confirmed','inspection_pending','inspection_passed',
                                       'inspection_failed','closed','cancelled')),
  received_date   DATE NOT NULL,
  vehicle_no      TEXT,
  lorry_receipt   TEXT,
  notes           TEXT,
  received_by     UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, grn_number)
);

CREATE TABLE grn_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  grn_id          UUID NOT NULL REFERENCES grns(id),
  spo_line_id     UUID NOT NULL REFERENCES spo_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  received_qty    NUMERIC(15,3) NOT NULL CHECK (received_qty >= 0),
  accepted_qty    NUMERIC(15,3),
  rejected_qty    NUMERIC(15,3),
  lot_id          UUID REFERENCES material_lots(id),
  batch_number    TEXT,
  unit_cost       NUMERIC(15,4),
  over_receipt_approved_by UUID,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, grn_id, line_no)
);

-- Backfill FK on supplier_performance_records
ALTER TABLE supplier_performance_records
  ADD CONSTRAINT fk_grn FOREIGN KEY (grn_id) REFERENCES grns(id);
```

---

## V025 — Production Jobs + Job Stages

```sql
CREATE TABLE production_jobs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  job_number      TEXT NOT NULL,
  cpo_id          UUID REFERENCES customer_purchase_orders(id),
  cpo_line_id     UUID REFERENCES cpo_lines(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  routing_id      UUID REFERENCES routing_templates(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  status          TEXT NOT NULL DEFAULT 'planned'
                    CHECK (status IN ('planned','material_partial','material_issued','in_progress',
                                       'qc_pending','completed','cancelled','on_hold')),
  planned_qty     NUMERIC(15,3) NOT NULL CHECK (planned_qty > 0),
  completed_qty   NUMERIC(15,3) NOT NULL DEFAULT 0,
  planned_start   DATE,
  planned_end     DATE,
  actual_start    TIMESTAMPTZ,
  actual_end      TIMESTAMPTZ,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, job_number)
);

CREATE TABLE job_stages (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  job_id          UUID NOT NULL REFERENCES production_jobs(id),
  routing_op_id   UUID REFERENCES routing_operations(id),
  seq_no          INTEGER NOT NULL,
  name            TEXT NOT NULL,
  work_centre     TEXT,
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','in_progress','completed','skipped')),
  requires_inspection BOOLEAN NOT NULL DEFAULT FALSE,
  inspection_type TEXT CHECK (inspection_type IN ('in_process','final','customer')),
  started_at      TIMESTAMPTZ,
  completed_at    TIMESTAMPTZ,
  completed_by    UUID,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, job_id, seq_no)
);
```

---

## V026 — Job Material Requirements + Issues

```sql
CREATE TABLE job_material_requirements (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  job_id          UUID NOT NULL REFERENCES production_jobs(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  required_qty    NUMERIC(15,3) NOT NULL CHECK (required_qty > 0),
  issued_qty      NUMERIC(15,3) NOT NULL DEFAULT 0,
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','partial','fully_issued','cancelled')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE job_material_issues (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  job_id          UUID NOT NULL REFERENCES production_jobs(id),
  requirement_id  UUID REFERENCES job_material_requirements(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  lot_id          UUID NOT NULL REFERENCES material_lots(id),
  issued_qty      NUMERIC(15,3) NOT NULL CHECK (issued_qty > 0),
  unit_cost       NUMERIC(15,4),
  issue_type      TEXT NOT NULL DEFAULT 'issue'
                    CHECK (issue_type IN ('issue','return','scrap')),
  issued_by       UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V027 — Quality Inspections + Inspection Parameters

```sql
CREATE TABLE quality_inspections (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  inspection_number TEXT NOT NULL,
  job_id          UUID REFERENCES production_jobs(id),
  job_stage_id    UUID REFERENCES job_stages(id),
  grn_id          UUID REFERENCES grns(id),
  inspection_type TEXT NOT NULL
                    CHECK (inspection_type IN ('incoming','in_process','final','customer','pre_dispatch')),
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','in_progress','pass','fail','conditional_pass')),
  quantity_inspected NUMERIC(15,3),
  quantity_pass   NUMERIC(15,3),
  quantity_fail   NUMERIC(15,3),
  inspector_id    UUID,
  customer_rep    TEXT,
  inspected_at    TIMESTAMPTZ,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, inspection_number)
);

CREATE TABLE inspection_parameters (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  inspection_id   UUID NOT NULL REFERENCES quality_inspections(id),
  parameter_name  TEXT NOT NULL,
  spec_value      TEXT,
  actual_value    TEXT,
  result          TEXT CHECK (result IN ('pass','fail','na')),
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V028 — NCRs + Concessions + Clearance Certificates

```sql
CREATE TABLE ncrs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  ncr_number      TEXT NOT NULL,
  inspection_id   UUID NOT NULL REFERENCES quality_inspections(id),
  status          TEXT NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open','under_review','rework','scrap','concession','closed')),
  defect_type     TEXT,
  defect_description TEXT,
  quantity_affected NUMERIC(15,3),
  raised_by       UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, ncr_number)
);

CREATE TABLE concessions (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  ncr_id          UUID NOT NULL REFERENCES ncrs(id),
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','approved','rejected')),
  justification   TEXT NOT NULL,
  conditions      TEXT,
  granted_by      UUID NOT NULL,
  granted_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE customer_clearance_certificates (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  cert_number     TEXT NOT NULL,
  job_id          UUID NOT NULL REFERENCES production_jobs(id),
  cpo_id          UUID NOT NULL REFERENCES customer_purchase_orders(id),
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','issued','approved','rejected')),
  issued_to       TEXT,
  customer_rep    TEXT,
  issued_at       TIMESTAMPTZ,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, cert_number)
);
```

---

## V029 — Dispatches + Dispatch Lines + POD + Packing

```sql
CREATE TABLE packing_specifications (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  customer_id     UUID REFERENCES customers(id),
  cpo_id          UUID REFERENCES customer_purchase_orders(id),
  packing_type    TEXT NOT NULL
                    CHECK (packing_type IN ('standard','pinewood','pallet','vacuum','fumigation','export_grade','custom')),
  material        TEXT,
  dimensions_cm   JSONB,
  weight_kg       NUMERIC(10,2),
  export_required BOOLEAN NOT NULL DEFAULT FALSE,
  special_instructions TEXT,
  responsibility  TEXT NOT NULL DEFAULT 'self'
                    CHECK (responsibility IN ('self','customer','third_party')),
  est_packing_cost NUMERIC(15,2),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE dispatches (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  dispatch_number TEXT NOT NULL,
  cpo_id          UUID NOT NULL REFERENCES customer_purchase_orders(id),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  packing_spec_id UUID REFERENCES packing_specifications(id),
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','ready','dispatched','delivered','delivery_failed')),
  dispatch_date   DATE,
  vehicle_no      TEXT,
  transporter     TEXT,
  lr_number       TEXT,
  actual_packing_cost NUMERIC(15,2),
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, dispatch_number)
);

CREATE TABLE dispatch_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  dispatch_id     UUID NOT NULL REFERENCES dispatches(id),
  cpo_line_id     UUID NOT NULL REFERENCES cpo_lines(id),
  job_id          UUID REFERENCES production_jobs(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  quantity        NUMERIC(15,3) NOT NULL CHECK (quantity > 0),
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, dispatch_id, line_no)
);

CREATE TABLE pod_records (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  dispatch_id     UUID NOT NULL UNIQUE REFERENCES dispatches(id),
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','delivered','partial','rejected')),
  delivered_date  DATE,
  received_by     TEXT,
  signature_gcs_path TEXT,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V030 — Invoice Sequence Counters

```sql
CREATE TABLE invoice_sequence_counters (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  gst_reg_id      UUID NOT NULL REFERENCES gst_registrations(id),
  series          TEXT NOT NULL,
  financial_year  TEXT NOT NULL,
  last_number     INTEGER NOT NULL DEFAULT 0,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, gst_reg_id, series, financial_year)
  -- Pessimistic SELECT FOR UPDATE locks this row during invoice creation
  -- to guarantee gapless sequential numbering per GSTN recommendation
);
```

---

## V031 — Invoices + Invoice Lines

```sql
CREATE TABLE invoices (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  invoice_number  TEXT NOT NULL,
  invoice_type    TEXT NOT NULL DEFAULT 'tax_invoice'
                    CHECK (invoice_type IN ('tax_invoice','proforma','credit_note','debit_note')),
  dispatch_id     UUID REFERENCES dispatches(id),
  cpo_id          UUID NOT NULL REFERENCES customer_purchase_orders(id),
  customer_id     UUID NOT NULL REFERENCES customers(id),
  billing_gst_id  UUID REFERENCES gst_registrations(id),
  customer_gstin  TEXT,
  place_of_supply TEXT NOT NULL,
  supply_type     TEXT NOT NULL DEFAULT 'intra_state'
                    CHECK (supply_type IN ('intra_state','inter_state','export')),
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','issued','irn_pending','irn_registered','cancelled','written_off')),
  invoice_date    DATE NOT NULL,
  due_date        DATE,
  currency        TEXT NOT NULL DEFAULT 'INR',
  taxable_amount  NUMERIC(15,2) NOT NULL DEFAULT 0,
  cgst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  sgst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  igst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  total_tax       NUMERIC(15,2) NOT NULL DEFAULT 0,
  total_amount    NUMERIC(15,2) NOT NULL DEFAULT 0,
  amount_paid     NUMERIC(15,2) NOT NULL DEFAULT 0,
  amount_due      NUMERIC(15,2) GENERATED ALWAYS AS (total_amount - amount_paid) STORED,
  irn             TEXT UNIQUE,
  ack_number      TEXT,
  ack_date        TIMESTAMPTZ,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, invoice_number)
);

CREATE TABLE invoice_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  invoice_id      UUID NOT NULL REFERENCES invoices(id),
  dispatch_line_id UUID REFERENCES dispatch_lines(id),
  line_no         INTEGER NOT NULL,
  item_id         UUID REFERENCES items(id),
  description     TEXT NOT NULL,
  hsn_code        TEXT,
  quantity        NUMERIC(15,3) NOT NULL,
  uom_id          UUID NOT NULL REFERENCES units_of_measure(id),
  unit_price      NUMERIC(15,4) NOT NULL,
  taxable_amount  NUMERIC(15,2) NOT NULL,
  gst_rate        NUMERIC(5,2),
  cgst_rate       NUMERIC(5,2),
  sgst_rate       NUMERIC(5,2),
  igst_rate       NUMERIC(5,2),
  cgst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  sgst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  igst_amount     NUMERIC(15,2) NOT NULL DEFAULT 0,
  line_total      NUMERIC(15,2) NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, invoice_id, line_no)
);
```

---

## V032 — Receipts + Receipt Allocations

```sql
CREATE TABLE receipts (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  receipt_number  TEXT NOT NULL,
  customer_id     UUID NOT NULL REFERENCES customers(id),
  receipt_date    DATE NOT NULL,
  mode            TEXT NOT NULL
                    CHECK (mode IN ('bank_transfer','cheque','cash','upi','other')),
  amount          NUMERIC(15,2) NOT NULL CHECK (amount > 0),
  reference       TEXT,
  unallocated_amount NUMERIC(15,2) NOT NULL,
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, receipt_number)
);

CREATE TABLE receipt_allocations (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  receipt_id      UUID NOT NULL REFERENCES receipts(id),
  invoice_id      UUID NOT NULL REFERENCES invoices(id),
  amount          NUMERIC(15,2) NOT NULL CHECK (amount > 0),
  allocated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by      UUID NOT NULL
);
```

---

## V033 — Supplier Bills + Supplier Payments

```sql
CREATE TABLE supplier_bills (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  bill_number     TEXT NOT NULL,
  supplier_id     UUID NOT NULL REFERENCES suppliers(id),
  spo_id          UUID REFERENCES supplier_purchase_orders(id),
  grn_id          UUID REFERENCES grns(id),
  supplier_invoice_no TEXT,
  bill_date       DATE NOT NULL,
  due_date        DATE,
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','pending_approval','approved','paid','partially_paid','cancelled')),
  three_way_match_status TEXT NOT NULL DEFAULT 'pending'
                    CHECK (three_way_match_status IN ('pending','matched','discrepancy','approved_with_variance')),
  taxable_amount  NUMERIC(15,2) NOT NULL DEFAULT 0,
  total_tax       NUMERIC(15,2) NOT NULL DEFAULT 0,
  total_amount    NUMERIC(15,2) NOT NULL DEFAULT 0,
  amount_paid     NUMERIC(15,2) NOT NULL DEFAULT 0,
  amount_due      NUMERIC(15,2) GENERATED ALWAYS AS (total_amount - amount_paid) STORED,
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, bill_number)
);

CREATE TABLE supplier_payments (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  payment_number  TEXT NOT NULL,
  supplier_id     UUID NOT NULL REFERENCES suppliers(id),
  bill_id         UUID REFERENCES supplier_bills(id),
  payment_date    DATE NOT NULL,
  mode            TEXT NOT NULL
                    CHECK (mode IN ('bank_transfer','cheque','cash','upi','other')),
  amount          NUMERIC(15,2) NOT NULL CHECK (amount > 0),
  reference       TEXT,
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, payment_number)
);
```

---

## V034 — Frame Agreements

```sql
CREATE TABLE frame_agreements (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  agreement_number TEXT NOT NULL,
  customer_id     UUID NOT NULL REFERENCES customers(id),
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('draft','active','expired','cancelled')),
  valid_from      DATE NOT NULL,
  valid_to        DATE,
  terms           TEXT,
  document_ref    TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, agreement_number)
);

CREATE TABLE frame_agreement_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  agreement_id    UUID NOT NULL REFERENCES frame_agreements(id),
  item_id         UUID REFERENCES items(id),
  line_no         INTEGER NOT NULL,
  description     TEXT,
  agreed_price    NUMERIC(15,4),
  min_qty         NUMERIC(15,3),
  max_qty         NUMERIC(15,3),
  uom_id          UUID REFERENCES units_of_measure(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, agreement_id, line_no)
);
```

---

## V035 — Reorder Policies + Replenishment Requests

```sql
CREATE TABLE reorder_policies (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  store_id        UUID NOT NULL REFERENCES stores(id),
  min_stock       NUMERIC(15,3) NOT NULL DEFAULT 0,
  reorder_point   NUMERIC(15,3) NOT NULL,
  reorder_qty     NUMERIC(15,3) NOT NULL CHECK (reorder_qty > 0),
  preferred_supplier_id UUID REFERENCES suppliers(id),
  lead_time_days  INTEGER NOT NULL DEFAULT 7,
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, item_id, store_id)
);

CREATE TABLE replenishment_requests (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  rr_number       TEXT NOT NULL,
  item_id         UUID NOT NULL REFERENCES items(id),
  store_id        UUID NOT NULL REFERENCES stores(id),
  policy_id       UUID REFERENCES reorder_policies(id),
  required_qty    NUMERIC(15,3) NOT NULL CHECK (required_qty > 0),
  status          TEXT NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open','pr_created','cancelled')),
  triggered_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  pr_id           UUID,  -- FK to purchase_requisitions backfilled in V021 or V043
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, rr_number)
);
```

---

## V036 — Inter-Site Transfers

```sql
CREATE TABLE inter_site_transfers (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  transfer_number TEXT NOT NULL,
  from_store_id   UUID NOT NULL REFERENCES stores(id),
  to_store_id     UUID NOT NULL REFERENCES stores(id),
  status          TEXT NOT NULL DEFAULT 'draft'
                    CHECK (status IN ('draft','dispatched','in_transit','received','cancelled')),
  dispatch_date   DATE,
  received_date   DATE,
  notes           TEXT,
  created_by      UUID NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, transfer_number)
);

CREATE TABLE inter_site_transfer_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  transfer_id     UUID NOT NULL REFERENCES inter_site_transfers(id),
  item_id         UUID NOT NULL REFERENCES items(id),
  lot_id          UUID REFERENCES material_lots(id),
  line_no         INTEGER NOT NULL,
  dispatched_qty  NUMERIC(15,3) NOT NULL CHECK (dispatched_qty > 0),
  received_qty    NUMERIC(15,3),
  unit_cost       NUMERIC(15,4),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, transfer_id, line_no)
);
```

---

## V037 — Assets + Locations

```sql
CREATE TABLE assets (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  asset_number    TEXT NOT NULL,
  name            TEXT NOT NULL,
  asset_type      TEXT NOT NULL DEFAULT 'machinery'
                    CHECK (asset_type IN ('machinery','vehicle','tool','fixture','building','other')),
  site_id         UUID NOT NULL REFERENCES physical_sites(id),
  make            TEXT,
  model           TEXT,
  serial_number   TEXT,
  purchase_date   DATE,
  purchase_cost   NUMERIC(15,2),
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','under_maintenance','disposed','retired')),
  next_service_date DATE,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, asset_number)
);
```

---

## V038 — Maintenance Work Orders

```sql
CREATE TABLE maintenance_schedules (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  asset_id        UUID NOT NULL REFERENCES assets(id),
  schedule_type   TEXT NOT NULL DEFAULT 'preventive'
                    CHECK (schedule_type IN ('preventive','statutory','calibration')),
  frequency_days  INTEGER NOT NULL,
  last_done_date  DATE,
  next_due_date   DATE NOT NULL,
  description     TEXT NOT NULL,
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE maintenance_work_orders (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  wo_number       TEXT NOT NULL,
  asset_id        UUID NOT NULL REFERENCES assets(id),
  schedule_id     UUID REFERENCES maintenance_schedules(id),
  wo_type         TEXT NOT NULL DEFAULT 'preventive'
                    CHECK (wo_type IN ('preventive','reactive','breakdown','statutory')),
  status          TEXT NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open','in_progress','completed','cancelled')),
  priority        TEXT NOT NULL DEFAULT 'normal'
                    CHECK (priority IN ('low','normal','high','critical')),
  reported_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  scheduled_date  DATE,
  started_at      TIMESTAMPTZ,
  completed_at    TIMESTAMPTZ,
  downtime_hours  NUMERIC(6,2),
  cost            NUMERIC(15,2),
  performed_by    TEXT,
  description     TEXT NOT NULL,
  resolution      TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, wo_number)
);
```

---

## V039 — Job Costing

```sql
CREATE TABLE job_costing_headers (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  job_id          UUID NOT NULL UNIQUE REFERENCES production_jobs(id),
  status          TEXT NOT NULL DEFAULT 'open'
                    CHECK (status IN ('open','closed','reviewed')),
  -- Estimated
  est_material_cost   NUMERIC(15,2) NOT NULL DEFAULT 0,
  est_consumable_cost NUMERIC(15,2) NOT NULL DEFAULT 0,
  est_labour_cost     NUMERIC(15,2) NOT NULL DEFAULT 0,
  est_overhead_pct    NUMERIC(5,2) NOT NULL DEFAULT 0,
  est_overhead_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  est_total_cost      NUMERIC(15,2) NOT NULL DEFAULT 0,
  -- Actual (updated as job progresses)
  actual_material_cost   NUMERIC(15,2) NOT NULL DEFAULT 0,
  actual_consumable_cost NUMERIC(15,2) NOT NULL DEFAULT 0,
  actual_labour_cost     NUMERIC(15,2) NOT NULL DEFAULT 0,
  actual_overhead_amount NUMERIC(15,2) NOT NULL DEFAULT 0,
  actual_total_cost      NUMERIC(15,2) NOT NULL DEFAULT 0,
  -- Selling / Commercial (from CPO — NEVER updated by costing)
  selling_price       NUMERIC(15,2),
  actual_margin_pct   NUMERIC(7,4) GENERATED ALWAYS AS (
    CASE WHEN selling_price > 0 AND actual_total_cost > 0
    THEN ((selling_price - actual_total_cost) / selling_price * 100.0)
    ELSE NULL END
  ) STORED,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- IMPORTANT: selling_price comes from CPO. It MUST NOT affect customer invoice.
-- Job costing is internal management intelligence only.

CREATE TABLE job_costing_labour_entries (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  costing_header_id UUID NOT NULL REFERENCES job_costing_headers(id),
  date            DATE NOT NULL,
  worker_name     TEXT,
  hours           NUMERIC(6,2) NOT NULL CHECK (hours > 0),
  rate_per_hour   NUMERIC(10,2),
  amount          NUMERIC(15,2) NOT NULL,
  notes           TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE job_costing_other_expenses (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  costing_header_id UUID NOT NULL REFERENCES job_costing_headers(id),
  expense_type    TEXT NOT NULL,
  description     TEXT NOT NULL,
  amount          NUMERIC(15,2) NOT NULL CHECK (amount >= 0),
  date            DATE NOT NULL,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V040 — Company Profiles

```sql
CREATE TABLE company_profiles (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL UNIQUE REFERENCES tenant_registry(id),
  company_name    TEXT NOT NULL,
  tagline         TEXT,
  year_established INTEGER,
  employee_count  INTEGER,
  factory_area_sqft NUMERIC(10,2),
  production_capacity TEXT,
  turnover_band   TEXT,  -- optional; e.g. "₹10–25 Cr" rather than exact figure
  certifications  JSONB,  -- array of {name, body, valid_to}
  equipment_list  JSONB,  -- array of {name, qty, capacity}
  project_portfolio JSONB, -- array of {description, customer_name (optional), year}
  services_offered TEXT[],
  export_markets  TEXT[],
  public_contact_email TEXT,
  public_contact_phone TEXT,
  website         TEXT,
  gcs_logo_path   TEXT,
  gcs_profile_pdf_path TEXT,
  last_published_at TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- Sensitive fields (exact turnover, valuation) stored separately with explicit publish flag
CREATE TABLE company_profile_publish_config (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL UNIQUE REFERENCES tenant_registry(id),
  show_turnover   BOOLEAN NOT NULL DEFAULT FALSE,
  show_valuation  BOOLEAN NOT NULL DEFAULT FALSE,
  anonymise_client_names BOOLEAN NOT NULL DEFAULT TRUE,
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V041 — Notifications + Notification Preferences

**Note:** `packing_specifications` was absorbed into V029 (Dispatches). V041 provides the notifications subsystem.

```sql
CREATE TABLE notifications (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant_registry(id),
  user_id           UUID NOT NULL REFERENCES app_users(id),
  notification_type TEXT NOT NULL,
  entity_type       TEXT,
  entity_id         UUID,
  title             TEXT NOT NULL,
  body              TEXT,
  is_read           BOOLEAN NOT NULL DEFAULT FALSE,
  read_at           TIMESTAMPTZ,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE notification_preferences (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id         UUID NOT NULL REFERENCES tenant_registry(id),
  user_id           UUID NOT NULL REFERENCES app_users(id),
  notification_type TEXT NOT NULL,
  in_app            BOOLEAN NOT NULL DEFAULT TRUE,
  whatsapp          BOOLEAN NOT NULL DEFAULT FALSE,
  email             BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, user_id, notification_type)
);
```

---

## V042 — Approval Requests

```sql
-- Generic approval request log. Used by PR approval, concession approval, and any
-- other entity requiring a formal approval gate.
CREATE TABLE approval_requests (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  entity_type     TEXT NOT NULL,   -- e.g. 'purchase_requisition','concession','grn_variance'
  entity_id       UUID NOT NULL,
  requested_by    UUID NOT NULL REFERENCES app_users(id),
  required_role   TEXT NOT NULL,   -- role that may approve
  status          TEXT NOT NULL DEFAULT 'pending'
                    CHECK (status IN ('pending','approved','rejected','withdrawn')),
  comments        TEXT,
  decided_by      UUID REFERENCES app_users(id),
  decided_at      TIMESTAMPTZ,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

---

## V043 — Backfill FKs + Replenishment PR Link

```sql
-- Backfill FK for replenishment_requests.pr_id (created after purchase_requisitions in V021)
ALTER TABLE replenishment_requests
  ADD CONSTRAINT fk_rr_pr FOREIGN KEY (pr_id) REFERENCES purchase_requisitions(id);
```

---

## V044 — Platform Admin Control Plane

**These tables are NOT tenant-scoped and do NOT receive RLS.** They are accessed ONLY by the
`boss-admin-api` Cloud Run service using `AdminFirebaseTokenVerifier`. Customer tokens are
rejected at the security filter before any data access.

```sql
CREATE TABLE platform_admin_users (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  firebase_uid    TEXT NOT NULL UNIQUE,
  email           TEXT NOT NULL UNIQUE,
  display_name    TEXT,
  role            TEXT NOT NULL DEFAULT 'admin'
                    CHECK (role IN ('super_admin','admin','support')),
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('active','suspended','deactivated')),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE platform_audit_events (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  admin_user_id   UUID REFERENCES platform_admin_users(id),
  action          TEXT NOT NULL,
  entity_type     TEXT NOT NULL,
  entity_id       UUID,
  tenant_id       UUID REFERENCES tenant_registry(id),  -- which tenant was affected (nullable)
  old_data        JSONB,
  new_data        JSONB,
  ip_address      INET,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
  -- Append-only: separate trigger blocks UPDATE/DELETE on platform_audit_events
);
-- NO RLS on either table — they are platform-level, not tenant-scoped.
-- platform_admin_users has no tenant_id; platform_audit_events has nullable tenant_id (reference only).
```

---

## V045 — Tenant Configuration Tables (FD-57: Option D — Onboarding Wizard Seeds Industry Defaults)

```sql
-- Item categories — tenant-defined; seeded from industry_type at onboarding
CREATE TABLE item_categories (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  name            TEXT NOT NULL,
  code            TEXT NOT NULL,
  parent_id       UUID REFERENCES item_categories(id),   -- supports nested categories
  applies_to      TEXT NOT NULL DEFAULT 'ALL'
                    CHECK (applies_to IN ('RAW_MATERIAL','CONSUMABLE','FINISHED_GOOD','SERVICE','SPARE_PART','ALL')),
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  is_system       BOOLEAN NOT NULL DEFAULT FALSE,         -- TRUE = seeded by platform; tenant cannot delete
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

-- Asset type configurations — tenant-defined; seeded from industry_type at onboarding
CREATE TABLE asset_type_configs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  name            TEXT NOT NULL,          -- e.g. "CNC Machine", "Plasma Cutter", "Overhead Crane"
  code            TEXT NOT NULL,
  service_interval_days INT,              -- default PM interval for this asset type
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  is_system       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code)
);

-- Inspection parameter templates — reusable sets of QC parameters per product type
CREATE TABLE inspection_parameter_templates (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  name            TEXT NOT NULL,          -- e.g. "Structural Steel — Post-Weld", "Incoming Raw Material"
  stage           TEXT,                   -- optional: which production stage this applies to
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  is_system       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE inspection_parameter_template_lines (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  template_id     UUID NOT NULL REFERENCES inspection_parameter_templates(id) ON DELETE CASCADE,
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  parameter_name  TEXT NOT NULL,          -- e.g. "Weld visual check", "Dimensional tolerance ±0.1mm"
  parameter_type  TEXT NOT NULL DEFAULT 'PASS_FAIL'
                    CHECK (parameter_type IN ('PASS_FAIL','NUMERIC','TEXT')),
  unit            TEXT,                   -- for NUMERIC type, e.g. "mm", "kg"
  is_mandatory    BOOLEAN NOT NULL DEFAULT TRUE,
  sort_order      INT NOT NULL DEFAULT 0
);

-- Tenant onboarding config — records which industry type was selected at onboarding
-- Used to determine which seed data to apply via the onboarding wizard
CREATE TABLE tenant_onboarding_config (
  tenant_id       UUID PRIMARY KEY REFERENCES tenant_registry(id),
  industry_type   TEXT NOT NULL
                    CHECK (industry_type IN (
                      'FABRICATION_STEEL',
                      'PRECISION_MACHINING',
                      'ELECTRONICS_ASSEMBLY',
                      'FORGING',
                      'CASTING',
                      'GENERAL_MANUFACTURING'
                    )),
  onboarding_completed_at TIMESTAMPTZ,
  onboarding_completed_by UUID REFERENCES app_users(id)
);
```

**RLS note:** `item_categories`, `asset_type_configs`, `inspection_parameter_templates`, `inspection_parameter_template_lines`, and `tenant_onboarding_config` all require RLS policies in V051 (`tenant_id = current_setting('app.current_tenant_id', true)::uuid`).

**Onboarding wizard (EPIC-00/01 scope):** When a new tenant is provisioned, the platform admin selects `industry_type`. The provisioning service calls a seed function (`seed_tenant_defaults(tenant_id, industry_type)`) that inserts `is_system = TRUE` rows into `item_categories`, `asset_type_configs`, and `inspection_parameter_templates`. Tenant admin can add their own rows but cannot delete `is_system = TRUE` rows.

**`items` table update:** Column `category_id UUID REFERENCES item_categories(id)` replaces free-text `category TEXT`. See V046 below.

---

## V046 — Items category FK

```sql
-- v1.1.1 correction: replace free-text category column with FK to item_categories
ALTER TABLE items ADD COLUMN category_id UUID REFERENCES item_categories(id);
ALTER TABLE items DROP COLUMN IF EXISTS category;

CREATE INDEX idx_items_category ON items(tenant_id, category_id);
```

---

## V045_1 — Configurable Business Values: global category registry (renamed from invalid V045b)

```sql
-- Global registry of all configurable option categories.
-- NO tenant_id — this is platform-level reference data, not tenant-scoped.
-- Platform Admin only can add/modify categories.
CREATE TABLE config_categories (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code                    TEXT NOT NULL UNIQUE,
  domain                  TEXT NOT NULL,
  label                   TEXT NOT NULL,
  value_type              TEXT NOT NULL DEFAULT 'SIMPLE'
                            CHECK (value_type IN ('SIMPLE','ORDERED','HIERARCHICAL','TEMPLATE')),
  is_ordered              BOOLEAN NOT NULL DEFAULT FALSE,
  allows_tenant_add       BOOLEAN NOT NULL DEFAULT TRUE,
  allows_tenant_edit      BOOLEAN NOT NULL DEFAULT FALSE,
  allows_tenant_deactivate BOOLEAN NOT NULL DEFAULT TRUE,
  is_system_protected     BOOLEAN NOT NULL DEFAULT FALSE,
  description             TEXT,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

**Seed rows (abbreviated):**

| code | domain | label | is_system_protected |
|---|---|---|---|
| ITEM_CATEGORY | MASTER_DATA | Item Category | false |
| PRODUCTION_STAGE | PRODUCTION | Production Stage | false |
| INSPECTION_TYPE | QUALITY | Inspection Type | false |
| DEFECT_CATEGORY | QUALITY | Defect Category | false |
| ASSET_TYPE | ASSETS | Asset Type | false |
| BREAKDOWN_REASON | ASSETS | Breakdown Reason | false |
| MAINTENANCE_TYPE | ASSETS | Maintenance Type | false |
| DISPATCH_METHOD | DISPATCH | Dispatch Method | false |
| REJECTION_REASON | QUALITY | Rejection Reason | false |
| PR_ORIGIN_TYPE | PROCUREMENT | PR Origin Type | true |
| PAYMENT_METHOD | FINANCE | Payment Method | false |
| NCR_STATUS | QUALITY | NCR Status | true |
| INVOICE_STATUS | FINANCE | Invoice Status | true |
| JOB_STATUS | PRODUCTION | Job Status | true |
| SPO_STATUS | PROCUREMENT | SPO Status | true |
| GRN_STATUS | PROCUREMENT | GRN Status | true |

---

## V046_1 — Configurable Business Values: tenant options + history (renamed from invalid V046a)

```sql
-- Tenant-scoped option values for each config_category.
-- Inherits from industry template at provisioning (is_system = TRUE rows).
-- Tenant admin can add their own (is_system = FALSE rows).
CREATE TABLE tenant_config_options (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  category_code    TEXT NOT NULL REFERENCES config_categories(code),
  code             TEXT NOT NULL,
  label            TEXT NOT NULL,
  short_label      TEXT,
  description      TEXT,
  sort_order       INTEGER NOT NULL DEFAULT 0,
  is_active        BOOLEAN NOT NULL DEFAULT TRUE,
  is_system        BOOLEAN NOT NULL DEFAULT FALSE,
  metadata         JSONB DEFAULT '{}',
  parent_id        UUID REFERENCES tenant_config_options(id),
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by       UUID REFERENCES app_users(id),
  UNIQUE (tenant_id, category_code, code)
);

-- Append-only audit trail for all option changes.
CREATE TABLE tenant_config_option_history (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  option_id        UUID NOT NULL REFERENCES tenant_config_options(id),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  changed_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  changed_by       UUID REFERENCES app_users(id),
  change_type      TEXT NOT NULL CHECK (change_type IN ('CREATED','UPDATED','DEACTIVATED','REACTIVATED')),
  old_label        TEXT,
  new_label        TEXT,
  old_sort_order   INTEGER,
  new_sort_order   INTEGER,
  old_is_active    BOOLEAN,
  new_is_active    BOOLEAN
);
```

---

## V047 — Versioned Routing Templates

```sql
-- Step 1: Remove FK dependency from job_stages on routing_operations (V025 column).
-- Add new columns BEFORE dropping the old table.
ALTER TABLE job_stages DROP COLUMN routing_op_id;
ALTER TABLE job_stages ADD COLUMN routing_template_stage_id UUID;  -- FK added after new table created below
ALTER TABLE job_stages ADD COLUMN applied_template_version INTEGER;  -- snapshot of version at job creation

-- Step 2: Remove FK from production_jobs on old routing_templates.
ALTER TABLE production_jobs DROP COLUMN routing_id;
ALTER TABLE production_jobs ADD COLUMN routing_template_id UUID;    -- FK added after new table created below
ALTER TABLE production_jobs ADD COLUMN routing_template_version INTEGER;  -- snapshot for historical display

-- Step 3: Drop old V015 tables (now safe — dependent FKs removed above).
DROP TABLE routing_operations;
DROP TABLE routing_templates;

-- Step 4: Create new versioned routing tables.

-- Template header. Version increments each time the template is revised.
-- Active jobs retain their original routing_template_id; they do NOT pick up new versions.
CREATE TABLE routing_templates (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  name             TEXT NOT NULL,
  code             TEXT NOT NULL,
  version          INTEGER NOT NULL DEFAULT 1,
  status           TEXT NOT NULL DEFAULT 'active'
                     CHECK (status IN ('draft','active','superseded','archived')),
  is_system        BOOLEAN NOT NULL DEFAULT FALSE,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by       UUID REFERENCES app_users(id),
  UNIQUE (tenant_id, code, version)
);

-- Individual stages within a template.
-- stage_name is a snapshot copied from the config option at template-creation time.
-- Changing the config option label does NOT retroactively change template stages.
CREATE TABLE routing_template_stages (
  id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  template_id              UUID NOT NULL REFERENCES routing_templates(id),
  tenant_id                UUID NOT NULL REFERENCES tenant_registry(id),
  stage_config_option_id   UUID REFERENCES tenant_config_options(id),
  stage_name               TEXT NOT NULL,
  stage_code               TEXT NOT NULL,
  seq_no                   INTEGER NOT NULL,
  requires_inspection      BOOLEAN NOT NULL DEFAULT FALSE,
  requires_approval        BOOLEAN NOT NULL DEFAULT FALSE,
  is_mandatory             BOOLEAN NOT NULL DEFAULT TRUE,
  can_skip                 BOOLEAN NOT NULL DEFAULT FALSE,
  allows_rework            BOOLEAN NOT NULL DEFAULT TRUE,
  blocks_next_stage        BOOLEAN NOT NULL DEFAULT TRUE,
  requires_evidence        BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (template_id, seq_no)
);

-- Step 5: Wire new FKs now that routing_templates and routing_template_stages exist.
ALTER TABLE production_jobs
  ADD CONSTRAINT fk_production_jobs_routing_template
  FOREIGN KEY (routing_template_id) REFERENCES routing_templates(id);

ALTER TABLE job_stages
  ADD CONSTRAINT fk_job_stages_routing_template_stage
  FOREIGN KEY (routing_template_stage_id) REFERENCES routing_template_stages(id);
```

**v1.1.2 correction note:** V047 now includes the required DROP TABLE statements for `routing_operations` and old `routing_templates` before recreating the versioned versions. The ALTER TABLE steps before DROP remove the dependent FK columns from `job_stages` and `production_jobs` to avoid FK constraint violation errors during the migration. Active jobs retain their stage history via `job_stages.name` (copied at job creation time) and `job_stages.applied_template_version`.

---

## V050 — Append-Only Triggers

```sql
-- Stock movements: never update or delete
CREATE OR REPLACE FUNCTION prevent_stock_movement_mutation()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'stock_movements is append-only. Use new movements to correct inventory.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_stock_movements_append_only
BEFORE UPDATE OR DELETE ON stock_movements
FOR EACH ROW EXECUTE FUNCTION prevent_stock_movement_mutation();

-- Audit events: never update or delete
CREATE OR REPLACE FUNCTION prevent_audit_event_mutation()
RETURNS TRIGGER AS $$
BEGIN
  RAISE EXCEPTION 'audit_events is append-only. Audit records cannot be modified.';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_audit_events_append_only
BEFORE UPDATE OR DELETE ON audit_events
FOR EACH ROW EXECUTE FUNCTION prevent_audit_event_mutation();
```

---

## V051 — RLS Policies

```sql
-- Template: apply to all tenant-scoped tables
-- The 'true' second argument to current_setting() makes an unset context return NULL
-- rather than raising an error. NULL::uuid != anything, so zero rows are returned (secure default).

DO $$
DECLARE
  t TEXT;
  tables TEXT[] := ARRAY[
    'audit_events','app_users','user_roles','invite_tokens',
    'legal_entities','gst_registrations','physical_sites','stores',
    'units_of_measure','uom_conversions','items',
    'customers','customer_contacts','suppliers','supplier_contacts',
    'supplier_performance_records','bom_headers','bom_lines',
    'routing_templates','routing_template_stages',
    'material_lots','stock_movements','stock_reservation_records',
    'rfqs','rfq_lines','quotations','quotation_lines','quotation_negotiation_history',
    'customer_purchase_orders','cpo_lines',
    'purchase_requisitions','pr_lines',
    'supplier_quotations','supplier_quotation_lines',
    'supplier_purchase_orders','spo_lines',
    'grns','grn_lines',
    'production_jobs','job_stages','job_material_requirements','job_material_issues',
    'quality_inspections','inspection_parameters','ncrs','concessions',
    'customer_clearance_certificates',
    'packing_specifications','dispatches','dispatch_lines','pod_records',
    'invoice_sequence_counters','invoices','invoice_lines',
    'receipts','receipt_allocations','supplier_bills','supplier_payments',
    'frame_agreements','reorder_policies','inter_site_transfers','inter_site_transfer_lines',
    'assets','maintenance_schedules','maintenance_work_orders',
    'job_costing_headers','job_costing_labour_entries','job_costing_other_expenses',
    'company_profiles','company_profile_publish_config',
    -- V045 tables (added in v1.1.1 to close RLS gap)
    'item_categories','asset_type_configs',
    'inspection_parameter_templates','inspection_parameter_template_lines',
    'tenant_onboarding_config',
    -- V041 tables
    'notifications','notification_preferences',
    -- V042 table
    'approval_requests',
    -- V043 table
    'replenishment_requests',
    -- V034 addition
    'frame_agreement_lines',
    -- V046_1 / V047 tables (was V046a / V047)
    'tenant_config_options','tenant_config_option_history'
  ];
BEGIN
  FOREACH t IN ARRAY tables
  LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format(
      'CREATE POLICY tenant_isolation ON %I
       USING (tenant_id = current_setting(''app.current_tenant_id'', true)::uuid)',
      t
    );
  END LOOP;
END;
$$;

-- Tables WITHOUT RLS (platform-wide reference data or platform admin):
-- outbox_events, idempotency_keys, hsn_codes, tenant_registry, tenant_entitlements, customer_groups
-- platform_admin_users, platform_audit_events (see V044)
```

---

## V052 — Index Catalogue

```sql
-- Tenant isolation (covering indexes for all RLS lookups)
CREATE INDEX idx_app_users_tenant ON app_users(tenant_id, status);
CREATE INDEX idx_items_tenant_code ON items(tenant_id, code);
CREATE INDEX idx_items_tenant_active ON items(tenant_id) WHERE is_active = TRUE;
CREATE INDEX idx_customers_tenant ON customers(tenant_id, is_active);
CREATE INDEX idx_suppliers_tenant ON suppliers(tenant_id, is_active);

-- Inventory hot paths
CREATE INDEX idx_material_lots_item_store ON material_lots(tenant_id, item_id, store_id) WHERE status = 'available';
CREATE INDEX idx_stock_movements_lot ON stock_movements(tenant_id, lot_id, created_at DESC);
CREATE INDEX idx_stock_movements_item ON stock_movements(tenant_id, item_id, created_at DESC);
CREATE INDEX idx_reservations_lot ON stock_reservation_records(tenant_id, lot_id) WHERE status = 'active';

-- Transaction lookups
CREATE INDEX idx_rfqs_customer ON rfqs(tenant_id, customer_id, status);
CREATE INDEX idx_quotations_customer ON quotations(tenant_id, customer_id, status);
CREATE INDEX idx_cpos_customer ON customer_purchase_orders(tenant_id, customer_id, status);
CREATE INDEX idx_spos_supplier ON supplier_purchase_orders(tenant_id, supplier_id, status);
CREATE INDEX idx_grns_spo ON grns(tenant_id, spo_id);
CREATE INDEX idx_jobs_cpo ON production_jobs(tenant_id, cpo_id);
CREATE INDEX idx_jobs_status ON production_jobs(tenant_id, status);
CREATE INDEX idx_invoices_customer ON invoices(tenant_id, customer_id, status);
CREATE INDEX idx_invoices_due ON invoices(tenant_id, due_date) WHERE amount_due > 0;
CREATE INDEX idx_supplier_bills_supplier ON supplier_bills(tenant_id, supplier_id, status);

-- Outbox relay (platform-level, no tenant_id)
CREATE INDEX idx_outbox_pending ON outbox_events(status, created_at) WHERE status = 'pending';

-- Finance
CREATE INDEX idx_receipt_allocations_invoice ON receipt_allocations(tenant_id, invoice_id);
CREATE INDEX idx_invoice_irn ON invoices(irn) WHERE irn IS NOT NULL;

-- Maintenance
CREATE INDEX idx_maintenance_wo_asset ON maintenance_work_orders(tenant_id, asset_id, status);
CREATE INDEX idx_maintenance_schedule_due ON maintenance_schedules(tenant_id, next_due_date) WHERE is_active = TRUE;

-- Configurable values (V046a)
CREATE INDEX idx_config_options_tenant_cat ON tenant_config_options(tenant_id, category_code);
CREATE INDEX idx_config_options_tenant_active ON tenant_config_options(tenant_id, category_code) WHERE is_active = TRUE;
CREATE INDEX idx_config_options_parent ON tenant_config_options(parent_id) WHERE parent_id IS NOT NULL;
CREATE INDEX idx_config_option_history_option ON tenant_config_option_history(option_id, changed_at DESC);

-- Routing templates (V047)
CREATE INDEX idx_routing_templates_tenant ON routing_templates(tenant_id, status);
CREATE INDEX idx_routing_template_stages_template ON routing_template_stages(template_id, seq_no);

-- V041 notifications
CREATE INDEX idx_notifications_user_unread ON notifications(tenant_id, user_id, created_at DESC) WHERE is_read = FALSE;

-- V042 approvals
CREATE INDEX idx_approval_requests_entity ON approval_requests(tenant_id, entity_type, entity_id) WHERE status = 'pending';

-- V034 addition
CREATE INDEX idx_frame_agreement_lines_agreement ON frame_agreement_lines(tenant_id, agreement_id);

-- V035 addition
CREATE INDEX idx_replenishment_requests_item_store ON replenishment_requests(tenant_id, item_id, store_id) WHERE status = 'open';
```

---

## V060 — Views

```sql
-- Inventory position view: available quantity per item per store
CREATE OR REPLACE VIEW inventory_position_view AS
SELECT
  ml.tenant_id,
  ml.item_id,
  i.code AS item_code,
  i.name AS item_name,
  ml.store_id,
  s.name AS store_name,
  SUM(ml.quantity) AS total_qty,
  SUM(ml.reserved_qty) AS reserved_qty,
  SUM(ml.quantity - ml.reserved_qty) AS available_qty,
  u.symbol AS uom_symbol
FROM material_lots ml
JOIN items i ON ml.item_id = i.id
JOIN stores s ON ml.store_id = s.id
JOIN units_of_measure u ON i.base_uom_id = u.id
WHERE ml.status = 'available'
GROUP BY ml.tenant_id, ml.item_id, i.code, i.name, ml.store_id, s.name, u.symbol;

-- Invoice AR summary view
CREATE OR REPLACE VIEW invoice_ar_summary_view AS
SELECT
  i.tenant_id,
  i.customer_id,
  c.name AS customer_name,
  COUNT(*) AS invoice_count,
  SUM(i.total_amount) AS total_billed,
  SUM(i.amount_paid) AS total_received,
  SUM(i.amount_due) AS total_outstanding,
  MIN(i.due_date) FILTER (WHERE i.amount_due > 0) AS oldest_due_date
FROM invoices i
JOIN customers c ON i.customer_id = c.id
WHERE i.status NOT IN ('cancelled', 'written_off')
GROUP BY i.tenant_id, i.customer_id, c.name;
```

---

## Table Count Summary

| Migration | Tables Added | Cumulative |
|---|---|---|
| V001 | 0 (extensions) | 0 |
| V002 | customer_groups, tenant_registry | 2 |
| V003 | tenant_entitlements | 3 |
| V004 | audit_events | 4 |
| V005 | outbox_events, idempotency_keys | 6 |
| V006 | app_users, user_roles, invite_tokens | 9 |
| V007 | legal_entities, gst_registrations | 11 |
| V008 | physical_sites, stores | 13 |
| V009 | units_of_measure, uom_conversions | 15 |
| V010 | hsn_codes | 16 |
| V011 | items | 17 |
| V012 | customers, customer_contacts | 19 |
| V013 | suppliers, supplier_contacts, supplier_performance_records | 22 |
| V014 | bom_headers, bom_lines | 24 |
| V015 | routing_templates, routing_operations | 26 |
| V016 | material_lots, stock_movements | 28 |
| V017 | stock_reservation_records | 29 |
| V018 | rfqs, rfq_lines | 31 |
| V019 | quotations, quotation_lines, quotation_negotiation_history | 34 |
| V020 | customer_purchase_orders, cpo_lines | 36 |
| V021 | purchase_requisitions, pr_lines | 38 |
| V022 | supplier_quotations, supplier_quotation_lines | 40 |
| V023 | supplier_purchase_orders, spo_lines | 42 |
| V024 | grns, grn_lines | 44 |
| V025 | production_jobs, job_stages | 46 |
| V026 | job_material_requirements, job_material_issues | 48 |
| V027 | quality_inspections, inspection_parameters | 50 |
| V028 | ncrs, concessions, customer_clearance_certificates | 53 |
| V029 | packing_specifications, dispatches, dispatch_lines, pod_records | 57 |
| V030 | invoice_sequence_counters | 58 |
| V031 | invoices, invoice_lines | 60 |
| V032 | receipts, receipt_allocations | 62 |
| V033 | supplier_bills, supplier_payments | 64 |
| V034 | frame_agreements, frame_agreement_lines | 66 |
| V035 | reorder_policies, replenishment_requests | 68 |
| V036 | inter_site_transfers, inter_site_transfer_lines | 70 |
| V037 | assets | 71 |
| V038 | maintenance_schedules, maintenance_work_orders | 73 |
| V039 | job_costing_headers, job_costing_labour_entries, job_costing_other_expenses | 76 |
| V040 | company_profiles, company_profile_publish_config | 78 |
| V041 | notifications, notification_preferences | 80 |
| V042 | approval_requests | 81 |
| V043 | 0 tables (FK backfill only: replenishment_requests.pr_id) | 81 |
| V044 | platform_admin_users, platform_audit_events | 83 |
| V045 | item_categories, asset_type_configs, inspection_parameter_templates, inspection_parameter_template_lines, tenant_onboarding_config | 88 |
| V045_1 | config_categories (global registry — no tenant_id) | 89 |
| V046 | ALTER items: add category_id FK, drop category TEXT | 89 |
| V046_1 | tenant_config_options, tenant_config_option_history | 91 |
| V047 | DROPS routing_templates+routing_operations; CREATES versioned routing_templates+routing_template_stages (net 0) | 91 |
| V050–V060 | 0 new tables (triggers, RLS, indexes, seeds, views) | 91 |
| **Total** | | **91 tables** |

**Table count notes:**
- v1.0 had 27 tables; v1.1 had 78; v1.1.1 had 88 (incorrect — routing duplicate not deducted)
- v1.1.2 correct count: 91 tables (78 from v1.1 + 5 V045 + 1 V045_1 + 2 V046_1 + 0 net V047 + 2 V041 + 1 V042 + 1 frame_agreement_lines V034 + 1 replenishment_requests V035 + 2 V044 = 91 net)

**v1.1.2 schema corrections applied:**
1. V045: ALL FOUR `tenants(id)` FK references corrected to `tenant_registry(id)` in item_categories, asset_type_configs, inspection_parameter_templates, inspection_parameter_template_lines
2. V047: DROP TABLE routing_operations; DROP TABLE routing_templates; added BEFORE duplicate CREATE TABLE
3. V047: ALTER TABLE job_stages and production_jobs added to remove FK dependencies before DROP
4. V047: ALTER TABLE FK wiring added after new routing tables created
5. `stock_movements` added to V051 RLS array (was missing — security gap)
6. V041–V044 DDL sections added (were completely absent in v1.1.1 despite being in migration index)
7. V045b/V046a renamed to V045_1/V046_1 (Flyway requires numeric-only sub-versions)
8. V034: frame_agreement_lines table added (migration title said "Frame Agreements + Frame Agreement Lines" but only frame_agreements existed)
9. V035: replenishment_requests table added (migration title said "Reorder Policies + Replenishment Requests" but only reorder_policies existed)
10. notifications, notification_preferences, approval_requests, frame_agreement_lines, replenishment_requests added to V051 RLS array
