-- V010: HSN codes — platform reference data, no RLS
CREATE TABLE hsn_codes (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  code        TEXT NOT NULL UNIQUE,
  description TEXT NOT NULL,
  gst_rate    NUMERIC(5,2),
  updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_hsn_codes_code ON hsn_codes(code);
