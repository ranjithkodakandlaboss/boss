-- Align ncrs and concessions tables with quality.yaml contract.
-- NON-DESTRUCTIVE: existing columns are retained as nullable for backward
-- compatibility with IncomingInspectionService and JobInspectionService
-- which write legacy free-text values. New rows written by QualityService
-- will populate defect_category_id instead.

-- Add defect_category_id as a new nullable column alongside defect_type.
-- No FK to tenant_config_options: config option existence is validated at the
-- service layer (via ConfigService) to avoid a cross-module schema dependency.
ALTER TABLE ncrs
    ADD COLUMN IF NOT EXISTS defect_category_id UUID;

-- Make defect_type nullable so existing services can omit it on new rows
-- (existing rows already have a value; no data is lost)
ALTER TABLE ncrs
    ALTER COLUMN defect_type DROP NOT NULL;

-- Make concession_type nullable so existing services can omit it on new rows
-- (existing rows already have a value; no data is lost)
ALTER TABLE concessions
    ALTER COLUMN concession_type DROP NOT NULL;

-- Tighten clearance_certificates: contract only allows pending | issued
-- Remove expired and revoked from the check constraint
ALTER TABLE clearance_certificates
    DROP CONSTRAINT IF EXISTS clearance_certificates_status_check;

ALTER TABLE clearance_certificates
    ADD CONSTRAINT clearance_certificates_status_check
        CHECK (status IN ('pending', 'issued'));
