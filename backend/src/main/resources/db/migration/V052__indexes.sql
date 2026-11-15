-- V052: Performance indexes on high-traffic query paths
-- Only creates indexes NOT already present in table-creation migrations.

-- Core lookup
CREATE INDEX idx_items_tenant_code          ON items(tenant_id, code);
CREATE INDEX idx_items_tenant_active        ON items(tenant_id, is_active);
CREATE INDEX idx_customers_tenant_code      ON customers(tenant_id, code);
CREATE INDEX idx_suppliers_tenant_code      ON suppliers(tenant_id, code);
CREATE INDEX IF NOT EXISTS idx_app_users_firebase_uid_bare ON app_users(firebase_uid);
CREATE INDEX idx_app_users_tenant_email     ON app_users(tenant_id, email);

-- Inventory — stock_movements date scan (lot index already in V016)
CREATE INDEX idx_stock_movements_date       ON stock_movements(tenant_id, created_at DESC);
CREATE INDEX idx_stock_reservations_job     ON stock_reservation_records(tenant_id, reference_id)
                                              WHERE reference_type = 'production_job';
CREATE INDEX idx_stock_reservations_item    ON stock_reservation_records(tenant_id, item_id);

-- Procurement
CREATE INDEX idx_rfqs_tenant_status         ON rfqs(tenant_id, status, created_at DESC);
CREATE INDEX idx_spos_tenant_status         ON spos(tenant_id, status, created_at DESC);
CREATE INDEX idx_grns_tenant_status         ON grns(tenant_id, status, received_date DESC);
CREATE INDEX idx_grns_spo                   ON grns(tenant_id, spo_id);

-- Sales / Dispatch
CREATE INDEX idx_cpos_tenant_status         ON cpos(tenant_id, status, created_at DESC);
CREATE INDEX idx_cpos_customer              ON cpos(tenant_id, customer_id);
CREATE INDEX idx_dispatches_tenant_status   ON dispatches(tenant_id, status, dispatch_date DESC);
CREATE INDEX idx_dispatches_cpo             ON dispatches(tenant_id, cpo_id);

-- Production
CREATE INDEX idx_prod_jobs_tenant_status    ON production_jobs(tenant_id, status, created_at DESC);
CREATE INDEX idx_prod_jobs_item             ON production_jobs(tenant_id, finished_item_id);
CREATE INDEX IF NOT EXISTS idx_job_stages_job ON job_stages(tenant_id, job_id, seq_no);

-- Invoicing
CREATE INDEX idx_invoices_tenant_status     ON invoices(tenant_id, status, invoice_date DESC);
CREATE INDEX idx_invoices_customer          ON invoices(tenant_id, customer_id);
CREATE INDEX idx_invoices_gst_reg           ON invoices(tenant_id, gst_reg_id);
CREATE INDEX idx_receipts_tenant_date       ON receipts(tenant_id, payment_date DESC);

-- Quality
CREATE INDEX idx_quality_insp_tenant_status ON quality_inspections(tenant_id, status, created_at DESC);
CREATE INDEX idx_ncrs_tenant_status         ON ncrs(tenant_id, status, created_at DESC);

-- Audit (high-write table — additional composite)
CREATE INDEX IF NOT EXISTS idx_audit_events_entity ON audit_events(tenant_id, entity_type, entity_id);
CREATE INDEX idx_audit_events_actor         ON audit_events(tenant_id, actor_id, created_at DESC);

-- Approvals / Notifications
CREATE INDEX idx_approval_requests_entity   ON approval_requests(tenant_id, entity_type, entity_id);
CREATE INDEX idx_notifications_user_read    ON notifications(tenant_id, user_id, is_read, created_at DESC);
