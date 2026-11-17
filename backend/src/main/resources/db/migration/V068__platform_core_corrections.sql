-- V068: Platform Core Corrections
-- 1. FORCE ROW LEVEL SECURITY on all tenant-scoped tables (#13)
-- 2. Add is_system to item_categories (#88, #98)
-- 3. Designate item_categories as authoritative DDL store in config_categories (#98)
-- 4. Re-enforce user_roles.role CHECK constraint excluding PLATFORM_ADMIN (#97)
-- 5. Define seed_tenant_defaults(p_tenant_id, p_industry_type) PostgreSQL function (#88, #91)

-- ============================================================
-- 1. FORCE ROW LEVEL SECURITY on all tenant-scoped tables
-- ============================================================
ALTER TABLE tenant_registry FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_entitlements FORCE ROW LEVEL SECURITY;
ALTER TABLE app_users FORCE ROW LEVEL SECURITY;
ALTER TABLE user_roles FORCE ROW LEVEL SECURITY;
ALTER TABLE invite_tokens FORCE ROW LEVEL SECURITY;
ALTER TABLE audit_events FORCE ROW LEVEL SECURITY;
ALTER TABLE outbox_events FORCE ROW LEVEL SECURITY;
ALTER TABLE legal_entities FORCE ROW LEVEL SECURITY;
ALTER TABLE gst_registrations FORCE ROW LEVEL SECURITY;
ALTER TABLE physical_sites FORCE ROW LEVEL SECURITY;
ALTER TABLE stores FORCE ROW LEVEL SECURITY;
ALTER TABLE units_of_measure FORCE ROW LEVEL SECURITY;
ALTER TABLE uom_conversions FORCE ROW LEVEL SECURITY;
ALTER TABLE items FORCE ROW LEVEL SECURITY;
ALTER TABLE item_categories FORCE ROW LEVEL SECURITY;
ALTER TABLE customers FORCE ROW LEVEL SECURITY;
ALTER TABLE customer_contacts FORCE ROW LEVEL SECURITY;
ALTER TABLE suppliers FORCE ROW LEVEL SECURITY;
ALTER TABLE supplier_contacts FORCE ROW LEVEL SECURITY;
ALTER TABLE supplier_performance_records FORCE ROW LEVEL SECURITY;
ALTER TABLE bom_headers FORCE ROW LEVEL SECURITY;
ALTER TABLE bom_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE routing_templates FORCE ROW LEVEL SECURITY;
ALTER TABLE routing_operations FORCE ROW LEVEL SECURITY;
ALTER TABLE routing_template_stages FORCE ROW LEVEL SECURITY;
ALTER TABLE material_lots FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_movements FORCE ROW LEVEL SECURITY;
ALTER TABLE stock_reservation_records FORCE ROW LEVEL SECURITY;
ALTER TABLE rfqs FORCE ROW LEVEL SECURITY;
ALTER TABLE rfq_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE quotations FORCE ROW LEVEL SECURITY;
ALTER TABLE quotation_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE quotation_revisions FORCE ROW LEVEL SECURITY;
ALTER TABLE cpos FORCE ROW LEVEL SECURITY;
ALTER TABLE cpo_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE purchase_requisitions FORCE ROW LEVEL SECURITY;
ALTER TABLE pr_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE supplier_quotations FORCE ROW LEVEL SECURITY;
ALTER TABLE supplier_quotation_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE spos FORCE ROW LEVEL SECURITY;
ALTER TABLE spo_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE grns FORCE ROW LEVEL SECURITY;
ALTER TABLE grn_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE production_jobs FORCE ROW LEVEL SECURITY;
ALTER TABLE job_stages FORCE ROW LEVEL SECURITY;
ALTER TABLE job_material_requirements FORCE ROW LEVEL SECURITY;
ALTER TABLE job_material_issues FORCE ROW LEVEL SECURITY;
ALTER TABLE quality_inspections FORCE ROW LEVEL SECURITY;
ALTER TABLE inspection_parameters FORCE ROW LEVEL SECURITY;
ALTER TABLE inspection_parameter_templates FORCE ROW LEVEL SECURITY;
ALTER TABLE ncrs FORCE ROW LEVEL SECURITY;
ALTER TABLE concessions FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatches FORCE ROW LEVEL SECURITY;
ALTER TABLE dispatch_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE pod_records FORCE ROW LEVEL SECURITY;
ALTER TABLE packing_specifications FORCE ROW LEVEL SECURITY;
ALTER TABLE invoice_sequence_counters FORCE ROW LEVEL SECURITY;
ALTER TABLE invoices FORCE ROW LEVEL SECURITY;
ALTER TABLE invoice_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE receipts FORCE ROW LEVEL SECURITY;
ALTER TABLE receipt_allocations FORCE ROW LEVEL SECURITY;
ALTER TABLE supplier_bills FORCE ROW LEVEL SECURITY;
ALTER TABLE supplier_payments FORCE ROW LEVEL SECURITY;
ALTER TABLE frame_agreements FORCE ROW LEVEL SECURITY;
ALTER TABLE frame_agreement_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE reorder_policies FORCE ROW LEVEL SECURITY;
ALTER TABLE replenishment_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE inter_site_transfers FORCE ROW LEVEL SECURITY;
ALTER TABLE inter_site_transfer_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE assets FORCE ROW LEVEL SECURITY;
ALTER TABLE asset_locations FORCE ROW LEVEL SECURITY;
ALTER TABLE asset_type_configs FORCE ROW LEVEL SECURITY;
ALTER TABLE maintenance_schedules FORCE ROW LEVEL SECURITY;
ALTER TABLE maintenance_work_orders FORCE ROW LEVEL SECURITY;
ALTER TABLE job_costing_headers FORCE ROW LEVEL SECURITY;
ALTER TABLE job_costing_lines FORCE ROW LEVEL SECURITY;
ALTER TABLE company_profiles FORCE ROW LEVEL SECURITY;
ALTER TABLE company_profile_sections FORCE ROW LEVEL SECURITY;
ALTER TABLE notifications FORCE ROW LEVEL SECURITY;
ALTER TABLE notification_preferences FORCE ROW LEVEL SECURITY;
ALTER TABLE approval_requests FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_config_options FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_config_option_history FORCE ROW LEVEL SECURITY;
ALTER TABLE tenant_onboarding_config FORCE ROW LEVEL SECURITY;

-- ============================================================
-- 2. Add is_system to item_categories (#88, #98)
-- ============================================================
ALTER TABLE item_categories ADD COLUMN IF NOT EXISTS is_system BOOLEAN NOT NULL DEFAULT FALSE;

-- ============================================================
-- 3. Update description of ITEM_CATEGORY in config_categories (#98)
-- ============================================================
UPDATE config_categories
SET description = 'Authoritative backing store is item_categories table (V045). Reads and writes route to item_categories; no duplicate data in tenant_config_options.'
WHERE code = 'ITEM_CATEGORY';

-- ============================================================
-- 4. Re-enforce user_roles.role CHECK constraint excluding PLATFORM_ADMIN (#97)
-- ============================================================
ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS user_roles_role_check;
ALTER TABLE user_roles DROP CONSTRAINT IF EXISTS chk_user_roles_tenant_role;
ALTER TABLE user_roles ADD CONSTRAINT chk_user_roles_tenant_role
  CHECK (role IN (
    'OWNER','OPS_MANAGER','SALES_MANAGER','PURCHASE_MANAGER',
    'PRODUCTION_SUPERVISOR','STORE_KEEPER','ACCOUNTS_STAFF',
    'QUALITY_INSPECTOR','FIELD_WORKER'));

-- ============================================================
-- 5. Stored function seed_tenant_defaults(p_tenant_id, p_industry_type) (#88, #91)
-- ============================================================
CREATE OR REPLACE FUNCTION seed_tenant_defaults(p_tenant_id UUID, p_industry_type TEXT)
RETURNS INTEGER LANGUAGE plpgsql AS $$
DECLARE
  v_inserted INTEGER := 0;
  v_count INTEGER := 0;
BEGIN
  IF p_industry_type NOT IN (
    'FABRICATION_STEEL','PRECISION_MACHINING','ELECTRONICS_ASSEMBLY',
    'FORGING','CASTING','GENERAL_MANUFACTURING'
  ) THEN
    RAISE EXCEPTION 'Invalid industry type: %. Supported: FABRICATION_STEEL, PRECISION_MACHINING, ELECTRONICS_ASSEMBLY, FORGING, CASTING, GENERAL_MANUFACTURING', p_industry_type;
  END IF;

  -- 1. Seed item_categories from industry_template_options for ITEM_CATEGORY with is_system=TRUE
  INSERT INTO item_categories (tenant_id, code, name, is_system, is_active)
  SELECT p_tenant_id, ito.code, ito.label, TRUE, TRUE
  FROM industry_template_options ito
  WHERE ito.industry_type = p_industry_type AND ito.category_code = 'ITEM_CATEGORY'
  ON CONFLICT (tenant_id, code) DO NOTHING;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  v_inserted := v_inserted + v_count;

  -- 2. Seed tenant_config_options from industry_template_options for non-ITEM_CATEGORY categories
  INSERT INTO tenant_config_options (tenant_id, category_id, code, label, sort_order, is_system, is_active)
  SELECT p_tenant_id, cc.id, ito.code, ito.label, ito.sort_order, TRUE, TRUE
  FROM industry_template_options ito
  JOIN config_categories cc ON cc.code = ito.category_code
  WHERE ito.industry_type = p_industry_type AND ito.category_code != 'ITEM_CATEGORY'
  ON CONFLICT (tenant_id, category_id, code) DO NOTHING;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  v_inserted := v_inserted + v_count;

  -- 3. Seed standard UOMs into units_of_measure
  INSERT INTO units_of_measure (tenant_id, name, symbol, uom_type, is_base) VALUES
    (p_tenant_id, 'Numbers',       'NOS', 'quantity', TRUE),
    (p_tenant_id, 'Pieces',        'PCS', 'quantity', FALSE),
    (p_tenant_id, 'Kilogram',      'KG',  'weight',   TRUE),
    (p_tenant_id, 'Gram',          'G',   'weight',   FALSE),
    (p_tenant_id, 'Metric Tonne',  'MT',  'weight',   FALSE),
    (p_tenant_id, 'Metre',         'M',   'length',   TRUE),
    (p_tenant_id, 'Millimetre',    'MM',  'length',   FALSE),
    (p_tenant_id, 'Centimetre',    'CM',  'length',   FALSE),
    (p_tenant_id, 'Litre',         'L',   'volume',   TRUE),
    (p_tenant_id, 'Millilitre',    'ML',  'volume',   FALSE),
    (p_tenant_id, 'Square Metre',  'SQM', 'area',     TRUE),
    (p_tenant_id, 'Hour',          'HR',  'time',     TRUE),
    (p_tenant_id, 'Minute',        'MIN', 'time',     FALSE),
    (p_tenant_id, 'Set',           'SET', 'quantity', FALSE),
    (p_tenant_id, 'Box',           'BOX', 'quantity', FALSE),
    (p_tenant_id, 'Carton',        'CTN', 'quantity', FALSE)
  ON CONFLICT (tenant_id, symbol) DO NOTHING;
  GET DIAGNOSTICS v_count = ROW_COUNT;
  v_inserted := v_inserted + v_count;

  -- 4. Record onboarding config
  INSERT INTO tenant_onboarding_config (tenant_id, industry_type, onboarding_completed_at)
  VALUES (p_tenant_id, p_industry_type, NOW())
  ON CONFLICT (tenant_id) DO UPDATE SET
    industry_type = EXCLUDED.industry_type,
    onboarding_completed_at = COALESCE(tenant_onboarding_config.onboarding_completed_at, NOW());

  RETURN v_inserted;
END;
$$;
