# BOSS Data Model — Database Index Catalogue
**Version:** v1.1.3 | **Date:** 16 November 2026
**Authority:** Platform Core & Shared Database Standards (Ranjith)

This document provides an exhaustive catalogue of database indexes across the BOSS platform. All tenant-scoped indexes are prefixed with `tenant_id` to ensure PostgreSQL index-range scans align with Row Level Security (`WHERE tenant_id = current_setting('app.current_tenant_id')::UUID`) filters.

---

## 1. High-Traffic Query Performance Indexes (`V052__indexes.sql`)

These indexes support high-traffic query paths identified in core domain services, including sorting, status-based list filtering, and entity joins.

| Index Name | Target Table | Columns Indexed | Predicate / Modifier | Query Access Pattern / Domain Service |
|---|---|---|---|---|
| `idx_items_tenant_code` | `items` | `(tenant_id, code)` | - | Fast item code lookup during RFQ, quote, and stock entry (`ItemService.getByCode`) |
| `idx_items_tenant_active` | `items` | `(tenant_id, is_active)` | - | Filtering active catalog items for drop-downs and BOM configuration |
| `idx_customers_tenant_code` | `customers` | `(tenant_id, code)` | - | Customer resolution by business code (`CustomerService.getByCode`) |
| `idx_suppliers_tenant_code` | `suppliers` | `(tenant_id, code)` | - | Supplier resolution by code (`SupplierService.getByCode`) |
| `idx_app_users_firebase_uid_bare` | `app_users` | `(firebase_uid)` | `IF NOT EXISTS` | Firebase JWT authentication filter lookup before tenant resolution (`FirebaseJwtFilter`) |
| `idx_app_users_tenant_email` | `app_users` | `(tenant_id, email)` | - | User lookup by email within tenant scope (`UserService.getByEmail`) |
| `idx_stock_movements_date` | `stock_movements` | `(tenant_id, created_at DESC)` | - | Chronological stock movement ledger reporting and audit scans |
| `idx_stock_reservations_job` | `stock_reservation_records` | `(tenant_id, reference_id)` | `WHERE reference_type = 'production_job'` | Partial index: query reserved inventory allocated to specific production jobs |
| `idx_stock_reservations_item` | `stock_reservation_records` | `(tenant_id, item_id)` | - | Aggregate reserved stock count per item across jobs |
| `idx_rfqs_tenant_status` | `rfqs` | `(tenant_id, status, created_at DESC)` | - | RFQ list view filtered by status sorted by latest first (`RfqService.listByStatus`) |
| `idx_spos_tenant_status` | `spos` | `(tenant_id, status, created_at DESC)` | - | Purchase order management dashboard filtered by status (`SpoService.listByStatus`) |
| `idx_grns_tenant_status` | `grns` | `(tenant_id, status, received_date DESC)` | - | Gate receipt and QA inspection queue filtering (`GrnService.listPendingInspection`) |
| `idx_grns_spo` | `grns` | `(tenant_id, spo_id)` | - | Inward reconciliation against SPO lines (3-way match verification) |
| `idx_cpos_tenant_status` | `cpos` | `(tenant_id, status, created_at DESC)` | - | Sales order backlog tracking filtered by status (`CpoService.listByStatus`) |
| `idx_cpos_customer` | `cpos` | `(tenant_id, customer_id)` | - | Customer purchase order history and credit limit tracking |
| `idx_dispatches_tenant_status` | `dispatches` | `(tenant_id, status, dispatch_date DESC)` | - | Dispatch schedule dashboard sorted by dispatch date |
| `idx_dispatches_cpo` | `dispatches` | `(tenant_id, cpo_id)` | - | Order delivery tracking and fulfillment verification |
| `idx_prod_jobs_tenant_status` | `production_jobs` | `(tenant_id, status, created_at DESC)` | - | Shop floor execution board filtered by job status (`JobService.listActive`) |
| `idx_prod_jobs_item` | `production_jobs` | `(tenant_id, finished_item_id)` | - | Finished goods production history and yield analysis |
| `idx_job_stages_job` | `job_stages` | `(tenant_id, job_id, seq_no)` | `IF NOT EXISTS` | Ordered retrieval of routing operations for active job cards |
| `idx_invoices_tenant_status` | `invoices` | `(tenant_id, status, invoice_date DESC)` | - | Accounts receivable aging and invoice registry |
| `idx_invoices_customer` | `invoices` | `(tenant_id, customer_id)` | - | Customer billing ledger and balance calculations |
| `idx_invoices_gst_reg` | `invoices` | `(tenant_id, gst_reg_id)` | - | GSTR-1 statutory tax reporting per GSTIN registration |
| `idx_receipts_tenant_date` | `receipts` | `(tenant_id, payment_date DESC)` | - | Bank reconciliation and cash receipt journal |
| `idx_quality_insp_tenant_status`| `quality_inspections` | `(tenant_id, status, created_at DESC)` | - | QA pending inspection workbench |
| `idx_ncrs_tenant_status` | `ncrs` | `(tenant_id, status, created_at DESC)` | - | Non-conformance reporting and review dashboard |
| `idx_audit_events_entity` | `audit_events` | `(tenant_id, entity_type, entity_id)` | `IF NOT EXISTS` | Entity-level audit history query (`AuditService.getEntityHistory`) |
| `idx_audit_events_actor` | `audit_events` | `(tenant_id, actor_id, created_at DESC)` | - | User activity monitoring and compliance audit |
| `idx_approval_requests_entity` | `approval_requests` | `(tenant_id, entity_type, entity_id)` | - | Check pending approvals on a specific document (e.g. PR, quotation) |
| `idx_notifications_user_read` | `notifications` | `(tenant_id, user_id, is_read, created_at DESC)`| - | User notification inbox query (unread first, latest first) |

---

## 2. Infrastructure, Concurrency & Middleware Indexes

| Index Name | Target Table | Columns Indexed | Predicate / Purpose |
|---|---|---|---|
| `idx_idempotency_tenant_key` | `idempotency_keys` | `(tenant_id, key)` | Fast lookup in `IdempotencyFilter` across mutations |
| `idx_idempotency_lock_status`| `idempotency_keys` | `(lock_status, created_at)` | Partial index for unlocking / cleaning timed-out in-flight locks (`V065`) |
| `idx_outbox_status_created` | `outbox_events` | `(status, created_at)` | Outbox relay poller polling for `status = 'pending'` ordered by `created_at` |
| `idx_tenant_registry_status` | `tenant_registry` | `(status)` | Platform admin tenant list and status check (`SuspendedTenantInterceptor`) |
| `idx_tenant_registry_slug` | `tenant_registry` | `(slug)` | Subdomain / tenant slug routing |
| `idx_industry_tmpl_type` | `industry_template_options` | `(industry_type, category_code)` | Fast template loading during `seed_tenant_defaults()` execution |

---

## 3. Core Domain & Relational Indexes

### Inventory & Warehouse
- `idx_material_lots_tenant`: `material_lots(tenant_id, item_id, store_id)` — Active stock balance by item and warehouse location.
- `idx_material_lots_status`: `material_lots(tenant_id, status)` — Quarantine vs available lot filtering.
- `idx_stock_movements_lot`: `stock_movements(lot_id) WHERE lot_id IS NOT NULL` — Traceability ledger for a specific batch.
- `idx_stock_reservations_tenant`: `stock_reservation_records(tenant_id, item_id, lot_id)` — Concurrency lock verification.

### Sales & Quotations
- `idx_quotations_tenant`: `quotations(tenant_id, customer_id)` — Quotation history per customer.
- `idx_quotations_status`: `quotations(tenant_id, status)` — Active sales pipeline tracking.
- `idx_quotation_lines_quote`: `quotation_lines(tenant_id, quotation_id)` — Fast quotation detail line retrieval.
- `idx_cpo_lines_cpo`: `cpo_lines(tenant_id, cpo_id)` — Sales order detail lines.

### Procurement & Invoicing
- `idx_prs_tenant`: `purchase_requisitions(tenant_id)` — Tenant requisition list.
- `idx_prs_status`: `purchase_requisitions(tenant_id, status)` — Pending PR approval queue.
- `idx_pr_lines_pr`: `pr_lines(tenant_id, pr_id)` — Lines belonging to purchase requisition.
- `idx_spos_tenant`: `spos(tenant_id, supplier_id)` — Inbound POs by supplier.
- `idx_spo_lines_spo`: `spo_lines(tenant_id, spo_id)` — Purchase order detail lines.
- `idx_supplier_bills_tenant`: `supplier_bills(tenant_id, supplier_id)` — Accounts payable ledger by vendor.
- `idx_inv_seq_tenant`: `invoice_sequence_counters(tenant_id, gst_reg_id)` — Pessimistic locking for gapless invoice numbering.

---

## 4. Index Maintenance Standards

1. **Prefix with `tenant_id`:** Every tenant-scoped table index MUST have `tenant_id` as the leading column. This satisfies PostgreSQL composite index efficiency when combined with RLS filters.
2. **Naming Convention:** `idx_{table}_{columns}` (e.g., `idx_invoices_customer`). For partial indexes, describe the filter (e.g., `idx_stock_reservations_job`).
3. **No Speculative Indexes:** Indexes are introduced only based on verified query access patterns in domain repositories, join clauses, or foreign keys.
4. **Idempotent Flyway Migrations:** All index creation DDL statements should be placed in sequential migration files.
