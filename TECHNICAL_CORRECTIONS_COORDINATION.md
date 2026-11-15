# Technical Corrections & Coordination Report: Master Data & Migrations

**Author:** Antigravity Agent  
**Date:** November 15, 2026  
**Integration Branch:** `claude/affectionate-cori-xt092x`  
**Working Branch:** `antigravity/master-data`  
**Assigned Issues:** #27, #28, #29, #30  

---

## 1. Overview & Policy Compliance
In strict adherence to **Rule 9** ("Do not modify Shared Platform files, Flyway migrations, CI or infrastructure. If a database or API contract correction is needed, document it and coordinate rather than modifying shared files"), **no shared platform files, production Flyway migrations (`src/main/resources/db/migration/*`), CI scripts, or infrastructure definitions were altered**.

All Master Data backend implementations, domain logic, persistence layers, and event publishers were cleanly created inside the isolated domain package `in.boss.masterdata.*`.

This document records the exact schema and API contract discrepancies discovered during development and testing, providing recommended remediations for coordination with Ranjith.

---

## 2. Production Flyway Migration Discrepancies (V051–V060)

The baseline commit `295022c` introduced migrations V051–V060 that fail on a clean PostgreSQL database due to column name mismatches against earlier migrations (V001–V047):

| Migration | Statement / Location | Error Encountered | Root Cause & Recommended Fix |
|---|---|---|---|
| **V051** | `ALTER TABLE idempotency_keys ENABLE ROW LEVEL SECURITY;` <br> `CREATE POLICY idempotency_keys_isolation ON idempotency_keys USING (tenant_id = boss_current_tenant_id());` | `ERROR: column "tenant_id" does not exist` | Per `06_DATA_MODEL/physical-schema.md`, `idempotency_keys` is explicitly listed under "Tables intentionally WITHOUT RLS" (platform-wide deduplication). Remove RLS policy on `idempotency_keys`. |
| **V052** | `CREATE INDEX idx_items_code ON items(tenant_id, item_code);` | `ERROR: column "item_code" does not exist` | In V011, the column name is `code`, not `item_code`. Fix index to: `idx_items_code ON items(tenant_id, code)`. |
| **V052** | `CREATE INDEX idx_customers_code ON customers(tenant_id, customer_code);` | `ERROR: column "customer_code" does not exist` | In V012, column is `code`. Fix index to: `idx_customers_code ON customers(tenant_id, code)`. |
| **V052** | `CREATE INDEX idx_suppliers_code ON suppliers(tenant_id, supplier_code);` | `ERROR: column "supplier_code" does not exist` | In V013, column is `code`. Fix index to: `idx_suppliers_code ON suppliers(tenant_id, code)`. |
| **V052** | `CREATE INDEX idx_stock_movements_date ON stock_movements(tenant_id, movement_date);` | `ERROR: column "movement_date" does not exist` | In V016, column is `created_at`. Fix index to: `idx_stock_movements_date ON stock_movements(tenant_id, created_at)`. |
| **V052** | `CREATE INDEX idx_stock_res_job ON stock_reservation_records(job_id);` | `ERROR: column "job_id" does not exist` | In V017, column is `reference_id`. Fix index to: `idx_stock_res_job ON stock_reservation_records(reference_id)`. |
| **V052** | `CREATE INDEX idx_grns_date ON grns(tenant_id, grn_date);` | `ERROR: column "grn_date" does not exist` | In V024, column is `received_date`. Fix index to: `idx_grns_date ON grns(tenant_id, received_date)`. |
| **V052** | `CREATE INDEX idx_production_jobs_item ON production_jobs(tenant_id, item_id);` | `ERROR: column "item_id" does not exist` | In V025, column is `finished_item_id`. Fix index to: `idx_production_jobs_item ON production_jobs(tenant_id, finished_item_id)`. |
| **V052** | `CREATE INDEX idx_invoices_legal_entity ON invoices(tenant_id, legal_entity_id);` | `ERROR: column "legal_entity_id" does not exist` | In V031, column is `gst_reg_id`. Fix index to: `idx_invoices_legal_entity ON invoices(tenant_id, gst_reg_id)`. |
| **V052** | `CREATE INDEX idx_receipts_date ON receipts(tenant_id, receipt_date);` | `ERROR: column "receipt_date" does not exist` | In V032, column is `payment_date`. Fix index to: `idx_receipts_date ON receipts(tenant_id, payment_date)`. |
| **V052** | `CREATE INDEX idx_app_users_firebase_uid ON app_users(firebase_uid);` | `ERROR: relation "idx_app_users_firebase_uid" already exists` | V006 already created this exact index. Add `IF NOT EXISTS` or remove redundant statement. |
| **V053** | `ALTER TABLE material_lots ADD CONSTRAINT chk_material_lots_qty CHECK (quantity_on_hand >= 0);` | `ERROR: column "quantity_on_hand" does not exist` | In V016, column is `quantity`. Fix constraint column to `quantity`. |
| **V053** | `ALTER TABLE uom_conversions ADD CONSTRAINT chk_uom_factor CHECK (conversion_factor > 0);` | `ERROR: column "conversion_factor" does not exist` | In V009, column is `factor`. Fix constraint column to `factor`. |
| **V053** | `ALTER TABLE spo_lines ADD CONSTRAINT chk_spo_line_qty CHECK (ordered_qty > 0);` | `ERROR: column "ordered_qty" does not exist` | In V023, column is `quantity`. Fix constraint column to `quantity`. |
| **V054** | `INSERT INTO units_of_measure (tenant_id, name, code, uom_type, is_base_unit) ...` | `ERROR: column "code" does not exist` | In V009, columns are `symbol` (not `code`) and `is_base` (not `is_base_unit`); check constraint requires `quantity` (not `count`); tenant FK requires an active tenant from `tenant_registry`. |
| **V055** | `INSERT INTO hsn_codes (code, description, gst_rate) VALUES ... ON CONFLICT (hsn_code) DO NOTHING;` | `ERROR: column "hsn_code" does not exist` | In V010, the column is `code` (not `hsn_code`). Fix to: `ON CONFLICT (code) DO NOTHING;`. |

---

## 3. OpenAPI Contract Coordination (`master-data.yaml`)

1. **Suppliers Endpoints:**
   - In `10_API_CONTRACTS/openapi/master-data.yaml`, `/suppliers` (GET, POST) was documented, but individual resource operations `/suppliers/{supplierId}` (GET, PATCH, DELETE) were omitted from the paths object.
   - However, Backlog issue #30 and the Definition of Done require full CRUD support for Suppliers (create, view, edit, soft delete with contact preservation).
   - **Resolution:** `in.boss.masterdata.api.SupplierController` implements `GET /api/v1/suppliers/{supplierId}`, `PATCH /api/v1/suppliers/{supplierId}`, and `DELETE /api/v1/suppliers/{supplierId}` with full security, validation, and audit recording. It is recommended to update `master-data.yaml` to include these paths.

2. **BOM Status Mapping:**
   - OpenAPI schema `BOMHeader` defines `status` enum as `[draft, active, superseded]`.
   - PostgreSQL schema V014 defines `bom_headers.status` check constraint as `('draft', 'approved', 'superseded', 'archived')`.
   - **Resolution:** The domain layer maps database `approved` to API `active`, and vice-versa, preserving DB schema constraints while adhering strictly to the external OpenAPI contract.

3. **Item Type Vocabulary:**
   - OpenAPI schema defines: `[RAW_MATERIAL, CONSUMABLE, FINISHED_GOOD, SERVICE, SPARE_PART, SEMI_FINISHED]`.
   - PostgreSQL check constraint defines: `('raw_material', 'semi_finished', 'finished_goods', 'consumable', 'service', 'tool', 'asset', 'other')`.
   - **Resolution:** Bidirectional conversion between uppercase API representations and snake_case database constraints is handled seamlessly in `ItemServiceImpl`.

4. **Item Category Store:**
   - Per Requirement 3, `item_categories` (V045) is the authoritative store.
   - Exposed endpoint `GET /api/v1/config/options/ITEM_CATEGORY` dynamically reads from `item_categories` for full backward compatibility with dropdown options while guaranteeing zero duplicate category models.

---

## 4. Test Evidence & Validation
The test suite consists of 38 automated tests executing in JUnit 5 against real PostgreSQL 15 containers:
- **Unit Tests (15):** BOM explosion calculation, cycle guard detection, circular reference rejection, max 5 level enforcement, GSTIN/PAN regex validation, item lifecycle dependency enforcement.
- **Integration Tests (23):** Item CRUD, UOM CRUD, BOM creation & revision, Customer CRUD, Supplier CRUD, Category CRUD, multi-tenant isolation verification, API RBAC security verification.
- **Results:** 38 passed, 0 failed, 0 skipped.
