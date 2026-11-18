-- Lifecycle operations lock an item before inspecting dependencies. Writers
-- lock the parent first, then its items, so a newly active dependency cannot
-- commit concurrently with deactivation.

CREATE FUNCTION boss_lock_item(p_tenant UUID, p_item UUID, p_require_active BOOLEAN)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
  active BOOLEAN;
BEGIN
  SELECT is_active INTO active FROM items
    WHERE tenant_id = p_tenant AND id = p_item FOR UPDATE;
  IF NOT FOUND OR (p_require_active AND NOT active) THEN
    RAISE EXCEPTION 'Item % is inactive or unavailable for this tenant', p_item
      USING ERRCODE = '23514';
  END IF;
END;
$$;

-- Child writes also lock their parent even when it is terminal: otherwise a
-- concurrent parent reactivation could miss an uncommitted child.
CREATE FUNCTION boss_lock_dependency_child()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  row_data JSONB := to_jsonb(NEW);
  parent_status TEXT;
  parent_id UUID := (row_data ->> TG_ARGV[1])::UUID;
  item_id UUID := (row_data ->> TG_ARGV[2])::UUID;
BEGIN
  EXECUTE format('SELECT status FROM %I WHERE tenant_id = $1 AND id = $2 FOR UPDATE', TG_ARGV[0])
    INTO parent_status USING NEW.tenant_id, parent_id;
  IF parent_status IS NULL THEN
    RAISE EXCEPTION 'Dependency parent % is unavailable for this tenant', parent_id
      USING ERRCODE = '23503';
  END IF;
  PERFORM boss_lock_item(NEW.tenant_id, item_id,
    (TG_ARGV[3] = 'approved' AND parent_status = 'approved')
    OR (TG_ARGV[3] <> 'approved' AND NOT parent_status = ANY(string_to_array(TG_ARGV[3], ','))));
  RETURN NEW;
END;
$$;

-- Parent activation is serialized with child insertion by the parent row lock.
CREATE FUNCTION boss_lock_dependency_parent()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  item_id UUID;
  item_column TEXT := TG_ARGV[2];
  direct_item UUID;
  requires_active BOOLEAN;
BEGIN
  requires_active := (TG_ARGV[3] = 'approved' AND NEW.status = 'approved')
    OR (TG_ARGV[3] <> 'approved' AND NOT NEW.status = ANY(string_to_array(TG_ARGV[3], ',')));
  IF TG_NARGS > 4 THEN
    direct_item := (to_jsonb(NEW) ->> TG_ARGV[4])::UUID;
  END IF;
  FOR item_id IN EXECUTE format(
      'SELECT id FROM (SELECT %I AS id FROM %I WHERE tenant_id = $1 AND %I = $2 UNION SELECT $3::uuid) AS refs WHERE id IS NOT NULL ORDER BY id',
      item_column, TG_ARGV[0], TG_ARGV[1])
      USING NEW.tenant_id, NEW.id, direct_item
  LOOP
    PERFORM boss_lock_item(NEW.tenant_id, item_id, requires_active);
  END LOOP;
  RETURN NEW;
END;
$$;

CREATE TRIGGER item_lock_bom_header BEFORE INSERT OR UPDATE OF status, finished_item_id, tenant_id ON bom_headers
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('bom_lines', 'bom_header_id', 'component_item_id', 'approved', 'finished_item_id');
CREATE TRIGGER item_lock_bom_line BEFORE INSERT OR UPDATE OF bom_header_id, component_item_id, tenant_id ON bom_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('bom_headers', 'bom_header_id', 'component_item_id', 'approved');

CREATE TRIGGER item_lock_cpo BEFORE INSERT OR UPDATE OF status, tenant_id ON cpos
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('cpo_lines', 'cpo_id', 'item_id', 'fulfilled,cancelled');
CREATE TRIGGER item_lock_cpo_line BEFORE INSERT OR UPDATE OF cpo_id, item_id, tenant_id ON cpo_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('cpos', 'cpo_id', 'item_id', 'fulfilled,cancelled');

CREATE TRIGGER item_lock_pr BEFORE INSERT OR UPDATE OF status, tenant_id ON purchase_requisitions
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('pr_lines', 'pr_id', 'item_id', 'fulfilled,rejected,cancelled');
CREATE TRIGGER item_lock_pr_line BEFORE INSERT OR UPDATE OF pr_id, item_id, tenant_id ON pr_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('purchase_requisitions', 'pr_id', 'item_id', 'fulfilled,rejected,cancelled');

CREATE TRIGGER item_lock_spo BEFORE INSERT OR UPDATE OF status, tenant_id ON spos
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('spo_lines', 'spo_id', 'item_id', 'fully_received,declined,cancelled');
CREATE TRIGGER item_lock_spo_line BEFORE INSERT OR UPDATE OF spo_id, item_id, tenant_id ON spo_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('spos', 'spo_id', 'item_id', 'fully_received,declined,cancelled');

CREATE TRIGGER item_lock_job BEFORE INSERT OR UPDATE OF status, finished_item_id, tenant_id ON production_jobs
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('job_material_requirements', 'job_id', 'item_id', 'completed,cancelled', 'finished_item_id');
CREATE TRIGGER item_lock_job_material BEFORE INSERT OR UPDATE OF job_id, item_id, tenant_id ON job_material_requirements
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('production_jobs', 'job_id', 'item_id', 'completed,cancelled');

CREATE TRIGGER item_lock_dispatch BEFORE INSERT OR UPDATE OF status, tenant_id ON dispatches
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('dispatch_lines', 'dispatch_id', 'item_id', 'delivered,cancelled');
CREATE TRIGGER item_lock_dispatch_line BEFORE INSERT OR UPDATE OF dispatch_id, item_id, tenant_id ON dispatch_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('dispatches', 'dispatch_id', 'item_id', 'delivered,cancelled');

CREATE FUNCTION boss_lock_stock_dependency()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  PERFORM boss_lock_item(NEW.tenant_id, NEW.item_id,
    (TG_TABLE_NAME = 'material_lots' AND (to_jsonb(NEW) ->> 'quantity')::numeric > 0)
    OR (TG_TABLE_NAME = 'stock_reservation_records' AND NEW.status = 'active'));
  RETURN NEW;
END;
$$;

CREATE TRIGGER item_lock_lot BEFORE INSERT OR UPDATE OF quantity, item_id, tenant_id ON material_lots
  FOR EACH ROW EXECUTE FUNCTION boss_lock_stock_dependency();
CREATE TRIGGER item_lock_reservation BEFORE INSERT OR UPDATE OF status, item_id, tenant_id ON stock_reservation_records
  FOR EACH ROW EXECUTE FUNCTION boss_lock_stock_dependency();
