-- V047: Versioned routing templates with stages (replaces V015 routing_operations for new features)
-- V015 routing_templates table is kept; this adds versioned stages alongside it.
CREATE TABLE routing_template_stages (
  id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id               UUID NOT NULL REFERENCES tenant_registry(id),
  routing_id              UUID NOT NULL REFERENCES routing_templates(id),
  stage_config_id         UUID REFERENCES tenant_config_options(id),
  seq_no                  INTEGER NOT NULL,
  name                    TEXT NOT NULL,
  stage_type              TEXT NOT NULL DEFAULT 'production'
                            CHECK (stage_type IN ('production','inspection','rework','hold')),
  setup_time_min          INTEGER NOT NULL DEFAULT 0,
  run_time_min            INTEGER NOT NULL DEFAULT 0,
  requires_inspection     BOOLEAN NOT NULL DEFAULT FALSE,
  inspection_template_id  UUID REFERENCES inspection_parameter_templates(id),
  is_optional             BOOLEAN NOT NULL DEFAULT FALSE,
  created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (tenant_id, routing_id, seq_no)
);

CREATE INDEX idx_routing_stages_routing ON routing_template_stages(tenant_id, routing_id);
