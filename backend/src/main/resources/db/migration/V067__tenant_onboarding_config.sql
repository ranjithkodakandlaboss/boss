-- V067: Create the tenant onboarding record defined in the v1.1.3 physical schema.
CREATE TABLE tenant_onboarding_config (
  tenant_id UUID PRIMARY KEY REFERENCES tenant_registry(id),
  industry_type TEXT NOT NULL
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

ALTER TABLE tenant_onboarding_config ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_onboarding_config_isolation ON tenant_onboarding_config
  USING (tenant_id = boss_current_tenant_id())
  WITH CHECK (tenant_id = boss_current_tenant_id());
