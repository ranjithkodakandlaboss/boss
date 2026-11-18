-- Preserve tenant-managed option ordering changes in the append-only history.
ALTER TABLE tenant_config_option_history
  ADD COLUMN old_sort_order INTEGER,
  ADD COLUMN new_sort_order INTEGER;

ALTER TABLE tenant_config_option_history
  DROP CONSTRAINT tenant_config_option_history_change_type_check;

ALTER TABLE tenant_config_option_history
  ADD CONSTRAINT tenant_config_option_history_change_type_check
  CHECK (change_type IN ('create', 'update', 'deactivate', 'reactivate', 'REORDERED'));
