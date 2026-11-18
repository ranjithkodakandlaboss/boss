-- V089: Maintenance module - asset register extensions, maintenance events, meter
-- readings, and DB-backed dropdown master data (config categories + industry seeds).
-- Additive and repeatable: safe on existing tenants and applied to new tenants via
-- seed_tenant_defaults (which copies every industry_template_options row).

-- 1. Asset register extensions ------------------------------------------------
ALTER TABLE assets DROP CONSTRAINT IF EXISTS assets_status_check;
ALTER TABLE assets ADD CONSTRAINT assets_status_check CHECK (status IN (
  'active','maintenance','disposed','transferred',
  'operational','in_maintenance','breakdown','pm_overdue','retired'));
ALTER TABLE assets ALTER COLUMN status SET DEFAULT 'operational';
UPDATE assets SET status = 'operational' WHERE status = 'active';
UPDATE assets SET status = 'in_maintenance' WHERE status = 'maintenance';
UPDATE assets SET status = 'retired' WHERE status IN ('disposed','transferred');

ALTER TABLE assets
  ADD COLUMN IF NOT EXISTS category TEXT,
  ADD COLUMN IF NOT EXISTS work_centre TEXT,
  ADD COLUMN IF NOT EXISTS criticality TEXT NOT NULL DEFAULT 'Medium',
  ADD COLUMN IF NOT EXISTS meter_type TEXT NOT NULL DEFAULT 'none',
  ADD COLUMN IF NOT EXISTS current_reading NUMERIC(14,2),
  ADD COLUMN IF NOT EXISTS last_service_reading NUMERIC(14,2),
  ADD COLUMN IF NOT EXISTS last_service_date DATE,
  ADD COLUMN IF NOT EXISTS next_service_date DATE,
  ADD COLUMN IF NOT EXISTS pm_frequency_metric TEXT,
  ADD COLUMN IF NOT EXISTS pm_frequency_hours NUMERIC(10,2),
  ADD COLUMN IF NOT EXISTS pm_frequency_days INTEGER,
  ADD COLUMN IF NOT EXISTS responsible_user_id UUID REFERENCES app_users(id),
  ADD COLUMN IF NOT EXISTS responsible_person TEXT,
  ADD COLUMN IF NOT EXISTS install_date DATE,
  ADD COLUMN IF NOT EXISTS archive_reason TEXT,
  ADD COLUMN IF NOT EXISTS archived_at TIMESTAMPTZ,
  ADD COLUMN IF NOT EXISTS archived_by UUID REFERENCES app_users(id);
UPDATE assets SET category = asset_type WHERE category IS NULL;

CREATE INDEX IF NOT EXISTS idx_assets_tenant_status ON assets(tenant_id, status);
CREATE INDEX IF NOT EXISTS idx_assets_tenant_wc ON assets(tenant_id, work_centre);

-- 2. Maintenance events -------------------------------------------------------
CREATE TABLE IF NOT EXISTS maintenance_events (
  id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id            UUID NOT NULL REFERENCES tenant_registry(id),
  event_number         TEXT NOT NULL,
  asset_id             UUID NOT NULL REFERENCES assets(id),
  event_type           TEXT NOT NULL CHECK (event_type IN ('Breakdown','Preventive','Corrective')),
  status               TEXT NOT NULL DEFAULT 'open'
                         CHECK (status IN ('open','in_progress','scheduled','closed','pending_approval')),
  severity             TEXT,
  symptom              TEXT NOT NULL,
  technician_user_id   UUID REFERENCES app_users(id),
  technician_name      TEXT NOT NULL,
  planned_date         DATE,
  expected_duration_hours NUMERIC(8,2),
  downtime_start       TIMESTAMPTZ,
  downtime_end         TIMESTAMPTZ,
  completion_date      TIMESTAMPTZ,
  downtime_hours       NUMERIC(10,2),
  affected_job_ids     UUID[] NOT NULL DEFAULT '{}',
  root_cause           TEXT,
  corrective_action    TEXT,
  work_done            TEXT,
  resolution_notes     TEXT,
  parts_used           TEXT,
  created_by           UUID REFERENCES app_users(id),
  closed_by            UUID REFERENCES app_users(id),
  created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, event_number),
  CHECK (downtime_end IS NULL OR downtime_start IS NULL OR downtime_end >= downtime_start)
);
CREATE INDEX IF NOT EXISTS idx_maint_events_asset ON maintenance_events(tenant_id, asset_id);
CREATE INDEX IF NOT EXISTS idx_maint_events_status ON maintenance_events(tenant_id, status);

-- 3. Meter readings -----------------------------------------------------------
CREATE TABLE IF NOT EXISTS asset_meter_readings (
  id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id        UUID NOT NULL REFERENCES tenant_registry(id),
  asset_id         UUID NOT NULL REFERENCES assets(id),
  reading          NUMERIC(14,2) NOT NULL CHECK (reading >= 0),
  previous_reading NUMERIC(14,2),
  reading_date     DATE NOT NULL DEFAULT CURRENT_DATE,
  reading_type     TEXT NOT NULL DEFAULT 'routine'
                     CHECK (reading_type IN ('routine','shift-end','post-maintenance','correction')),
  recorded_by      UUID REFERENCES app_users(id),
  notes            TEXT,
  created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_meter_readings_asset ON asset_meter_readings(tenant_id, asset_id, created_at DESC);

DO $$
DECLARE t TEXT;
BEGIN
  FOREACH t IN ARRAY ARRAY['maintenance_events','asset_meter_readings'] LOOP
    EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', t);
    EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', t);
    EXECUTE format('DROP POLICY IF EXISTS %I ON %I', t || '_isolation', t);
    EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id = boss_current_tenant_id()) WITH CHECK (tenant_id = boss_current_tenant_id())', t || '_isolation', t);
  END LOOP;
END $$;

-- 4. Dropdown categories ------------------------------------------------------
INSERT INTO config_categories (code, name, scope) VALUES
  ('ASSET_CATEGORY',         'Asset Categories',           'tenant'),
  ('WORK_CENTRE',            'Work Centres',               'tenant'),
  ('ASSET_CRITICALITY',      'Asset Criticality',          'tenant'),
  ('ASSET_STATUS',           'Asset Statuses',             'system'),
  ('METER_TYPE',             'Meter Types',                'system'),
  ('MAINTENANCE_EVENT_TYPE', 'Maintenance Event Types',    'system'),
  ('MAINTENANCE_SEVERITY',   'Maintenance Severities',     'tenant'),
  ('MAINTENANCE_EVENT_STATUS','Maintenance Event Statuses','system')
ON CONFLICT (code) DO NOTHING;

-- 5. Industry template seeds (new tenants receive these via seed_tenant_defaults)
CREATE TEMP TABLE _maint_seed (category_code TEXT, code TEXT, label TEXT, sort_order INT) ON COMMIT DROP;
INSERT INTO _maint_seed VALUES
  ('ASSET_CATEGORY','MACHINING','Machining Equipment',1),
  ('ASSET_CATEGORY','PRESS_FORMING','Press & Forming',2),
  ('ASSET_CATEGORY','WELDING_FAB','Welding & Fabrication',3),
  ('ASSET_CATEGORY','SURFACE_COATING','Surface & Coating',4),
  ('ASSET_CATEGORY','INSPECTION_METROLOGY','Inspection & Metrology',5),
  ('ASSET_CATEGORY','MATERIAL_HANDLING','Material Handling',6),
  ('ASSET_CATEGORY','UTILITIES','Utilities & Compressors',7),
  ('WORK_CENTRE','CNC_BAY','CNC Bay',1),
  ('WORK_CENTRE','TURNING_MILLING','Turning & Milling',2),
  ('WORK_CENTRE','PRESS_SHOP','Press Shop',3),
  ('WORK_CENTRE','WELDING_BAY','Welding Bay',4),
  ('WORK_CENTRE','ASSEMBLY_FITMENT','Assembly & Fitment',5),
  ('WORK_CENTRE','QUALITY_LAB','Quality Lab',6),
  ('WORK_CENTRE','STORES_DISPATCH','Stores & Dispatch',7),
  ('ASSET_CRITICALITY','Low','Low',1),
  ('ASSET_CRITICALITY','Medium','Medium',2),
  ('ASSET_CRITICALITY','High','High',3),
  ('ASSET_CRITICALITY','Critical','Critical',4),
  ('ASSET_STATUS','operational','Operational',1),
  ('ASSET_STATUS','in_maintenance','In Maintenance',2),
  ('ASSET_STATUS','breakdown','Breakdown',3),
  ('ASSET_STATUS','pm_overdue','PM Overdue',4),
  ('ASSET_STATUS','retired','Retired',5),
  ('METER_TYPE','none','Calendar only',1),
  ('METER_TYPE','hours','Running hours',2),
  ('METER_TYPE','cycles','Cycles',3),
  ('METER_TYPE','km','Kilometres',4),
  ('MAINTENANCE_EVENT_TYPE','Breakdown','Breakdown',1),
  ('MAINTENANCE_EVENT_TYPE','Preventive','Preventive',2),
  ('MAINTENANCE_EVENT_TYPE','Corrective','Corrective',3),
  ('MAINTENANCE_SEVERITY','Low','Low',1),
  ('MAINTENANCE_SEVERITY','Medium','Medium',2),
  ('MAINTENANCE_SEVERITY','High','High',3),
  ('MAINTENANCE_SEVERITY','Critical','Critical',4),
  ('MAINTENANCE_EVENT_STATUS','open','Open',1),
  ('MAINTENANCE_EVENT_STATUS','in_progress','In Progress',2),
  ('MAINTENANCE_EVENT_STATUS','scheduled','Scheduled',3),
  ('MAINTENANCE_EVENT_STATUS','pending_approval','Pending Approval',4),
  ('MAINTENANCE_EVENT_STATUS','closed','Closed',5);

INSERT INTO industry_template_options (industry_type, category_code, code, label, sort_order)
SELECT i.industry_type, s.category_code, s.code, s.label, s.sort_order
FROM _maint_seed s
CROSS JOIN (VALUES ('FABRICATION_STEEL'),('PRECISION_MACHINING'),('ELECTRONICS_ASSEMBLY'),
                   ('FORGING'),('CASTING'),('GENERAL_MANUFACTURING')) AS i(industry_type)
ON CONFLICT (industry_type, category_code, code) DO NOTHING;

-- 6. Backfill existing tenants (tenant_config_options forces RLS, so set the tenant)
DO $$
DECLARE r RECORD;
BEGIN
  FOR r IN SELECT id FROM tenant_registry LOOP
    PERFORM set_config('app.current_tenant_id', r.id::text, true);
    INSERT INTO tenant_config_options (tenant_id, category_id, code, label, sort_order, is_system, is_active)
    SELECT r.id, cc.id, s.code, s.label, s.sort_order, TRUE, TRUE
    FROM _maint_seed s JOIN config_categories cc ON cc.code = s.category_code
    ON CONFLICT (tenant_id, category_id, code) DO NOTHING;
  END LOOP;
  PERFORM set_config('app.current_tenant_id', '', true);
END $$;
