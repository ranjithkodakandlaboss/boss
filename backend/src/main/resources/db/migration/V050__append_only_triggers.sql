-- V050: Append-only triggers — block UPDATE and DELETE on immutable ledger tables (issue #18)
-- Covers: stock_movements, audit_events, tenant_config_option_history, platform_audit_events

CREATE OR REPLACE FUNCTION boss_block_mutation()
RETURNS TRIGGER LANGUAGE plpgsql AS $$
BEGIN
  RAISE EXCEPTION 'Mutation blocked: % on % is append-only', TG_OP, TG_TABLE_NAME;
END;
$$;

-- stock_movements
CREATE TRIGGER trg_stock_movements_no_update
  BEFORE UPDATE ON stock_movements
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();

CREATE TRIGGER trg_stock_movements_no_delete
  BEFORE DELETE ON stock_movements
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();

-- audit_events
CREATE TRIGGER trg_audit_events_no_update
  BEFORE UPDATE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();

CREATE TRIGGER trg_audit_events_no_delete
  BEFORE DELETE ON audit_events
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();

-- tenant_config_option_history
CREATE TRIGGER trg_config_option_history_no_update
  BEFORE UPDATE ON tenant_config_option_history
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();

CREATE TRIGGER trg_config_option_history_no_delete
  BEFORE DELETE ON tenant_config_option_history
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();

-- platform_audit_events (admin API, no RLS)
CREATE TRIGGER trg_platform_audit_no_update
  BEFORE UPDATE ON platform_audit_events
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();

CREATE TRIGGER trg_platform_audit_no_delete
  BEFORE DELETE ON platform_audit_events
  FOR EACH ROW EXECUTE FUNCTION boss_block_mutation();
