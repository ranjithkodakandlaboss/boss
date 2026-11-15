# BOSS — Configurable Business Values Design
**Version:** v1.1.1 | **Date:** 15 November 2026
**Status:** APPROVED FOR IMPLEMENTATION

This is the authoritative design for the configurable reference-data capability. Every agent implementing a screen with dropdowns, selection lists, production stages, inspection types, or business categories **must follow this design**.

---

## 1. The Problem

Two fabrication companies have different production stages. One company uses:
> Cutting → Fit-up → Welding → Painting → Final Inspection → Dispatch

Another uses:
> Cutting → Machining → Assembly → Surface Treatment → Testing → Packing → Dispatch

BOSS must support both without code changes. The same applies to inspection types, item categories, asset types, breakdown reasons, procurement categories, and many other values across all domains.

**Rule:** No dropdown list is hard-coded in the frontend or backend. All options come from the configurable reference-data system.

---

## 2. Configuration Hierarchy

```
Level 1: BOSS System Defaults       — maintained by Platform Admin; never tenant-editable
Level 2: Industry Template Defaults — seeded at tenant provisioning from industry_type
Level 3: Tenant Configuration       — tenant admin can add/edit/reorder/deactivate
Level 4: (Future) Site Overrides    — not in pilot scope; architecture must not block it
```

**Cascading rules:**
- A tenant inherits the industry template defaults at provisioning.
- Tenant admins can add their own options (new rows with `is_system = FALSE`).
- Tenant admins can deactivate system-seeded options (`is_active = FALSE` on tenant's copy).
- Tenant admins cannot edit the master system definition — they get a tenant-scoped copy.
- One tenant's changes never affect another tenant.

---

## 3. Industry Types (Pilot)

| Code | Label |
|---|---|
| `FABRICATION_STEEL` | Steel Fabrication |
| `PRECISION_MACHINING` | Precision Machining |
| `ELECTRONICS_ASSEMBLY` | Electronics Assembly |
| `FORGING` | Forging |
| `CASTING` | Casting |
| `GENERAL_MANUFACTURING` | General Manufacturing |

These are stored in `tenant_onboarding_config.industry_type`. Selected once at provisioning.

---

## 4. Data Model

### 4.1 Configuration Category Registry (system-level)

```sql
-- V045b (new in v1.1.1)
CREATE TABLE config_categories (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code            TEXT NOT NULL UNIQUE,
  domain          TEXT NOT NULL,
  label           TEXT NOT NULL,
  value_type      TEXT NOT NULL DEFAULT 'SIMPLE'
                    CHECK (value_type IN ('SIMPLE','ORDERED','HIERARCHICAL','TEMPLATE')),
  is_ordered      BOOLEAN NOT NULL DEFAULT FALSE,
  allows_tenant_add   BOOLEAN NOT NULL DEFAULT TRUE,
  allows_tenant_edit  BOOLEAN NOT NULL DEFAULT FALSE,
  allows_tenant_deactivate BOOLEAN NOT NULL DEFAULT TRUE,
  is_system_protected BOOLEAN NOT NULL DEFAULT FALSE,
  description     TEXT,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
  -- NO tenant_id — this is global registry
);
```

### 4.2 Tenant Configuration Options

```sql
-- V046a (new in v1.1.1)
CREATE TABLE tenant_config_options (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  category_code   TEXT NOT NULL REFERENCES config_categories(code),
  code            TEXT NOT NULL,
  label           TEXT NOT NULL,
  short_label     TEXT,
  description     TEXT,
  sort_order      INTEGER NOT NULL DEFAULT 0,
  is_active       BOOLEAN NOT NULL DEFAULT TRUE,
  is_system       BOOLEAN NOT NULL DEFAULT FALSE,
  metadata        JSONB DEFAULT '{}',
  parent_id       UUID REFERENCES tenant_config_options(id),
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  created_by      UUID REFERENCES app_users(id),
  UNIQUE (tenant_id, category_code, code)
);
-- RLS: tenant-scoped
-- Audit events on INSERT/UPDATE

CREATE TABLE tenant_config_option_history (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  option_id       UUID NOT NULL REFERENCES tenant_config_options(id),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  changed_by      UUID NOT NULL REFERENCES app_users(id),
  change_type     TEXT NOT NULL CHECK (change_type IN ('CREATED','UPDATED','DEACTIVATED','REACTIVATED','REORDERED')),
  old_value       JSONB,
  new_value       JSONB,
  changed_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
-- RLS: tenant-scoped. Append-only.
```

### 4.3 Production Routing Templates (versioned)

```sql
-- V047 (new in v1.1.1 — supersedes V015 routing_templates partially)
CREATE TABLE routing_templates (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id       UUID NOT NULL REFERENCES tenant_registry(id),
  name            TEXT NOT NULL,
  code            TEXT NOT NULL,
  version         INTEGER NOT NULL DEFAULT 1,
  status          TEXT NOT NULL DEFAULT 'active'
                    CHECK (status IN ('draft','active','superseded','archived')),
  is_system       BOOLEAN NOT NULL DEFAULT FALSE,
  created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, code, version)
);

CREATE TABLE routing_template_stages (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  template_id             UUID NOT NULL REFERENCES routing_templates(id),
  tenant_id               UUID NOT NULL REFERENCES tenant_registry(id),
  stage_config_option_id  UUID REFERENCES tenant_config_options(id),
  stage_name              TEXT NOT NULL,
  stage_code              TEXT NOT NULL,
  seq_no                  INTEGER NOT NULL,
  department              TEXT,
  expected_duration_hrs   NUMERIC(8,2),
  requires_inspection     BOOLEAN NOT NULL DEFAULT FALSE,
  requires_approval       BOOLEAN NOT NULL DEFAULT FALSE,
  is_mandatory            BOOLEAN NOT NULL DEFAULT TRUE,
  can_skip                BOOLEAN NOT NULL DEFAULT FALSE,
  allows_rework           BOOLEAN NOT NULL DEFAULT TRUE,
  blocks_next_stage       BOOLEAN NOT NULL DEFAULT TRUE,
  requires_evidence       BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (template_id, seq_no),
  UNIQUE (template_id, stage_code)
);
-- RLS: tenant-scoped
```

### 4.4 Inspection Parameter Templates (linked to routing)

Existing tables from V045 (`inspection_parameter_templates`, `inspection_parameter_template_lines`) are retained. The routing stage can reference an inspection template.

```sql
-- V045 already has these tables. V047a adds the link:
ALTER TABLE routing_template_stages
  ADD COLUMN inspection_template_id UUID REFERENCES inspection_parameter_templates(id);
```

---

## 5. Category Code Registry (Complete)

This is the authoritative list of all configurable category codes in BOSS v1.1.1.

| Category Code | Domain | Label | Value Type | Tenant Can Add | Tenant Can Edit |
|---|---|---|---|---|---|
| `ITEM_CATEGORY` | Inventory | Item Categories | HIERARCHICAL | Yes | No |
| `ITEM_GROUP` | Inventory | Item Groups | SIMPLE | Yes | No |
| `STOCK_ADJUSTMENT_REASON` | Inventory | Stock Adjustment Reasons | SIMPLE | Yes | No |
| `RFQ_TYPE` | Sales | RFQ Types | SIMPLE | Yes | No |
| `QUOTATION_REJECTION_REASON` | Sales | Quotation Rejection Reasons | SIMPLE | Yes | No |
| `CPO_CANCELLATION_REASON` | Sales | CPO Cancellation Reasons | SIMPLE | Yes | No |
| `CUSTOMER_TYPE` | Sales | Customer Types | SIMPLE | Yes | No |
| `CUSTOMER_INDUSTRY` | Sales | Customer Industry Classifications | SIMPLE | Yes | No |
| `PR_TYPE` | Procurement | PR Types | SIMPLE | Yes | No |
| `PROCUREMENT_CATEGORY` | Procurement | Procurement Categories | SIMPLE | Yes | No |
| `SUPPLIER_CATEGORY` | Procurement | Supplier Categories | SIMPLE | Yes | No |
| `REJECTION_REASON_PROCUREMENT` | Procurement | Procurement Rejection Reasons | SIMPLE | Yes | No |
| `PRODUCTION_STAGE` | Production | Production Stage Names | ORDERED | Yes | No |
| `JOB_TYPE` | Production | Job Types | SIMPLE | Yes | No |
| `HOLD_REASON` | Production | Job Hold Reasons | SIMPLE | Yes | No |
| `REWORK_REASON` | Production | Rework Reasons | SIMPLE | Yes | No |
| `INSPECTION_TYPE` | Quality | Inspection Types | SIMPLE | Yes | No |
| `DEFECT_CATEGORY` | Quality | Defect Categories | SIMPLE | Yes | No |
| `REJECTION_REASON_QUALITY` | Quality | Quality Rejection Reasons | SIMPLE | Yes | No |
| `ASSET_TYPE` | Maintenance | Asset Types | SIMPLE | Yes | No |
| `MAINTENANCE_CATEGORY` | Maintenance | Maintenance Categories | SIMPLE | Yes | No |
| `BREAKDOWN_REASON` | Maintenance | Breakdown Reasons | SIMPLE | Yes | No |
| `FAILURE_CATEGORY` | Maintenance | Failure Categories | SIMPLE | Yes | No |
| `DISPATCH_METHOD` | Dispatch | Dispatch Methods | SIMPLE | Yes | No |
| `PACKING_TYPE` | Dispatch | Packing Types | SIMPLE | Yes | No |
| `TRANSPORT_CATEGORY` | Dispatch | Transport Categories | SIMPLE | Yes | No |
| `PAYMENT_METHOD` | Finance | Payment Methods | SIMPLE | No | No |
| `EXPENSE_CATEGORY` | Finance | Expense Categories | SIMPLE | Yes | No |
| `FACILITY_TYPE` | Profile | Facility Types | SIMPLE | Yes | No |
| `CAPABILITY_CATEGORY` | Profile | Manufacturing Capability Categories | SIMPLE | Yes | No |

**System-protected (no tenant editing, no deactivation):**
- Invoice status transitions
- Job status transitions (DRAFT → IN_PROGRESS → QC_PENDING → COMPLETED)
- GRN status transitions
- Payment settlement states

---

## 6. Industry Template Defaults

The `seed_tenant_defaults(tenant_id UUID, industry_type TEXT)` PostgreSQL function populates all category codes above with appropriate defaults for the selected industry type.

**Example: FABRICATION_STEEL production stages:**
```
1. Cutting
2. Fit-up
3. Welding
4. Painting
5. Final Inspection (requires_inspection=true)
6. Dispatch
```

**Example: PRECISION_MACHINING production stages:**
```
1. Raw Material Verification
2. Turning
3. Milling
4. Grinding
5. Deburring
6. Quality Inspection (requires_inspection=true)
7. Dispatch
```

---

## 7. API

### Retrieve options for a category
```
GET /api/v1/config/options/{categoryCode}
Authorization: Bearer {customer_jwt}
X-Site-Id: {siteId}  (optional — for future site-level overrides)

Response 200:
{
  "categoryCode": "PRODUCTION_STAGE",
  "options": [
    { "id": "uuid", "code": "CUTTING", "label": "Cutting", "sortOrder": 1, "isActive": true, "metadata": {} },
    ...
  ]
}
```

### Manage tenant options (OWNER role only)
```
POST   /api/v1/config/options/{categoryCode}       — add tenant option
PATCH  /api/v1/config/options/{categoryCode}/{id}  — edit label/sortOrder/metadata
DELETE /api/v1/config/options/{categoryCode}/{id}  — deactivate (not delete)
POST   /api/v1/config/options/{categoryCode}/reorder — update sort_order
```

### Routing template management
```
GET    /api/v1/config/routing-templates            — list active templates
POST   /api/v1/config/routing-templates            — create
GET    /api/v1/config/routing-templates/{id}       — get with stages
PUT    /api/v1/config/routing-templates/{id}       — update (creates new version; previous → superseded)
DELETE /api/v1/config/routing-templates/{id}       — archive (not delete if active jobs exist)
```

Full OpenAPI spec: `10_API_CONTRACTS/openapi/config.yaml`.

---

## 8. Important Constraints

### Configuration changes must NOT corrupt active records
- If a production stage option is deactivated, existing jobs already in that stage retain their `stage_name` snapshot — they do NOT become invalid.
- If a routing template is updated (new version created), existing jobs retain their original `routing_template_id` and version — they do NOT silently pick up the new stages.
- Options can be deactivated; they cannot be deleted if any active or historical record references them.

### Historical records preserve original labels
Every entity that selects a config option stores both:
1. `{field}_option_id UUID` — FK to the option (for joins when option is still active)
2. `{field}_label TEXT` — snapshot of the label at the time of selection (for display when option is renamed/deactivated)

Example: `production_job_stages.stage_config_option_id` and `production_job_stages.stage_name` (snapshot).

### System defaults cannot be overwritten
System options (`is_system = TRUE`) cannot be edited or deleted. Tenants can deactivate them (which creates a tenant-override row with `is_active = FALSE`).

---

## 9. Acceptance Criteria

| # | Criterion |
|---|---|
| 1 | New company receives correct defaults for selected industry type |
| 2 | Tenant admin can add their own permitted option |
| 3 | Tenant admin can deactivate a system-seeded option |
| 4 | One tenant's changes do NOT affect another tenant (RLS test) |
| 5 | Active records cannot have their stage/category silently changed |
| 6 | Historical records preserve original label even after rename |
| 7 | Production jobs retain the routing template version applied at creation |
| 8 | Mandatory stage inspections cannot be bypassed |
| 9 | Unauthorised role receives 403 when attempting config change |
| 10 | Config changes generate audit events |
| 11 | Frontend dropdowns load correctly from config API |
| 12 | Deactivated options are hidden from new selection but visible in history |

---

## 10. Stories (EPIC-18)

EPIC-18 — Configurable Business Values is added in v1.1.1. See `MASTER_USER_STORY_BACKLOG.csv` for EPIC-18-S001 through EPIC-18-S006.
