-- Lifecycle operations lock an item before inspecting dependencies. Writers
-- lock the parent first, then its items, so a newly active dependency cannot
-- commit concurrently with deactivation.

CREATE INDEX idx_bom_lines_lifecycle_item ON bom_lines (tenant_id, component_item_id);
CREATE INDEX idx_cpo_lines_lifecycle_item ON cpo_lines (tenant_id, item_id);
CREATE INDEX idx_pr_lines_lifecycle_item ON pr_lines (tenant_id, item_id);
CREATE INDEX idx_spo_lines_lifecycle_item ON spo_lines (tenant_id, item_id);
CREATE INDEX idx_job_requirements_lifecycle_item ON job_material_requirements (tenant_id, item_id);
CREATE INDEX idx_job_issues_lifecycle_item ON job_material_issues (tenant_id, item_id);
CREATE INDEX idx_dispatch_lines_lifecycle_item ON dispatch_lines (tenant_id, item_id);
CREATE INDEX idx_rfq_lines_lifecycle_item ON rfq_lines (tenant_id, item_id);
CREATE INDEX idx_quotation_lines_lifecycle_item ON quotation_lines (tenant_id, item_id);
CREATE INDEX idx_supplier_quotation_lines_lifecycle_item ON supplier_quotation_lines (tenant_id, item_id);
CREATE INDEX idx_frame_agreement_lines_lifecycle_item ON frame_agreement_lines (tenant_id, item_id);
CREATE INDEX idx_grn_lines_lifecycle_item ON grn_lines (tenant_id, item_id);
CREATE INDEX idx_invoice_lines_lifecycle_item ON invoice_lines (tenant_id, item_id);
CREATE INDEX idx_transfer_lines_lifecycle_item ON inter_site_transfer_lines (tenant_id, item_id);
CREATE INDEX idx_routing_lifecycle_item ON routing_templates (tenant_id, finished_item_id);
CREATE INDEX idx_replenishment_lifecycle_item ON replenishment_requests (tenant_id, item_id);
CREATE INDEX idx_quality_lifecycle_item ON quality_inspections (tenant_id, item_id);
CREATE INDEX idx_ncr_lifecycle_item ON ncrs (tenant_id, item_id);
CREATE INDEX idx_supplier_return_lines_lifecycle_item ON supplier_return_lines (tenant_id, item_id);
CREATE INDEX idx_packing_lifecycle_item ON packing_specifications (tenant_id, item_id);

CREATE FUNCTION boss_lock_item(p_tenant UUID, p_item UUID, p_require_active BOOLEAN)
RETURNS VOID LANGUAGE plpgsql AS $$
DECLARE
  active BOOLEAN;
BEGIN
  SELECT is_active INTO active FROM items
    WHERE tenant_id = p_tenant AND id = p_item FOR UPDATE;
  IF NOT FOUND OR (p_require_active AND NOT active) THEN
    RAISE EXCEPTION 'Item % is inactive or unavailable for this tenant', p_item
      USING ERRCODE = '23B01';
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
  refs_query TEXT;
BEGIN
  requires_active := (TG_ARGV[3] = 'approved' AND NEW.status = 'approved')
    OR (TG_ARGV[3] <> 'approved' AND NOT NEW.status = ANY(string_to_array(TG_ARGV[3], ',')));
  IF TG_NARGS > 4 THEN
    direct_item := (to_jsonb(NEW) ->> TG_ARGV[4])::UUID;
  END IF;
  refs_query := format('SELECT %I AS id FROM %I WHERE tenant_id = $1 AND %I = $2',
      item_column, TG_ARGV[0], TG_ARGV[1]);
  IF TG_NARGS > 5 THEN
    refs_query := refs_query || format(' UNION SELECT %I AS id FROM %I WHERE tenant_id = $1 AND %I = $2',
        item_column, TG_ARGV[5], TG_ARGV[1]);
  END IF;
  FOR item_id IN EXECUTE format(
      'SELECT id FROM (%s UNION SELECT $3::uuid) AS refs WHERE id IS NOT NULL ORDER BY id', refs_query)
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
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('pr_lines', 'pr_id', 'item_id', 'ordered,fulfilled,rejected,cancelled');
CREATE TRIGGER item_lock_pr_line BEFORE INSERT OR UPDATE OF pr_id, item_id, tenant_id ON pr_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('purchase_requisitions', 'pr_id', 'item_id', 'ordered,fulfilled,rejected,cancelled');

CREATE TRIGGER item_lock_spo BEFORE INSERT OR UPDATE OF status, tenant_id ON spos
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('spo_lines', 'spo_id', 'item_id', 'fully_received,declined,cancelled');
CREATE TRIGGER item_lock_spo_line BEFORE INSERT OR UPDATE OF spo_id, item_id, tenant_id ON spo_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('spos', 'spo_id', 'item_id', 'fully_received,declined,cancelled');

CREATE TRIGGER item_lock_job BEFORE INSERT OR UPDATE OF status, finished_item_id, tenant_id ON production_jobs
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('job_material_requirements', 'job_id', 'item_id', 'completed,cancelled', 'finished_item_id', 'job_material_issues');
CREATE TRIGGER item_lock_job_material BEFORE INSERT OR UPDATE OF job_id, item_id, tenant_id ON job_material_requirements
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('production_jobs', 'job_id', 'item_id', 'completed,cancelled');
CREATE TRIGGER item_lock_job_issue BEFORE INSERT OR UPDATE OF job_id, item_id, tenant_id ON job_material_issues
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('production_jobs', 'job_id', 'item_id', 'completed,cancelled');

CREATE TRIGGER item_lock_dispatch BEFORE INSERT OR UPDATE OF status, tenant_id ON dispatches
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('dispatch_lines', 'dispatch_id', 'item_id', 'delivered,cancelled');
CREATE TRIGGER item_lock_dispatch_line BEFORE INSERT OR UPDATE OF dispatch_id, item_id, tenant_id ON dispatch_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('dispatches', 'dispatch_id', 'item_id', 'delivered,cancelled');

CREATE TRIGGER item_lock_rfq BEFORE INSERT OR UPDATE OF status, tenant_id ON rfqs
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('rfq_lines', 'rfq_id', 'item_id', 'closed,cancelled');
CREATE TRIGGER item_lock_rfq_line BEFORE INSERT OR UPDATE OF rfq_id, item_id, tenant_id ON rfq_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('rfqs', 'rfq_id', 'item_id', 'closed,cancelled');

CREATE TRIGGER item_lock_quotation BEFORE INSERT OR UPDATE OF status, tenant_id ON quotations
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('quotation_lines', 'quotation_id', 'item_id', 'accepted,rejected,expired,superseded');
CREATE TRIGGER item_lock_quotation_line BEFORE INSERT OR UPDATE OF quotation_id, item_id, tenant_id ON quotation_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('quotations', 'quotation_id', 'item_id', 'accepted,rejected,expired,superseded');

CREATE TRIGGER item_lock_supplier_quotation BEFORE INSERT OR UPDATE OF status, tenant_id ON supplier_quotations
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('supplier_quotation_lines', 'sq_id', 'item_id', 'selected,rejected');
CREATE TRIGGER item_lock_supplier_quotation_line BEFORE INSERT OR UPDATE OF sq_id, item_id, tenant_id ON supplier_quotation_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('supplier_quotations', 'sq_id', 'item_id', 'selected,rejected');

CREATE TRIGGER item_lock_agreement BEFORE INSERT OR UPDATE OF status, tenant_id ON frame_agreements
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('frame_agreement_lines', 'fa_id', 'item_id', 'expired,terminated');
CREATE TRIGGER item_lock_agreement_line BEFORE INSERT OR UPDATE OF fa_id, item_id, tenant_id ON frame_agreement_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('frame_agreements', 'fa_id', 'item_id', 'expired,terminated');

CREATE TRIGGER item_lock_grn BEFORE INSERT OR UPDATE OF status, tenant_id ON grns
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('grn_lines', 'grn_id', 'item_id', 'accepted,partially_accepted,rejected,closed');
CREATE TRIGGER item_lock_grn_line BEFORE INSERT OR UPDATE OF grn_id, item_id, tenant_id ON grn_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('grns', 'grn_id', 'item_id', 'accepted,partially_accepted,rejected,closed');

CREATE TRIGGER item_lock_invoice BEFORE INSERT OR UPDATE OF status, tenant_id ON invoices
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('invoice_lines', 'invoice_id', 'item_id', 'paid,cancelled,credit_note');
CREATE TRIGGER item_lock_invoice_line BEFORE INSERT OR UPDATE OF invoice_id, item_id, tenant_id ON invoice_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('invoices', 'invoice_id', 'item_id', 'paid,cancelled,credit_note');

CREATE TRIGGER item_lock_transfer BEFORE INSERT OR UPDATE OF status, tenant_id ON inter_site_transfers
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_parent('inter_site_transfer_lines', 'transfer_id', 'item_id', 'received,cancelled');
CREATE TRIGGER item_lock_transfer_line BEFORE INSERT OR UPDATE OF transfer_id, item_id, tenant_id ON inter_site_transfer_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_dependency_child('inter_site_transfers', 'transfer_id', 'item_id', 'received,cancelled');

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

CREATE FUNCTION boss_lock_direct_dependency()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
DECLARE
  row_data JSONB := to_jsonb(NEW);
  item_id UUID := (row_data ->> TG_ARGV[0])::UUID;
  requires_active BOOLEAN := FALSE;
BEGIN
  IF item_id IS NULL THEN
    RETURN NEW;
  END IF;
  IF TG_ARGV[1] = 'is_active' THEN
    requires_active := (row_data ->> 'is_active')::boolean;
  ELSIF TG_ARGV[1] = 'status' THEN
    requires_active := NOT (row_data ->> 'status') = ANY(string_to_array(TG_ARGV[2], ','));
  END IF;
  PERFORM boss_lock_item(NEW.tenant_id, item_id, requires_active);
  RETURN NEW;
END;
$$;

CREATE TRIGGER item_lock_routing BEFORE INSERT OR UPDATE OF status, finished_item_id, tenant_id ON routing_templates
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('finished_item_id', 'status', 'archived');
CREATE TRIGGER item_lock_reorder BEFORE INSERT OR UPDATE OF is_active, item_id, tenant_id ON reorder_policies
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('item_id', 'is_active');
CREATE TRIGGER item_lock_replenishment BEFORE INSERT OR UPDATE OF status, item_id, tenant_id ON replenishment_requests
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('item_id', 'status', 'fulfilled,cancelled');
CREATE TRIGGER item_lock_inspection BEFORE INSERT OR UPDATE OF status, item_id, tenant_id ON quality_inspections
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('item_id', 'status', 'completed,cancelled');
CREATE TRIGGER item_lock_ncr BEFORE INSERT OR UPDATE OF status, item_id, tenant_id ON ncrs
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('item_id', 'status', 'resolved,closed,scrapped');
CREATE TRIGGER item_lock_movement BEFORE INSERT OR UPDATE OF item_id, tenant_id ON stock_movements
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('item_id', 'reference');
CREATE TRIGGER item_lock_return BEFORE INSERT OR UPDATE OF item_id, tenant_id ON supplier_return_lines
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('item_id', 'reference');
CREATE TRIGGER item_lock_packing BEFORE INSERT OR UPDATE OF item_id, tenant_id ON packing_specifications
  FOR EACH ROW EXECUTE FUNCTION boss_lock_direct_dependency('item_id', 'reference');
