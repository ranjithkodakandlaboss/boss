-- V052: Performance indexes on high-traffic query paths
-- Tenant + status/date composite indexes for list/filter queries

-- Core lookup patterns
CREATE INDEX IF NOT EXISTS idx_items_tenant_code          ON items(tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_items_tenant_active        ON items(tenant_id, is_active);
CREATE INDEX IF NOT EXISTS idx_customers_tenant_code      ON customers(tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_suppliers_tenant_code      ON suppliers(tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_app_users_firebase_uid     ON app_users(firebase_uid);
CREATE INDEX IF NOT EXISTS idx_app_users_tenant_email     ON app_users(tenant_id, email);

-- Inventory
CREATE INDEX IF NOT EXISTS idx_material_lots_item         ON material_lots(tenant_id, item_id, store_id);
CREATE INDEX IF NOT EXISTS idx_stock_movements_lot        ON stock_movements(tenant_id, lot_id, created_at);
CREATE INDEX IF NOT EXISTS idx_stock_movements_date       ON stock_movements(tenant_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_stock_reservations_job     ON stock_reservation_records(tenant_id, reference_id);
CREATE INDEX IF NOT EXISTS idx_stock_reservations_item    ON stock_reservation_records(tenant_id, item_id);

-- Procurement
CREATE INDEX IF NOT EXISTS idx_rfqs_tenant_status         ON rfqs(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_spos_tenant_status         ON spos(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_spos_supplier              ON spos(tenant_id, supplier_id);
CREATE INDEX IF NOT EXISTS idx_grns_tenant_status         ON grns(tenant_id, status, received_date DESC);
CREATE INDEX IF NOT EXISTS idx_grns_spo                   ON grns(tenant_id, spo_id);

-- Sales / Dispatch
CREATE INDEX IF NOT EXISTS idx_cpos_tenant_status         ON cpos(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cpos_customer              ON cpos(tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_dispatches_tenant_status   ON dispatches(tenant_id, status, dispatch_date DESC);
CREATE INDEX IF NOT EXISTS idx_dispatches_cpo             ON dispatches(tenant_id, cpo_id);

-- Production
CREATE INDEX IF NOT EXISTS idx_prod_jobs_tenant_status    ON production_jobs(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_prod_jobs_item             ON production_jobs(tenant_id, finished_item_id);
CREATE INDEX IF NOT EXISTS idx_job_stages_job             ON job_stages(tenant_id, job_id, seq_no);

-- Invoicing
CREATE INDEX IF NOT EXISTS idx_invoices_tenant_status     ON invoices(tenant_id, status, invoice_date DESC);
CREATE INDEX IF NOT EXISTS idx_invoices_customer          ON invoices(tenant_id, customer_id);
CREATE INDEX IF NOT EXISTS idx_invoices_legal_entity      ON invoices(tenant_id, gst_reg_id);
CREATE INDEX IF NOT EXISTS idx_receipts_tenant_date       ON receipts(tenant_id, payment_date DESC);

-- Quality
CREATE INDEX IF NOT EXISTS idx_quality_insp_tenant_status ON quality_inspections(tenant_id, status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_ncrs_tenant_status         ON ncrs(tenant_id, status, created_at DESC);

-- Outbox / Audit (high-write tables)
CREATE INDEX IF NOT EXISTS idx_outbox_status_created      ON outbox_events(status, created_at) WHERE status = 'pending';
CREATE INDEX IF NOT EXISTS idx_audit_events_entity        ON audit_events(tenant_id, entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_audit_events_actor         ON audit_events(tenant_id, actor_id, created_at DESC);

-- Approvals / Notifications
CREATE INDEX IF NOT EXISTS idx_approval_requests_entity   ON approval_requests(tenant_id, entity_type, entity_id);
CREATE INDEX IF NOT EXISTS idx_notifications_user_read    ON notifications(tenant_id, user_id, is_read, created_at DESC);

-- Idempotency
-- idx_idempotency_key already created in V005
