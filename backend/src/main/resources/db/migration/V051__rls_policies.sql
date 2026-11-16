-- V051: Row-Level Security policies on all tenant-scoped tables (issue #13)
-- Pattern: USING (tenant_id = current_setting('app.current_tenant_id', true)::uuid)
-- Empty/missing setting returns no rows (secure default, never an error).

-- Helper: returns current tenant UUID or null (not error) when not set
CREATE OR REPLACE FUNCTION boss_current_tenant_id()
RETURNS UUID LANGUAGE plpgsql STABLE AS $$
DECLARE
  v TEXT;
BEGIN
  v := current_setting('app.current_tenant_id', true);
  IF v IS NULL OR v = '' THEN RETURN NULL; END IF;
  RETURN v::uuid;
END;
$$;

-- ============================================================
-- Enable RLS and create policies — one block per table group
-- ============================================================

-- tenant_registry (tenants see only themselves via platform filter)
ALTER TABLE tenant_registry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_registry_isolation ON tenant_registry
  USING (id = boss_current_tenant_id());

-- tenant_entitlements
ALTER TABLE tenant_entitlements ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_entitlements_isolation ON tenant_entitlements
  USING (tenant_id = boss_current_tenant_id());

-- app_users / user_roles / invite_tokens
ALTER TABLE app_users ENABLE ROW LEVEL SECURITY;
CREATE POLICY app_users_isolation ON app_users
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE user_roles ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_roles_isolation ON user_roles
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE invite_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY invite_tokens_isolation ON invite_tokens
  USING (tenant_id = boss_current_tenant_id());

-- audit_events
ALTER TABLE audit_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY audit_events_isolation ON audit_events
  USING (tenant_id = boss_current_tenant_id());

-- outbox_events (tenant_id nullable — platform events have NULL; RLS allows both)
ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY outbox_events_isolation ON outbox_events
  USING (tenant_id IS NULL OR tenant_id = boss_current_tenant_id());

-- idempotency_keys: RLS is applied in V063 after tenant_id column is added.

-- legal_entities / gst_registrations
ALTER TABLE legal_entities ENABLE ROW LEVEL SECURITY;
CREATE POLICY legal_entities_isolation ON legal_entities
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE gst_registrations ENABLE ROW LEVEL SECURITY;
CREATE POLICY gst_registrations_isolation ON gst_registrations
  USING (tenant_id = boss_current_tenant_id());

-- physical_sites / stores
ALTER TABLE physical_sites ENABLE ROW LEVEL SECURITY;
CREATE POLICY physical_sites_isolation ON physical_sites
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE stores ENABLE ROW LEVEL SECURITY;
CREATE POLICY stores_isolation ON stores
  USING (tenant_id = boss_current_tenant_id());

-- units_of_measure / uom_conversions (tenant-scoped; global UOMs use platform tenant)
ALTER TABLE units_of_measure ENABLE ROW LEVEL SECURITY;
CREATE POLICY uom_isolation ON units_of_measure
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE uom_conversions ENABLE ROW LEVEL SECURITY;
CREATE POLICY uom_conversions_isolation ON uom_conversions
  USING (tenant_id = boss_current_tenant_id());

-- items / item_categories
ALTER TABLE items ENABLE ROW LEVEL SECURITY;
CREATE POLICY items_isolation ON items
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE item_categories ENABLE ROW LEVEL SECURITY;
CREATE POLICY item_categories_isolation ON item_categories
  USING (tenant_id = boss_current_tenant_id());

-- customers / customer_contacts
ALTER TABLE customers ENABLE ROW LEVEL SECURITY;
CREATE POLICY customers_isolation ON customers
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE customer_contacts ENABLE ROW LEVEL SECURITY;
CREATE POLICY customer_contacts_isolation ON customer_contacts
  USING (tenant_id = boss_current_tenant_id());

-- suppliers / supplier_contacts / supplier_performance_records
ALTER TABLE suppliers ENABLE ROW LEVEL SECURITY;
CREATE POLICY suppliers_isolation ON suppliers
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE supplier_contacts ENABLE ROW LEVEL SECURITY;
CREATE POLICY supplier_contacts_isolation ON supplier_contacts
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE supplier_performance_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY supplier_perf_isolation ON supplier_performance_records
  USING (tenant_id = boss_current_tenant_id());

-- bom_headers / bom_lines
ALTER TABLE bom_headers ENABLE ROW LEVEL SECURITY;
CREATE POLICY bom_headers_isolation ON bom_headers
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE bom_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY bom_lines_isolation ON bom_lines
  USING (tenant_id = boss_current_tenant_id());

-- routing_templates / routing_operations / routing_template_stages
ALTER TABLE routing_templates ENABLE ROW LEVEL SECURITY;
CREATE POLICY routing_templates_isolation ON routing_templates
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE routing_operations ENABLE ROW LEVEL SECURITY;
CREATE POLICY routing_operations_isolation ON routing_operations
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE routing_template_stages ENABLE ROW LEVEL SECURITY;
CREATE POLICY routing_template_stages_isolation ON routing_template_stages
  USING (tenant_id = boss_current_tenant_id());

-- material_lots / stock_movements / stock_reservation_records
ALTER TABLE material_lots ENABLE ROW LEVEL SECURITY;
CREATE POLICY material_lots_isolation ON material_lots
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE stock_movements ENABLE ROW LEVEL SECURITY;
CREATE POLICY stock_movements_isolation ON stock_movements
  USING (tenant_id = boss_current_tenant_id());

ALTER TABLE stock_reservation_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY stock_reservations_isolation ON stock_reservation_records
  USING (tenant_id = boss_current_tenant_id());

-- RFQs / quotations / CPOs / PRs
ALTER TABLE rfqs ENABLE ROW LEVEL SECURITY;
CREATE POLICY rfqs_isolation ON rfqs USING (tenant_id = boss_current_tenant_id());

ALTER TABLE rfq_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY rfq_lines_isolation ON rfq_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE quotations ENABLE ROW LEVEL SECURITY;
CREATE POLICY quotations_isolation ON quotations USING (tenant_id = boss_current_tenant_id());

ALTER TABLE quotation_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY quotation_lines_isolation ON quotation_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE quotation_revisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY quotation_revisions_isolation ON quotation_revisions USING (tenant_id = boss_current_tenant_id());

ALTER TABLE cpos ENABLE ROW LEVEL SECURITY;
CREATE POLICY cpos_isolation ON cpos USING (tenant_id = boss_current_tenant_id());

ALTER TABLE cpo_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY cpo_lines_isolation ON cpo_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE purchase_requisitions ENABLE ROW LEVEL SECURITY;
CREATE POLICY pr_isolation ON purchase_requisitions USING (tenant_id = boss_current_tenant_id());

ALTER TABLE pr_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY pr_lines_isolation ON pr_lines USING (tenant_id = boss_current_tenant_id());

-- Supplier quotations / SPOs / GRNs
ALTER TABLE supplier_quotations ENABLE ROW LEVEL SECURITY;
CREATE POLICY sq_isolation ON supplier_quotations USING (tenant_id = boss_current_tenant_id());

ALTER TABLE supplier_quotation_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY sq_lines_isolation ON supplier_quotation_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE spos ENABLE ROW LEVEL SECURITY;
CREATE POLICY spos_isolation ON spos USING (tenant_id = boss_current_tenant_id());

ALTER TABLE spo_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY spo_lines_isolation ON spo_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE grns ENABLE ROW LEVEL SECURITY;
CREATE POLICY grns_isolation ON grns USING (tenant_id = boss_current_tenant_id());

ALTER TABLE grn_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY grn_lines_isolation ON grn_lines USING (tenant_id = boss_current_tenant_id());

-- Production
ALTER TABLE production_jobs ENABLE ROW LEVEL SECURITY;
CREATE POLICY production_jobs_isolation ON production_jobs USING (tenant_id = boss_current_tenant_id());

ALTER TABLE job_stages ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_stages_isolation ON job_stages USING (tenant_id = boss_current_tenant_id());

ALTER TABLE job_material_requirements ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_mat_req_isolation ON job_material_requirements USING (tenant_id = boss_current_tenant_id());

ALTER TABLE job_material_issues ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_mat_issues_isolation ON job_material_issues USING (tenant_id = boss_current_tenant_id());

-- Quality
ALTER TABLE quality_inspections ENABLE ROW LEVEL SECURITY;
CREATE POLICY quality_insp_isolation ON quality_inspections USING (tenant_id = boss_current_tenant_id());

ALTER TABLE inspection_parameters ENABLE ROW LEVEL SECURITY;
CREATE POLICY insp_params_isolation ON inspection_parameters USING (tenant_id = boss_current_tenant_id());

ALTER TABLE inspection_parameter_templates ENABLE ROW LEVEL SECURITY;
CREATE POLICY insp_tmpl_isolation ON inspection_parameter_templates USING (tenant_id = boss_current_tenant_id());

ALTER TABLE ncrs ENABLE ROW LEVEL SECURITY;
CREATE POLICY ncrs_isolation ON ncrs USING (tenant_id = boss_current_tenant_id());

ALTER TABLE concessions ENABLE ROW LEVEL SECURITY;
CREATE POLICY concessions_isolation ON concessions USING (tenant_id = boss_current_tenant_id());

-- Dispatch / Invoicing
ALTER TABLE dispatches ENABLE ROW LEVEL SECURITY;
CREATE POLICY dispatches_isolation ON dispatches USING (tenant_id = boss_current_tenant_id());

ALTER TABLE dispatch_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY dispatch_lines_isolation ON dispatch_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE pod_records ENABLE ROW LEVEL SECURITY;
CREATE POLICY pod_records_isolation ON pod_records USING (tenant_id = boss_current_tenant_id());

ALTER TABLE packing_specifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY packing_specs_isolation ON packing_specifications USING (tenant_id = boss_current_tenant_id());

ALTER TABLE invoice_sequence_counters ENABLE ROW LEVEL SECURITY;
CREATE POLICY inv_seq_isolation ON invoice_sequence_counters USING (tenant_id = boss_current_tenant_id());

ALTER TABLE invoices ENABLE ROW LEVEL SECURITY;
CREATE POLICY invoices_isolation ON invoices USING (tenant_id = boss_current_tenant_id());

ALTER TABLE invoice_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY invoice_lines_isolation ON invoice_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE receipts ENABLE ROW LEVEL SECURITY;
CREATE POLICY receipts_isolation ON receipts USING (tenant_id = boss_current_tenant_id());

ALTER TABLE receipt_allocations ENABLE ROW LEVEL SECURITY;
CREATE POLICY receipt_alloc_isolation ON receipt_allocations USING (tenant_id = boss_current_tenant_id());

ALTER TABLE supplier_bills ENABLE ROW LEVEL SECURITY;
CREATE POLICY supplier_bills_isolation ON supplier_bills USING (tenant_id = boss_current_tenant_id());

ALTER TABLE supplier_payments ENABLE ROW LEVEL SECURITY;
CREATE POLICY supplier_payments_isolation ON supplier_payments USING (tenant_id = boss_current_tenant_id());

-- Procurement extras
ALTER TABLE frame_agreements ENABLE ROW LEVEL SECURITY;
CREATE POLICY frame_agreements_isolation ON frame_agreements USING (tenant_id = boss_current_tenant_id());

ALTER TABLE frame_agreement_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY frame_agreement_lines_isolation ON frame_agreement_lines USING (tenant_id = boss_current_tenant_id());

ALTER TABLE reorder_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY reorder_policies_isolation ON reorder_policies USING (tenant_id = boss_current_tenant_id());

ALTER TABLE replenishment_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY replenishment_requests_isolation ON replenishment_requests USING (tenant_id = boss_current_tenant_id());

ALTER TABLE inter_site_transfers ENABLE ROW LEVEL SECURITY;
CREATE POLICY ist_isolation ON inter_site_transfers USING (tenant_id = boss_current_tenant_id());

ALTER TABLE inter_site_transfer_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY ist_lines_isolation ON inter_site_transfer_lines USING (tenant_id = boss_current_tenant_id());

-- Assets / Maintenance
ALTER TABLE assets ENABLE ROW LEVEL SECURITY;
CREATE POLICY assets_isolation ON assets USING (tenant_id = boss_current_tenant_id());

ALTER TABLE asset_locations ENABLE ROW LEVEL SECURITY;
CREATE POLICY asset_locations_isolation ON asset_locations USING (tenant_id = boss_current_tenant_id());

ALTER TABLE asset_type_configs ENABLE ROW LEVEL SECURITY;
CREATE POLICY asset_type_configs_isolation ON asset_type_configs USING (tenant_id = boss_current_tenant_id());

ALTER TABLE maintenance_schedules ENABLE ROW LEVEL SECURITY;
CREATE POLICY maintenance_schedules_isolation ON maintenance_schedules USING (tenant_id = boss_current_tenant_id());

ALTER TABLE maintenance_work_orders ENABLE ROW LEVEL SECURITY;
CREATE POLICY maintenance_wo_isolation ON maintenance_work_orders USING (tenant_id = boss_current_tenant_id());

-- Job costing
ALTER TABLE job_costing_headers ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_costing_isolation ON job_costing_headers USING (tenant_id = boss_current_tenant_id());

ALTER TABLE job_costing_lines ENABLE ROW LEVEL SECURITY;
CREATE POLICY job_costing_lines_isolation ON job_costing_lines USING (tenant_id = boss_current_tenant_id());

-- Company profile / notifications
ALTER TABLE company_profiles ENABLE ROW LEVEL SECURITY;
CREATE POLICY company_profiles_isolation ON company_profiles USING (tenant_id = boss_current_tenant_id());

ALTER TABLE company_profile_sections ENABLE ROW LEVEL SECURITY;
CREATE POLICY company_profile_sections_isolation ON company_profile_sections USING (tenant_id = boss_current_tenant_id());

ALTER TABLE notifications ENABLE ROW LEVEL SECURITY;
CREATE POLICY notifications_isolation ON notifications USING (tenant_id = boss_current_tenant_id());

ALTER TABLE notification_preferences ENABLE ROW LEVEL SECURITY;
CREATE POLICY notification_prefs_isolation ON notification_preferences USING (tenant_id = boss_current_tenant_id());

ALTER TABLE approval_requests ENABLE ROW LEVEL SECURITY;
CREATE POLICY approval_requests_isolation ON approval_requests USING (tenant_id = boss_current_tenant_id());

-- Tenant config options / history
ALTER TABLE tenant_config_options ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_config_opts_isolation ON tenant_config_options USING (tenant_id = boss_current_tenant_id());

ALTER TABLE tenant_config_option_history ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_config_hist_isolation ON tenant_config_option_history USING (tenant_id = boss_current_tenant_id());

-- ============================================================
-- Tables intentionally WITHOUT RLS (platform-wide reference data)
-- ============================================================
-- hsn_codes          — global reference, no tenant_id
-- config_categories  — global reference, no tenant_id
-- platform_admin_users / platform_audit_events — admin API, separate auth
-- customer_groups    — platform-managed
