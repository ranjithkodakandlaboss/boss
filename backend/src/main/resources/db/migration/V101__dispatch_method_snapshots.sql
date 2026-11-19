-- Dispatch methods are tenant-managed options whose labels must remain historical.
INSERT INTO config_categories (code, name, scope) VALUES
  ('DISPATCH_METHOD', 'Dispatch Methods', 'tenant')
ON CONFLICT (code) DO NOTHING;

ALTER TABLE dispatches
  ADD COLUMN dispatch_method_option_id UUID REFERENCES tenant_config_options(id),
  ADD COLUMN dispatch_method_label_snapshot TEXT;

CREATE OR REPLACE FUNCTION set_dispatch_method_label_snapshot()
RETURNS TRIGGER
LANGUAGE plpgsql
AS $$
DECLARE
  option_label TEXT;
BEGIN
  IF NEW.dispatch_method_option_id IS NULL THEN
    NEW.dispatch_method_label_snapshot := NULL;
    RETURN NEW;
  END IF;

  IF TG_OP = 'UPDATE'
     AND NEW.dispatch_method_option_id IS NOT DISTINCT FROM OLD.dispatch_method_option_id
     AND NEW.tenant_id IS NOT DISTINCT FROM OLD.tenant_id THEN
    NEW.dispatch_method_label_snapshot := OLD.dispatch_method_label_snapshot;
    RETURN NEW;
  END IF;

  SELECT o.label
    INTO option_label
  FROM tenant_config_options o
  JOIN config_categories c ON c.id = o.category_id
  WHERE o.id = NEW.dispatch_method_option_id
    AND o.tenant_id = NEW.tenant_id
    AND o.is_active
    AND c.code = 'DISPATCH_METHOD'
    AND c.scope = 'tenant'
    AND c.is_active;

  IF NOT FOUND THEN
    RAISE EXCEPTION 'Dispatch method option must be active and belong to this tenant'
      USING ERRCODE = '23514';
  END IF;

  NEW.dispatch_method_label_snapshot := option_label;
  RETURN NEW;
END;
$$;

CREATE TRIGGER dispatches_set_method_label_snapshot
  BEFORE INSERT OR UPDATE ON dispatches
  FOR EACH ROW
  EXECUTE FUNCTION set_dispatch_method_label_snapshot();
